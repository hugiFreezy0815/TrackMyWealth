package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.ImportLedgerRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** US-07-04: an import row is the manual entry a member would type for the same booking. */
class ImportLedgerRowServiceTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final ImportLedgerRowService service = new ImportLedgerRowService(objectMapper);

  @Test
  void theRequestCarriesWhatTheFileStatesAndNothingElse() {
    CreateTransactionRequest request = service.request(row("Strom Januar", "Stadtwerke"));

    assertThat(request.transactionType()).isEqualTo("EXPENSE");
    assertThat(request.bookingDate()).isEqualTo(LocalDate.of(2019, 1, 2));
    assertThat(request.amount()).isEqualByComparingTo("-85.00");
    assertThat(request.currency()).isEqualTo("EUR");
    assertThat(request.merchantDescription()).isEqualTo("Strom Januar");
    assertThat(request.mcc()).isEqualTo("4900");
    assertThat(request.notes()).isEqualTo("note");
    assertThat(request.externalId()).isEqualTo("REF-1");
    assertThat(request.fxRateToAccountCurrency()).isNull();
    assertThat(request.counterpartyAccountId()).isNull();
    assertThat(request.feeAmount()).isNull();
  }

  @Test
  void withoutADescriptionTheCounterpartyDescribesTheRow() {
    assertThat(service.request(row(null, "Stadtwerke")).merchantDescription())
        .isEqualTo("Stadtwerke");
  }

  @Test
  void theSourceDataKeepsTheCellsCounterpartyValueDateAndBankCode() {
    Map<String, String> cells = new LinkedHashMap<>();
    cells.put("Buchungstag", "02.01.2019");
    cells.put("Betrag", "-85,00");

    ImportLedgerRow ledgerRow = service.toLedgerRow(row("Strom Januar", "Stadtwerke"), cells);

    JsonNode data = objectMapper.readTree(ledgerRow.rawSourceData());
    assertThat(data.path("counterpartyName").stringValue()).isEqualTo("Stadtwerke");
    assertThat(data.path("valueDate").stringValue()).isEqualTo("2019-01-03");
    assertThat(data.path("bankTransactionCode").stringValue()).isEqualTo("PMNT-ICDT-STDO");
    assertThat(data.path("cells").path("Betrag").stringValue()).isEqualTo("-85,00");
    assertThat(data.path("cells").propertyNames()).containsExactly("Buchungstag", "Betrag");
  }

  @Test
  void descriptionsCompareTrimmedLowerCasedAndWithBlanksCollapsed() {
    assertThat(ImportLedgerRowService.normalizedDescription("  REWE\u00a0 Markt\t12 "))
        .isEqualTo("rewe markt 12");
    assertThat(ImportLedgerRowService.normalizedDescription(null)).isEmpty();
  }

  private static CanonicalImportRow row(String description, String counterparty) {
    return new CanonicalImportRow(
        LocalDate.of(2019, 1, 2),
        LocalDate.of(2019, 1, 3),
        new BigDecimal("-85.00"),
        "EUR",
        "EXPENSE",
        description,
        counterparty,
        "REF-1",
        "4900",
        "PMNT-ICDT-STDO",
        "note");
  }
}
