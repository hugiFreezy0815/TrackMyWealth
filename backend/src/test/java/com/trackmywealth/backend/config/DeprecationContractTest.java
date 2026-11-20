package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.web.ApiDeprecation;
import com.trackmywealth.backend.web.Deprecations.Deprecation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeprecationContractTest {

  private static final String NOTE = "https://example.org/migrate#old";

  record Item(
      String name,
      @ApiDeprecation(since = "2027-03-01", migration = NOTE, sunset = "2027-09-01")
          String oldName) {}

  record Soon(
      @ApiDeprecation(since = "2027-04-01", migration = NOTE) String code, String notDocumented) {}

  @Test
  void aDeprecatedOperationIsMarkedAndItsSuccessResponsesListTheHeaders() {
    Operation operation = operationWithResponses().description("Lists the items.");

    DeprecationContract.document(
        operation,
        new Deprecation(true, LocalDate.of(2027, 2, 1), null, List.of(URI.create(NOTE))));

    assertThat(operation.getDeprecated()).isTrue();
    assertThat(operation.getDescription())
        .isEqualTo("Lists the items. Deprecated since 2027-02-01. Migration: " + NOTE + ".");
    ApiResponses responses = operation.getResponses();
    assertThat(responses.get("200").getHeaders())
        .containsKeys("X-Correlation-Id", "Deprecation", "Link")
        .doesNotContainKey("Sunset");
    assertThat(responses.get("200").getHeaders().get("Deprecation").getRequired()).isTrue();
    assertThat(responses.get("201").getHeaders()).containsKeys("Deprecation", "Link");
    // Error responses refer to the shared problem response, which cannot list them.
    assertThat(responses.get("default").getHeaders()).isNull();
    assertThat(responses.get("409").getHeaders()).isNull();
    assertThat(responses.get("400").getHeaders()).isNull();
  }

  @Test
  void aDeprecatedOperationWithASunsetSaysSoAndABlankDescriptionIsReplaced() {
    Operation operation = operationWithResponses().description(" ");

    DeprecationContract.document(
        operation,
        new Deprecation(
            true, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 9, 1), List.of(URI.create(NOTE))));

    assertThat(operation.getDescription())
        .isEqualTo(
            "Deprecated since 2027-02-01, removed from 2027-09-01. Migration: " + NOTE + ".");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aFieldWithoutSunsetAndComponentsWithoutPropertiesAreHandled() {
    Schema soon = new ObjectSchema();
    soon.addProperty("code", new StringSchema().description("The code."));
    soon.addExtension(RecordSchemaMarker.RECORD_EXTENSION, Soon.class);
    Schema empty = new ObjectSchema();
    empty.addExtension(RecordSchemaMarker.RECORD_EXTENSION, Soon.class);
    OpenAPI openApi =
        new OpenAPI()
            .components(new Components().addSchemas("Soon", soon).addSchemas("Empty", empty));

    DeprecationContract.markFields(openApi);

    assertThat(((Schema) soon.getProperties().get("code")).getDescription())
        .isEqualTo("The code. Deprecated since 2027-04-01. Migration: " + NOTE + ".");
    assertThat(soon.getProperties()).containsOnlyKeys("code");
    assertThat(empty.getProperties()).isNull();
  }

  @Test
  void anOperationWithOnlyADeprecatedFieldStaysUnmarkedButListsTheSunset() {
    Operation operation = operationWithResponses();

    DeprecationContract.document(
        operation,
        new Deprecation(
            false, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 9, 1), List.of(URI.create(NOTE))));

    assertThat(operation.getDeprecated()).isNull();
    assertThat(operation.getDescription()).isNull();
    assertThat(operation.getResponses().get("200").getHeaders())
        .containsKeys("Deprecation", "Link", "Sunset");
    assertThat(operation.getResponses().get("200").getHeaders().get("Sunset").getDescription())
        .contains("Wed, 01 Sep 2027 00:00:00 GMT");
  }

  @Test
  void anOperationWithoutResponsesIsOnlyMarked() {
    Operation operation = new Operation();

    DeprecationContract.document(
        operation,
        new Deprecation(true, LocalDate.of(2027, 2, 1), null, List.of(URI.create(NOTE))));

    assertThat(operation.getDeprecated()).isTrue();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void marksTheDeprecatedPropertyOnACopyOfItsSchema() {
    StringSchema shared = new StringSchema();
    Schema item = new ObjectSchema();
    item.addProperty("name", shared);
    item.addProperty("oldName", shared);
    item.addExtension(RecordSchemaMarker.RECORD_EXTENSION, Item.class);
    Schema unmarked = new ObjectSchema().addProperty("oldName", shared);
    OpenAPI openApi =
        new OpenAPI()
            .components(new Components().addSchemas("Item", item).addSchemas("Other", unmarked));

    DeprecationContract.markFields(openApi);

    Schema oldName = (Schema) item.getProperties().get("oldName");
    assertThat(oldName.getDeprecated()).isTrue();
    assertThat(oldName.getDescription())
        .isEqualTo(
            "Deprecated since 2027-03-01, removed from 2027-09-01. Migration: " + NOTE + ".");
    assertThat(shared.getDeprecated()).isNull();
    assertThat(((Schema) item.getProperties().get("name")).getDeprecated()).isNull();
    assertThat(((Schema) unmarked.getProperties().get("oldName")).getDeprecated()).isNull();
    // The mark stays for ResponseFieldContract, which takes it.
    assertThat(item.getExtensions()).containsKey(RecordSchemaMarker.RECORD_EXTENSION);
  }

  @Test
  void aDocumentWithoutSchemasIsLeftAlone() {
    OpenAPI openApi = new OpenAPI();
    DeprecationContract.markFields(openApi);
    assertThat(openApi.getComponents()).isNull();

    OpenAPI withoutSchemas = new OpenAPI().components(new Components());
    DeprecationContract.markFields(withoutSchemas);
    assertThat(withoutSchemas.getComponents().getSchemas()).isNull();
  }

  private static Operation operationWithResponses() {
    ApiResponses responses = new ApiResponses();
    responses.addApiResponse(
        "200",
        new ApiResponse().description("OK").addHeaderObject("X-Correlation-Id", new Header()));
    responses.addApiResponse("201", new ApiResponse().description("Created"));
    responses.addApiResponse("400", new ApiResponse().description("Bad Request"));
    responses.addApiResponse(
        "409", new ApiResponse().$ref("#/components/responses/ApiProblemResponse"));
    responses.addApiResponse(
        "default", new ApiResponse().$ref("#/components/responses/ApiProblemResponse"));
    return new Operation().responses(responses);
  }
}
