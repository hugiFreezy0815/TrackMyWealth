package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.ForeignCurrencyResolution;
import com.trackmywealth.backend.dto.LatestCategoryAssignment;
import com.trackmywealth.backend.dto.TransactionRemovalValues;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.TransactionCategorizationLogRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records individual transactions on the append-only ledger (US-07-01, widened from the slice the
 * credit-card stories US-09-01..04 needed). Accepted: the single-account cash types ({@code
 * INCOME}, {@code EXPENSE}, {@code DEPOSIT}, {@code WITHDRAWAL}, {@code INTEREST}, {@code FEE},
 * {@code TAX}, {@code REFUND}), the card types {@code CREDIT_CARD_PURCHASE} and {@code SETTLEMENT},
 * and the investment types {@code BUY}, {@code SELL} and {@code DIVIDEND}. Two-sided types
 * (TRANSFER, DEBT_REPAYMENT, PENSION_CONTRIBUTION) arrive with US-10-01. A card account accepts
 * only its own two types; a {@code FEE} is also created alongside a foreign-currency card purchase,
 * see {@link #recordTransaction}.
 *
 * <p><b>US-07-01 investment types</b> go on any account that holds positions (depot, mandate,
 * crypto, a pension that holds funds), with their cash leg on that same account, in the trade
 * currency - a depot's cash may be held in several currencies. The rules (see {@link
 * #validateInvestment}) are checked here rather than only on the request, so an import (EPIC 07)
 * can reuse them: a trade's {@code amount} must agree with {@code -(quantity * unitPrice) -
 * feeAmount} within the rounding a statement can carry (see {@link #amountTolerance}), since a
 * statement's rounding is the authority; a dividend's gross minus withheld tax must equal its net
 * {@code amount} exactly. A sale's cash leg is proceeds minus costs, so it may be zero or negative
 * for a tiny sale. A sale is not checked against the held quantity: positions are derived later
 * (US-15-01), and an unmatched sale is a reconciliation difference, never a rejection (FR-DEP-007).
 * The same shape rules are enforced by V36's check constraints for every writer.
 *
 * <p>Every amount is cash-direction signed and stored as sent (see {@link Transaction}); the sign
 * each type must carry is checked in {@link #validate}. Whether an account is a credit card is
 * decided by its {@code hasStatementCycle} capability flag, never by {@code account_type} ({@code
 * ArchitectureTest}, US-05-04). No DB trigger guards {@code transaction.account_id} against the
 * account's type - {@code trg_extension_type_guard} (V5) only fires on inserts into the extension
 * tables - so these service checks are the only thing standing between a card purchase and, say, a
 * cash account.
 *
 * <p><b>US-09-04/FR-CC-010</b>: a {@code CREDIT_CARD_PURCHASE} may be in a currency other than the
 * card's own {@code billing_currency} (compared here, not {@code account.nativeCurrency} - the two
 * may legitimately differ, {@code CreateAccountRequest}). <b>US-07-01/DM-06</b>: a cash type on an
 * ordinary account may likewise be in another currency; a {@code SETTLEMENT} must match exactly.
 * The applied rate is resolved, in priority order, from an explicit {@code
 * fxRateToAccountCurrency}, a disclosed {@code billedAmount} (rate derived by division), or - if
 * neither is given - {@link FxRateService}'s generic daily rate, flagged {@code fxRateEstimated}
 * (PR-011). See {@link #resolveForeignCurrency}. A disclosed {@code feeAmount} is recorded as its
 * own {@code FEE} row, linked via {@code relatedTransactionId} - never folded into the purchase.
 *
 * <p>After every new row, {@link SettlementDetectionService} re-checks the cards it can affect, so
 * a payment and its card-side credit are linked (and kept out of spending) as soon as both exist.
 *
 * <p>The balance a purchase changes is read through {@link AccountValuationService}, not computed
 * here: recording and valuing are separate concerns, and the balance is always derived from the
 * ledger, never stored (CLAUDE.md: derived data is rebuildable from source).
 */
@Service
public class TransactionService {

  // Names the resource in a 412 VERSION_CONFLICT detail (VersionPreconditionService); shared by
  // every service that writes a transaction under If-Match.
  static final String VERSIONED_RESOURCE = "transaction";

  private static final String CREDIT_CARD_PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String SETTLEMENT = "SETTLEMENT";
  private static final String WITHDRAWAL = "WITHDRAWAL";
  private static final String FEE = "FEE";
  // US-07-01 cash types: single-account, cash-direction signed. Money leaves the account for the
  // first group and enters it for the second. TRANSFER, DEBT_REPAYMENT and PENSION_CONTRIBUTION are
  // two-sided and belong to US-10-01.
  private static final Set<String> OUTFLOW_CASH_TYPES = Set.of(WITHDRAWAL, FEE, "EXPENSE", "TAX");
  private static final Set<String> INFLOW_CASH_TYPES =
      Set.of("INCOME", "DEPOSIT", "INTEREST", "REFUND");
  private static final Set<String> CASH_TYPES =
      Stream.concat(OUTFLOW_CASH_TYPES.stream(), INFLOW_CASH_TYPES.stream())
          .collect(Collectors.toUnmodifiableSet());
  // Cash movements a custodian account (holds positions: depot, mandate, crypto) can carry itself.
  // INCOME/EXPENSE/REFUND are consumer-spending types and belong on a cash or savings account;
  // trades and dividends are the investment types below.
  private static final Set<String> CUSTODY_CASH_TYPES =
      Set.of("DEPOSIT", WITHDRAWAL, "INTEREST", FEE, "TAX");
  // A credit card is only ever charged (purchase, incl. its own FEE row) or paid down (settlement).
  private static final Set<String> CARD_TYPES = Set.of(CREDIT_CARD_PURCHASE, SETTLEMENT);
  private static final String BUY = "BUY";
  private static final String SELL = "SELL";
  private static final String DIVIDEND = "DIVIDEND";
  // US-07-01: need a security and an account that holds positions. BUY and SELL move the position.
  private static final Set<String> TRADE_TYPES = Set.of(BUY, SELL);
  private static final Set<String> INVESTMENT_TYPES = Set.of(BUY, SELL, DIVIDEND);
  // US-10-01: one leg, or both at once, of a transfer between two of the workspace's own accounts.
  private static final Set<String> TRANSFER_TYPES = TransferRecordingService.TRANSFER_TYPES;
  private static final Set<String> SUPPORTED_TYPES =
      Stream.of(CASH_TYPES, CARD_TYPES, INVESTMENT_TYPES, TRANSFER_TYPES)
          .flatMap(Set::stream)
          .collect(Collectors.toUnmodifiableSet());
  // For a currency without an ISO minor unit (Currency#getDefaultFractionDigits is -1).
  private static final int DEFAULT_MINOR_DIGITS = 2;
  private static final BigDecimal HALF = new BigDecimal("0.5");
  private static final String ACTIVE = "ACTIVE";
  private static final String MANUAL = "MANUAL";
  private static final String MCC_KEY = "mcc";

  // Matches fx_rate_to_account_currency's own NUMERIC(20,10) - the division that derives a rate
  // from a disclosed billedAmount needs an explicit scale (BigDecimal#divide has none by default).
  private static final int FX_RATE_SCALE = 10;

  // fx_rate_to_account_currency's own NUMERIC(20,10) allows at most 10 integer digits - the
  // explicit-rate path is already bounded by CreateTransactionRequest's matching @Digits
  // annotation (checked before the service ever runs), but a billedAmount-derived rate is computed
  // here, after that request-level check, so it needs the same bound applied by hand.
  private static final BigDecimal MAX_FX_RATE = BigDecimal.TEN.pow(10);

  // A page is never larger than this whatever the client asks for, and its order is fixed here, not
  // taken from the request: newest booking first, with created_at and id as tie-breakers so paging
  // is stable across rows booked on the same day.
  private static final int MAX_PAGE_SIZE = 200;
  private static final Sort LEDGER_ORDER =
      Sort.by(Sort.Order.desc("bookingDate"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final FxRateService fxRateService;
  private final SecurityRepository securityRepository;
  private final CategorizationService categorizationService;
  private final TransactionCategorizationLogRepository categorizationLogRepository;
  private final TransferDetectionService transferDetectionService;
  private final TransferRecordingService transferRecordingService;
  private final ObjectMapper objectMapper;
  private final String fxDefaultSource;
  private final VersionPreconditionService versionPreconditionService;

  public TransactionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      SettlementDetectionService settlementDetectionService,
      FxRateService fxRateService,
      SecurityRepository securityRepository,
      CategorizationService categorizationService,
      TransactionCategorizationLogRepository categorizationLogRepository,
      TransferDetectionService transferDetectionService,
      TransferRecordingService transferRecordingService,
      ObjectMapper objectMapper,
      @Value("${app.fx.default-source}") String fxDefaultSource,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.fxRateService = fxRateService;
    this.securityRepository = securityRepository;
    this.categorizationService = categorizationService;
    this.categorizationLogRepository = categorizationLogRepository;
    this.transferDetectionService = transferDetectionService;
    this.transferRecordingService = transferRecordingService;
    this.objectMapper = objectMapper;
    this.fxDefaultSource = fxDefaultSource;
    this.versionPreconditionService = versionPreconditionService;
  }

  /**
   * Records the purchase. When {@code request.externalId()} is set it is an idempotency key: a
   * retry of an already-recorded request returns that original row (same 201 and body) instead of
   * appending a second one, so a client that lost the first response cannot double the debt. Two
   * requests racing on the same new key hit {@code uq_transaction_external_id}, which {@code
   * GlobalExceptionHandler} reports as 409 - the caller's retry then finds the winner and replays.
   */
  @Transactional
  public TransactionResponse recordTransaction(
      UUID accountId, CreateTransactionRequest request, AuthenticatedUserPrincipal actor) {
    return recordTransactionInternal(accountId, request, actor, MANUAL, null, null, true);
  }

  /**
   * US-07-06: inserts a correction replacement through the ordinary creation validator and
   * categorization/detection pipeline, while preserving the original provenance. The source
   * external id is deliberately not copied: it continues to identify the historical source row.
   */
  @Transactional
  TransactionResponse recordCorrectionReplacement(
      UUID accountId,
      CreateTransactionRequest request,
      String source,
      String rawSourceData,
      UUID correctsTransactionId,
      AuthenticatedUserPrincipal actor) {
    return recordTransactionInternal(
        accountId, request, actor, source, rawSourceData, correctsTransactionId, false);
  }

  /**
   * Whether a correction request describes the same immutable financial state. Text fields are
   * intentionally excluded: merchant description and notes may be edited in place (FR-LIF-004).
   *
   * <p>A correction is the desired end state, not an idempotent retry: a field the request leaves
   * out means "not set", where {@link #findReplay} reads it as "not specified". So FX rate, fee and
   * counterparty use their own comparisons here; using the replay ones would answer a correction
   * that drops one of them with a silent text-only edit. An omitted MCC keeps the original's.
   */
  boolean financialStateMatches(
      Transaction transaction, UUID targetAccountId, CreateTransactionRequest request) {
    return transaction.getAccount().getId().equals(targetAccountId)
        && transaction.getTransactionType().equals(request.transactionType())
        && transaction.getBookingDate().equals(request.bookingDate())
        && transaction.getAmount().compareTo(request.amount()) == 0
        && transaction.getCurrency().equals(request.currency())
        && sameCorrectionFx(transaction, request)
        && sameCorrectionFee(transaction, request)
        && sameInvestment(transaction, request)
        && sameCorrectionCounterparty(transaction, request)
        && (request.mcc() == null
            || request.mcc().equals(extractMcc(transaction.getRawSourceData())));
  }

  private TransactionResponse recordTransactionInternal(
      UUID accountId,
      CreateTransactionRequest request,
      AuthenticatedUserPrincipal actor,
      String source,
      String rawSourceData,
      UUID correctsTransactionId,
      boolean allowReplay) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);

    // Before validate(): a replay must answer with the original row even if the account has been
    // archived since, rather than turn a successful earlier request into a 409 on retry.
    Optional<Transaction> replay = allowReplay ? findReplay(accountId, request) : Optional.empty();
    if (replay.isPresent()) {
      return toResponse(
          replay.get(), latestAssignments(List.of(replay.get())).get(replay.get().getId()));
    }
    // Only a CREDIT_CARD_PURCHASE ever needs the card's billing_currency (the FX check, resolution
    // and any FEE row below) - every other card write (e.g. the card-side SETTLEMENT leg)
    // previously
    // needed no such lookup, and still doesn't.
    boolean isCardPurchase = CREDIT_CARD_PURCHASE.equals(request.transactionType());
    AccountCreditCard cardExtension =
        isCardPurchase && account.isHasStatementCycle()
            ? accountCreditCardRepository.findById(accountId).orElse(null)
            : null;
    boolean foreignCurrency = validate(account, cardExtension, request);
    if (request.securityId() != null && !securityRepository.existsById(request.securityId())) {
      // Same answer as a snapshot holding (US-25-01): the master is shared, and the id reveals
      // nothing a workspace could not already look up.
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Security "
              + request.securityId()
              + " does not exist. Create it first with POST /api/v1/securities.");
    }
    // US-10-01: the other account of a two-sided transfer, checked before anything is written.
    Account counterparty = transferRecordingService.findCounterparty(account, request, actor);
    // The currency a rate converts into: the card's billing currency, else the account's own.
    String accountCurrency =
        cardExtension != null ? cardExtension.getBillingCurrency() : account.getNativeCurrency();

    Transaction transaction = new Transaction();
    transaction.setWorkspace(account.getWorkspace());
    transaction.setAccount(account);
    transaction.setTransactionType(request.transactionType());
    transaction.setBookingDate(request.bookingDate());
    transaction.setAmount(request.amount());
    transaction.setCurrency(request.currency());
    transaction.setMerchantDescription(request.merchantDescription());
    transaction.setNotes(request.notes());
    transaction.setSource(source);
    transaction.setExternalId(allowReplay ? request.externalId() : null);
    transaction.setCorrectsTransactionId(correctsTransactionId);
    // FR-CC-002/RULE-011: the MCC is source data, kept in raw_source_data - category_id is a
    // separate column a later categorization writes, so neither can overwrite the other.
    transaction.setRawSourceData(sourceDataWithMcc(rawSourceData, request.mcc()));
    transaction.setCreatedBy(actor.userId());
    transaction.setSecurityId(request.securityId());
    transaction.setQuantity(request.quantity());
    transaction.setUnitPrice(request.unitPrice());
    transaction.setTradeDate(request.tradeDate());
    transaction.setSettlementDate(request.settlementDate());
    transaction.setGrossAmount(request.grossAmount());
    transaction.setTaxWithheldAmount(request.taxWithheldAmount());
    // Only a dividend with its withholding disclosed has a known net that differs from "amount
    // received"; without the gross, amount is simply what arrived.
    transaction.setNetAmount(request.grossAmount() == null ? null : request.amount());
    if (!isCardPurchase) {
      // A trade's costs are part of its own row; a card purchase's fee becomes a FEE row below.
      transaction.setFeeAmount(request.feeAmount());
    }
    if (counterparty != null) {
      // Linked from the start: a manual two-sided transfer never waits for matching (DM-05).
      transaction.setInternalTransfer(true);
      transaction.setCounterpartyAccountId(counterparty.getId());
    }
    if (foreignCurrency) {
      ForeignCurrencyResolution resolution = resolveForeignCurrency(accountCurrency, request);
      transaction.setFxRateToAccountCurrency(resolution.rate());
      transaction.setFxRateDate(request.bookingDate());
      transaction.setFxRateEstimated(resolution.estimated());
    }

    // flush, not a plain save: forces the INSERT (and any constraint/trigger rejection) to happen
    // here, inside this method, rather than deferred to end-of-transaction commit - same reasoning
    // as AccountService's own saveAndFlush calls.
    Transaction saved = transactionRepository.saveAndFlush(transaction);
    // US-08-01: once per new row, in this same transaction; a replay above is never re-categorized.
    Optional<String> assignedBy = categorizationService.categorize(saved);

    if (isCardPurchase && foreignCurrency && request.feeAmount() != null) {
      Transaction fee = new Transaction();
      fee.setWorkspace(account.getWorkspace());
      fee.setAccount(account);
      fee.setTransactionType(FEE);
      fee.setBookingDate(request.bookingDate());
      // A disclosed fee is billed the same direction as the purchase it belongs to (it increases
      // what the card owes) - the request carries it as a positive magnitude for the caller's
      // convenience (see CreateTransactionRequest), negated here to the ledger's own sign
      // convention rather than asking every caller to think in cash-direction signs for this one
      // field.
      fee.setAmount(request.feeAmount().negate());
      // Charged by the issuer in the card's own billing currency, not the purchase's original one -
      // no FX rate of its own.
      fee.setCurrency(cardExtension.getBillingCurrency());
      fee.setRelatedTransactionId(saved.getId());
      fee.setSource(source);
      fee.setCreatedBy(actor.userId());
      categorizationService.categorize(transactionRepository.saveAndFlush(fee));
    }

    if (counterparty != null) {
      transferRecordingService.recordCreditLeg(saved, counterparty, request, actor);
    }

    // Same transaction, so the response below already shows the internal-transfer flag if this row
    // just completed a settlement pair or an own-account transfer.
    settlementDetectionService.detectAfterWrite(account, request.bookingDate());
    transferDetectionService.detectAfterWrite(account, request.bookingDate());
    return toResponse(saved, assignedBy.orElse(null));
  }

  /**
   * Newest booking first. With {@code uncategorized}, only the rows in UNCATEGORIZED: the
   * actionable list FR-CAT-013 asks for, per account (a workspace-wide list comes later).
   */
  @Transactional(readOnly = true)
  public Page<TransactionResponse> listTransactions(
      UUID accountId, boolean uncategorized, Pageable pageable, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    Pageable bounded =
        PageRequest.of(
            pageable.getPageNumber(),
            Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
            LEDGER_ORDER);
    Page<Transaction> page =
        uncategorized
            ? transactionRepository
                .findByAccountIdAndCategoryIdAndVoidedAtIsNullAndReplacesTransactionIdIsNull(
                    accountId, categorizationService.uncategorizedCategoryId(), bounded)
            : transactionRepository.findByAccountId(accountId, bounded);
    Map<UUID, String> assignments = latestAssignments(page.getContent());
    return page.map(transaction -> toResponse(transaction, assignments.get(transaction.getId())));
  }

  // How each row got its category, for a whole page in one query.
  private Map<UUID, String> latestAssignments(List<Transaction> transactions) {
    List<UUID> categorized =
        transactions.stream()
            .filter(transaction -> transaction.getCategoryId() != null)
            .map(Transaction::getId)
            .toList();
    if (categorized.isEmpty()) {
      return Map.of();
    }
    Map<UUID, UUID> currentCategory =
        transactions.stream()
            .filter(transaction -> transaction.getCategoryId() != null)
            .collect(Collectors.toMap(Transaction::getId, Transaction::getCategoryId));
    // A latest row that no longer describes the current category (e.g. an override reset to
    // UNCATEGORIZED) says nothing about how the category was assigned.
    return categorizationLogRepository.findLatestAssignments(categorized).stream()
        .filter(latest -> latest.describes(currentCategory.get(latest.getTransactionId())))
        .collect(
            Collectors.toMap(
                LatestCategoryAssignment::getTransactionId,
                LatestCategoryAssignment::getAssignedBy));
  }

  /**
   * US-08-02: a member's own category for one transaction, which no automatic run replaces
   * (RULE-031, FR-CAT-014). Any type may be overridden; the category must be assignable for the
   * workspace and cannot be UNCATEGORIZED ({@link #resetCategory} is the way back). Needs EDIT on
   * the account. Idempotent: overriding with the category already overridden writes nothing.
   */
  @Transactional
  public TransactionResponse overrideCategory(
      UUID accountId,
      UUID transactionId,
      UUID categoryId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction transaction = requireCategorizable(accountId, transactionId, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, transaction.getVersion(), VERSIONED_RESOURCE);
    boolean alreadyOverridden =
        categoryId.equals(transaction.getCategoryId())
            && categorizationService.isOverridden(transaction);
    if (!alreadyOverridden) {
      categorizationService.override(transaction, categoryId, actor);
    }
    return toResponse(
        transaction, latestAssignments(List.of(transaction)).get(transaction.getId()));
  }

  /**
   * US-08-02 "reset to automatic": the explicit way to give up an override - the automatic layers
   * categorize the row again at once. Idempotent: a row that is not overridden is returned
   * unchanged.
   */
  @Transactional
  public TransactionResponse resetCategory(
      UUID accountId,
      UUID transactionId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Transaction transaction = requireCategorizable(accountId, transactionId, actor);
    versionPreconditionService.requireCurrent(
        expectedVersion, transaction.getVersion(), VERSIONED_RESOURCE);
    if (categorizationService.isOverridden(transaction)) {
      categorizationService.resetToAutomatic(transaction);
    }
    return toResponse(
        transaction, latestAssignments(List.of(transaction)).get(transaction.getId()));
  }

  // The category is an annotation, not a financial field (FR-CAT-014), so an archived account's
  // rows may still be categorized; a voided row keeps what it had when it was voided. The row is
  // locked until this transaction ends, so an automatic re-run of it waits (or ran first) instead
  // of
  // interleaving with the override check and write (RULE-031).
  private Transaction requireCategorizable(
      UUID accountId, UUID transactionId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    Transaction transaction =
        transactionRepository
            .findByIdForUpdate(transactionId)
            .filter(row -> row.getAccount().getId().equals(accountId))
            .orElseThrow(
                () -> accessControlService.denyAsNotFound(actor, "Transaction", transactionId));
    if (transaction.isReversal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "A reversing entry has no category of its own; it follows the voided original.");
    }
    if (transaction.getVoidedAt() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A voided transaction's category cannot be changed.");
    }
    return transaction;
  }

  private Optional<Transaction> findReplay(UUID accountId, CreateTransactionRequest request) {
    if (request.externalId() == null) {
      return Optional.empty();
    }
    Optional<Transaction> existing =
        transactionRepository.findByAccountIdAndSourceAndExternalId(
            accountId, MANUAL, request.externalId());
    // The same key must mean the same purchase: only the ledger-frozen financial fields are
    // compared (notes and merchant text stay editable, so comparing them would turn a later edit
    // into a false conflict) - including the FX/fee fields a foreign-currency purchase adds.
    if (existing.isPresent() && existing.get().getDeletedAt() != null) {
      // US-07-02: the key stays taken by the soft-deleted row it recorded.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "externalId '"
              + request.externalId()
              + "' was used for a transaction that has since been deleted; restore it instead.");
    }
    existing.ifPresent(
        row -> {
          boolean samePurchase =
              row.getTransactionType().equals(request.transactionType())
                  && row.getBookingDate().equals(request.bookingDate())
                  && row.getAmount().compareTo(request.amount()) == 0
                  && row.getCurrency().equals(request.currency())
                  && sameFxRate(row, request)
                  && sameFee(row, request)
                  && sameInvestment(row, request)
                  && sameCounterparty(row, request);
          if (!samePurchase) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "externalId '"
                    + request.externalId()
                    + "' was already used for a different transaction on this account.");
          }
        });
    return existing;
  }

  // An explicit fxRateToAccountCurrency is compared directly; a billedAmount is compared by
  // re-deriving the same rate resolveForeignCurrency would (division by zero can't occur here -
  // amount == 0 already fails validate()'s own sign check on the *original*, successful request,
  // and a compareTo-only comparison against a row that could only exist with a nonzero amount is
  // what's being asked). Neither given on the retry is not itself a conflict signal.
  private static boolean sameFxRate(Transaction row, CreateTransactionRequest request) {
    BigDecimal requestedRate;
    if (request.fxRateToAccountCurrency() != null) {
      requestedRate = request.fxRateToAccountCurrency();
    } else if (request.billedAmount() != null && request.amount().signum() != 0) {
      requestedRate =
          request.billedAmount().divide(request.amount(), FX_RATE_SCALE, RoundingMode.HALF_UP);
    } else if (request.billedAmount() != null) {
      return false; // a zero amount can't derive a comparable rate - not the same request
    } else {
      return true;
    }
    return row.getFxRateToAccountCurrency() != null
        && row.getFxRateToAccountCurrency().compareTo(requestedRate) == 0;
  }

  private boolean sameFee(Transaction row, CreateTransactionRequest request) {
    if (!CREDIT_CARD_PURCHASE.equals(row.getTransactionType())) {
      return sameDecimal(row.getFeeAmount(), request.feeAmount());
    }
    if (request.feeAmount() == null) {
      return true;
    }
    return transactionRepository
        .findByRelatedTransactionId(row.getId())
        .map(fee -> fee.getAmount().negate().compareTo(request.feeAmount()) == 0)
        .orElse(false);
  }

  // Desired state: no rate given means "let the server derive it", which matches a row whose rate
  // was derived (estimated) or that needs none (same currency); an explicit rate or billedAmount
  // matches only an explicit rate of the same value. sameFxRate compares the value only.
  private static boolean sameCorrectionFx(Transaction row, CreateTransactionRequest request) {
    if (request.fxRateToAccountCurrency() == null && request.billedAmount() == null) {
      return row.getFxRateToAccountCurrency() == null || row.isFxRateEstimated();
    }
    return !row.isFxRateEstimated() && sameFxRate(row, request);
  }

  // Desired state: the counterparty must be the same, including none. For a two-sided transfer the
  // credit leg must hold what creation would write: counterpartyAmount, or the mirrored amount when
  // both accounts share a currency. A counterparty gained by matching has no credit leg of ours.
  private boolean sameCorrectionCounterparty(Transaction row, CreateTransactionRequest request) {
    if (!Objects.equals(row.getCounterpartyAccountId(), request.counterpartyAccountId())) {
      return false;
    }
    if (request.counterpartyAccountId() == null) {
      return true;
    }
    BigDecimal expectedCredit =
        request.counterpartyAmount() != null
            ? request.counterpartyAmount()
            : request.amount().negate();
    return transactionRepository
        .findByRelatedTransactionId(row.getId())
        .map(credit -> credit.getAmount().compareTo(expectedCredit) == 0)
        .orElse(request.counterpartyAmount() == null);
  }

  // Unlike idempotency replay, correction is a desired-state comparison: null means no linked fee.
  private boolean sameCorrectionFee(Transaction row, CreateTransactionRequest request) {
    if (!CREDIT_CARD_PURCHASE.equals(row.getTransactionType())) {
      return sameDecimal(row.getFeeAmount(), request.feeAmount());
    }
    Optional<Transaction> fee = transactionRepository.findByRelatedTransactionId(row.getId());
    if (request.feeAmount() == null) {
      return fee.isEmpty();
    }
    return fee.map(existing -> existing.getAmount().negate().compareTo(request.feeAmount()) == 0)
        .orElse(false);
  }

  // US-10-01: a two-sided transfer names its other account; a one-sided leg matched or confirmed
  // since may have gained one, which is not a different request.
  private boolean sameCounterparty(Transaction row, CreateTransactionRequest request) {
    if (request.counterpartyAccountId() == null) {
      return true;
    }
    if (!request.counterpartyAccountId().equals(row.getCounterpartyAccountId())) {
      return false;
    }
    return request.counterpartyAmount() == null
        || transactionRepository
            .findByRelatedTransactionId(row.getId())
            .map(credit -> credit.getAmount().compareTo(request.counterpartyAmount()) == 0)
            .orElse(false);
  }

  // Every investment field is frozen and part of what the key identifies, so all must match; two
  // nulls match (a cash row carries none of them).
  private static boolean sameInvestment(Transaction row, CreateTransactionRequest request) {
    return Objects.equals(row.getSecurityId(), request.securityId())
        && sameDecimal(row.getQuantity(), request.quantity())
        && sameDecimal(row.getUnitPrice(), request.unitPrice())
        && Objects.equals(row.getTradeDate(), request.tradeDate())
        && Objects.equals(row.getSettlementDate(), request.settlementDate())
        && sameDecimal(row.getGrossAmount(), request.grossAmount())
        && sameDecimal(row.getTaxWithheldAmount(), request.taxWithheldAmount());
  }

  // compareTo, not equals: the stored NUMERIC comes back at the column's scale (10.0000000000),
  // the request at whatever scale the client sent (10).
  private static boolean sameDecimal(BigDecimal stored, BigDecimal requested) {
    return stored == null
        ? requested == null
        : requested != null && stored.compareTo(requested) == 0;
  }

  /**
   * Which cash types an account takes, decided by capability flags, never {@code account_type}
   * (US-05-04, enforced by ArchitectureTest). Narrowing later would break callers, so this starts
   * strict: a loan or mortgage (amortisation) is serviced by DEBT_REPAYMENT (US-10-02) - a signed
   * INTEREST/EXPENSE row would move its balance the wrong way - and a pension (contribution limit)
   * by PENSION_CONTRIBUTION (US-10-01). A custodian account takes only its own cash movements.
   */
  private static Set<String> cashTypesAllowedOn(Account account) {
    if (account.isHasAmortisation() || account.isHasContributionLimit()) {
      return Set.of();
    }
    return account.isHoldsPositions() ? CUSTODY_CASH_TYPES : CASH_TYPES;
  }

  private static String allowedHint(Account account) {
    Set<String> allowed = cashTypesAllowedOn(account);
    return allowed.isEmpty()
        ? "; it is serviced by its own transaction types"
        : "; allowed: " + String.join(", ", new TreeSet<>(allowed));
  }

  /**
   * Structural/business validation, throwing on the first violation. Returns whether the entry is
   * in a currency other than the account's own - the one caller, {@link #recordTransaction}, needs
   * that same boolean right after and previously recomputed it by hand a second time.
   */
  private static boolean validate(
      Account account, AccountCreditCard cardExtension, CreateTransactionRequest request) {
    String type = request.transactionType();
    if (!SUPPORTED_TYPES.contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Only "
              + String.join(", ", new TreeSet<>(SUPPORTED_TYPES))
              + " transactions can be recorded so far.");
    }
    if (!account.isHasTransactions()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "This account does not hold transactions.");
    }
    boolean card = account.isHasStatementCycle();
    boolean isCardPurchase = CREDIT_CARD_PURCHASE.equals(type);
    if (isCardPurchase && !card) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          CREDIT_CARD_PURCHASE + " can only be recorded against a credit-card account.");
    }
    // Only a card purchase needs cardExtension (the FX/billing_currency logic below) - the caller
    // deliberately skips the lookup for every other card write, so this must not fire for those.
    if (isCardPurchase && cardExtension == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "Credit-card details are missing.");
    }
    if (card && !CARD_TYPES.contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          WITHDRAWAL.equals(type)
              ? "A credit card is paid down by a " + SETTLEMENT + ", not a " + WITHDRAWAL + "."
              : "A credit card only accepts "
                  + String.join(", ", new TreeSet<>(CARD_TYPES))
                  + " transactions.");
    }
    if (!ACTIVE.equals(account.getStatus())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ACCOUNT_ARCHIVED,
          "Cannot record a transaction on an archived account.");
    }
    // A SETTLEMENT is not a cash type: it stays acceptable on an ordinary account as the payment
    // leg
    // of a card payment (US-09-02), whatever that account's kind.
    if (CASH_TYPES.contains(type) && !cashTypesAllowedOn(account).contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          type + " cannot be recorded on this kind of account" + allowedHint(account) + ".");
    }
    // Decided by the capability flag alone: a pension that holds funds trades like a depot; its
    // contribution limit governs contributions (US-10-01), not what happens inside it.
    if (INVESTMENT_TYPES.contains(type) && !account.isHoldsPositions()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          type + " can only be recorded on an account that holds positions.");
    }
    validateInvestment(type, request);
    TransferRecordingService.validateRequest(account, type, request);

    // US-09-04/FR-CC-010, US-07-01/DM-06: the currency may differ from the account's own. A card is
    // compared against its billing_currency, not account.nativeCurrency - the two may legitimately
    // differ (CreateAccountRequest). Any currency is accepted structurally here;
    // resolveForeignCurrency is what actually requires a real, derivable rate.
    String accountCurrency =
        isCardPurchase ? cardExtension.getBillingCurrency() : account.getNativeCurrency();
    boolean foreignCurrency = !accountCurrency.equals(request.currency());
    // A SETTLEMENT is the one type that must be in the account's own currency: matching pairs
    // payment and card credit by exact amount (US-09-02), which a converted row cannot satisfy.
    if (foreignCurrency && SETTLEMENT.equals(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "currency must match the account's currency ("
              + accountCurrency
              + ") for a "
              + type
              + ".");
    }
    if (!foreignCurrency
        && (request.fxRateToAccountCurrency() != null || request.billedAmount() != null)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "fxRateToAccountCurrency and billedAmount are only valid for a foreign-currency"
              + " transaction.");
    }
    // Two kinds of fee travel with their transaction: a trade's costs, part of its amount, and a
    // card issuer's disclosed foreign-transaction fee (FR-CC-010). Any other fee is simply its own
    // FEE transaction.
    boolean feeAllowed = TRADE_TYPES.contains(type) || (isCardPurchase && foreignCurrency);
    if (request.feeAmount() != null && !feeAllowed) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "feeAmount is only valid on a "
              + BUY
              + " or "
              + SELL
              + ", or a foreign-currency card purchase; record a "
              + FEE
              + " transaction instead.");
    }
    if (request.fxRateToAccountCurrency() != null && request.billedAmount() != null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Provide either fxRateToAccountCurrency or billedAmount, not both.");
    }
    if (request.fxRateToAccountCurrency() != null
        && request.fxRateToAccountCurrency().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "fxRateToAccountCurrency must be positive.");
    }
    if (request.billedAmount() != null
        && request.billedAmount().signum() != request.amount().signum()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "billedAmount must carry the same sign as amount (both cash-direction signed).");
    }
    // Only a sale can have a zero cash leg (proceeds exactly equal to costs); no rate follows from
    // it, and dividing by it below would fail.
    if (request.billedAmount() != null && request.amount().signum() == 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "A zero amount implies no rate: give fxRateToAccountCurrency instead of billedAmount.");
    }
    if (request.feeAmount() != null && request.feeAmount().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "feeAmount must be a positive magnitude.");
    }

    // Cash-direction signed ledger: rejecting a wrong sign rather than flipping it keeps "what you
    // send is what is stored". Money leaves the account for a purchase, a withdrawal, an expense,
    // fee or tax, a buy and the payment side of a settlement; it enters for income, deposit,
    // interest, refund, a dividend and the card side of a settlement. A sale has no fixed sign: its
    // cash leg is proceeds minus costs, which is zero or negative when the costs of selling a
    // near-worthless remnant exceed its proceeds, so requireTradeAmountMatches alone decides it.
    boolean mustBePositive =
        INFLOW_CASH_TYPES.contains(type)
            || DIVIDEND.equals(type)
            || (SETTLEMENT.equals(type) && card);
    // A one-sided TRANSFER may run either way; TransferRecordingService decides a transfer's sign.
    boolean signFixedByType = !SELL.equals(type) && !TRANSFER_TYPES.contains(type);
    if (signFixedByType
        && (mustBePositive ? request.amount().signum() <= 0 : request.amount().signum() >= 0)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          mustBePositive
              ? "amount must be positive for a " + type + " (e.g. 45.00): money enters the account."
              : "amount must be negative for a "
                  + type
                  + " (e.g. -85.00): money leaves the account.");
    }
    if (TRADE_TYPES.contains(type)) {
      requireTradeAmountMatches(request);
    }
    return foreignCurrency;
  }

  /**
   * US-07-01: the fields only an investment type carries, and the rules between them. Checked for
   * every type, so a cash or card entry carrying one of them is rejected rather than silently
   * storing a quantity no position will ever read.
   */
  private static void validateInvestment(String type, CreateTransactionRequest request) {
    if (!INVESTMENT_TYPES.contains(type)) {
      boolean anyInvestmentField =
          Stream.of(
                  request.securityId(),
                  request.quantity(),
                  request.unitPrice(),
                  request.tradeDate(),
                  request.settlementDate(),
                  request.grossAmount(),
                  request.taxWithheldAmount())
              .anyMatch(Objects::nonNull);
      if (anyInvestmentField) {
        throw unprocessable(
            "securityId, quantity, unitPrice, tradeDate, settlementDate, grossAmount and"
                + " taxWithheldAmount are only valid for "
                + String.join(", ", new TreeSet<>(INVESTMENT_TYPES))
                + ".");
      }
      return;
    }
    if (request.securityId() == null) {
      throw unprocessable("securityId is required for a " + type + ".");
    }
    if (request.unitPrice() != null && request.unitPrice().signum() <= 0) {
      throw unprocessable("unitPrice must be positive.");
    }
    if (TRADE_TYPES.contains(type)) {
      validateTrade(type, request);
    } else {
      validateDividend(request);
    }
  }

  private static void validateTrade(String type, CreateTransactionRequest request) {
    if (request.quantity() == null || request.unitPrice() == null) {
      throw unprocessable("quantity and unitPrice are required for a " + type + ".");
    }
    // Position-direction signed, so a position is the plain sum of its trades' quantities.
    boolean buy = BUY.equals(type);
    if (buy ? request.quantity().signum() <= 0 : request.quantity().signum() >= 0) {
      throw unprocessable(
          buy
              ? "quantity must be positive for a BUY (e.g. 10): shares enter the position."
              : "quantity must be negative for a SELL (e.g. -10): shares leave the position.");
    }
    if (request.grossAmount() != null || request.taxWithheldAmount() != null) {
      throw unprocessable(
          "grossAmount and taxWithheldAmount are only valid for a " + DIVIDEND + ".");
    }
    // FR-TRX-008: performance reads the trade date, cash balances the settlement date. The
    // booking date is the day the statement books the trade's cash, which can never precede the
    // trade itself; it usually equals the settlement date, but some custodians book on trade date,
    // so no tighter rule is imposed.
    if (request.tradeDate() != null
        && request.settlementDate() != null
        && request.settlementDate().isBefore(request.tradeDate())) {
      throw unprocessable("settlementDate cannot be before tradeDate.");
    }
    if (request.tradeDate() != null && request.tradeDate().isAfter(request.bookingDate())) {
      throw unprocessable(
          "tradeDate cannot be after bookingDate: a trade's cash is booked on or after its trade"
              + " date.");
    }
  }

  private static void validateDividend(CreateTransactionRequest request) {
    if (request.tradeDate() != null || request.settlementDate() != null) {
      throw unprocessable(
          "tradeDate and settlementDate are only valid for a " + BUY + " or " + SELL + ".");
    }
    // A dividend never moves the position, so its quantity is only the shares entitled.
    if (request.quantity() != null && request.quantity().signum() <= 0) {
      throw unprocessable("quantity must be positive for a DIVIDEND: the shares entitled.");
    }
    if ((request.grossAmount() == null) != (request.taxWithheldAmount() == null)) {
      throw unprocessable("grossAmount and taxWithheldAmount must be given together.");
    }
    if (request.grossAmount() != null) {
      if (request.taxWithheldAmount().signum() < 0) {
        throw unprocessable("taxWithheldAmount cannot be negative.");
      }
      // FR-TAXR-001: gross, withheld and net must reconcile exactly - they come from one advice.
      BigDecimal net = request.grossAmount().subtract(request.taxWithheldAmount());
      if (net.compareTo(request.amount()) != 0) {
        throw unprocessable(
            "grossAmount minus taxWithheldAmount ("
                + net.toPlainString()
                + ") must equal amount, the net cash received.");
      }
    }
    // Per-share gross against the gross total (or the amount, when nothing was withheld), with
    // the same rounding allowance as a trade, since a per-share rate is rounded on the advice too.
    if (request.quantity() != null && request.unitPrice() != null) {
      BigDecimal gross = request.grossAmount() != null ? request.grossAmount() : request.amount();
      BigDecimal perShareTotal = request.quantity().multiply(request.unitPrice());
      BigDecimal tolerance =
          amountTolerance(request.currency(), request.quantity(), request.unitPrice());
      if (perShareTotal.subtract(gross).abs().compareTo(tolerance) > 0) {
        throw unprocessable(
            "quantity x unitPrice ("
                + perShareTotal.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + ") must equal the gross dividend within "
                + tolerance.toPlainString()
                + "; if tax was withheld, give grossAmount and taxWithheldAmount.");
      }
    }
  }

  // The statement's amount is the authority; the check only catches a typo or a mixed-up field.
  private static void requireTradeAmountMatches(CreateTransactionRequest request) {
    BigDecimal fee = request.feeAmount() == null ? BigDecimal.ZERO : request.feeAmount();
    BigDecimal expected = request.quantity().multiply(request.unitPrice()).negate().subtract(fee);
    BigDecimal tolerance =
        amountTolerance(request.currency(), request.quantity(), request.unitPrice());
    if (expected.subtract(request.amount()).abs().compareTo(tolerance) > 0) {
      throw unprocessable(
          "amount must equal -(quantity x unitPrice) - feeAmount = "
              + expected.setScale(4, RoundingMode.HALF_UP).toPlainString()
              + " within "
              + tolerance.toPlainString()
              + ".");
    }
  }

  /**
   * How far a statement's {@code amount} may lie from {@code quantity x unitPrice}, given how both
   * were rounded on it: one minor unit of the currency (0.01 for CHF, 1 for JPY) for the booked
   * amount, plus half a unit in the last place of the unit price for every share, because a
   * statement shows the (average) price rounded while the amount is computed from the exact one. A
   * price is taken as given at least to the currency's minor unit - "100" means 100.00 - so a
   * whole-number price does not widen the allowance to half a currency unit per share.
   *
   * <p>E.g. 25,000 shares at a shown 12.3457 (exact 12.345678): 0.01 + 25,000 x 0.00005 = 1.26,
   * which accepts the booked 308,641.95 against the computed 308,642.50, while 10 x 100 with a fee
   * of 5 still rejects a typed 1,010 for the correct 1,005 (0.01 + 10 x 0.005 = 0.06).
   */
  static BigDecimal amountTolerance(String currency, BigDecimal quantity, BigDecimal unitPrice) {
    int minorDigits = minorDigits(currency);
    BigDecimal bookedAmountRounding = BigDecimal.ONE.movePointLeft(minorDigits);
    int priceDigits = Math.max(unitPrice.stripTrailingZeros().scale(), minorDigits);
    BigDecimal priceRounding = HALF.movePointLeft(priceDigits);
    return bookedAmountRounding.add(quantity.abs().multiply(priceRounding)).stripTrailingZeros();
  }

  private static int minorDigits(String currency) {
    int digits = Currency.getInstance(currency).getDefaultFractionDigits();
    return digits < 0 ? DEFAULT_MINOR_DIGITS : digits;
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }

  /**
   * The rate to record for a foreign-currency card purchase, in priority order: an explicit {@code
   * fxRateToAccountCurrency}; a disclosed {@code billedAmount} (rate derived by division, scaled to
   * {@value #FX_RATE_SCALE} places - {@code fx_rate_to_account_currency}'s own NUMERIC(20,10)); or,
   * if neither is given, {@link FxRateService}'s generic daily rate, flagged {@code estimated}
   * (PR-011). A 422 if even that fallback has nothing to offer - the edge case the story itself
   * calls out: never leave {@code fx_rate_to_account_currency} null with no indication why.
   */
  private ForeignCurrencyResolution resolveForeignCurrency(
      String accountCurrency, CreateTransactionRequest request) {
    if (request.fxRateToAccountCurrency() != null) {
      return new ForeignCurrencyResolution(request.fxRateToAccountCurrency(), false);
    }
    if (request.billedAmount() != null) {
      BigDecimal rate =
          request.billedAmount().divide(request.amount(), FX_RATE_SCALE, RoundingMode.HALF_UP);
      if (rate.abs().compareTo(MAX_FX_RATE) >= 0) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "billedAmount implies a rate with too many digits to record"
                + " (fx_rate_to_account_currency allows at most 10 integer digits).");
      }
      return new ForeignCurrencyResolution(rate, false);
    }
    Optional<CurrencyConversionResult> fallback =
        fxRateService.tryGetConversionRate(
            request.currency(), accountCurrency, request.bookingDate(), fxDefaultSource);
    if (fallback.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "No FX rate is available for "
              + request.currency()
              + " to "
              + accountCurrency
              + " on "
              + request.bookingDate()
              + "; supply fxRateToAccountCurrency or billedAmount explicitly.");
    }
    return new ForeignCurrencyResolution(fallback.get().rate(), true);
  }

  private TransactionResponse toResponse(Transaction transaction, String categoryAssignedBy) {
    return new TransactionResponse(
        transaction.getId(),
        transaction.getAccount().getId(),
        transaction.getTransactionType(),
        transaction.getBookingDate(),
        transaction.getAmount(),
        transaction.getCurrency(),
        transaction.getMerchantDescription(),
        extractMcc(transaction.getRawSourceData()),
        transaction.getNotes(),
        transaction.getSource(),
        transaction.getExternalId(),
        transaction.isInternalTransfer(),
        transaction.getFxRateToAccountCurrency(),
        transaction.isFxRateEstimated(),
        transaction.getRelatedTransactionId(),
        transaction.getCreatedAt(),
        transaction.getSecurityId(),
        transaction.getQuantity(),
        transaction.getUnitPrice(),
        transaction.getFeeAmount(),
        transaction.getTradeDate(),
        transaction.getSettlementDate(),
        transaction.getGrossAmount(),
        transaction.getTaxWithheldAmount(),
        transaction.getNetAmount(),
        transaction.getCategoryId(),
        categoryAssignedBy,
        removalOf(transaction),
        transaction.getVoidedAt(),
        transaction.getVoidReason(),
        transaction.getRestoredAt(),
        transaction.getReplacesTransactionId(),
        transaction.getCorrectsTransactionId(),
        transaction.getDeletedAt(),
        transaction.getCounterpartyAccountId(),
        VersionPreconditionService.persistedVersion(transaction.getVersion(), VERSIONED_RESOURCE));
  }

  /**
   * US-07-02/FR-LIF-002b: how the row would be removed, decided by its provenance - a manual row is
   * soft-deleted (T1), anything imported is voided (T2); {@code null} once it cannot be removed any
   * more. T3 (a reconciled row) arrives with reconciliation (US-25-02).
   */
  static String removalOf(Transaction transaction) {
    if (transaction.getVoidedAt() != null
        || transaction.isReversal()
        || transaction.getDeletedAt() != null) {
      return null;
    }
    return MANUAL.equals(transaction.getSource())
        ? TransactionRemovalValues.SOFT_DELETE
        : TransactionRemovalValues.VOID;
  }

  /** Responses for many rows at once, with how each got its category (one log query). */
  public List<TransactionResponse> toResponses(List<Transaction> transactions) {
    Map<UUID, String> assignments = latestAssignments(transactions);
    return transactions.stream()
        .map(transaction -> toResponse(transaction, assignments.get(transaction.getId())))
        .toList();
  }

  // A correction keeps the original's raw_source_data (US-07-06) and sets a corrected MCC in it;
  // a new row's raw_source_data is just its MCC. Anything else an import wrote stays as it was.
  private String sourceDataWithMcc(String rawSourceData, String mcc) {
    if (rawSourceData == null) {
      return mcc == null ? null : objectMapper.writeValueAsString(Map.of(MCC_KEY, mcc));
    }
    if (mcc == null) {
      return rawSourceData;
    }
    JsonNode existing = objectMapper.readTree(rawSourceData);
    ObjectNode merged =
        existing instanceof ObjectNode object ? object : objectMapper.createObjectNode();
    merged.put(MCC_KEY, mcc);
    return objectMapper.writeValueAsString(merged);
  }

  // raw_source_data may in future carry a richer, import-defined shape (EPIC 07); only the "mcc"
  // key is echoed in responses (CategorizationService also reads the ISO 20022 keys), so read just
  // that and tolerate anything else being present. An
  // importer may well write the MCC as a JSON number ({"mcc":5411}) rather than a string; both
  // read back as the four-digit code (a number loses leading zeros, so it is re-padded).
  private String extractMcc(String rawSourceData) {
    if (rawSourceData == null) {
      return null;
    }
    JsonNode mcc = objectMapper.readTree(rawSourceData).path(MCC_KEY);
    if (mcc.isString()) {
      return mcc.stringValue();
    }
    if (mcc.isIntegralNumber()) {
      return String.format("%04d", mcc.longValue());
    }
    return null;
  }
}
