package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * US-09-01: records individual credit-card purchases on the append-only ledger (FR-CC-001, DM-11).
 * This is deliberately the narrow slice of US-07-01 ("manually record a transaction of any
 * supported type") that the card story needs: only {@code CREDIT_CARD_PURCHASE} is accepted so far,
 * and US-07-01 widens the accepted set rather than replacing this method.
 *
 * <p>Whether an account is a credit card is decided by its {@code hasStatementCycle} capability
 * flag, never by {@code account_type} ({@code ArchitectureTest}, US-05-04). No DB trigger guards
 * {@code transaction.account_id} against the account's type - {@code trg_extension_type_guard} (V5)
 * only fires on inserts into the extension tables - so this service check is the only thing
 * standing between a card purchase and, say, a cash account.
 *
 * <p>The balance a purchase changes is read through {@link AccountValuationService}, not computed
 * here: recording and valuing are separate concerns, and the balance is always derived from the
 * ledger, never stored (CLAUDE.md: derived data is rebuildable from source).
 */
@Service
public class TransactionService {

  private static final String CREDIT_CARD_PURCHASE = "CREDIT_CARD_PURCHASE";
  private static final String ACTIVE = "ACTIVE";
  private static final String MCC_KEY = "mcc";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final ObjectMapper objectMapper;

  public TransactionService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      ObjectMapper objectMapper) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public TransactionResponse recordTransaction(
      UUID accountId, CreateTransactionRequest request, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
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
    transaction.setSource("MANUAL");
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
    return toResponse(transactionRepository.saveAndFlush(transaction));
  }

  @Transactional(readOnly = true)
  public List<TransactionResponse> listTransactions(
      UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    return transactionRepository
        .findByAccountIdOrderByBookingDateDescCreatedAtDesc(accountId)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  private static void validate(Account account, CreateTransactionRequest request) {
    if (!CREDIT_CARD_PURCHASE.equals(request.transactionType())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Only " + CREDIT_CARD_PURCHASE + " transactions can be recorded so far.");
    }
    if (!account.isHasStatementCycle()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          CREDIT_CARD_PURCHASE + " can only be recorded against a credit-card account.");
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
              + "); foreign-currency card purchases are not supported yet.");
    }
    // Cash-direction signed ledger: a purchase is money leaving the card's headroom, so it is
    // negative. Rejecting rather than flipping the sign keeps "what you send is what is stored".
    if (request.amount().signum() >= 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "amount must be negative for a " + CREDIT_CARD_PURCHASE + " (e.g. -85.00).");
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
        transaction.getCreatedAt());
  }

  // raw_source_data may in future carry a richer, import-defined shape (EPIC 07); only the "mcc"
  // key is this story's contract, so read just that and tolerate anything else being present.
  private String extractMcc(String rawSourceData) {
    if (rawSourceData == null) {
      return null;
    }
    return objectMapper.readTree(rawSourceData).path(MCC_KEY).stringValue(null);
  }
}
