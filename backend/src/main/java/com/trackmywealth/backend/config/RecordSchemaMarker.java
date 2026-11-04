package com.trackmywealth.backend.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Type;
import java.util.Iterator;

/**
 * US-29-07 (#451): marks every schema springdoc generates from a Java record with that record, so
 * {@link ResponseFieldContract} can read each property's nullability from the record component it
 * comes from.
 *
 * <p>A converter because only here are a schema and its Java type side by side: the document's
 * schemas carry names only, and a JSpecify {@code @Nullable} is a type-use annotation that swagger
 * does not pass to property customizers. The mark is the {@link #RECORD_EXTENSION} extension on the
 * named schema; {@link ResponseFieldContract} reads and removes it, so it never reaches the
 * document. Stateless: swagger keeps its converters in one JVM-wide list, which every application
 * context in a test JVM shares.
 */
final class RecordSchemaMarker implements ModelConverter {

  /** The extension holding the record class; {@link ResponseFieldContract} removes it. */
  static final String RECORD_EXTENSION = "x-trackmywealth-record";

  @Override
  public Schema<?> resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    if (!chain.hasNext()) {
      return null;
    }
    Schema<?> resolved = chain.next().resolve(type, context, chain);
    Class<?> raw = rawClass(type.getType());
    if (resolved != null && raw.isRecord()) {
      Schema<?> named = namedModel(resolved, context);
      if (named != null) {
        named.addExtension(RECORD_EXTENSION, raw);
      }
    }
    return resolved;
  }

  /**
   * The named model behind {@code resolved}: itself when swagger returned the model, or the defined
   * model it refers to when swagger returned a reference.
   */
  private static Schema<?> namedModel(Schema<?> resolved, ModelConverterContext context) {
    String ref = resolved.get$ref();
    if (ref == null) {
      return resolved.getName() == null ? null : resolved;
    }
    // Swagger refers to a model as #/components/schemas/<name>.
    return context.getDefinedModels().get(ref.substring(ref.lastIndexOf('/') + 1));
  }

  private static Class<?> rawClass(Type type) {
    return type instanceof Class<?> plain ? plain : Json.mapper().constructType(type).getRawClass();
  }
}
