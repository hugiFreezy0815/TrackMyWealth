package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.error.ImportRowCode;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.web.CorrelationIdFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** US-29-04 (#374): how an operation's problem codes appear in the OpenAPI contract. */
class ProblemCodeDocumentationConfigTest {

  @Test
  void eachStatusGetsOneResponseListingItsCodes() {
    Operation operation = new Operation();

    ProblemCodeDocumentationConfig.document(
        operation,
        EnumSet.of(
            ProblemCode.NOT_FOUND,
            ProblemCode.ACCOUNT_ARCHIVED,
            ProblemCode.IMPORT_BATCH_STATE,
            ProblemCode.INTERNAL));

    assertThat(operation.getResponses()).containsOnlyKeys("404", "409", "500");
    ApiResponse conflict = operation.getResponses().get("409");
    assertThat(conflict.getDescription())
        .contains("`ACCOUNT_ARCHIVED`: " + ProblemCode.ACCOUNT_ARCHIVED.meaning())
        .contains("`IMPORT_BATCH_STATE`: " + ProblemCode.IMPORT_BATCH_STATE.meaning())
        .doesNotContain("NOT_FOUND");
    assertThat(conflict.getContent().get("application/problem+json").getSchema().get$ref())
        .isEqualTo(ApiDocumentationConfig.API_PROBLEM_REF);
    assertThat(conflict.getHeaders()).containsOnlyKeys(CorrelationIdFilter.HEADER);
    assertThat(operation.getExtensions())
        .containsEntry(
            ProblemCodeDocumentationConfig.PROBLEM_CODES_EXTENSION,
            List.of("NOT_FOUND", "ACCOUNT_ARCHIVED", "IMPORT_BATCH_STATE", "INTERNAL"));
  }

  @Test
  void aStatusTheClientRetriesLaterDocumentsRetryAfter() {
    Operation operation = new Operation();

    ProblemCodeDocumentationConfig.document(
        operation, EnumSet.of(ProblemCode.IMPORT_PDF_BUSY, ProblemCode.RATE_LIMITED));

    assertThat(operation.getResponses().get("503").getHeaders())
        .containsKeys(HttpHeaders.RETRY_AFTER, CorrelationIdFilter.HEADER);
    assertThat(operation.getResponses().get("429").getHeaders())
        .containsKey(HttpHeaders.RETRY_AFTER);
  }

  @Test
  void theGenericReferenceAnotherCustomizerPutOnAStatusIsReplaced() {
    Operation operation = new Operation().responses(new ApiResponses());
    operation.getResponses().addApiResponse("412", new ApiResponse().$ref("#/x"));
    operation.getResponses().addApiResponse("200", new ApiResponse().description("OK"));

    ProblemCodeDocumentationConfig.document(operation, EnumSet.of(ProblemCode.VERSION_CONFLICT));

    assertThat(operation.getResponses().get("412").get$ref()).isNull();
    assertThat(operation.getResponses().get("412").getDescription()).contains("VERSION_CONFLICT");
    assertThat(operation.getResponses().get("200").getDescription()).isEqualTo("OK");
  }

  @Test
  void theRowErrorCodeIsTheRowCodeEnum() {
    ObjectSchema rowError = new ObjectSchema();
    rowError.addProperty("code", new StringSchema());
    OpenAPI openApi =
        new OpenAPI().components(new Components().addSchemas("ImportRowErrorResponse", rowError));

    new ProblemCodeDocumentationConfig().importRowCodes().customise(openApi);

    assertThat(rowError.getProperties().get("code").getEnum())
        .containsExactlyElementsOf(Arrays.stream(ImportRowCode.values()).map(Enum::name).toList());
  }

  @Test
  void aSpecWithoutTheRowSchemaIsLeftAlone() {
    OpenAPI empty = new OpenAPI();
    new ProblemCodeDocumentationConfig().importRowCodes().customise(empty);
    assertThat(empty.getComponents()).isNull();

    OpenAPI other = new OpenAPI().components(new Components().addSchemas("X", new ObjectSchema()));
    new ProblemCodeDocumentationConfig().importRowCodes().customise(other);
    assertThat(other.getComponents().getSchemas()).containsOnlyKeys("X");
  }
}
