package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * US-29-07 (#451): each schema generated from a record carries the record, the top-level one and
 * those it refers to alike; generic records by their raw class; other classes carry nothing.
 */
class RecordSchemaMarkerTest {

  record Inner(String value) {}

  record Page<T>(List<T> content) {}

  record Outer(String id, Inner inner, Page<Inner> page) {}

  static final class NotARecord {
    public String name;
  }

  record WithPlainClass(NotARecord plain) {}

  @Test
  void everyRecordSchemaIsMarkedWithItsRecord() {
    Map<String, Schema> schemas = converters().readAll(Outer.class);

    assertThat(recordOf(schemas.get("Outer"))).isEqualTo(Outer.class);
    assertThat(recordOf(schemas.get("Inner"))).isEqualTo(Inner.class);
    assertThat(recordOf(schemas.get("PageInner"))).isEqualTo(Page.class);
  }

  @Test
  void aSchemaGeneratedFromAnotherClassIsNotMarked() {
    Map<String, Schema> schemas = converters().readAll(WithPlainClass.class);

    assertThat(recordOf(schemas.get("WithPlainClass"))).isEqualTo(WithPlainClass.class);
    assertThat(schemas.get("NotARecord").getExtensions()).isNull();
  }

  @Test
  void aReferenceIsFollowedToItsDefinedModel() {
    ModelConverterContextImpl context = new ModelConverterContextImpl(List.of());
    Schema<?> model = new Schema<>().name("Inner");
    context.defineModel("Inner", model);

    Schema<?> returned =
        new RecordSchemaMarker()
            .resolve(
                new AnnotatedType(Inner.class),
                context,
                List.<ModelConverter>of(
                        (type, ctx, chain) -> new Schema<>().$ref("#/components/schemas/Inner"))
                    .iterator());

    assertThat(returned.get$ref()).isEqualTo("#/components/schemas/Inner");
    assertThat(recordOf(model)).isEqualTo(Inner.class);
  }

  @Test
  void anUnnamedSchemaNothingAndTheEndOfTheChainAreLeftAlone() {
    ModelConverterContextImpl context = new ModelConverterContextImpl(List.of());
    RecordSchemaMarker marker = new RecordSchemaMarker();
    Schema<?> inline = new Schema<>();

    assertThat(
            marker.resolve(
                new AnnotatedType(Inner.class),
                context,
                List.<ModelConverter>of((type, ctx, chain) -> inline).iterator()))
        .isSameAs(inline);
    assertThat(inline.getExtensions()).isNull();
    assertThat(
            marker.resolve(
                new AnnotatedType(Inner.class),
                context,
                List.<ModelConverter>of((type, ctx, chain) -> null).iterator()))
        .isNull();
    assertThat(
            marker.resolve(
                new AnnotatedType(Inner.class), context, List.<ModelConverter>of().iterator()))
        .isNull();
  }

  private static ModelConverters converters() {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new RecordSchemaMarker());
    return converters;
  }

  private static Object recordOf(Schema<?> schema) {
    return schema.getExtensions().get(RecordSchemaMarker.RECORD_EXTENSION);
  }
}
