package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * Records individual transactions on the append-only ledger. This is deliberately the narrow slice
 * of US-07-01 ("manually record a transaction of any supported type") that the credit-card stories
 * need: US-09-01's {@code CREDIT_CARD_PURCHASE}, and US-09-02's {@code SETTLEMENT} (either leg of a
 * card payment) and {@code WITHDRAWAL} (money out of an ordinary account). US-07-01 widens the
 * accepted set rather than replacing this method.
 *
 * <p>Every amount is cash-direction signed and stored as sent (see {@link Transaction}); the sign
 * each type must carry is checked in {@link #validate}. Whether an account is a credit card is
 * decided by its {@code hasStatementCycle} capability flag, never by {@code account_type} ({@code
 * ArchitectureTest}, US-05-04). No DB trigger guards {@code transaction.account_id} against the
 * account's type - {@code trg_extension_type_guard} (V5) only fires on inserts into the extension
 * tables - so these service checks are the only thing standing between a card purchase and, say, a
 * cash account.
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
  private static final Set<String> SUPPORTED_TYPES =
      Set.of(CREDIT_CARD_PURCHASE, SETTLEMENT, WITHDRAWAL);
  private static final String ACTIVE = "ACTIVE";
  private static final String MANUAL = "MANUAL";
  private static final String MCC_KEY = "mcc";

  // A page is never larger than this whatever the client asks for, and its order is fixed here, not
  // taken from the request: newest booking first, with created_at and id as tie-breakers so paging
  // is stable across rows booked on the same day.
  private static final int MAX_PAGE_SIZE = 200;
  private static final Sort LEDGER_ORDER =
      Sort.by(Sort.Order.desc("bookingDate"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final ObjectMapper objectMapper;

  public TransactionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      SettlementDetectionService settlementDetectionService,
      ObjectMapper objectMapper) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.objectMapper = objectMapper;
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
    validate(account, request);

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

    // flush, not a plain save: forces the INSERT (and any constraint/trigger rejection) to happen
    // here, inside this method, rather than deferred to end-of-transaction commit - same reasoning
    // as AccountService's own saveAndFlush calls.
    Transaction saved = transactionRepository.saveAndFlush(transaction);
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
    // into a false conflict).
    existing.ifPresent(
        row -> {
          boolean samePurchase =
              row.getTransactionType().equals(request.transactionType())
                  && row.getBookingDate().equals(request.bookingDate())
                  && row.getAmount().compareTo(request.amount()) == 0
                  && row.getCurrency().equals(request.currency());
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

  private static void validate(Account account, CreateTransactionRequest request) {
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
    if (CREDIT_CARD_PURCHASE.equals(type) && !card) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          CREDIT_CARD_PURCHASE + " can only be recorded against a credit-card account.");
    }
    if (WITHDRAWAL.equals(type) && card) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "A credit card is paid down by a " + SETTLEMENT + ", not a " + WITHDRAWAL + ".");
    }
    if (!ACTIVE.equals(account.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cannot record a transaction on an archived account.");
    }
    // US-09-04 owns foreign-currency card purchases (original amount + issuer rate + fee). Until
    // it lands, accepting another currency here would silently drop the FX rate DM-06 requires.
    if (!account.getNativeCurrency().equals(request.currency())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "currency must match the account's currency ("
              + account.getNativeCurrency()
              + "); foreign-currency transactions are not supported yet.");
    }
    // Cash-direction signed ledger: rejecting a wrong sign rather than flipping it keeps "what you
    // send is what is stored". Money leaves the account for a purchase, a withdrawal, and the
    // payment side of a settlement; it enters the card for the card side of a settlement.
    boolean mustBePositive = SETTLEMENT.equals(type) && card;
    if (mustBePositive ? request.amount().signum() <= 0 : request.amount().signum() >= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          mustBePositive
              ? "amount must be positive for the card side of a "
                  + SETTLEMENT
                  + " (e.g. 1200.00): it reduces what the card owes."
              : "amount must be negative for a "
                  + type
                  + " (e.g. -85.00): money leaves the account.");
    }
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
