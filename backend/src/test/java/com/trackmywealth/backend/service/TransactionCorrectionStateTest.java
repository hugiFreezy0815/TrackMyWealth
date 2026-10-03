package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.TransactionCategorizationLogRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-07-06: which correction requests count as a financial change ({@link
 * TransactionService#financialStateMatches}) and which echo a server-estimated FX rate ({@link
 * TransactionCorrectionService#echoesEstimatedRate}), without a database. Every field {@code
 * trg_transaction_append_only} freezes must count; text and scale must not. {@code
 * TransactionRemovalControllerTest} covers what each outcome does against PostgreSQL.
 */
class TransactionCorrectionStateTest {

  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID OTHER_ACCOUNT = UUID.randomUUID();
  private static final UUID SECURITY = UUID.randomUUID();
  private static final UUID COUNTERPARTY = UUID.randomUUID();
  private static final LocalDate BOOKED = LocalDate.of(2026, 9, 28);

  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final TransactionService service =
      new TransactionService(
          mock(AccountLookupService.class),
          mock(AccessControlService.class),
          transactionRepository,
          mock(AccountCreditCardRepository.class),
          mock(SettlementDetectionService.class),
          mock(FxRateService.class),
          mock(SecurityRepository.class),
          mock(CategorizationService.class),
          mock(TransactionCategorizationLogRepository.class),
          mock(TransferDetectionService.class),
          mock(TransferRecordingService.class),
          JsonMapper.builder().build(),
          "ECB",
          new VersionPreconditionService(),
          mock(AccountDataQualityService.class));

  private Transaction row;

  // A BUY carries every investment field and a fee of its own (no FEE row), so one row can change
  // each frozen field in turn.
  @BeforeEach
  void setUp() {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", ACCOUNT);
    row = new Transaction();
    row.setAccount(account);
    row.setTransactionType("BUY");
    row.setBookingDate(BOOKED);
    row.setAmount(new BigDecimal("-1005.0000"));
    row.setCurrency("CHF");
    row.setFeeAmount(new BigDecimal("5.0000"));
    row.setSecurityId(SECURITY);
    row.setQuantity(new BigDecimal("10.0000000000"));
    row.setUnitPrice(new BigDecimal("100.0000000000"));
    row.setTradeDate(BOOKED);
    row.setSettlementDate(BOOKED.plusDays(2));
    row.setRawSourceData("{\"mcc\":\"6211\",\"bank\":\"kept\"}");
    when(transactionRepository.findByRelatedTransactionId(row.getId()))
        .thenReturn(Optional.empty());
  }

  @Test
  void theRowSentBackAsReadMatches() {
    assertThat(service.financialStateMatches(row, ACCOUNT, asRead().build())).isTrue();
  }

  // The stored NUMERIC comes back at its column's scale; a client may send fewer places.
  @Test
  void theSameFiguresAtAnotherScaleMatch() {
    CreateTransactionRequest request =
        asRead()
            .with(r -> r.amount = new BigDecimal("-1005"))
            .with(r -> r.quantity = BigDecimal.TEN)
            .build();

    assertThat(service.financialStateMatches(row, ACCOUNT, request)).isTrue();
  }

  static Stream<Arguments> financialEdits() {
    return Stream.of(
        edit("transaction type", r -> r.type = "SELL"),
        edit("booking date", r -> r.bookingDate = BOOKED.minusDays(1)),
        edit("amount", r -> r.amount = new BigDecimal("-1004.99")),
        edit("currency", r -> r.currency = "EUR"),
        edit("fx rate", r -> r.fxRate = new BigDecimal("0.95")),
        edit("billed amount", r -> r.billedAmount = new BigDecimal("-950.00")),
        edit("fee", r -> r.fee = new BigDecimal("6")),
        edit("no fee", r -> r.fee = null),
        edit("security", r -> r.securityId = UUID.randomUUID()),
        edit("quantity", r -> r.quantity = BigDecimal.ONE),
        edit("unit price", r -> r.unitPrice = BigDecimal.ONE),
        edit("trade date", r -> r.tradeDate = BOOKED.minusDays(1)),
        edit("settlement date", r -> r.settlementDate = BOOKED.plusDays(3)),
        edit("gross amount", r -> r.grossAmount = BigDecimal.TEN),
        edit("withheld tax", r -> r.taxWithheldAmount = BigDecimal.ONE),
        edit("counterparty", r -> r.counterparty = COUNTERPARTY),
        edit("mcc", r -> r.mcc = "6011"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("financialEdits")
  void everyFrozenFieldIsAFinancialChange(String field, Consumer<Edit> change) {
    assertThat(service.financialStateMatches(row, ACCOUNT, asRead().with(change).build()))
        .isFalse();
  }

  @Test
  void anotherAccountIsAFinancialChange() {
    assertThat(service.financialStateMatches(row, OTHER_ACCOUNT, asRead().build())).isFalse();
  }

  // Description and notes are annotations (FR-LIF-004), edited in place.
  @Test
  void descriptionAndNotesAreNotFinancial() {
    CreateTransactionRequest request =
        asRead().with(r -> r.merchant = "Broker AG").with(r -> r.notes = "for Anna").build();

    assertThat(service.financialStateMatches(row, ACCOUNT, request)).isTrue();
  }

  // MCC is source data: omitted keeps the original's, the same one matches.
  @Test
  void anOmittedOrUnchangedMccMatches() {
    assertThat(service.financialStateMatches(row, ACCOUNT, asRead().build())).isTrue();
    assertThat(
            service.financialStateMatches(row, ACCOUNT, asRead().with(r -> r.mcc = "6211").build()))
        .isTrue();
  }

  // --- FX rate as desired state -------------------------------------------------------------

  @Test
  void noRateMatchesAnEstimatedRateButNotAnExplicitOne() {
    row.setFxRateToAccountCurrency(new BigDecimal("0.9000000000"));
    row.setFxRateEstimated(true);
    assertThat(service.financialStateMatches(row, ACCOUNT, asRead().build())).isTrue();

    row.setFxRateEstimated(false);
    assertThat(service.financialStateMatches(row, ACCOUNT, asRead().build())).isFalse();
  }

  @Test
  void theSameExplicitRateMatchesAsARateOrAsTheBilledAmountItFollowsFrom() {
    row.setFxRateToAccountCurrency(new BigDecimal("0.9500000000"));

    assertThat(
            service.financialStateMatches(
                row, ACCOUNT, asRead().with(r -> r.fxRate = new BigDecimal("0.95")).build()))
        .isTrue();
    assertThat(
            service.financialStateMatches(
                row,
                ACCOUNT,
                asRead().with(r -> r.billedAmount = new BigDecimal("-954.75")).build()))
        .isTrue();
  }

  @Test
  void onlyAnUnchangedEstimateWithoutABilledAmountIsAnEcho() {
    row.setFxRateToAccountCurrency(new BigDecimal("0.9000000000"));
    row.setFxRateEstimated(true);

    assertThat(echoes(new BigDecimal("0.90"), null)).isTrue();
    assertThat(echoes(new BigDecimal("0.91"), null)).isFalse();
    assertThat(echoes(new BigDecimal("0.90"), new BigDecimal("-904.50"))).isFalse();
    assertThat(echoes(null, null)).isFalse();

    row.setFxRateEstimated(false);
    assertThat(echoes(new BigDecimal("0.90"), null)).isFalse();
  }

  // --- counterparty as desired state --------------------------------------------------------

  @Test
  void aTwoSidedTransferMatchesItsCounterpartyAndCreditLegOnly() {
    row.setTransactionType("TRANSFER");
    row.setAmount(new BigDecimal("-100.0000"));
    row.setFeeAmount(null);
    row.setCounterpartyAccountId(COUNTERPARTY);
    Transaction credit = new Transaction();
    credit.setAmount(new BigDecimal("100.0000"));
    when(transactionRepository.findByRelatedTransactionId(row.getId()))
        .thenReturn(Optional.of(credit));
    Edit transfer =
        asRead()
            .with(r -> r.type = "TRANSFER")
            .with(r -> r.amount = new BigDecimal("-100"))
            .with(r -> r.fee = null)
            .with(r -> r.counterparty = COUNTERPARTY);

    assertThat(service.financialStateMatches(row, ACCOUNT, transfer.build())).isTrue();
    assertThat(
            service.financialStateMatches(
                row, ACCOUNT, transfer.with(r -> r.counterparty = null).build()))
        .as("no counterparty means a one-sided transfer, not unchanged")
        .isFalse();
    assertThat(
            service.financialStateMatches(
                row,
                ACCOUNT,
                transfer
                    .with(r -> r.counterparty = COUNTERPARTY)
                    .with(r -> r.counterpartyAmount = new BigDecimal("99"))
                    .build()))
        .isFalse();
  }

  private boolean echoes(BigDecimal fxRate, BigDecimal billedAmount) {
    return TransactionCorrectionService.echoesEstimatedRate(
        row,
        new CorrectTransactionRequest(
            null,
            "BUY",
            BOOKED,
            row.getAmount(),
            "CHF",
            null,
            null,
            null,
            fxRate,
            billedAmount,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private Edit asRead() {
    Edit edit = new Edit();
    edit.type = row.getTransactionType();
    edit.bookingDate = row.getBookingDate();
    edit.amount = row.getAmount();
    edit.currency = row.getCurrency();
    edit.fee = row.getFeeAmount();
    edit.securityId = row.getSecurityId();
    edit.quantity = row.getQuantity();
    edit.unitPrice = row.getUnitPrice();
    edit.tradeDate = row.getTradeDate();
    edit.settlementDate = row.getSettlementDate();
    return edit;
  }

  private static Arguments edit(String field, Consumer<Edit> change) {
    return Arguments.of(field, change);
  }

  /** A correction's desired state, as a client that read the row and changed some of it sends. */
  static final class Edit {
    String type;
    LocalDate bookingDate;
    BigDecimal amount;
    String currency;
    String merchant;
    String mcc;
    String notes;
    BigDecimal fxRate;
    BigDecimal billedAmount;
    BigDecimal fee;
    UUID securityId;
    BigDecimal quantity;
    BigDecimal unitPrice;
    LocalDate tradeDate;
    LocalDate settlementDate;
    BigDecimal grossAmount;
    BigDecimal taxWithheldAmount;
    UUID counterparty;
    BigDecimal counterpartyAmount;

    Edit with(Consumer<Edit> change) {
      change.accept(this);
      return this;
    }

    CreateTransactionRequest build() {
      return new CreateTransactionRequest(
          type,
          bookingDate,
          amount,
          currency,
          merchant,
          mcc,
          notes,
          null,
          fxRate,
          billedAmount,
          fee,
          securityId,
          quantity,
          unitPrice,
          tradeDate,
          settlementDate,
          grossAmount,
          taxWithheldAmount,
          counterparty,
          counterpartyAmount);
    }
  }
}
