package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.UpdateTransactionRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-06: how {@link TransactionCorrectionService} decides between an in-place edit and a
 * correction, and what it refuses before anything is removed. Collaborators are mocked; {@code
 * TransactionCorrectionControllerTest} runs the same flows end to end against PostgreSQL.
 */
class TransactionCorrectionServiceTest {

  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID OTHER_ACCOUNT = UUID.randomUUID();
  private static final UUID SECURITY = UUID.randomUUID();
  private static final LocalDate BOOKED = LocalDate.of(2026, 9, 28);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final TransactionRemovalService removalService = mock(TransactionRemovalService.class);
  private final TransactionService transactionService = mock(TransactionService.class);
  private final TransactionCorrectionService service =
      new TransactionCorrectionService(
          accountLookupService,
          accessControlService,
          transactionRepository,
          removalService,
          transactionService,
          new VersionPreconditionService());

  private Account account;
  private Account otherAccount;

  @BeforeEach
  void setUp() {
    account = account(ACCOUNT);
    otherAccount = account(OTHER_ACCOUNT);
    when(accountLookupService.findAccountOrThrow(ACCOUNT, ACTOR)).thenReturn(account);
    when(accountLookupService.findAccountOrThrow(OTHER_ACCOUNT, ACTOR)).thenReturn(otherAccount);
    // Built before stubbing: Mockito cannot stub a mock while another stubbing is open.
    TransactionResponse stored = response(4);
    TransactionResponse replacement = response(0);
    when(transactionService.toResponses(anyList())).thenReturn(List.of(stored));
    when(transactionService.recordReplacement(any(), any(), any(), any())).thenReturn(replacement);
  }

  // --- which edits are financial ----------------------------------------------------------------

