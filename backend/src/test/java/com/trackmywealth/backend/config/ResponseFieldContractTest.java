package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * US-29-07 (#451): every response field is required and the {@code @Nullable} ones nullable;
 * request schemas keep their own required list, and a schema both use is split.
 */
class ResponseFieldContractTest {

  enum Kind {
    A,
    B
  }

  record Part(String column, @Nullable String pattern) {}

  record ItemResponse(
      String id,
      @Nullable String logoUrl,
      @Nullable Kind kind,
      @Nullable Part part,
      Part shared,
      String amount,
      Note nothing) {}

  record ItemRequest(String name, Part shared) {}

  @Test
  void everyResponsePropertyIsRequiredAndOnlyTheNullableOnesAllowNull() {
    OpenAPI api = document();

    ResponseFieldContract.apply(api, Set.of());

    Schema<?> item = schema(api, "ItemResponse");
    assertThat(item.getRequired())
        .containsExactlyInAnyOrderElementsOf(item.getProperties().keySet())
        .containsExactlyInAnyOrder("id", "logoUrl", "kind", "part", "shared", "amount", "nothing");
    Map<String, Schema> properties = item.getProperties();
    assertThat(properties.get("id").getTypes()).containsExactly("string");
    assertThat(properties.get("logoUrl").getTypes()).containsExactly("string", "null");
    assertThat(properties.get("kind").getTypes()).containsExactly("string", "null");
    assertThat(properties.get("kind").getEnum()).containsExactly("A", "B", null);
    List<Schema> either = properties.get("part").getAnyOf();
    assertThat(either).hasSize(2);
    // Part is used by the request too, so the response refers to its copy.
    assertThat(either.get(0).get$ref()).isEqualTo("#/components/schemas/PartResponse");
    assertThat(either.get(1).getTypes()).containsExactly("null");
    assertThat(properties.get("part").getDescription()).isEqualTo("The part");
    assertThat(either.get(0).getDescription()).isNull();
  }

  @Test
  void aPropertySchemaSharedBetweenPropertiesIsCopiedNotChanged() {
    OpenAPI api = document();
    Schema<?> decimal = schema(api, "ItemResponse").getProperties().get("logoUrl");

    ResponseFieldContract.apply(api, Set.of());

    assertThat(decimal.getTypes()).containsExactly("string");
    assertThat(schema(api, "ItemResponse").getProperties().get("logoUrl")).isNotSameAs(decimal);
  }

  @Test
  void requestSchemasKeepTheirRequiredListAndASharedSchemaGetsAResponseCopy() {
    OpenAPI api = document();

    ResponseFieldContract.apply(api, Set.of());

    assertThat(schema(api, "ItemRequest").getRequired()).containsExactly("name");
    Schema<?> requestPart = schema(api, "Part");
    assertThat(requestPart.getRequired()).isNull();
    assertThat(requestPart.getProperties().get("pattern").getTypes()).containsExactly("string");
    assertThat(schema(api, "ItemRequest").getProperties().get("shared").get$ref())
        .isEqualTo("#/components/schemas/Part");

    Schema<?> responsePart = schema(api, "PartResponse");
    assertThat(responsePart.getRequired()).containsExactlyInAnyOrder("column", "pattern");
    assertThat(responsePart.getProperties().get("pattern").getTypes())
        .containsExactly("string", "null");
    Schema<?> item = schema(api, "ItemResponse");
    assertThat(item.getProperties().get("shared").get$ref())
        .isEqualTo("#/components/schemas/PartResponse");
    List<Schema> part = item.getProperties().get("part").getAnyOf();
    assertThat(part.get(0).get$ref()).isEqualTo("#/components/schemas/PartResponse");
    // Note is the response's alone: its reference stays as it is.
    assertThat(item.getProperties().get("nothing").get$ref())
        .isEqualTo("#/components/schemas/Note");
  }

  @Test
  void theRecordMarksAreRemovedFromTheDocument() {
    OpenAPI api = document();

    ResponseFieldContract.apply(api, Set.of());

    api.getComponents()
        .getSchemas()
        .values()
        .forEach(schema -> assertThat(schema.getExtensions()).isNull());
  }

  @Test
  void aResponseSchemaWithoutARecordFailsNamingItUnlessItIsBuiltByHand() {
    OpenAPI api = document();
    schema(api, "ItemResponse").getExtensions().clear();

    assertThatThrownBy(() -> ResponseFieldContract.apply(api, Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ItemResponse is not generated from a record");

    OpenAPI handBuilt = document();
    schema(handBuilt, "ItemResponse").getExtensions().clear();
    ResponseFieldContract.apply(handBuilt, Set.of("ItemResponse"));
    assertThat(schema(handBuilt, "ItemResponse").getRequired()).isNull();
  }

  @Test
  void aPropertyNoComponentHasFailsNamingIt() {
    OpenAPI api = document();
    schema(api, "ItemResponse").addProperty("extra", new StringSchema());

    assertThatThrownBy(() -> ResponseFieldContract.apply(api, Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ItemResponse has the property extra");
  }

  @Test
  void aPropertyMadeNullableByItsSchemaAnnotationAloneFails() {
    OpenAPI api = document();
    Schema<?> amount = new StringSchema();
    amount.setTypes(new java.util.LinkedHashSet<>(List.of("string", "null")));
    schema(api, "ItemResponse").getProperties().put("amount", amount);

    assertThatThrownBy(() -> ResponseFieldContract.apply(api, Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ItemResponse.amount is nullable")
        .hasMessageContaining("mark the component @Nullable");
  }

  @Test
  void aTakenNameForTheResponseCopyFails() {
    OpenAPI api = document();
    api.getComponents().addSchemas("PartResponse", new ObjectSchema());

    assertThatThrownBy(() -> ResponseFieldContract.apply(api, Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PartResponse is taken");
  }

  @Test
  void aDocumentWithoutSchemasIsLeftAlone() {
    OpenAPI api = new OpenAPI();

    ResponseFieldContract.apply(api, Set.of());

    assertThat(api.getComponents()).isNull();
  }

  @Test
  void theNullableRuleIsTheComponentsJspecifyAnnotation() {
    var components = ItemResponse.class.getRecordComponents();

    assertThat(ResponseFieldContract.isNullable(components[0])).isFalse();
    assertThat(ResponseFieldContract.isNullable(components[1])).isTrue();
  }

  record Empty() {}

  record Note(String text) {}

  record Plain(String code, String more) {}

  /**
   * The parts of a document springdoc may leave out or shape differently: no paths, a response
   * without content, a media type without a schema, a reference to an unknown or external schema, a
   * component request body, composed and OpenAPI 3.0-style property schemas.
   */
  @Test
  void theDocumentsOtherShapesAreHandled() {
    Schema<?> empty = marked(new ObjectSchema(), Empty.class);
    empty.setProperties(new java.util.LinkedHashMap<>());
    Schema<?> plain = marked(new ObjectSchema(), Plain.class);
    plain.addExtension("x-other", true);
    Schema<?> code = new Schema<>();
    code.setType("string");
    code.setTypes(new java.util.LinkedHashSet<>());
    plain.addProperty("code", code);
    Schema<?> more = new Schema<>().allOf(List.of(new Schema<>().$ref("other.yaml#/Thing")));
    more.setNot(new Schema<>().$ref("#/components/schemas/Unknown"));
    plain.addProperty("more", more);
    Components components =
        new Components()
            .addSchemas("Empty", empty)
            .addSchemas("Plain", plain)
            .addRequestBodies(
                "Shared", new RequestBody().content(json("#/components/schemas/Unused")));
    components.addResponses("NoContent", new ApiResponse().description("No content"));
    components.addResponses(
        "Bare", new ApiResponse().content(new Content().addMediaType("a/b", new MediaType())));
    components.addResponses("Plain", new ApiResponse().content(json("#/components/schemas/Plain")));
    components.addResponses("Empty", new ApiResponse().content(json("#/components/schemas/Empty")));
    Operation bare = new Operation().responses(new ApiResponses().addApiResponse("200", null));
    OpenAPI api =
        new OpenAPI()
            .components(components)
            .paths(new Paths().addPathItem("/bare", new PathItem().get(bare)));

    ResponseFieldContract.apply(api, Set.of());

    assertThat(empty.getRequired()).isNull();
    assertThat(plain.getRequired()).containsExactlyInAnyOrder("code", "more");
    assertThat(plain.getExtensions()).containsOnlyKeys("x-other");
    assertThat(plain.getProperties().get("code")).isSameAs(code);
  }

  @Test
  void aRepointedSchemaKeepsItsOtherReferences() {
    Schema<?> note = marked(new ObjectSchema(), Note.class);
    note.addProperty(
        "text",
        new Schema<>()
            .allOf(
                List.of(
                    new Schema<>().$ref("other.yaml#/Thing"),
                    new Schema<>().$ref("#/components/schemas/Shared"))));
    Schema<?> shared = marked(new ObjectSchema(), Note.class);
    shared.addProperty("text", new StringSchema());
    Components components = new Components().addSchemas("Note", note).addSchemas("Shared", shared);
    components.addResponses("Note", new ApiResponse().content(json("#/components/schemas/Note")));
    Operation create =
        new Operation().requestBody(new RequestBody().content(json("#/components/schemas/Shared")));
    OpenAPI api =
        new OpenAPI()
            .components(components)
            .paths(new Paths().addPathItem("/notes", new PathItem().post(create)));

    ResponseFieldContract.apply(api, Set.of());

    List<Schema> parts = schema(api, "Note").getProperties().get("text").getAllOf();
    assertThat(parts.get(0).get$ref()).isEqualTo("other.yaml#/Thing");
    assertThat(parts.get(1).get$ref()).isEqualTo("#/components/schemas/SharedResponse");
    assertThat(schema(api, "SharedResponse").getRequired()).containsExactly("text");
    assertThat(schema(api, "Shared").getRequired()).isNull();
  }

  @Test
  void aNullableComposedOrOldStylePropertyIsWrappedOrTyped() {
    Schema<?> composed =
        new Schema<>().allOf(List.of(new Schema<>().$ref("#/components/schemas/A")));
    Schema<?> wrapped = ResponseFieldContract.nullable(composed);
    assertThat(wrapped.getAnyOf()).hasSize(2);

    Schema<Object> oldStyle = new Schema<>();
    oldStyle.setType("string");
    assertThat(ResponseFieldContract.nullable(oldStyle).getTypes())
        .containsExactly("string", "null");

    Schema<Object> enumWithNull = new Schema<>();
    enumWithNull.setTypes(new java.util.LinkedHashSet<>(List.of("string")));
    enumWithNull.setEnum(new java.util.ArrayList<>(java.util.Arrays.asList("A", null)));
    List<Object> values =
        new java.util.ArrayList<>(ResponseFieldContract.nullable(enumWithNull).getEnum());
    assertThat(values).containsExactly("A", null);
  }

  @Test
  void aPropertyFlaggedNullableTheOpenApi30WayWithoutNullableFails() {
    Schema<?> plain = marked(new ObjectSchema(), Plain.class);
    plain.addProperty("code", new StringSchema().nullable(true));
    plain.addProperty("more", new StringSchema());
    Components components = new Components().addSchemas("Plain", plain);
    components.addResponses("Plain", new ApiResponse().content(json("#/components/schemas/Plain")));

    assertThatThrownBy(
            () -> ResponseFieldContract.apply(new OpenAPI().components(components), Set.of()))
        .hasMessageContaining("Plain.code is nullable");
  }

  @Test
  void aDocumentWithComponentsButNoSchemasIsLeftAlone() {
    OpenAPI api = new OpenAPI().components(new Components());

    ResponseFieldContract.apply(api, Set.of());

    assertThat(api.getComponents().getSchemas()).isNull();
  }

  private static Schema<?> marked(Schema<?> schema, Class<?> record) {
    schema.addExtension(RecordSchemaMarker.RECORD_EXTENSION, record);
    return schema;
  }

  /**
   * GET /items answers an ItemResponse, POST /items takes an ItemRequest: the schemas as springdoc
   * would generate them, marked by {@link RecordSchemaMarker}. {@code logoUrl} and {@code amount}
   * share one property schema instance, as springdoc shares the decimal string's.
   */
  private static OpenAPI document() {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new RecordSchemaMarker());
    Map<String, Schema> schemas = new java.util.TreeMap<>();
    schemas.putAll(converters.readAll(ItemResponse.class));
    schemas.putAll(converters.readAll(ItemRequest.class));
    schemas.get("ItemRequest").setRequired(new java.util.ArrayList<>(List.of("name")));
    Schema<?> item = schemas.get("ItemResponse");
    item.getProperties().get("part").setDescription("The part");
    item.getProperties().put("amount", item.getProperties().get("logoUrl"));

    Operation list =
        new Operation()
            .responses(
                new ApiResponses()
                    .addApiResponse(
                        "200",
                        new ApiResponse().content(json("#/components/schemas/ItemResponse"))));
    Operation create =
        new Operation()
            .requestBody(new RequestBody().content(json("#/components/schemas/ItemRequest")));
    Components components = new Components();
    components.setSchemas(schemas);
    return new OpenAPI()
        .components(components)
        .paths(new Paths().addPathItem("/items", new PathItem().get(list).post(create)));
  }

  private static Content json(String ref) {
    return new Content()
        .addMediaType("application/json", new MediaType().schema(new Schema<>().$ref(ref)));
  }

  private static Schema<?> schema(OpenAPI api, String name) {
    return api.getComponents().getSchemas().get(name);
  }
}
