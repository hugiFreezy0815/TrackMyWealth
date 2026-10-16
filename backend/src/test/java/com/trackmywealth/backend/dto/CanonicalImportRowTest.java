package com.trackmywealth.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.config.JacksonConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * An import row's canonical values are stored as JSON ({@code import_row_raw.canonical_data}) with
 * the application's mapper and read back by the preview, the re-check and the commit.
 */
class CanonicalImportRowTest {

  private static final ApplicationContextRunner MAPPER =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(JacksonConfig.class);

  @Test
  void anAdjustedValueDateSurvivesStorage() {
    CanonicalImportRow interest = row(true);

    MAPPER.run(
        context -> {
          ObjectMapper mapper = context.getBean(ObjectMapper.class);
          assertThat(
                  mapper.readValue(mapper.writeValueAsString(interest), CanonicalImportRow.class))
              .isEqualTo(interest);
        });
  }

  // #334: rows stored before valueDateAdjusted existed have no such property. Jackson 3 fails a
  // missing primitive by default (FAIL_ON_NULL_FOR_PRIMITIVES), which made every older batch
  // unreadable.
  @Test
  void aRowStoredBeforeTheValueDateFlagExistedStillReads() {
    MAPPER.run(
        context -> {
          ObjectMapper mapper = context.getBean(ObjectMapper.class);
          String stored =
              mapper.writeValueAsString(row(false)).replace(",\"valueDateAdjusted\":false", "");
          assertThat(stored).doesNotContain("valueDateAdjusted");

          assertThat(mapper.readValue(stored, CanonicalImportRow.class)).isEqualTo(row(false));
        });
  }

  private static CanonicalImportRow row(boolean valueDateAdjusted) {
    return new CanonicalImportRow(
        LocalDate.of(2031, 2, 28),
        LocalDate.of(2031, 2, 28),
        new BigDecimal("-12.40"),
        "EUR",
        "EXPENSE",
        "Darlehenszins",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        valueDateAdjusted);
  }
}
