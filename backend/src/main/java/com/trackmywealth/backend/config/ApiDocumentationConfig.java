package com.trackmywealth.backend.config;

import com.trackmywealth.backend.web.CorrelationIdFilter;
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
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
  private static final String DECIMAL_FORMAT = "decimal";
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
                            ApiResponses responses = responsesOf(operation);
                            if (!responses.containsKey("default")) {
                              responses.addApiResponse(
                                  "default", new ApiResponse().$ref(PROBLEM_RESPONSE_REF));
                            }
                            responses
                                .values()
                                .forEach(ApiDocumentationConfig::documentCorrelationResponseHeader);
                          }));
    };
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
        new StringSchema().description("Request correlation id; quote it when reporting an error."));
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
        .content(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, problemMediaType))
        .addHeaderObject(CorrelationIdFilter.HEADER, correlationResponseHeader());
  }

  private static ApiResponses responsesOf(io.swagger.v3.oas.models.Operation operation) {
    if (operation.getResponses() == null) {
      operation.setResponses(new ApiResponses());
    }
    return operation.getResponses();
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
