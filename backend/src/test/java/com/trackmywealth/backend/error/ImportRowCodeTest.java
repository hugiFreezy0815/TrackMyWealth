package com.trackmywealth.backend.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportHoldingsValues;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * US-29-04 (#374): the row-code registry lists exactly the codes the parsers use - the string
 * constants they store stay the source the rows are written with.
 */
class ImportRowCodeTest {

  private static final Pattern ROW_CODE = Pattern.compile("^(IMPORT_ROW_|HOLDING_)[A-Z_]+$");

  @Test
  void theRegistryMatchesTheParsersConstants() {
    Set<String> constants = new TreeSet<>();
    for (Class<?> holder :
        new Class<?>[] {
          ImportRowErrorValues.class, ImportBatchValues.class, ImportHoldingsValues.class
        }) {
      constants.addAll(rowCodeConstantsOf(holder));
    }
    Set<String> registered =
        Arrays.stream(ImportRowCode.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(constants).as("the scan found the constants").hasSizeGreaterThan(30);
    assertThat(registered).isEqualTo(constants);
  }

  @ParameterizedTest
  @EnumSource(ImportRowCode.class)
  void everyRowCodeHasAnEnglishAndAGermanText(ImportRowCode code) {
    String english = Messages.EN.getProperty(code.messageKey());
    String german = Messages.DE.getProperty(code.messageKey());
    assertThat(english).as("EN %s", code.messageKey()).isNotBlank();
    assertThat(german).as("DE %s", code.messageKey()).isNotBlank().isNotEqualTo(english);
    assertThat(code.meaning()).isNotBlank().endsWith(".");
    assertThat(code.kind()).isNotNull();
  }

  @Test
  void noBundleHoldsARowTextForAnUnknownCode() {
    Set<String> registered =
        Arrays.stream(ImportRowCode.values()).map(Enum::name).collect(Collectors.toSet());
    for (var bundle : new java.util.Properties[] {Messages.EN, Messages.DE}) {
      Set<String> orphans = new TreeSet<>();
      for (String key : bundle.stringPropertyNames()) {
        if (key.startsWith(ImportRowCode.MESSAGE_KEY_PREFIX)
            && !registered.contains(key.substring(ImportRowCode.MESSAGE_KEY_PREFIX.length()))) {
          orphans.add(key);
        }
      }
      assertThat(orphans).as("tmw.import.row.* keys without an ImportRowCode").isEmpty();
    }
  }

  private static Set<String> rowCodeConstantsOf(Class<?> holder) {
    Set<String> codes = new TreeSet<>();
    for (Field field : holder.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)
          && field.getType() == String.class) {
        try {
          String value = (String) field.get(null);
          if (value != null && ROW_CODE.matcher(value).matches()) {
            codes.add(value);
          }
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return codes;
  }
}
