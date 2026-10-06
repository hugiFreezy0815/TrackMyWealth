package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Workspace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** US-07-04: what turns a parsed row into an error row or a warned one before the preview. */
class ImportRowCheckServiceTest {

  private static final LocalDate DAY = LocalDate.of(2019, 1, 5);
  private static final String SOURCE = "ECB";

  private final TransactionService transactionService = mock(TransactionService.class);
  private final FxRateService fxRateService = mock(FxRateService.class);
  private final ImportRowCheckService service =
      new ImportRowCheckService(
          transactionService,
          fxRateService,
          new ImportLedgerRowService(JsonMapper.builder().build()),
          SOURCE);
  private final Account account = account();

  @Test
  void aRowTheLedgerTakesIsUnchanged() {
    when(transactionService.requireRecordable(eq(account), any())).thenReturn(Optional.empty());
    ParsedImportRow row = row("EUR", DAY);

    assertThat(service.checkRecordable(account, row, new HashMap<>())).isSameAs(row);
  }

  @Test
  void aRowTheLedgerRefusesIsAnErrorWithTheLedgersReasonAndKeepsItsValues() {
    when(transactionService.requireRecordable(eq(account), any()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT, "amount must be negative for a EXPENSE."));
    ParsedImportRow row = row("EUR", DAY);

    ParsedImportRow checked = service.checkRecordable(account, row, new HashMap<>());

    assertThat(checked.status()).isEqualTo(ImportRowErrorValues.STATUS_ERROR);
    assertThat(checked.errorCode()).isEqualTo(ImportRowErrorValues.LEDGER_REJECTED);
    assertThat(checked.errorArgs())
        .containsExactly(Map.entry("reason", "amount must be negative for a EXPENSE."));
    assertThat(checked.canonical()).isEqualTo(row.canonical());
    assertThat(checked.rawData()).isEqualTo(row.rawData());
  }

  @Test
  void aForeignRowWithoutAnyRateIsAnErrorNamingThePairAndDate() {
    when(transactionService.requireRecordable(eq(account), any())).thenReturn(Optional.of("EUR"));
    when(fxRateService.tryGetConversionRateFetchingMissing("USD", "EUR", DAY, SOURCE))
        .thenReturn(Optional.empty());

    ParsedImportRow checked = service.checkRecordable(account, row("USD", DAY), new HashMap<>());

    assertThat(checked.errorCode()).isEqualTo(ImportRowErrorValues.FX_RATE_UNAVAILABLE);
    assertThat(checked.errorArgs())
        .containsExactly(
            Map.entry("currency", "USD"),
            Map.entry("accountCurrency", "EUR"),
            Map.entry("date", "2019-01-05"));
  }

  @Test
  void eachRateIsLookedUpOncePerBatch() {
    when(transactionService.requireRecordable(eq(account), any())).thenReturn(Optional.of("EUR"));
    when(fxRateService.tryGetConversionRateFetchingMissing("USD", "EUR", DAY, SOURCE))
        .thenReturn(Optional.of(mock(CurrencyConversionResult.class)));
    Map<String, Boolean> rates = new HashMap<>();

    ParsedImportRow first = row("USD", DAY);
    assertThat(service.checkRecordable(account, first, rates)).isSameAs(first);
    service.checkRecordable(account, row("USD", DAY), rates);

    verify(fxRateService, times(1)).tryGetConversionRateFetchingMissing("USD", "EUR", DAY, SOURCE);
  }

  @Test
  void aFutureDateAndAnotherCurrencyAreWarnings() {
    CanonicalImportRow future = row("USD", DAY.plusDays(1)).canonical();

    assertThat(service.warnings(account, future, DAY))
        .containsExactly(
            ImportBatchValues.WARNING_FUTURE_DATE, ImportBatchValues.WARNING_CURRENCY_DIFFERS);
    assertThat(service.warnings(account, row("EUR", DAY).canonical(), DAY)).isEmpty();
    assertThat(
            ImportRowCheckService.warningArgs(
                ImportBatchValues.WARNING_CURRENCY_DIFFERS, future, "EUR"))
        .containsExactly(Map.entry("currency", "USD"), Map.entry("accountCurrency", "EUR"));
    assertThat(
            ImportRowCheckService.warningArgs(ImportBatchValues.WARNING_FUTURE_DATE, future, "EUR"))
        .containsExactly(Map.entry("date", "2019-01-06"));
  }

  private static ParsedImportRow row(String currency, LocalDate date) {
    return ParsedImportRow.parsed(
        1,
        Map.of("Betrag", "-12,40"),
        new CanonicalImportRow(
            date,
            null,
            new BigDecimal("-12.40"),
            currency,
            "EXPENSE",
            "Bakery",
            null,
            null,
            null,
            null,
            null));
  }

  private static Account account() {
    Account account = new Account();
    account.setWorkspace(new Workspace());
    account.setNativeCurrency("EUR");
    account.setHasTransactions(true);
    return account;
  }
}
