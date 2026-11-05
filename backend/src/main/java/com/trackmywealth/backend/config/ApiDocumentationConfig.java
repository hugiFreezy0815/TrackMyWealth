package com.trackmywealth.backend.config;

import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.web.CorrelationIdFilter;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.PagedArgumentResolver;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SortWhitelist;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Keeps the generated OpenAPI contract aligned with EPIC-29's runtime wire conventions.
 *
 * <p>Jackson serializes every {@link BigDecimal} as an exact plain decimal string ({@link
 * JacksonConfig}); Springdoc must describe the same type or generated clients will incorrectly use
 * floating-point numbers. Every operation also accepts the optional correlation-id header and
 * advertises the shared RFC 9457 error envelope, and every operation outside {@link
 * SecurityConfig#PUBLIC_API_PATTERNS} requires the bearer access token - the same list Spring
 * Security permits, so the contract cannot claim a public operation that is not.
 */
@Configuration
public class ApiDocumentationConfig {

  private static final String API_PROBLEM_SCHEMA = "ApiProblem";
  private static final String API_PROBLEM_RESPONSE = "ApiProblemResponse";

  /** The problem envelope's schema, for the per-status error responses (#374). */
  public static final String API_PROBLEM_REF = "#/components/schemas/" + API_PROBLEM_SCHEMA;

  /**
   * The schema extension for an open set of string values (#374): the values known today, where a
   * client must expect others. Used for the problem and import row codes.
   */
  public static final String EXTENSIBLE_ENUM = "x-extensible-enum";

  // Any order before ApiDocumentOrder's, which must run last.
  private static final int WIRE_CONVENTIONS_ORDER = 0;
  // After the wire conventions and the row codes (both 0), still before ApiDocumentOrder.
  private static final int RESPONSE_FIELDS_ORDER = 100;

  private static final String OK = "200";
  private static final String UNAUTHORIZED = "401";
  private static final String FORBIDDEN = "403";
  private static final String BEARER_SCHEME = "bearerAuth";
  private static final List<PathPattern> ADMIN_API = patterns(SecurityConfig.ADMIN_API_PATTERNS);
  private static final List<PathPattern> PUBLIC_API = patterns(SecurityConfig.PUBLIC_API_PATTERNS);
  private static final String DECIMAL_FORMAT = "decimal";
  private static final String QUERY = "query";
  // springdoc's name for an undecorated Pageable parameter.
  private static final String PAGEABLE_PARAMETER = "pageable";
  private static final String PROBLEM_RESPONSE_REF =
      "#/components/responses/" + API_PROBLEM_RESPONSE;

  static {
    SpringDocUtils.getConfig()
        .replaceWithSchema(
            BigDecimal.class,
            new StringSchema()
                .format(DECIMAL_FORMAT)
                .description(
                    "Exact decimal encoded as a plain JSON string. Strings are canonical on"
                        + " requests; JSON numbers remain accepted for compatibility."));
  }

  /**
   * US-29-02 (#372): the document's fixed header. The server is the relative {@code /}, not the
   * host springdoc would take from the request, so the committed {@code api/openapi.yaml} does not
   * depend on where it was generated.
   */
  @Bean
  OpenAPI apiDocument() {
    return new OpenAPI()
        .info(
            new Info()
                .title("TrackMyWealth API")
                .version("v1")
                .description(
                    "The REST API of TrackMyWealth. Committed as api/openapi.yaml; the build fails"
                        + " when it differs from the code."))
        .servers(List.of(new Server().url("/").description("The server serving this document")));
  }

  /**
   * US-29-02 (#372): runs after every other customizer, so whatever they added is put in a fixed
   * order too ({@link ApiDocumentOrder}). It alone has {@link Ordered#LOWEST_PRECEDENCE}: every
   * other {@link OpenApiCustomizer} must come earlier through {@link OrderedOpenApiCustomizer}, or
   * it would tie with this one and could run after it ({@code OpenApiContractTest} checks).
   */
  @Bean
  OpenApiCustomizer deterministicDocumentOrder() {
    return new OrderedOpenApiCustomizer(Ordered.LOWEST_PRECEDENCE, ApiDocumentOrder::apply);
  }

  /**
   * US-29-07 (#451): marks each schema generated from a record with the record, for {@link
   * #responseFieldContract()}.
   */
  @Bean
  ModelConverter recordSchemaMarker() {
    return new RecordSchemaMarker();
  }

  /**
   * US-29-07 (#451): every response field required, the {@code @Nullable} ones nullable ({@link
   * ResponseFieldContract}). After the wire conventions and the row codes, which add to the
   * schemas; {@code ApiProblem} is built here and declares its own required list.
   */
  @Bean
  OpenApiCustomizer responseFieldContract() {
    return new OrderedOpenApiCustomizer(
        RESPONSE_FIELDS_ORDER,
        openApi -> ResponseFieldContract.apply(openApi, Set.of(API_PROBLEM_SCHEMA)));
  }

  @Bean
  OpenApiCustomizer apiWireConventions() {
    return new OrderedOpenApiCustomizer(WIRE_CONVENTIONS_ORDER, ApiDocumentationConfig::wire);
  }

  private static void wire(OpenAPI openApi) {
    Components components = componentsOf(openApi);
    components.addSchemas(API_PROBLEM_SCHEMA, problemSchema());
    components.addResponses(API_PROBLEM_RESPONSE, problemResponse());
    ResponseHeaderContract.addComponents(components);

    components.addSecuritySchemes(
        BEARER_SCHEME,
        new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description(
                "The access token from set-up, login or refresh, sent as Authorization: Bearer"
                    + " <token>."));
    openApi.setSecurity(List.of(new SecurityRequirement().addList(BEARER_SCHEME)));

    if (openApi.getPaths() == null) {
      return;
    }
    openApi
        .getPaths()
        .forEach(
            (path, pathItem) ->
                pathItem
                    .readOperations()
                    .forEach(
                        operation -> {
                          documentAuthentication(path, operation);
                          documentCorrelationRequestHeader(operation);
                          documentOptimisticConcurrency(operation);
                          Map<String, ApiResponse> responses = responsesOf(operation);
                          responses.putIfAbsent(
                              "default", new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
                          responses
                              .values()
                              .forEach(ApiDocumentationConfig::documentCorrelationResponseHeader);
                        }));
  }

  /**
   * US-29-02 (#372): an operation a caller without a token may call says so with an empty security
   * requirement, overriding the document's bearer default; every other one documents the 401 it
   * answers without a valid token, and an administration operation the 403 for anyone else.
   */
  private static void documentAuthentication(
      String path, io.swagger.v3.oas.models.Operation operation) {
    PathContainer container = PathContainer.parsePath(path);
    if (matchesAny(PUBLIC_API, container)) {
      operation.setSecurity(new ArrayList<>());
      return;
    }
    Map<String, ApiResponse> responses = responsesOf(operation);
    responses.putIfAbsent(UNAUTHORIZED, new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
    if (matchesAny(ADMIN_API, container)) {
      responses.putIfAbsent(FORBIDDEN, new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
    }
  }

  private static boolean matchesAny(List<PathPattern> patterns, PathContainer path) {
    return patterns.stream().anyMatch(pattern -> pattern.matches(path));
  }

  private static List<PathPattern> patterns(List<String> patterns) {
    return patterns.stream().map(PathPatternParser.defaultInstance::parse).toList();
  }

  /**
   * US-29-02 (#372): an endpoint marked {@link SuccessStatus} documents the statuses it declares in
   * place of springdoc's generated 200, which is all springdoc can infer from a {@code
   * ResponseEntity}. A declared status springdoc already generated itself ({@code @ResponseStatus}
   * or an explicit {@code @ApiResponse}) starts from that response, with its description; every
   * other one starts from the generated 200 or, without one, from the first declared status
   * springdoc generated, and takes the status's reason phrase - so a find-or-create's 200 keeps the
   * body of its generated 201. Each gets its own copy of its source's maps - media types, headers,
   * links, examples, encodings and extensions - so adding to or removing from one leaves the others
   * alone; the objects in them (schemas, headers) stay shared, as springdoc shares them between
   * operations. A 204 has no body.
   */
  @Bean
  OperationCustomizer successStatusResponses() {
    return (operation, handlerMethod) -> {
      SuccessStatus declared = handlerMethod.getMethodAnnotation(SuccessStatus.class);
      if (declared != null) {
        documentSuccessStatuses(responsesOf(operation), declared.value());
      }
      // US-29-09 (#455): after the statuses, so each header lands on the final success responses.
      SendsHeaders headers = handlerMethod.getMethodAnnotation(SendsHeaders.class);
      if (headers != null) {
        ResponseHeaderContract.document(responsesOf(operation), headers.value());
      }
      return operation;
    };
  }

  private static void documentSuccessStatuses(
      Map<String, ApiResponse> responses, HttpStatus... statuses) {
    ApiResponse generated = responses.remove(OK);
    ApiResponse template = generated != null ? generated : firstGenerated(responses, statuses);
    for (HttpStatus status : statuses) {
      String code = String.valueOf(status.value());
      ApiResponse own = status == HttpStatus.OK ? generated : responses.get(code);
      ApiResponse source = own != null ? own : template;
      ApiResponse response =
          source == null ? new ApiResponse() : copyOf(source, status != HttpStatus.NO_CONTENT);
      // Only a status's own response describes it; a template's description is another status's.
      boolean ownDescription =
          own != null && own.getDescription() != null && !own.getDescription().isBlank();
      responses.put(
          code,
          response.description(ownDescription ? own.getDescription() : status.getReasonPhrase()));
    }
  }

  private static ApiResponse firstGenerated(
      Map<String, ApiResponse> responses, HttpStatus... statuses) {
    return Arrays.stream(statuses)
        .map(status -> responses.get(String.valueOf(status.value())))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static ApiResponse copyOf(ApiResponse generated, boolean withBody) {
    ApiResponse copy = new ApiResponse();
    if (generated.getHeaders() != null) {
      copy.setHeaders(new LinkedHashMap<>(generated.getHeaders()));
    }
    if (generated.getLinks() != null) {
      copy.setLinks(new LinkedHashMap<>(generated.getLinks()));
    }
    if (generated.getExtensions() != null) {
      copy.setExtensions(new LinkedHashMap<>(generated.getExtensions()));
    }
    if (withBody && generated.getContent() != null) {
      copy.setContent(new Content());
      generated
          .getContent()
          .forEach((type, media) -> copy.getContent().addMediaType(type, copyOf(media)));
    }
    return copy;
  }

  private static io.swagger.v3.oas.models.media.MediaType copyOf(
      io.swagger.v3.oas.models.media.MediaType media) {
    io.swagger.v3.oas.models.media.MediaType copy =
        new io.swagger.v3.oas.models.media.MediaType().schema(media.getSchema());
    // Swagger tells "no example" from an example of null by this flag; only the latter is written.
    if (media.getExampleSetFlag()) {
      copy.setExample(media.getExample());
    }
    if (media.getExamples() != null) {
      copy.setExamples(new LinkedHashMap<>(media.getExamples()));
    }
    if (media.getEncoding() != null) {
      copy.setEncoding(new LinkedHashMap<>(media.getEncoding()));
    }
    if (media.getExtensions() != null) {
      copy.setExtensions(new LinkedHashMap<>(media.getExtensions()));
    }
    return copy;
  }

  /**
   * A {@link Paged} list documents the one paging contract (#373): {@code page}, {@code size} with
   * its default and cap, and {@code sort} with exactly the values its whitelist accepts - in place
   * of springdoc's generic {@code Pageable} parameters, which would offer any property.
   */
  @Bean
  OperationCustomizer pagedListParameters() {
    return (operation, handlerMethod) -> {
      Arrays.stream(handlerMethod.getMethodParameters())
          .map(parameter -> parameter.getParameterAnnotation(Paged.class))
          .filter(Objects::nonNull)
          .findFirst()
          .ifPresent(
              paged -> documentPaging(operation, SortWhitelist.of(paged), paged.allowSort()));
      return operation;
    };
  }

  private static void documentPaging(
      io.swagger.v3.oas.models.Operation operation, SortWhitelist whitelist, boolean allowSort) {
    Set<String> pagingNames =
        Set.of(
            PAGEABLE_PARAMETER,
            PagedArgumentResolver.PAGE,
            PagedArgumentResolver.SIZE,
            PagedArgumentResolver.SORT);
    if (operation.getParameters() != null) {
      operation.getParameters().removeIf(parameter -> pagingNames.contains(parameter.getName()));
    }
    operation.addParametersItem(
        new Parameter()
            .name(PagedArgumentResolver.PAGE)
            .in(QUERY)
            .required(false)
            .description("Zero-based page number.")
            .schema(new IntegerSchema().format("int32").minimum(BigDecimal.ZERO)._default(0)));
    operation.addParametersItem(
        new Parameter()
            .name(PagedArgumentResolver.SIZE)
            .in(QUERY)
            .required(false)
            .description(
                "Items per page; a larger value is capped to "
                    + PagingValues.MAX_SIZE
                    + ", and page.size in the response says the size applied.")
            .schema(
                new IntegerSchema()
                    .format("int32")
                    .minimum(BigDecimal.ONE)
                    .maximum(BigDecimal.valueOf(PagingValues.MAX_SIZE))
                    ._default(PagingValues.DEFAULT_SIZE)));
    if (allowSort) {
      List<String> sortValues = new ArrayList<>();
      for (String field : whitelist.fieldNames()) {
        sortValues.add(field);
        sortValues.add(field + ",asc");
        sortValues.add(field + ",desc");
      }
      StringSchema sortValue = new StringSchema();
      sortValue.setEnum(sortValues);
      operation.addParametersItem(
          new Parameter()
              .name(PagedArgumentResolver.SORT)
              .in(QUERY)
              .required(false)
              .explode(true)
              .description(
                  "Repeatable <field>[,asc|desc]; any other field is a 400 INVALID_SORT. Default: "
                      + whitelist.defaultSort()
                      + ". The order always ends with a unique tie-breaker, so pages never"
                      + " overlap.")
              .schema(new ArraySchema().items(sortValue)));
    }
    responsesOf(operation).putIfAbsent("400", new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
  }

  private static Components componentsOf(OpenAPI openApi) {
    if (openApi.getComponents() == null) {
      openApi.setComponents(new Components());
    }
    return openApi.getComponents();
  }

  private static ObjectSchema problemSchema() {
    ObjectSchema problem = new ObjectSchema();
    problem.setDescription(
        "RFC 9457 problem detail extended with a stable application code and correlation id.");
    problem.addProperty("title", new StringSchema());
    problem.addProperty("status", new IntegerSchema().format("int32"));
    problem.addProperty("detail", new StringSchema());
    StringSchema code = new StringSchema();
    code.setDescription(
        "Stable application error code (docs/api/error-codes.md); each operation lists the codes"
            + " it can return. An open set: new codes are added without a new API version, so a"
            + " client must handle a code it does not know by the response's HTTP status. Existing"
            + " codes are never renamed, and removed only as a declared breaking change.");
    // #374: x-extensible-enum, not enum - adding a code is additive (a closed enum would make it a
    // breaking change for every error response). scripts/api_breaking.py guards removals through
    // api/problem-codes.json, which oasdiff cannot do for this extension.
    code.addExtension(
        EXTENSIBLE_ENUM, Arrays.stream(ProblemCode.values()).map(Enum::name).toList());
    problem.addProperty(ProblemCode.PROPERTY, code);
    problem.addProperty(
        "correlationId",
        new StringSchema()
            .description("Request correlation id; quote it when reporting an error."));
    problem.addProperty("instance", new StringSchema().format("uri"));

    ObjectSchema validationError = new ObjectSchema();
    validationError.addProperty("field", new StringSchema());
    validationError.addProperty("message", new StringSchema());
    problem.addProperty(
        "errors",
        new ArraySchema()
            .description("Present on bean-validation failures; one entry per rejected field.")
            .items(validationError));
    // #451: the keys every problem carries. Spring leaves out detail and instance when unset, and
    // errors exists only for a validation failure: those are absent then, never null.
    problem.setRequired(List.of("title", "status", ProblemCode.PROPERTY));
    return problem;
  }

  private static ApiResponse problemResponse() {
    io.swagger.v3.oas.models.media.MediaType problemMediaType =
        new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref(API_PROBLEM_REF));
    return new ApiResponse()
        .description("Error response using the TrackMyWealth problem-detail contract.")
        .content(
            new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, problemMediaType))
        .addHeaderObject(CorrelationIdFilter.HEADER, correlationResponseHeader());
  }

  // As a Map: Swagger's ApiResponses is a LinkedHashMap, and only map operations are needed here.
  private static Map<String, ApiResponse> responsesOf(
      io.swagger.v3.oas.models.Operation operation) {
    if (operation.getResponses() == null) {
      operation.setResponses(new ApiResponses());
    }
    return operation.getResponses();
  }

  private static void documentOptimisticConcurrency(io.swagger.v3.oas.models.Operation operation) {
    if (operation.getParameters() == null) {
      return;
    }
    operation.getParameters().stream()
        .filter(
            parameter ->
                IfMatchVersionParser.HEADER.equalsIgnoreCase(parameter.getName())
                    && "header".equals(parameter.getIn()))
        .findFirst()
        .ifPresent(
            parameter -> {
              parameter.setRequired(true);
              parameter.setDescription(
                  "Strong ETag of the resource version last read, for example \"7\". Missing"
                      + " values return 428 VERSION_REQUIRED; stale values return 412"
                      + " VERSION_CONFLICT.");
              Map<String, ApiResponse> responses = responsesOf(operation);
              responses.putIfAbsent("412", new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
              responses.putIfAbsent("428", new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
            });
  }

  private static void documentCorrelationRequestHeader(
      io.swagger.v3.oas.models.Operation operation) {
    boolean alreadyDocumented =
        operation.getParameters() != null
            && operation.getParameters().stream()
                .anyMatch(
                    parameter ->
                        CorrelationIdFilter.HEADER.equalsIgnoreCase(parameter.getName())
                            && "header".equals(parameter.getIn()));
    if (alreadyDocumented) {
      return;
    }
    operation.addParametersItem(
        new Parameter()
            .name(CorrelationIdFilter.HEADER)
            .in("header")
            .required(false)
            .description(
                "Optional caller correlation id. Must match [A-Za-z0-9._-]{8,64}; otherwise the"
                    + " server generates a UUID.")
            .schema(new StringSchema().minLength(8).maxLength(64).pattern("[A-Za-z0-9._-]{8,64}")));
  }

  private static void documentCorrelationResponseHeader(ApiResponse response) {
    if (response != null
        && (response.getHeaders() == null
            || !response.getHeaders().containsKey(CorrelationIdFilter.HEADER))) {
      response.addHeaderObject(CorrelationIdFilter.HEADER, correlationResponseHeader());
    }
  }

  private static Header correlationResponseHeader() {
    return new Header()
        .description("Correlation id assigned to this request.")
        .schema(new StringSchema());
  }
}
