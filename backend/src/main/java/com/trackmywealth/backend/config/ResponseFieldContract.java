package com.trackmywealth.backend.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.core.util.AnnotationsUtils;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * US-29-07 (#451): every response schema says which fields are always there and which can be null,
 * so a client generated from {@code api/openapi.yaml} gets {@code string} or {@code string | null},
 * never an optional field the backend always sends.
 *
 * <p><b>Presence:</b> Jackson writes every component of a response record, a null one as {@code
 * null} (a response record that sets {@code @JsonInclude} or has an {@code Optional} component
 * fails the generation), so every property of a response schema is {@code required}.
 * <b>Nullability:</b> a property is nullable exactly when its record component is JSpecify {@link
 * Nullable} (ADR 0006); the record comes from {@link RecordSchemaMarker}. A nullable property is
 * {@code type: [<type>, "null"]} (a {@code null} added to an enum's values), or {@code anyOf} its
 * schema and {@code null} for a reference.
 *
 * <p>Request schemas are left as they are: there, {@code required} is what bean validation demands
 * of the caller. A schema used by a request and a response alike (the import-template parts) is
 * split: requests keep it, responses refer to a copy named {@code <name>Response}, so the two never
 * constrain each other. A schema built by hand ({@code ApiProblem}) declares its own required list
 * and is left alone; any other response schema without a record fails the document's generation,
 * naming it, rather than going out half described.
 */
final class ResponseFieldContract {

  /** The suffix of a shared schema's response copy. */
  static final String RESPONSE_COPY_SUFFIX = "Response";

  private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
  private static final String NULL_TYPE = "null";
  private static final String RESPONSE_SCHEMA = "Response schema ";

  /** The inclusions that write a null component as {@code null}; the global default is ALWAYS. */
  private static final Set<JsonInclude.Include> WRITES_EVERY_KEY =
      EnumSet.of(JsonInclude.Include.ALWAYS, JsonInclude.Include.USE_DEFAULTS);

  /** Component types Jackson writes as {@code null} when empty. */
  private static final Set<Class<?>> OPTIONALS =
      Set.of(Optional.class, OptionalInt.class, OptionalLong.class, OptionalDouble.class);

  private ResponseFieldContract() {}

  /**
   * Applies the contract to {@code openApi}; {@code handBuilt} names the schemas built in code that
   * declare their own required list.
   */
  static void apply(OpenAPI openApi, Set<String> handBuilt) {
    Components components = openApi.getComponents();
    if (components == null || components.getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = components.getSchemas();
    Map<String, Class<?>> records = takeRecordMarks(schemas);

    Set<String> requestSide = closure(requestRoots(openApi), schemas);
    Set<String> responseSide = closure(responseRoots(openApi), schemas);
    Set<String> shared = new TreeSet<>(requestSide);
    shared.retainAll(responseSide);

    Map<String, String> copyNames = new LinkedHashMap<>();
    for (String name : shared) {
      String copyName = name + RESPONSE_COPY_SUFFIX;
      if (schemas.containsKey(copyName)) {
        throw new IllegalStateException(
            "Schema "
                + name
                + " is used by requests and responses, but its response copy's name "
                + copyName
                + " is taken: rename one of the records (#451)");
      }
      copyNames.put(name, copyName);
    }

    Set<String> responseSchemas = new TreeSet<>(responseSide);
    responseSchemas.removeAll(shared);
    UnaryOperator<Schema> toCopies = schema -> repointed(schema, copyNames);
    for (String name : responseSchemas) {
      schemas.put(name, toCopies.apply(schemas.get(name)));
    }
    copyNames.forEach(
        (name, copyName) -> {
          schemas.put(copyName, repointedCopy(schemas.get(name), copyNames));
          records.put(copyName, records.get(name));
          responseSchemas.add(copyName);
        });
    forEachResponseContent(openApi, content -> repointContent(content, toCopies));

    for (String name : responseSchemas) {
      if (handBuilt.contains(name)) {
        continue;
      }
      Class<?> record = records.get(name);
      if (record == null) {
        throw new IllegalStateException(
            RESPONSE_SCHEMA
                + name
                + " is not generated from a record, so its fields' presence and nullability are"
                + " unknown: answer with a record, or build the schema by hand with its own"
                + " required list (#451)");
      }
      requireEveryKeyWritten(name, record);
      document(name, schemas.get(name), record);
    }
  }

  /**
   * Fails when Jackson could leave a key of {@code record} out, which would make {@code required} a
   * lie: an {@code @JsonInclude} other than {@code ALWAYS} on the record or a component, or an
   * {@code Optional} component (an empty one is written as {@code null}, a value the walk of the
   * tests cannot see). The global default is checked by {@code OpenApiContractTest}.
   */
  private static void requireEveryKeyWritten(String name, Class<?> record) {
    if (omitsKeys(record.getAnnotation(JsonInclude.class))) {
      throw new IllegalStateException(
          RESPONSE_SCHEMA
              + name
              + ": "
              + record.getSimpleName()
              + " sets @JsonInclude, so Jackson may leave keys out that the contract lists as"
              + " required - remove it; a field that can be null is @Nullable (#451)");
    }
    for (RecordComponent component : record.getRecordComponents()) {
      String where = record.getSimpleName() + "." + component.getName();
      // JsonInclude cannot target a record component; javac puts it on the accessor (and field).
      if (omitsKeys(component.getAccessor().getAnnotation(JsonInclude.class))) {
        throw new IllegalStateException(
            RESPONSE_SCHEMA
                + name
                + ": "
                + where
                + " sets @JsonInclude, so Jackson may leave the key out that the contract lists"
                + " as required - remove it; a field that can be null is @Nullable (#451)");
      }
      if (OPTIONALS.contains(component.getType())) {
        throw new IllegalStateException(
            RESPONSE_SCHEMA
                + name
                + ": "
                + where
                + " is an Optional - answer with the value and mark the component @Nullable"
                + " (org.jspecify.annotations) (#451)");
      }
    }
  }

  private static boolean omitsKeys(@Nullable JsonInclude include) {
    return include != null && !WRITES_EVERY_KEY.contains(include.value());
  }

  /** The record each marked schema comes from; the marks are removed from the document. */
  private static Map<String, Class<?>> takeRecordMarks(Map<String, Schema> schemas) {
    Map<String, Class<?>> records = new HashMap<>();
    schemas.forEach(
        (name, schema) -> {
          Map<String, Object> extensions = schema.getExtensions();
          if (extensions == null) {
            return;
          }
          Object record = extensions.remove(RecordSchemaMarker.RECORD_EXTENSION);
          if (record instanceof Class<?> type) {
            records.put(name, type);
          }
          // A missing section stays missing: an empty map would be written as "{}".
          if (extensions.isEmpty()) {
            schema.setExtensions(null);
          }
        });
    return records;
  }

  /**
   * Makes every property of {@code schema} required, and the ones whose component is {@link
   * Nullable} nullable. A property without a component of that name fails: Jackson would write
   * another field than the document describes.
   */
  private static void document(String name, Schema<?> schema, Class<?> record) {
    Map<String, Schema> properties = schema.getProperties();
    if (properties == null || properties.isEmpty()) {
      return;
    }
    Map<String, RecordComponent> byName =
        Arrays.stream(record.getRecordComponents())
            .collect(Collectors.toMap(RecordComponent::getName, component -> component));
    schema.setRequired(new ArrayList<>(properties.keySet()));
    for (Map.Entry<String, Schema> property : properties.entrySet()) {
      RecordComponent component = byName.get(property.getKey());
      if (component == null) {
        throw new IllegalStateException(
            RESPONSE_SCHEMA
                + name
                + " has the property "
                + property.getKey()
                + ", which is no component of "
                + record.getName()
                + " (#451)");
      }
      if (isNullable(component)) {
        property.setValue(nullable(property.getValue()));
      } else if (allowsNull(property.getValue())) {
        throw new IllegalStateException(
            RESPONSE_SCHEMA
                + name
                + "."
                + property.getKey()
                + " is nullable, but "
                + record.getSimpleName()
                + "."
                + component.getName()
                + " is not @Nullable: mark the component @Nullable"
                + " (org.jspecify.annotations), not its @Schema (#451)");
      }
    }
  }

  /** Whether the component is declared {@link Nullable} - a type-use annotation on its type. */
  static boolean isNullable(RecordComponent component) {
    return component.getAnnotatedType().isAnnotationPresent(Nullable.class);
  }

  /**
   * The nullable form of {@code property}, as a copy: springdoc shares some property schemas
   * between all properties of a type (the decimal string), which must stay as they are.
   */
  static Schema<?> nullable(Schema<?> property) {
    // Read before copying: a 3.1 copy keeps only the 3.1 type list, not a 3.0-style single type.
    Set<String> types = typesOf(property);
    Schema<?> copy = AnnotationsUtils.clone(property, true);
    // OpenAPI 3.0's flag; 3.1 says it with the null type alone.
    copy.setNullable(null);
    if (copy.get$ref() != null || types.isEmpty()) {
      Schema<Object> either = new Schema<>();
      either.setDescription(copy.getDescription());
      copy.setDescription(null);
      either.setAnyOf(List.of(copy, new Schema<>().types(Set.of(NULL_TYPE))));
      return either;
    }
    Set<String> withNull = new LinkedHashSet<>(types);
    withNull.add(NULL_TYPE);
    copy.setType(null);
    copy.setTypes(withNull);
    if (copy.getEnum() != null && !copy.getEnum().contains(null)) {
      List<Object> values = new ArrayList<>(copy.getEnum());
      values.add(null);
      ((Schema<Object>) copy).setEnum(values);
    }
    return copy;
  }

  /** Whether springdoc already made {@code property} nullable, from a {@code @Schema} on it. */
  private static boolean allowsNull(Schema<?> property) {
    return Boolean.TRUE.equals(property.getNullable()) || typesOf(property).contains(NULL_TYPE);
  }

  private static Set<String> typesOf(Schema<?> schema) {
    if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
      return schema.getTypes();
    }
    return schema.getType() == null ? Set.of() : Set.of(schema.getType());
  }

  /** The schemas the request bodies and parameters refer to, directly. */
  private static Set<String> requestRoots(OpenAPI openApi) {
    Set<String> roots = new HashSet<>();
    forEachOperation(
        openApi,
        operation -> {
          RequestBody body = operation.getRequestBody();
          if (body != null) {
            contentRefs(body.getContent(), roots);
          }
          if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
              refs(parameter.getSchema(), roots);
              contentRefs(parameter.getContent(), roots);
            }
          }
        });
    Components components = openApi.getComponents();
    if (components.getRequestBodies() != null) {
      components.getRequestBodies().values().forEach(body -> contentRefs(body.getContent(), roots));
    }
    return roots;
  }

  /** The schemas the responses (the operations' and the shared ones) refer to, directly. */
  private static Set<String> responseRoots(OpenAPI openApi) {
    Set<String> roots = new HashSet<>();
    forEachResponseContent(openApi, content -> contentRefs(content, roots));
    return roots;
  }

  // Content as a Map: Swagger's Content is a LinkedHashMap, and only map operations are needed.
  private static void forEachResponseContent(
      OpenAPI openApi, Consumer<Map<String, MediaType>> action) {
    forEachOperation(
        openApi,
        operation -> {
          if (operation.getResponses() != null) {
            operation.getResponses().values().forEach(response -> contentOf(response, action));
          }
        });
    Map<String, ApiResponse> shared = openApi.getComponents().getResponses();
    if (shared != null) {
      shared.values().forEach(response -> contentOf(response, action));
    }
  }

  private static void contentOf(ApiResponse response, Consumer<Map<String, MediaType>> action) {
    if (response != null && response.getContent() != null) {
      action.accept(response.getContent());
    }
  }

  private static void forEachOperation(OpenAPI openApi, Consumer<Operation> action) {
    if (openApi.getPaths() == null) {
      return;
    }
    for (PathItem item : openApi.getPaths().values()) {
      item.readOperations().forEach(action);
    }
  }

  private static void contentRefs(Map<String, MediaType> content, Set<String> names) {
    if (content != null) {
      content.values().forEach(media -> refs(media.getSchema(), names));
    }
  }

  private static void repointContent(
      Map<String, MediaType> content, UnaryOperator<Schema> repoint) {
    content.values().forEach(media -> media.setSchema(repoint.apply(media.getSchema())));
  }

  /** The names {@code roots} refer to, and every name those schemas refer to in turn. */
  static Set<String> closure(Set<String> roots, Map<String, Schema> schemas) {
    Set<String> reached = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>(roots);
    while (!pending.isEmpty()) {
      String name = pending.pop();
      if (reached.add(name) && schemas.containsKey(name)) {
        Set<String> next = new HashSet<>();
        refs(schemas.get(name), next);
        pending.addAll(next);
      }
    }
    reached.retainAll(schemas.keySet());
    return reached;
  }

  /** Adds the names {@code schema} refers to, anywhere inside it, to {@code names}. */
  private static void refs(Schema<?> schema, Set<String> names) {
    if (schema == null) {
      return;
    }
    String ref = schema.get$ref();
    if (ref != null && ref.startsWith(SCHEMA_REF_PREFIX)) {
      names.add(ref.substring(SCHEMA_REF_PREFIX.length()));
    }
    children(schema).forEach(child -> refs(child, names));
  }

  private static List<Schema> children(Schema<?> schema) {
    List<Schema> children = new ArrayList<>();
    if (schema.getProperties() != null) {
      children.addAll(schema.getProperties().values());
    }
    if (schema.getItems() != null) {
      children.add(schema.getItems());
    }
    if (schema.getAdditionalProperties() instanceof Schema<?> values) {
      children.add(values);
    }
    addAll(children, schema.getAllOf());
    addAll(children, schema.getAnyOf());
    addAll(children, schema.getOneOf());
    if (schema.getNot() != null) {
      children.add(schema.getNot());
    }
    return children;
  }

  private static void addAll(List<Schema> children, @Nullable Collection<Schema> more) {
    if (more != null) {
      children.addAll(more);
    }
  }

  /**
   * {@code schema} with its references to shared schemas pointing at their response copies: itself
   * when it has none, otherwise a copy - its parts may be shared with request schemas.
   */
  private static Schema repointed(Schema<?> schema, Map<String, String> copyNames) {
    if (schema == null || copyNames.isEmpty()) {
      return schema;
    }
    Set<String> referred = new HashSet<>();
    refs(schema, referred);
    referred.retainAll(copyNames.keySet());
    return referred.isEmpty() ? schema : repointedCopy(schema, copyNames);
  }

  private static Schema repointedCopy(Schema<?> schema, Map<String, String> copyNames) {
    Schema<?> copy = AnnotationsUtils.clone(schema, true);
    repointInPlace(copy, copyNames);
    return copy;
  }

  private static void repointInPlace(Schema<?> schema, Map<String, String> copyNames) {
    String ref = schema.get$ref();
    if (ref != null && ref.startsWith(SCHEMA_REF_PREFIX)) {
      String copyName = copyNames.get(ref.substring(SCHEMA_REF_PREFIX.length()));
      if (copyName != null) {
        schema.set$ref(SCHEMA_REF_PREFIX + copyName);
      }
    }
    children(schema).forEach(child -> repointInPlace(child, copyNames));
  }
}