  @Test
  void theRowSentBackUnchangedIsNotAFinancialChange() {
    Transaction row = purchase();

    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, asRead(row)))
        .isFalse();
  }

  @Test
  void descriptionAndNotesAreNotFinancial() {
    Transaction row = purchase();
    UpdateTransactionRequest edit =
        with(asRead(row), r -> copy(r).merchantDescription("Corner shop").notes("for Anna"));

    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, edit)).isFalse();
  }

  // The stored NUMERIC comes back at its column's scale; the client may send fewer places.
  @Test
  void theSameAmountAtAnotherScaleIsNotAChange() {
    Transaction row = purchase();
    UpdateTransactionRequest edit =
        with(asRead(row), r -> copy(r).amount(new BigDecimal("-85.0000")));

    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, edit)).isFalse();
  }

  static Stream<Arguments> financialEdits() {
    return Stream.<Arguments>of(
        Arguments.of("amount", edit(b -> b.amount(new BigDecimal("-58.00")))),
        Arguments.of("currency", edit(b -> b.currency("EUR"))),
        Arguments.of("booking date", edit(b -> b.bookingDate(BOOKED.minusDays(1)))),
        Arguments.of("fx rate", edit(b -> b.fxRate(new BigDecimal("0.95")))),
        Arguments.of("fee", edit(b -> b.feeAmount(new BigDecimal("1.50")))),
        Arguments.of("security", edit(b -> b.securityId(SECURITY))),
        Arguments.of("quantity", edit(b -> b.quantity(BigDecimal.TEN))),
        Arguments.of("unit price", edit(b -> b.unitPrice(BigDecimal.ONE))),
        Arguments.of("trade date", edit(b -> b.tradeDate(BOOKED))),
        Arguments.of("settlement date", edit(b -> b.settlementDate(BOOKED))),
        Arguments.of("gross amount", edit(b -> b.grossAmount(BigDecimal.TEN))),
        Arguments.of("withheld tax", edit(b -> b.taxWithheldAmount(BigDecimal.ONE))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("financialEdits")
  void everyFrozenFieldIsAFinancialChange(String field, UnaryOperator<Edit> change) {
    Transaction row = purchase();
    UpdateTransactionRequest edit = change.apply(copy(asRead(row))).build();

    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, edit)).isTrue();
  }

  @Test
  void movingToAnotherAccountIsAFinancialChange() {
    Transaction row = purchase();

    assertThat(TransactionCorrectionService.changesFinancialFields(row, otherAccount, asRead(row)))
        .isTrue();
  }

  @Test
  void aRateIsComparedWhetherSentDirectlyOrAsABilledAmount() {
    Transaction row = purchase();
    row.setCurrency("EUR");
    row.setFxRateToAccountCurrency(new BigDecimal("0.9500000000"));
    UpdateTransactionRequest omitted = asRead(row);
    UpdateTransactionRequest sameBilled =
        with(omitted, r -> copy(r).billedAmount(new BigDecimal("-80.75")));
    UpdateTransactionRequest otherBilled =
        with(omitted, r -> copy(r).billedAmount(new BigDecimal("-90.00")));
    UpdateTransactionRequest echoed = with(omitted, r -> copy(r).fxRate(new BigDecimal("0.95")));

    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, omitted))
        .as("no rate stated")
        .isFalse();
    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, echoed))
        .as("the stored rate sent back")
        .isFalse();
    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, sameBilled))
        .as("a billed amount implying the stored rate")
        .isFalse();
    assertThat(TransactionCorrectionService.changesFinancialFields(row, account, otherBilled))
        .isTrue();
  }

  // --- in place ---------------------------------------------------------------------------------

  @Test
  void aNonFinancialEditIsSavedInPlaceWithoutRemovingAnything() {
    Transaction row = imported();
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));

    TransactionCorrectionResponse response =
        service.update(
            ACCOUNT,
            row.getId(),
            with(asRead(row), r -> copy(r).notes("split with Anna")),
            3,
            ACTOR);

    assertThat(response.removal()).isNull();
    assertThat(response.version()).isEqualTo(4);
    assertThat(row.getNotes()).isEqualTo("split with Anna");
    assertThat(row.getVoidedAt()).isNull();
    verify(transactionRepository).saveAndFlush(row);
    verify(removalService, never()).removeRows(anyList(), any(), any(), any());
    verify(transactionService, never()).recordReplacement(any(), any(), any(), any());
  }

  // --- corrections ------------------------------------------------------------------------------

  @Test
  void aManualRowIsSoftDeletedAndReplaced() {
    Transaction row = purchase();
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    UpdateTransactionRequest edit =
        with(asRead(row), r -> copy(r).amount(new BigDecimal("-58.00")));

    TransactionCorrectionResponse response = service.update(ACCOUNT, row.getId(), edit, 3, ACTOR);

    assertThat(response.removal()).isEqualTo("SOFT_DELETE");
    verify(removalService).removeRows(eq(List.of(row)), eq(null), eq(ACTOR), any());
    ArgumentCaptor<CreateTransactionRequest> replacement =
        ArgumentCaptor.forClass(CreateTransactionRequest.class);
    verify(transactionService)
        .recordReplacement(eq(account), replacement.capture(), eq(row), eq(ACTOR));
    assertThat(replacement.getValue().transactionType()).isEqualTo("CREDIT_CARD_PURCHASE");
    assertThat(replacement.getValue().amount()).isEqualByComparingTo("-58.00");
    assertThat(replacement.getValue().externalId()).as("the key stays with the original").isNull();
  }

  @Test
  void anImportedRowIsVoidedWithTheMembersReason() {
    Transaction row = imported();
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    UpdateTransactionRequest edit =
        with(asRead(row), r -> copy(r).amount(new BigDecimal("-80.00")).reason("  Wrong amount "));

    TransactionCorrectionResponse response = service.update(ACCOUNT, row.getId(), edit, 3, ACTOR);

    assertThat(response.removal()).isEqualTo("VOID");
    verify(removalService).removeRows(eq(List.of(row)), eq("Wrong amount"), eq(ACTOR), any());
  }

  @Test
  void anImportedRowsCorrectionWithoutAReasonRemovesNothing() {
    Transaction row = imported();
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    UpdateTransactionRequest edit =
        with(asRead(row), r -> copy(r).amount(new BigDecimal("-80.00")));

    assertThatThrownBy(() -> service.update(ACCOUNT, row.getId(), edit, 3, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    verify(removalService, never()).removeRows(anyList(), any(), any(), any());
  }

  @Test
  void aMoveNeedsEditOnTheTargetAccountAndLocksBothAccountsCards() {
    Transaction row = purchase();
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    UpdateTransactionRequest edit = with(asRead(row), r -> copy(r).accountId(OTHER_ACCOUNT));

    service.update(ACCOUNT, row.getId(), edit, 3, ACTOR);

    verify(accessControlService).requireAccountAccess(ACTOR, otherAccount, AccessLevelValues.EDIT);
    verify(removalService).lockCards(List.of(account, otherAccount), row.getId());
    verify(transactionService).recordReplacement(eq(otherAccount), any(), eq(row), eq(ACTOR));
  }

  // An estimate is the system's guess, not something the member disclosed: sent back unchanged, it
  // is resolved again for the replacement's own values rather than recorded as a disclosed rate.
  @Test
  void anEstimatedRateSentBackIsEstimatedAgainForTheReplacement() {
    Transaction row = purchase();
    row.setCurrency("EUR");
    row.setFxRateToAccountCurrency(new BigDecimal("0.9500000000"));
    row.setFxRateEstimated(true);
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    UpdateTransactionRequest edit =
        with(
            asRead(row),
            r -> copy(r).fxRate(new BigDecimal("0.95")).amount(new BigDecimal("-50.00")));

    service.update(ACCOUNT, row.getId(), edit, 3, ACTOR);

    ArgumentCaptor<CreateTransactionRequest> replacement =
        ArgumentCaptor.forClass(CreateTransactionRequest.class);
    verify(transactionService).recordReplacement(any(), replacement.capture(), any(), any());
    assertThat(replacement.getValue().fxRateToAccountCurrency()).isNull();
  }

  // --- refusals ---------------------------------------------------------------------------------

  @Test
  void aLinkedRowIsNotCorrectedButStillEditableInPlace() {
    Transaction purchase = purchase();
    Transaction fee = new Transaction();
    fee.setRelatedTransactionId(purchase.getId());
    when(transactionRepository.findByIdForUpdate(purchase.getId()))
        .thenReturn(Optional.of(purchase));
    when(transactionRepository.findByRelatedTransactionId(purchase.getId()))
        .thenReturn(Optional.of(fee));
    UpdateTransactionRequest correction =
        with(asRead(purchase), r -> copy(r).amount(new BigDecimal("-50.00")));

    assertThatThrownBy(() -> service.update(ACCOUNT, purchase.getId(), correction, 3, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    verify(removalService, never()).removeRows(anyList(), any(), any(), any());

    service.update(
        ACCOUNT, purchase.getId(), with(asRead(purchase), r -> copy(r).notes("hotel")), 3, ACTOR);
    assertThat(purchase.getNotes()).isEqualTo("hotel");
  }

  @Test
  void aTransferLegIsNotCorrected() {
    Transaction leg = purchase();
    leg.setTransactionType("TRANSFER");
    when(transactionRepository.findByIdForUpdate(leg.getId())).thenReturn(Optional.of(leg));
    UpdateTransactionRequest correction =
        with(asRead(leg), r -> copy(r).amount(new BigDecimal("-50.00")));

    assertThatThrownBy(() -> service.update(ACCOUNT, leg.getId(), correction, 3, ACTOR))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void aVoidedRowOrAReversalCannotBeEdited() {
    Transaction voided = imported();
    voided.setVoidedAt(OffsetDateTime.now());
    Transaction reversal = imported();
    reversal.setReplacesTransactionId(voided.getId());
    for (Transaction row : List.of(voided, reversal)) {
      when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));

      assertThatThrownBy(() -> service.update(ACCOUNT, row.getId(), asRead(row), 3, ACTOR))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
  }

  @Test
  void aStaleVersionChangesNothing() {
    Transaction row = purchase();
    when(transactionRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
    UpdateTransactionRequest edit = with(asRead(row), r -> copy(r).notes("late"));

    assertThatThrownBy(() -> service.update(ACCOUNT, row.getId(), edit, 2, ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
    assertThat(row.getNotes()).isNull();
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private static Account account(UUID id) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setWorkspace(new Workspace());
    return account;
  }

  private Transaction purchase() {
    Transaction row = new Transaction();
    ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(row, "version", 3);
    row.setAccount(account);
    row.setTransactionType("CREDIT_CARD_PURCHASE");
    row.setBookingDate(BOOKED);
    row.setAmount(new BigDecimal("-85.0000"));
    row.setCurrency("CHF");
    row.setMerchantDescription("Shop");
    row.setSource("MANUAL");
    return row;
  }

  private Transaction imported() {
    Transaction row = purchase();
    row.setSource("CSV");
    return row;
  }

  private static TransactionResponse response(int version) {
    TransactionResponse response = mock(TransactionResponse.class);
    when(response.version()).thenReturn(version);
    return response;
  }

  // The row as a client reads it, sent back unchanged.
  private static UpdateTransactionRequest asRead(Transaction row) {
    return new UpdateTransactionRequest(
        row.getAccount().getId(),
        row.getBookingDate(),
        row.getAmount(),
        row.getCurrency(),
        row.getMerchantDescription(),
        row.getNotes(),
        null,
        null,
        row.getFeeAmount(),
        row.getSecurityId(),
        row.getQuantity(),
        row.getUnitPrice(),
        row.getTradeDate(),
        row.getSettlementDate(),
        row.getGrossAmount(),
        row.getTaxWithheldAmount(),
        null);
  }

  private static UpdateTransactionRequest with(
      UpdateTransactionRequest request, Function<UpdateTransactionRequest, Edit> change) {
    return change.apply(request).build();
  }

  private static UnaryOperator<Edit> edit(UnaryOperator<Edit> change) {
    return change;
  }

  private static Edit copy(UpdateTransactionRequest request) {
    return new Edit(request);
  }

  /** A changed copy of a request; records have no withers. */
  static final class Edit {
    private UUID accountId;
    private LocalDate bookingDate;
    private BigDecimal amount;
    private String currency;
    private String merchantDescription;
    private String notes;
    private BigDecimal fxRate;
    private BigDecimal billedAmount;
    private BigDecimal feeAmount;
    private UUID securityId;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private LocalDate tradeDate;
    private LocalDate settlementDate;
    private BigDecimal grossAmount;
    private BigDecimal taxWithheldAmount;
    private String reason;

    Edit(UpdateTransactionRequest r) {
      accountId = r.accountId();
      bookingDate = r.bookingDate();
      amount = r.amount();
      currency = r.currency();
      merchantDescription = r.merchantDescription();
      notes = r.notes();
      fxRate = r.fxRateToAccountCurrency();
      billedAmount = r.billedAmount();
      feeAmount = r.feeAmount();
      securityId = r.securityId();
      quantity = r.quantity();
      unitPrice = r.unitPrice();
      tradeDate = r.tradeDate();
      settlementDate = r.settlementDate();
      grossAmount = r.grossAmount();
      taxWithheldAmount = r.taxWithheldAmount();
      reason = r.reason();
    }

    Edit accountId(UUID value) {
      accountId = value;
      return this;
    }

    Edit bookingDate(LocalDate value) {
      bookingDate = value;
      return this;
    }

    Edit amount(BigDecimal value) {
      amount = value;
      return this;
    }

    Edit currency(String value) {
      currency = value;
      return this;
    }

    Edit merchantDescription(String value) {
      merchantDescription = value;
      return this;
    }

    Edit notes(String value) {
      notes = value;
      return this;
    }

    Edit fxRate(BigDecimal value) {
      fxRate = value;
      return this;
    }

    Edit billedAmount(BigDecimal value) {
      billedAmount = value;
      return this;
    }

    Edit feeAmount(BigDecimal value) {
      feeAmount = value;
      return this;
    }

    Edit securityId(UUID value) {
      securityId = value;
      return this;
    }

    Edit quantity(BigDecimal value) {
      quantity = value;
      return this;
    }

    Edit unitPrice(BigDecimal value) {
      unitPrice = value;
      return this;
    }

    Edit tradeDate(LocalDate value) {
      tradeDate = value;
      return this;
    }

    Edit settlementDate(LocalDate value) {
      settlementDate = value;
      return this;
    }

    Edit grossAmount(BigDecimal value) {
      grossAmount = value;
      return this;
    }

    Edit taxWithheldAmount(BigDecimal value) {
      taxWithheldAmount = value;
      return this;
    }

    Edit reason(String value) {
      reason = value;
      return this;
    }

    UpdateTransactionRequest build() {
      return new UpdateTransactionRequest(
          accountId,
          bookingDate,
          amount,
          currency,
          merchantDescription,
          notes,
          fxRate,
          billedAmount,
          feeAmount,
          securityId,
          quantity,
          unitPrice,
          tradeDate,
          settlementDate,
          grossAmount,
          taxWithheldAmount,
          reason);
    }
  }
}
