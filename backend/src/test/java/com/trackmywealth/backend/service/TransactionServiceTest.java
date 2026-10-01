package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.TransactionCategorizationLogRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * US-07-01: the investment rules {@link TransactionService} owns for {@code BUY}, {@code SELL} and
 * {@code DIVIDEND}, with the repositories mocked. Each rejection asserts the rule that produced it
 * (its message), since several rules overlap and a status alone would not tell them apart. {@code
 * TransactionControllerTest} covers the same flows end to end against PostgreSQL.
 */
class TransactionServiceTest {

  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID SECURITY = UUID.randomUUID();
  private static final LocalDate BOOKED = LocalDate.of(2026, 9, 28);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID());

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final SecurityRepository securityRepository = mock(SecurityRepository.class);
  private final TransactionService service =
      new TransactionService(
          accountLookupService,
          mock(AccessControlService.class),
          transactionRepository,
          mock(AccountCreditCardRepository.class),
          mock(SettlementDetectionService.class),
          mock(FxRateService.class),
          securityRepository,
          mock(CategorizationService.class),
          mock(TransactionCategorizationLogRepository.class),
          mock(TransferDetectionService.class),
          mock(TransferRecordingService.class),
          mock(ObjectMapper.class),
          "ECB");

  private Account depot;

  @BeforeEach
  void setUp() {
    depot = new Account();
    ReflectionTestUtils.setField(depot, "id", ACCOUNT);
    depot.setWorkspace(new Workspace());
    depot.setNativeCurrency("CHF");
    depot.setStatus("ACTIVE");
    depot.setHoldsPositions(true);
    when(accountLookupService.findAccountOrThrow(eq(ACCOUNT), any())).thenReturn(depot);
    when(securityRepository.existsById(SECURITY)).thenReturn(true);
    when(transactionRepository.saveAndFlush(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  // --- trades --------------------------------------------------------------------------------

  @Nested
  class Trades {

    @Test
    void aBuyAtTheExactFiguresIsRecordedWithItsFeeOnTheTradeRow() {
      TransactionResponse bought = record(trade("BUY", "-1005.00", "10", "100", "5"));

      assertThat(bought.quantity()).isEqualByComparingTo("10");
      assertThat(bought.feeAmount()).isEqualByComparingTo("5");
    }

    @Test
    void aSecurityIsRequiredAndMustExist() {
      rejects(
          request("BUY", "-1000.00", "CHF", null, "10", "100", null),
          "securityId is required for a BUY");
      UUID unknown = UUID.randomUUID();
      rejects(
          request("BUY", "-1000.00", "CHF", unknown, "10", "100", null),
          "Security " + unknown + " does not exist");
    }

    @Test
    void quantityAndPriceAreRequired() {
      rejects(trade("BUY", "-1000.00", null, "100", null), "quantity and unitPrice are required");
      rejects(trade("SELL", "1000.00", "-10", null, null), "quantity and unitPrice are required");
    }

    @Test
    void quantityIsPositionSigned() {
      rejects(trade("BUY", "-1000.00", "-10", "100", null), "quantity must be positive for a BUY");
      rejects(trade("SELL", "1000.00", "10", "100", null), "quantity must be negative for a SELL");
    }

    @Test
    void priceAndFeeArePositiveMagnitudes() {
      rejects(trade("BUY", "-1000.00", "10", "-100", null), "unitPrice must be positive");
      rejects(trade("BUY", "-1000.00", "10", "0", null), "unitPrice must be positive");
      rejects(trade("BUY", "-995.00", "10", "100", "-5"), "feeAmount must be a positive magnitude");
    }

    @Test
    void aBuyAlwaysTakesCashOut() {
      rejects(trade("BUY", "1000.00", "10", "100", null), "amount must be negative for a BUY");
    }

    @Test
    void aSalesSignFollowsItsFiguresNotItsType() {
      // -4 x 110 is 440 received; -440 contradicts the trade's own figures.
      rejects(
          trade("SELL", "-440.00", "-4", "110", null),
          "amount must equal -(quantity x unitPrice) - feeAmount = 440.0000");
      // Selling a remnant: 0.50 of proceeds, 5.00 of costs.
      assertThat(record(trade("SELL", "-4.50", "-1", "0.50", "5")).amount())
          .isEqualByComparingTo("-4.50");
      // Proceeds exactly equal to costs.
      assertThat(record(trade("SELL", "0.00", "-1", "5", "5")).amount()).isZero();
    }

    @Test
    void aZeroCashLegCannotDeriveARateFromABilledAmount() {
      CreateTransactionRequest zeroSale =
          new CreateTransactionRequest(
              "SELL",
              BOOKED,
              new BigDecimal("0.00"),
              "USD",
              null,
              null,
              null,
              null,
              null,
              new BigDecimal("0.00"),
              new BigDecimal("5"),
              SECURITY,
              new BigDecimal("-1"),
              new BigDecimal("5"),
              null,
              null,
              null,
              null,
              null,
              null);

      rejects(zeroSale, "A zero amount implies no rate");
    }

    @Test
    void dividendFieldsDoNotBelongOnATrade() {
      rejects(
          withWithholding(trade("BUY", "-1000.00", "10", "100", null), "1000.00", "0"),
          "grossAmount and taxWithheldAmount are only valid for a DIVIDEND");
    }

    @Test
    void settlementCannotPrecedeTradeAndBookingCannotPrecedeTrade() {
      rejects(
          withDates(trade("BUY", "-1000.00", "10", "100", null), BOOKED, BOOKED.minusDays(1)),
          "settlementDate cannot be before tradeDate");
      rejects(
          withDates(trade("BUY", "-1000.00", "10", "100", null), BOOKED.plusDays(1), null),
          "tradeDate cannot be after bookingDate");
      assertThat(
              record(
                      withDates(
                          trade("BUY", "-1000.00", "10", "100", null), BOOKED.minusDays(2), BOOKED))
                  .tradeDate())
          .isEqualTo(BOOKED.minusDays(2));
    }

    @Test
    void onlyAnAccountThatHoldsPositionsTrades() {
      depot.setHoldsPositions(false);

      rejects(
          trade("BUY", "-1000.00", "10", "100", null),
          "can only be recorded on an account that holds positions");
    }
  }

  // --- the rounding a statement can carry ------------------------------------------------------

  @Nested
  class StatementRounding {

    @Test
    void aTypoOnASmallTradeIsCaught() {
      // 10 x 100 + 5 = 1005; the allowance is 0.01 + 10 x 0.005 = 0.06.
      rejects(trade("BUY", "-1010.00", "10", "100", "5"), "= -1005.0000 within 0.06");
      record(trade("BUY", "-1005.05", "10", "100", "5"));
    }

    @Test
    void aLargeTradeAtARoundedAveragePriceIsAcceptedButASlipIsNot() {
      // 25,000 at an exact 12.345678 books 308,641.95; the shown 12.3457 computes 308,642.50.
      record(trade("BUY", "-308641.95", "25000", "12.3457", null));
      rejects(trade("BUY", "-308652.50", "25000", "12.3457", null), "within 1.26");
    }

    @Test
    void aCurrencyWithoutCentsAllowsItsOwnMinorUnit() {
      // 1.2345 x 2,345 = 2,894.9025, booked as 2,895 yen; allowance 1 + 1.2345 x 0.5.
      record(request("BUY", "-2895", "JPY", SECURITY, "1.2345", "2345", null, "0.0056"));
      rejects(
          request("BUY", "-2900", "JPY", SECURITY, "1.2345", "2345", null, "0.0056"),
          "within 1.61725");
    }

    @ParameterizedTest
    @CsvSource({
      // currency, quantity, unitPrice, expected allowance
      "CHF, 10, 100, 0.06", // "100" is taken as 100.00: 0.01 + 10 x 0.005
      "CHF, 3, 33.3333, 0.01015", // 0.01 + 3 x 0.00005
      "CHF, -25000, 12.3457, 1.26", // a sale: the quantity's magnitude counts
      "JPY, 1.2345, 2345, 1.61725", // no minor unit: 1 + 1.2345 x 0.5
      "BHD, 10, 1.5, 0.006", // three decimals, price taken as 1.500: 0.001 + 10 x 0.0005
      "XAU, 2, 1800, 0.02" // no ISO minor unit: treated as two decimals
    })
    void theAllowanceIsOneMinorUnitPlusHalfAPriceUnitPerShare(
        String currency, String quantity, String unitPrice, String expected) {
      assertThat(
              TransactionService.amountTolerance(
                  currency, new BigDecimal(quantity), new BigDecimal(unitPrice)))
          .isEqualByComparingTo(expected);
    }
  }

  // --- dividends -----------------------------------------------------------------------------

  @Nested
  class Dividends {

    @Test
    void grossWithheldAndNetAreKeptAndReconcileExactly() {
      TransactionResponse recorded =
          record(dividend("65.00", SECURITY, "200", "0.50", "100.00", "35.00"));

      assertThat(recorded.netAmount()).isEqualByComparingTo("65.00");
      rejects(
          dividend("65.00", SECURITY, null, null, "100.00", "30.00"),
          "grossAmount minus taxWithheldAmount (70.00) must equal amount");
    }

    @Test
    void grossAndWithheldComeTogether() {
      rejects(
          dividend("65.00", SECURITY, null, null, "100.00", null),
          "grossAmount and taxWithheldAmount must be given together");
      rejects(
          dividend("65.00", SECURITY, null, null, null, "35.00"),
          "grossAmount and taxWithheldAmount must be given together");
    }

    @Test
    void aWithholdingIsNeverNegative() {
      rejects(
          dividend("65.00", SECURITY, null, null, "60.00", "-5.00"),
          "taxWithheldAmount cannot be negative");
    }

    @Test
    void thePerShareRateMustMatchTheGross() {
      rejects(
          dividend("65.00", SECURITY, "200", "0.50", null, null),
          "must equal the gross dividend within");
    }

    @Test
    void theSharesEntitledArePositiveAndThePriceTooIfGiven() {
      rejects(
          dividend("65.00", SECURITY, "-200", null, null, null),
          "quantity must be positive for a DIVIDEND");
      rejects(dividend("65.00", SECURITY, "200", "0", null, null), "unitPrice must be positive");
    }

    @Test
    void aDividendIsCashReceivedForAKnownSecurity() {
      rejects(
          dividend("-65.00", SECURITY, null, null, null, null),
          "amount must be positive for a DIVIDEND");
      rejects(
          dividend("65.00", null, null, null, null, null), "securityId is required for a DIVIDEND");
    }

    @Test
    void aDividendHasNoTradeDatesNorATradesFee() {
      rejects(
          withDates(dividend("65.00", SECURITY, null, null, null, null), BOOKED, null),
          "tradeDate and settlementDate are only valid for a BUY or SELL");
      rejects(
          withDates(dividend("65.00", SECURITY, null, null, null, null), null, BOOKED),
          "tradeDate and settlementDate are only valid for a BUY or SELL");
      rejects(
          request("DIVIDEND", "65.00", "CHF", SECURITY, null, null, "1"),
          "feeAmount is only valid on a BUY or SELL");
    }
  }

  // --- other types ---------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({"DEPOSIT, 100.00", "INTEREST, 12.00", "FEE, -5.00", "TAX, -3.00"})
  void noOtherTypeCarriesAnInvestmentField(String type, String amount) {
    rejects(
        request(type, amount, "CHF", SECURITY, null, null, null),
        "are only valid for BUY, DIVIDEND, SELL");
    rejects(
        withWithholding(request(type, amount, "CHF", null, null, null, null), "100.00", "35.00"),
        "are only valid for BUY, DIVIDEND, SELL");
    rejects(
        withDates(request(type, amount, "CHF", null, null, null, null), BOOKED, null),
        "are only valid for BUY, DIVIDEND, SELL");
  }

  @Test
  void aStandaloneFeeIsItsOwnRowWithoutAFeeAmount() {
    rejects(
        request("FEE", "-5.00", "CHF", null, null, null, "5"),
        "feeAmount is only valid on a BUY or SELL");
  }

  @Test
  void aSettlementStaysInTheAccountsOwnCurrencyWhileOtherTypesMayNot() {
    // US-07-01 narrowed this check to SETTLEMENT alone: matching pairs a card payment by exact
    // amount (US-09-02), which a converted row cannot satisfy. Trades and dividends may differ.
    depot.setHoldsPositions(false);

    rejects(
        request("SETTLEMENT", "-100.00", "USD", null, null, null, null, "0.9"),
        "currency must match the account's currency");
  }

  // --- helpers -------------------------------------------------------------------------------

  private TransactionResponse record(CreateTransactionRequest request) {
    return service.recordTransaction(ACCOUNT, request, ACTOR);
  }

  private void rejects(CreateTransactionRequest request, String reasonFragment) {
    clearInvocations(transactionRepository); // an earlier record() in the same test did save
    assertThatThrownBy(() -> record(request))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
              assertThat(e.getReason()).contains(reasonFragment);
            });
    verify(transactionRepository, never()).saveAndFlush(any());
  }

  private static CreateTransactionRequest trade(
      String type, String amount, String quantity, String unitPrice, String feeAmount) {
    return request(type, amount, "CHF", SECURITY, quantity, unitPrice, feeAmount);
  }

  private static CreateTransactionRequest dividend(
      String amount,
      UUID securityId,
      String quantity,
      String unitPrice,
      String grossAmount,
      String taxWithheldAmount) {
    return withWithholding(
        request("DIVIDEND", amount, "CHF", securityId, quantity, unitPrice, null),
        grossAmount,
        taxWithheldAmount);
  }

  private static CreateTransactionRequest request(
      String type,
      String amount,
      String currency,
      UUID securityId,
      String quantity,
      String unitPrice,
      String feeAmount) {
    return request(type, amount, currency, securityId, quantity, unitPrice, feeAmount, null);
  }

  private static CreateTransactionRequest request(
      String type,
      String amount,
      String currency,
      UUID securityId,
      String quantity,
      String unitPrice,
      String feeAmount,
      String fxRate) {
    return new CreateTransactionRequest(
        type,
        BOOKED,
        new BigDecimal(amount),
        currency,
        null,
        null,
        null,
        null,
        decimal(fxRate),
        null,
        decimal(feeAmount),
        securityId,
        decimal(quantity),
        decimal(unitPrice),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static CreateTransactionRequest withDates(
      CreateTransactionRequest r, LocalDate tradeDate, LocalDate settlementDate) {
    return new CreateTransactionRequest(
        r.transactionType(),
        r.bookingDate(),
        r.amount(),
        r.currency(),
        r.merchantDescription(),
        r.mcc(),
        r.notes(),
        r.externalId(),
        r.fxRateToAccountCurrency(),
        r.billedAmount(),
        r.feeAmount(),
        r.securityId(),
        r.quantity(),
        r.unitPrice(),
        tradeDate,
        settlementDate,
        r.grossAmount(),
        r.taxWithheldAmount(),
        null,
        null);
  }

  private static CreateTransactionRequest withWithholding(
      CreateTransactionRequest r, String grossAmount, String taxWithheldAmount) {
    return new CreateTransactionRequest(
        r.transactionType(),
        r.bookingDate(),
        r.amount(),
        r.currency(),
        r.merchantDescription(),
        r.mcc(),
        r.notes(),
        r.externalId(),
        r.fxRateToAccountCurrency(),
        r.billedAmount(),
        r.feeAmount(),
        r.securityId(),
        r.quantity(),
        r.unitPrice(),
        r.tradeDate(),
        r.settlementDate(),
        decimal(grossAmount),
        decimal(taxWithheldAmount),
        null,
        null);
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
