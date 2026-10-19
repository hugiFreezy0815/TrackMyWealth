package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportHoldingsRule;
import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * US-07-13 (#273): {@link ImportFileParserService#validateTemplate} keeps holdings and cash
 * statement templates apart - a SNAPSHOT template carries a holdings rule and none of the cash
 * settings, a cash template neither a holdings rule nor a spreadsheet - and has no dry run.
 */
class ImportHoldingsTemplateValidationTest {

  private static final ImportColumnMapping NO_COLUMNS =
      new ImportColumnMapping(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null);
  private static final ImportColumnMapping BOOKING_COLUMNS =
      new ImportColumnMapping(
          "Datum", null, "Betrag", null, null, null, null, null, null, null, null, null, null, null,
          null, null);

  private final ImportFileParserService parser =
      new ImportFileParserService(
          new PdfImportReaderService(mock(LocalOcrService.class)),
          new PdfBalanceCheckService(),
          new ImportStatementBalanceReaderService());

  @Test
  void aSnapshotTemplateWithItsRuleAloneIsValid() {
    assertThatCode(
            () ->
                parser.validateTemplate(
                    snapshot("PDF_TEXT", ImportHoldingsReaderServiceTest.SWISSQUOTE), null))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                parser.validateTemplate(
                    ImportHoldingsReaderServiceTest.sheetTemplate(
                        ImportHoldingsReaderServiceTest.CASH),
                    List.of("Währung", "Kontosaldo")))
        .doesNotThrowAnyException();
  }

  @Test
  void aSnapshotTemplateCarriesNoCashStatementSetting() {
    ImportTemplateDefinition withBookings =
        definition(
            "SNAPSHOT",
            "PDF_TEXT",
            BOOKING_COLUMNS,
            null,
            "USER_SELECTED",
            ImportHoldingsReaderServiceTest.SWISSQUOTE);
    assertInvalid(withBookings, "templateClass");
    ImportTemplateDefinition withBalance =
        new ImportTemplateDefinition(
            "SNAPSHOT",
            ",",
            "UTF-8",
            ".",
            "'",
            "dd.MM.yyyy",
            0,
            0,
            0,
            "SINGLE_SIGNED_COLUMN",
            "FROM_ACCOUNT",
            null,
            NO_COLUMNS,
            Map.of(),
            "USER_SELECTED",
            "PDF_TEXT",
            null,
            Map.of(),
            null,
            null,
            new ImportStatementBalanceRule("(?P<date>x)(?P<amount>y)", null),
            ImportHoldingsReaderServiceTest.SWISSQUOTE);
    assertInvalid(withBalance, "templateClass");
    assertInvalid(
        definition(
            "SNAPSHOT",
            "CSV",
            NO_COLUMNS,
            null,
            "USER_SELECTED",
            ImportHoldingsReaderServiceTest.SWISSQUOTE),
        "fileFormat");
    assertInvalid(
        definition("SNAPSHOT", "PDF_TEXT", NO_COLUMNS, null, "USER_SELECTED", null),
        "holdingsRule");
    assertThatThrownBy(
            () ->
                parser.validateTemplate(
                    definition(
                        "SNAPSHOT",
                        "PDF_TEXT",
                        NO_COLUMNS,
                        null,
                        "PREAMBLE_LINE",
                        ImportHoldingsReaderServiceTest.SWISSQUOTE),
                    null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_UNSUPPORTED));
  }

  @Test
  void aCashTemplateHasNoHoldingsRuleAndReadsNoSpreadsheet() {
    assertInvalid(
        definition(
            "CASH_TRANSACTIONS",
            "CSV",
            BOOKING_COLUMNS,
            null,
            "USER_SELECTED",
            ImportHoldingsReaderServiceTest.SWISSQUOTE),
        "holdingsRule");
    assertInvalid(
        definition(
            "CASH_TRANSACTIONS", "SPREADSHEET", BOOKING_COLUMNS, null, "USER_SELECTED", null),
        "fileFormat");
  }

  @Test
  void aHoldingsStatementHasNoDryRun() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    new byte[] {1},
                    snapshot("PDF_TEXT", ImportHoldingsReaderServiceTest.SWISSQUOTE),
                    "CHF"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_UNSUPPORTED));
  }

  private void assertInvalid(ImportTemplateDefinition template, String field) {
    assertThatThrownBy(() -> parser.validateTemplate(template, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
              assertThat(e.getBody().getProperties()).containsEntry("field", field);
            });
  }

  private static ImportTemplateDefinition snapshot(String format, ImportHoldingsRule rule) {
    return definition("SNAPSHOT", format, NO_COLUMNS, null, "USER_SELECTED", rule);
  }

  private static ImportTemplateDefinition definition(
      String templateClass,
      String format,
      ImportColumnMapping mapping,
      ImportStatementBalanceRule balance,
      String accountStrategy,
      ImportHoldingsRule rule) {
    return new ImportTemplateDefinition(
        templateClass,
        ",",
        "UTF-8",
        ".",
        "'",
        "dd.MM.yyyy",
        0,
        0,
        0,
        "SINGLE_SIGNED_COLUMN",
        "FROM_ACCOUNT",
        null,
        mapping,
        Map.of(),
        accountStrategy,
        format,
        null,
        Map.of(),
        null,
        null,
        balance,
        rule);
  }
}
