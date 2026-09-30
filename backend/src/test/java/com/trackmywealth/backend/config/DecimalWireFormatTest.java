package com.trackmywealth.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EPIC-29 (#149, #176) DoD, "a serialization test per money-carrying DTO": every record in the
 * {@code dto} package that has a {@link BigDecimal} component is built and serialized with the
 * application's rule ({@link JacksonConfig}), and each such component must come out as a plain
 * decimal string with its scale kept. Found by scanning the package, so a DTO added later is
 * covered without anyone remembering to add it here.
 */
class DecimalWireFormatTest {

  private static final BigDecimal SAMPLE = new BigDecimal("12345678.1234567891");
  private static final String SAMPLE_ON_THE_WIRE = "12345678.1234567891";

  private final JsonMapper jsonMapper = mapper();

  @Test
  void everyDecimalInEveryDtoIsAPlainString() throws Exception {
    List<Class<?>> checked = new ArrayList<>();
    for (Class<?> record : dtoRecordsWithDecimals()) {
      JsonNode json = jsonMapper.valueToTree(instantiate(record));
      for (RecordComponent component : record.getRecordComponents()) {
        if (component.getType() == BigDecimal.class) {
          JsonNode value = json.path(component.getName());
          assertThat(value.isString())
              .as("%s.%s is a JSON string", record.getSimpleName(), component.getName())
              .isTrue();
          assertThat(value.asString())
              .as("%s.%s keeps every digit", record.getSimpleName(), component.getName())
              .isEqualTo(SAMPLE_ON_THE_WIRE);
        }
      }
      checked.add(record);
    }
    // Guards against the scan silently finding nothing.
    assertThat(checked).hasSizeGreaterThan(10);
  }

  @Test
  void scaleIsKeptAndNeverWrittenInExponentForm() {
    assertThat(jsonMapper.writeValueAsString(new BigDecimal("1005.0000")))
        .isEqualTo("\"1005.0000\"");
    assertThat(jsonMapper.writeValueAsString(new BigDecimal("1E+3"))).isEqualTo("\"1000\"");
    assertThat(jsonMapper.writeValueAsString(new BigDecimal("0.00000001")))
        .isEqualTo("\"0.00000001\"");
  }

  @Test
  void requestsStillAcceptNumbersAsWellAsStrings() {
    record Amount(BigDecimal amount) {}

    assertThat(jsonMapper.readValue("{\"amount\":\"12345678.1234567891\"}", Amount.class).amount())
        .isEqualByComparingTo(SAMPLE);
    assertThat(jsonMapper.readValue("{\"amount\":-10.5}", Amount.class).amount())
        .isEqualByComparingTo("-10.5");
  }

  private static JsonMapper mapper() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new JacksonConfig().decimalsAsPlainStrings().customize(builder);
    return builder.build();
  }

  private static List<Class<?>> dtoRecordsWithDecimals() {
    List<Class<?>> records = new ArrayList<>();
    for (JavaClass javaClass :
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.trackmywealth.backend.dto")) {
      Class<?> type = javaClass.reflect();
      if (type.isRecord()
          && Arrays.stream(type.getRecordComponents())
              .anyMatch(component -> component.getType() == BigDecimal.class)) {
        records.add(type);
      }
    }
    return records;
  }

  private static Object instantiate(Class<?> record) throws Exception {
    RecordComponent[] components = record.getRecordComponents();
    Object[] values = new Object[components.length];
    Class<?>[] types = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
      values[i] = sampleOf(types[i]);
    }
    Constructor<?> canonical = record.getDeclaredConstructor(types);
    canonical.setAccessible(true);
    return canonical.newInstance(values);
  }

  private static Object sampleOf(Class<?> type) throws Exception {
    if (type == BigDecimal.class) {
      return SAMPLE;
    }
    if (type == String.class) {
      return "sample";
    }
    if (type == UUID.class) {
      return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
    if (type == LocalDate.class) {
      return LocalDate.of(2026, 9, 30);
    }
    if (type == OffsetDateTime.class) {
      return OffsetDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.UTC);
    }
    if (type == boolean.class || type == Boolean.class) {
      return false;
    }
    if (type == int.class || type == Integer.class) {
      return 0;
    }
    if (type == long.class || type == Long.class) {
      return 0L;
    }
    if (type == List.class) {
      return List.of();
    }
    if (type == Set.class) {
      return Set.of();
    }
    if (type == Map.class) {
      return Map.of();
    }
    if (type.isRecord()) {
      return instantiate(type);
    }
    return null;
  }
}
