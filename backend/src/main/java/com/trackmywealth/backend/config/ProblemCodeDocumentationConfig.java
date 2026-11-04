package com.trackmywealth.backend.config;

import com.trackmywealth.backend.error.ClientReaction;
import com.trackmywealth.backend.error.ImportRowCode;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.web.CorrelationIdFilter;
import com.trackmywealth.backend.web.OperationProblemCodes;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Puts the problem-code catalogue (US-29-04, #374) into the OpenAPI contract: per operation, one
 * error response per status listing the codes it can carry ({@link OperationProblemCodes}), and the
 * same list as {@code x-problem-codes}; and the row codes as the enum of {@code
 * ImportRowErrorResponse.code}. The {@code ApiProblem.code} enum itself is in {@link
 * ApiDocumentationConfig}'s schema.
 */
@Configuration
public class ProblemCodeDocumentationConfig {

  /** The operation extension that lists every code the operation can answer with. */
  public static final String PROBLEM_CODES_EXTENSION = "x-problem-codes";

  private static final String ROW_ERROR_SCHEMA = "ImportRowErrorResponse";

  @Bean
  OperationCustomizer problemCodeResponses() {
    return (operation, handlerMethod) -> {
      document(operation, OperationProblemCodes.documentedWithStatus(handlerMethod.getMethod()));
      return operation;
    };
  }

  // Any order before ApiDocumentOrder's, which must run last (#372).
  private static final int ROW_CODES_ORDER = 0;

  @Bean
  OpenApiCustomizer importRowCodes() {
    return new OrderedOpenApiCustomizer(
        ROW_CODES_ORDER, ProblemCodeDocumentationConfig::documentRowCodes);
  }

  private static void documentRowCodes(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    Schema<?> rowError = openApi.getComponents().getSchemas().get(ROW_ERROR_SCHEMA);
    if (rowError == null || rowError.getProperties() == null) {
      return;
    }
    StringSchema code = new StringSchema();
    code.setDescription(
        "Stable row code; the text is tmw.import.row.<code> with args in order"
            + " (docs/api/error-codes.md). An open set: a client shows message for a code it does"
            + " not know. Existing codes are never renamed, and removed only as a declared"
            + " breaking change.");
    code.addExtension(
        ApiDocumentationConfig.EXTENSIBLE_ENUM,
        Arrays.stream(ImportRowCode.values()).map(Enum::name).toList());
    rowError.getProperties().put("code", code);
  }

  /**
   * Documents {@code codes} on {@code operation}, each under the status the operation answers it
   * with. Package-private for the unit test: it replaces the generic {@code ApiProblemResponse}
   * reference another customizer may have put on a status.
   */
  static void document(Operation operation, Map<ProblemCode, HttpStatus> codes) {
    Map<Integer, List<ProblemCode>> byStatus = new TreeMap<>();
    codes.forEach(
        (code, status) ->
            byStatus.computeIfAbsent(status.value(), value -> new ArrayList<>()).add(code));
    if (operation.getResponses() == null) {
      operation.setResponses(new ApiResponses());
    }
    byStatus.forEach(
        (status, statusCodes) ->
            operation.getResponses().put(Integer.toString(status), response(statusCodes)));
    operation.addExtension(
        PROBLEM_CODES_EXTENSION, codes.keySet().stream().map(Enum::name).toList());
  }

  private static ApiResponse response(List<ProblemCode> codes) {
    StringBuilder description = new StringBuilder("Problem codes:");
    for (ProblemCode code : codes) {
      description.append("\n- `").append(code.name()).append("`: ").append(code.meaning());
    }
    ApiResponse response =
        new ApiResponse()
            .description(description.toString())
            .content(
                new Content()
                    .addMediaType(
                        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        new MediaType()
                            .schema(new Schema<>().$ref(ApiDocumentationConfig.API_PROBLEM_REF))))
            .addHeaderObject(
                CorrelationIdFilter.HEADER,
                new Header()
                    .description("Correlation id assigned to this request.")
                    .schema(new StringSchema()));
    if (codes.stream().anyMatch(code -> code.reaction() == ClientReaction.RETRY_LATER)) {
      response.addHeaderObject(
          HttpHeaders.RETRY_AFTER,
          new Header()
              .description("Seconds to wait before retrying, when the server can tell.")
              .schema(new IntegerSchema().format("int64")));
    }
    return response;
  }
}
