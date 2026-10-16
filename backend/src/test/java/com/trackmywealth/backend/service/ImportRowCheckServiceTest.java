package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportRateNeed;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
  private final AccountCreditCardRepository creditCards = mock(AccountCreditCardRepository.class);
  private final TransactionRepository repository = mock(TransactionRepository.class);
  private final ImportRowCheckService service =
      new ImportRowCheckService(
          transactionService,
          fxRateService,
          new ImportLedgerRowService(JsonMapper.builder().build()),
          SOURCE,
          creditCards,
          repository);
  private final Account account = account();

  @Test
  void bookingBeforeOpeningBalanceIsWarnedButNotRejected() {
    CanonicalImportRow before =
        new CanonicalImportRow(
            DAY.minusDays(1),
            null,
            new BigDecimal("-10"),
            "EUR",
            "EXPENSE",
            "Test",
            null,
            null,
            null,
            null,
            null);
    assertThat(service.warnings(account, before, DAY.plusDays(30), DAY, false))
        .containsExactly(ImportBatchValues.WARNING_BEFORE_OPENING_BALANCE);
    assertThat(
            ImportRowCheckService.warningArgs(
                ImportBatchValues.WARNING_BEFORE_OPENING_BALANCE, before, "EUR"))
        .containsEntry("date", DAY.minusDays(1).toString());
  }

  @Test
  void bookingsOnOrAfterOpeningDateAreNotWarned() {
    for (LocalDate booking : List.of(DAY, DAY.plusDays(1))) {
      CanonicalImportRow row =
          new CanonicalImportRow(
              booking,
              null,
              new BigDecimal("-10"),
              "EUR",
              "EXPENSE",
              "Test",
              null,
              null,
              null,
              null,
              null);
      assertThat(service.warnings(account, row, DAY.plusDays(30), DAY, false))
          .doesNotContain(ImportBatchValues.WARNING_BEFORE_OPENING_BALANCE);
    }
  }

  @Test
  void accountWithoutOpeningBalanceHasNoOpeningDateWarning() {
    CanonicalImportRow row =
        new CanonicalImportRow(
            DAY.minusDays(1),
            null,
            new BigDecimal("-10"),
            "EUR",
            "EXPENSE",
            "Test",
            null,
            null,
            null,
            null,
            null);
    assertThat(service.warnings(account, row, DAY.plusDays(30), null, false))
        .doesNotContain(ImportBatchValues.WARNING_BEFORE_OPENING_BALANCE);
  }

  @Test
  void oneSourcePaymentCannotMatchTwoCardSettlements() {
    UUID source = UUID.randomUUID();
    when(repository.findPairablePaymentDatesByAmounts(
            eq(source), eq("EUR"), any(), any(), any(), any()))
        .thenReturn(List.<Object[]>of(new Object[] {new BigDecimal("-30"), DAY}));
    List<ParsedImportRow> rows =
        java.util.stream.IntStream.rangeClosed(1, 2)
            .mapToObj(
                n ->
                    ParsedImportRow.parsed(
                        n,
                        Map.of(),
                        new CanonicalImportRow(
                            DAY,
                            null,
                            new BigDecimal("30"),
                            "EUR",
                            "SETTLEMENT",
                            "Payment",
                            null,
                            null,
                            null,
                            null,
                            null)))
            .toList();
    assertThat(service.matchedSettlementRows(source, "EUR", rows)).containsExactly(1);
  }

  @Test
  void twoPaymentsOnSameDateCanMatchTwoSettlements() {
    UUID source = UUID.randomUUID();
    when(repository.findPairablePaymentDatesByAmounts(
            eq(source), eq("EUR"), any(), any(), any(), any()))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {new BigDecimal("-30"), DAY},
                new Object[] {new BigDecimal("-30"), DAY}));
    List<ParsedImportRow> rows =
        java.util.stream.IntStream.rangeClosed(1, 2)
            .mapToObj(
                n ->
                    ParsedImportRow.parsed(
                        n,
                        Map.of(),
                        new CanonicalImportRow(
                            DAY,
                            null,
                            new BigDecimal("30"),
                            "EUR",
                            "SETTLEMENT",
                            "Payment",
                            null,
                            null,
                            null,
                            null,
                            null)))
            .toList();
    assertThat(service.matchedSettlementRows(source, "EUR", rows)).containsExactly(1, 2);
  }

  @Test
  void settlementPreviewBatchesThousandsOfRowsInsteadOfQueryingOncePerRow() {
    List<ParsedImportRow> rows =
        java.util.stream.IntStream.range(0, 1200)
            .mapToObj(
                n ->
                    ParsedImportRow.parsed(
                        n + 1,
                        Map.of(),
                        new CanonicalImportRow(
                            DAY,
                            null,
                            BigDecimal.valueOf(n + 1),
                            "EUR",
                            "SETTLEMENT",
                            "Payment",
                            null,
                            null,
                            null,
                            null,
                            null)))
            .toList();
    UUID source = UUID.randomUUID();

    assertThat(service.matchedSettlementRows(source, "EUR", rows)).isEmpty();
    verify(repository, org.mockito.Mockito.times(3))
        .findPairablePaymentDatesByAmounts(eq(source), eq("EUR"), any(), any(), any(), any());
  }

  @Test
  void settlementPreviewWithNoVisibleSourceDoesNotQueryOtherAccountTransactions() {
    ParsedImportRow credit =
        ParsedImportRow.parsed(
            1,
            Map.of(),
            new CanonicalImportRow(
                DAY,
                null,
                new BigDecimal("30"),
                "EUR",
                "SETTLEMENT",
                "Payment",
                null,
                null,
                null,
                null,
                null));

    assertThat(service.matchedSettlementRows(null, "EUR", List.of(credit))).isEmpty();
    verifyNoInteractions(repository);
  }

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
  void theRatesABatchNeedsAreOnePerPairAndDateAndSkipRowsTheLedgerRefuses() {
    ParsedImportRow refused = row("CHF", DAY);
    when(transactionService.requireRecordable(eq(account), any()))
        .thenAnswer(
            invocation -> {
              String currency = invocation.<CreateTransactionRequest>getArgument(1).currency();
              if ("CHF".equals(currency)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "refused");
              }
              return "EUR".equals(currency) ? Optional.empty() : Optional.of("EUR");
            });

    assertThat(
            service.ratesNeeded(
                account,
                List.of(
                    row("USD", DAY),
                    row("USD", DAY),
                    row("USD", DAY.plusDays(1)),
                    row("EUR", DAY),
                    refused,
                    ParsedImportRow.error(
                        2, Map.of(), ImportRowErrorValues.DATE_UNPARSEABLE, Map.of()))))
        .containsExactly(
            new ImportRateNeed("USD", "EUR", DAY),
            new ImportRateNeed("USD", "EUR", DAY.plusDays(1)));
    verifyNoInteractions(fxRateService);
  }

  @Test
  void ratesLookedUpBeforeTheStagingTransactionAreNotAskedForAgain() {
    when(transactionService.requireRecordable(eq(account), any())).thenReturn(Optional.of("EUR"));
    when(fxRateService.tryGetConversionRateFetchingMissing("USD", "EUR", DAY, SOURCE))
        .thenReturn(Optional.empty());

    // Outside any transaction: the provider may be asked for a missing date here.
    Map<String, Boolean> rates =
        service.lookUpRates(List.of(new ImportRateNeed("USD", "EUR", DAY)));
    // Inside the staging transaction: only the answer above is used.
    ParsedImportRow checked = service.checkRecordable(account, row("USD", DAY), rates);

    assertThat(rates).containsExactly(Map.entry("USD/EUR/2019-01-05", false));
    assertThat(checked.errorCode()).isEqualTo(ImportRowErrorValues.FX_RATE_UNAVAILABLE);
    verify(fxRateService, times(1)).tryGetConversionRateFetchingMissing("USD", "EUR", DAY, SOURCE);
  }

  @Test
  void issuerProvidedCardFxDoesNotLookUpAGenericRate() {
    CanonicalImportRow purchase =
        new CanonicalImportRow(
            DAY,
            null,
            new BigDecimal("-55.00"),
            "CHF",
            "CREDIT_CARD_PURCHASE",
            "Foreign merchant",
            null,
            "BANK-1",
            null,
            null,
            null,
            new BigDecimal("-50.00"),
            "EUR",
            new BigDecimal("1.100000"));
    ParsedImportRow parsed = ParsedImportRow.parsed(1, Map.of(), purchase);
    when(transactionService.requireRecordable(eq(account), any())).thenReturn(Optional.of("CHF"));

    assertThat(service.ratesNeeded(account, List.of(parsed))).isEmpty();
    assertThat(service.checkRecordable(account, parsed, new HashMap<>())).isSameAs(parsed);
    verifyNoInteractions(fxRateService);
  }

  @Test
  void aFutureDateAndAnotherCurrencyAreWarnings() {
    CanonicalImportRow future = row("USD", DAY.plusDays(1)).canonical();

    assertThat(service.warnings(account, future, DAY, false))
        .containsExactly(
            ImportBatchValues.WARNING_FUTURE_DATE, ImportBatchValues.WARNING_CURRENCY_DIFFERS);
    assertThat(service.warnings(account, row("EUR", DAY).canonical(), DAY, false)).isEmpty();
    assertThat(
            ImportRowCheckService.warningArgs(
                ImportBatchValues.WARNING_CURRENCY_DIFFERS, future, "EUR"))
        .containsExactly(Map.entry("currency", "USD"), Map.entry("accountCurrency", "EUR"));
    assertThat(
            ImportRowCheckService.warningArgs(ImportBatchValues.WARNING_FUTURE_DATE, future, "EUR"))
        .containsExactly(Map.entry("date", "2019-01-06"));
  }

  // #334: a value date read as February's last day is a warning naming that day.
  @Test
  void anAdjustedValueDateIsAWarningNamingTheDayItIsReadAs() {
    CanonicalImportRow interest =
        new CanonicalImportRow(
            DAY,
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
            true);

    assertThat(service.warnings(account, interest, DAY, false))
        .containsExactly(ImportBatchValues.WARNING_VALUE_DATE_ADJUSTED);
    assertThat(
            ImportRowCheckService.warningArgs(
                ImportBatchValues.WARNING_VALUE_DATE_ADJUSTED, interest, "EUR"))
        .containsExactly(Map.entry("date", "2031-02-28"));
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
