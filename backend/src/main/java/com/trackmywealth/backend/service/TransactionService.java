package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.CurrencyConversionResult;
import com.trackmywealth.backend.dto.ForeignCurrencyResolution;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
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

/**
 * Records individual transactions on the append-only ledger (US-07-01, widened from the slice the
 * credit-card stories US-09-01..04 needed). Accepted so far: the single-account cash types ({@code
 * INCOME}, {@code EXPENSE}, {@code DEPOSIT}, {@code WITHDRAWAL}, {@code INTEREST}, {@code FEE},
 * {@code TAX}, {@code REFUND}) plus the card types {@code CREDIT_CARD_PURCHASE} and {@code
 * SETTLEMENT}. Two-sided types (TRANSFER, DEBT_REPAYMENT, PENSION_CONTRIBUTION) arrive with
 * US-10-01 and the investment types (BUY, SELL, DIVIDEND) with the security master (US-12-01). A
 * card account accepts only its own two types; a {@code FEE} is also created alongside a
 * foreign-currency card purchase, see {@link #recordTransaction}.
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

  private static final String CREDIT_CARD_PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String SETTLEMENT = "SETTLEMENT";
  private static final String WITHDRAWAL = "WITHDRAWAL";
  private static final String FEE = "FEE";
  // US-07-01 cash types: single-account, cash-direction signed. Money leaves the account for the
  // first group and enters it for the second. TRANSFER, DEBT_REPAYMENT and PENSION_CONTRIBUTION are
  // two-sided and belong to US-10-01; BUY/SELL/DIVIDEND need the security master (US-12-01).
  private static final Set<String> OUTFLOW_CASH_TYPES = Set.of(WITHDRAWAL, FEE, "EXPENSE", "TAX");
  private static final Set<String> INFLOW_CASH_TYPES =
      Set.of("INCOME", "DEPOSIT", "INTEREST", "REFUND");
  private static final Set<String> CASH_TYPES =
      Stream.concat(OUTFLOW_CASH_TYPES.stream(), INFLOW_CASH_TYPES.stream())
          .collect(Collectors.toUnmodifiableSet());
  // A credit card is only ever charged (purchase, incl. its own FEE row) or paid down (settlement).
  private static final Set<String> CARD_TYPES = Set.of(CREDIT_CARD_PURCHASE, SETTLEMENT);
  private static final Set<String> SUPPORTED_TYPES =
      Stream.concat(CASH_TYPES.stream(), Stream.of(CREDIT_CARD_PURCHASE, SETTLEMENT))
          .collect(Collectors.toUnmodifiableSet());
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
  private final ObjectMapper objectMapper;
  private final String fxDefaultSource;

  public TransactionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      SettlementDetectionService settlementDetectionService,
      FxRateService fxRateService,
      ObjectMapper objectMapper,
      @Value("${app.fx.default-source}") String fxDefaultSource) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.fxRateService = fxRateService;
    this.objectMapper = objectMapper;
    this.fxDefaultSource = fxDefaultSource;
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
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);

    // Before validate(): a replay must answer with the original row even if the account has been
    // archived since, rather than turn a successful earlier request into a 409 on retry.
    Optional<Transaction> replay = findReplay(accountId, request);
    if (replay.isPresent()) {
      return toResponse(replay.get());
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
    transaction.setSource(MANUAL);
    transaction.setExternalId(request.externalId());
    // FR-CC-002/RULE-011: the MCC is source data, kept in raw_source_data - category_id is a
    // separate column a later categorization writes, so neither can overwrite the other.
    transaction.setRawSourceData(
        request.mcc() == null
            ? null
            : objectMapper.writeValueAsString(Map.of(MCC_KEY, request.mcc())));
    transaction.setCreatedBy(actor.userId());
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

    if (foreignCurrency && request.feeAmount() != null) {
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
      fee.setSource(MANUAL);
      fee.setCreatedBy(actor.userId());
      transactionRepository.saveAndFlush(fee);
    }

    // Same transaction, so the response below already shows the internal-transfer flag if this row
    // just completed a settlement pair.
    settlementDetectionService.detectAfterWrite(account, request.bookingDate());
    return toResponse(saved);
  }

  @Transactional(readOnly = true)
  public Page<TransactionResponse> listTransactions(
      UUID accountId, Pageable pageable, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    Pageable bounded =
        PageRequest.of(
            pageable.getPageNumber(),
            Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
            LEDGER_ORDER);
    return transactionRepository.findByAccountId(accountId, bounded).map(this::toResponse);
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
    existing.ifPresent(
        row -> {
          boolean samePurchase =
              row.getTransactionType().equals(request.transactionType())
                  && row.getBookingDate().equals(request.bookingDate())
                  && row.getAmount().compareTo(request.amount()) == 0
                  && row.getCurrency().equals(request.currency())
                  && sameFxRate(row, request)
                  && sameFee(row, request);
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
    if (request.feeAmount() == null) {
      return true;
    }
    return transactionRepository
        .findByRelatedTransactionId(row.getId())
        .map(fee -> fee.getAmount().negate().compareTo(request.feeAmount()) == 0)
        .orElse(false);
  }

  /**
   * Structural/business validation, throwing on the first violation. Returns whether this is a
   * foreign-currency card purchase - the one caller, {@link #recordTransaction}, needs that same
   * boolean right after and previously recomputed it by hand a second time.
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
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cannot record a transaction on an archived account.");
    }

    // US-09-04/FR-CC-010, US-07-01/DM-06: the currency may differ from the account's own. A card is
    // compared against its billing_currency, not account.nativeCurrency - the two may legitimately
    // differ (CreateAccountRequest). Any currency is accepted structurally here;
    // resolveForeignCurrency is what actually requires a real, derivable rate.
    String accountCurrency =
        isCardPurchase ? cardExtension.getBillingCurrency() : account.getNativeCurrency();
    boolean foreignCurrency = !accountCurrency.equals(request.currency());
    // A SETTLEMENT is the one type that must be in the account's own currency: matching pairs
    // payment and card credit by exact amount (US-09-02), which a converted row cannot satisfy.
    if (foreignCurrency && !isCardPurchase && !CASH_TYPES.contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "currency must match the account's currency ("
              + accountCurrency
              + ") for a "
              + type
              + ".");
    }
    if (!foreignCurrency
        && (request.fxRateToAccountCurrency() != null
            || request.billedAmount() != null
            || request.feeAmount() != null)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "fxRateToAccountCurrency, billedAmount and feeAmount are only valid for a"
              + " foreign-currency transaction.");
    }
    // A disclosed foreign-transaction fee is a card-issuer concept (FR-CC-010); on an ordinary
    // account the fee is simply its own FEE transaction.
    if (request.feeAmount() != null && !isCardPurchase) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "feeAmount is only valid for a foreign-currency card purchase; record a "
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
    if (request.feeAmount() != null && request.feeAmount().signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "feeAmount must be a positive magnitude.");
    }

    // Cash-direction signed ledger: rejecting a wrong sign rather than flipping it keeps "what you
    // send is what is stored". Money leaves the account for a purchase, a withdrawal, an expense,
    // fee or tax and the payment side of a settlement; it enters for income, deposit, interest,
    // refund and the card side of a settlement.
    boolean mustBePositive = INFLOW_CASH_TYPES.contains(type) || (SETTLEMENT.equals(type) && card);
    if (mustBePositive ? request.amount().signum() <= 0 : request.amount().signum() >= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          mustBePositive
              ? "amount must be positive for a " + type + " (e.g. 45.00): money enters the account."
              : "amount must be negative for a "
                  + type
                  + " (e.g. -85.00): money leaves the account.");
    }
    return foreignCurrency;
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

  private TransactionResponse toResponse(Transaction transaction) {
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
        transaction.getCreatedAt());
  }

  // raw_source_data may in future carry a richer, import-defined shape (EPIC 07); only the "mcc"
  // key is this story's contract, so read just that and tolerate anything else being present. An
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
