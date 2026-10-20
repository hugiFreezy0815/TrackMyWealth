package com.trackmywealth.backend.config;

import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.web.CorrelationIdFilter;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.PagedArgumentResolver;
import com.trackmywealth.backend.web.RetryableWhenBusy;
import com.trackmywealth.backend.web.SortWhitelist;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Keeps the generated OpenAPI contract aligned with EPIC-29's runtime wire conventions.
 *
 * <p>Jackson serializes every {@link BigDecimal} as an exact plain decimal string ({@link
 * JacksonConfig}); Springdoc must describe the same type or generated clients will incorrectly use
 * floating-point numbers. Every operation also accepts the optional correlation-id header and
 * advertises the shared RFC 9457 error envelope.
 */
@Configuration
public class ApiDocumentationConfig {

  private static final String API_PROBLEM_SCHEMA = "ApiProblem";
  private static final String API_PROBLEM_RESPONSE = "ApiProblemResponse";
  private static final String RETRYABLE_PROBLEM_RESPONSE = "ApiProblemRetryableResponse";

  /**
   * A 503 a client may retry unchanged (e.g. {@code IMPORT_PDF_BUSY}): the problem envelope, with a
   * {@code Retry-After} header when the server can tell how long to wait.
   */
  public static final String RETRYABLE_PROBLEM_RESPONSE_REF =
      "#/components/responses/" + RETRYABLE_PROBLEM_RESPONSE;

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

  @Bean
  OpenApiCustomizer apiWireConventions() {
    return openApi -> {
      Components components = componentsOf(openApi);
      components.addSchemas(API_PROBLEM_SCHEMA, problemSchema());
      components.addResponses(API_PROBLEM_RESPONSE, problemResponse());
      components.addResponses(
          RETRYABLE_PROBLEM_RESPONSE,
          problemResponse()
              .description(
                  "The server cannot do this now; retry the same request later, after the"
                      + " Retry-After seconds when the header is sent.")
              .addHeaderObject(
                  HttpHeaders.RETRY_AFTER,
                  new Header()
                      .description("Seconds to wait before retrying.")
                      .schema(new IntegerSchema().format("int64"))));

      if (openApi.getPaths() == null) {
        return;
      }
      openApi
          .getPaths()
          .values()
          .forEach(
              pathItem ->
                  pathItem
                      .readOperations()
                      .forEach(
                          operation -> {
                            documentCorrelationRequestHeader(operation);
                            documentOptimisticConcurrency(operation);
                            Map<String, ApiResponse> responses = responsesOf(operation);
                            responses.putIfAbsent(
                                "default", new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
                            responses
                                .values()
                                .forEach(ApiDocumentationConfig::documentCorrelationResponseHeader);
                          }));
    };
  }

  /**
   * An endpoint marked {@link RetryableWhenBusy} documents its retryable 503 - added here rather
   * than by an {@code @ApiResponse} on the method, which would drop the generated success response.
   */
  @Bean
  OperationCustomizer retryableWhenBusyResponses() {
    return (operation, handlerMethod) -> {
      if (handlerMethod.hasMethodAnnotation(RetryableWhenBusy.class)) {
        responsesOf(operation)
            .putIfAbsent("503", new ApiResponse().$ref(RETRYABLE_PROBLEM_RESPONSE_REF));
      }
      return operation;
    };
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
          .ifPresent(paged -> documentPaging(operation, SortWhitelist.of(paged)));
      return operation;
    };
  }

  private static void documentPaging(
      io.swagger.v3.oas.models.Operation operation, SortWhitelist whitelist) {
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
    problem.addProperty(
        "code",
        new StringSchema()
            .description("Stable application error code. Existing values are never renamed."));
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
    return problem;
  }

  private static ApiResponse problemResponse() {
    io.swagger.v3.oas.models.media.MediaType problemMediaType =
        new io.swagger.v3.oas.models.media.MediaType()
            .schema(new Schema<>().$ref("#/components/schemas/" + API_PROBLEM_SCHEMA));
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
