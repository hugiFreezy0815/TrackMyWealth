package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-10-01: recording a transfer between two of the workspace's own accounts, for {@link
 * TransactionService#recordTransaction}. A {@code TRANSFER} (or {@code PENSION_CONTRIBUTION}, into
 * an account with a contribution limit) with a {@code counterpartyAccountId} is one entry with two
 * legs: the debit the caller records, and the credit on the other account written here, already
 * linked as an internal transfer (DM-05). A {@code TRANSFER} without a counterparty is a one-sided
 * leg, left to {@link TransferDetectionService} and {@link TransferResolutionService}.
 *
 * <p>Each leg is in its own account's currency - the other side's amount is {@code
 * counterpartyAmount} - so no FX field ever applies. A loan or mortgage is serviced by {@code
 * DEBT_REPAYMENT} (US-10-02), a card by a {@code SETTLEMENT}.
 */
@Service
public class TransferRecordingService {

  static final String TRANSFER = "TRANSFER";
  static final String PENSION_CONTRIBUTION = "PENSION_CONTRIBUTION";

  /** The types that are one leg, or both at once, of an own-account transfer. */
  static final Set<String> TRANSFER_TYPES = Set.of(TRANSFER, PENSION_CONTRIBUTION);

  private static final String SETTLEMENT = "SETTLEMENT";
  private static final String ACTIVE = "ACTIVE";
  private static final String MANUAL = "MANUAL";
  private static final String NOT_FOUND = "Account not found.";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final CategorizationService categorizationService;

  public TransferRecordingService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      CategorizationService categorizationService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.categorizationService = categorizationService;
  }

  /**
   * What a transfer entry must look like before its counterparty is loaded; for any other type,
   * that it carries no counterparty field. With a counterparty the money leaves this account; a
   * one-sided {@code TRANSFER} may run either way. {@code PENSION_CONTRIBUTION} always names the
   * pension it pays into.
   */
  static void validateRequest(Account account, String type, CreateTransactionRequest request) {
    if (!TRANSFER_TYPES.contains(type)) {
      if (request.counterpartyAccountId() != null || request.counterpartyAmount() != null) {
        throw unprocessable(
            "counterpartyAccountId and counterpartyAmount are only valid for a "
                + TRANSFER
                + " or "
                + PENSION_CONTRIBUTION
                + ".");
      }
      return;
    }
    if (account.isHasAmortisation()) {
      throw unprocessable(
          "A loan or mortgage is serviced by its own transaction type, not a " + type + ".");
    }
    if (!request.currency().equals(account.getNativeCurrency())) {
      throw unprocessable(
          "A transfer leg is in its account's own currency ("
              + account.getNativeCurrency()
              + "); give counterpartyAmount for what the other account receives.");
    }
    if (PENSION_CONTRIBUTION.equals(type) && request.counterpartyAccountId() == null) {
      throw unprocessable("A PENSION_CONTRIBUTION needs the pension account it pays into.");
    }
    if (request.counterpartyAccountId() == null) {
      if (request.counterpartyAmount() != null) {
        throw unprocessable("counterpartyAmount needs a counterpartyAccountId.");
      }
      if (request.amount().signum() == 0) {
        throw unprocessable("amount of a " + TRANSFER + " cannot be zero.");
      }
      return;
    }
    if (request.amount().signum() >= 0) {
      throw unprocessable(
          "amount must be negative for a "
              + type
              + " to another account (e.g. -500.00): money leaves this account.");
    }
    if (request.counterpartyAmount() != null && request.counterpartyAmount().signum() <= 0) {
      throw unprocessable("counterpartyAmount must be positive: money enters the other account.");
    }
  }

  /**
   * The other account of a two-sided transfer, or {@code null} when the request names none: one of
   * the same workspace's accounts, which the member may edit too, since the entry writes there, and
   * one that can take this kind of transfer. Call after {@link #validateRequest}.
   */
  Account findCounterparty(
      Account account, CreateTransactionRequest request, AuthenticatedUserPrincipal actor) {
    if (request.counterpartyAccountId() == null) {
      return null;
    }
    Account counterparty = accountLookupService.findAccountOrThrow(request.counterpartyAccountId(), actor);
    // RLS already hides another workspace's accounts; checked here too, since the credit leg is
    // written into this account's workspace. Answered like an account the caller cannot see.
    if (!counterparty.getWorkspace().getId().equals(account.getWorkspace().getId())) {
      throw accessControlService.denyAsNotFound(
          actor, "Account", request.counterpartyAccountId());
    }
    accessControlService.requireAccountAccess(actor, counterparty, AccessLevelValues.EDIT);
    String type = request.transactionType();
    if (counterparty.getId().equals(account.getId())) {
      throw unprocessable("A transfer needs two different accounts.");
    }
    if (!ACTIVE.equals(counterparty.getStatus())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ACCOUNT_ARCHIVED,
          "Cannot transfer to or from an archived account.");
    }
    if (!counterparty.isHasTransactions()) {
      throw unprocessable("The other account does not hold transactions.");
    }
    if (counterparty.isHasStatementCycle()) {
      throw unprocessable(
          "A credit card is paid down by a " + SETTLEMENT + ", not a " + type + ".");
    }
    if (counterparty.isHasAmortisation()) {
      throw unprocessable(
          "A loan or mortgage is serviced by its own transaction type, not a " + type + ".");
    }
    if (PENSION_CONTRIBUTION.equals(type) != counterparty.isHasContributionLimit()) {
      throw unprocessable(
          PENSION_CONTRIBUTION.equals(type)
              ? "A PENSION_CONTRIBUTION goes into an account with a contribution limit."
              : "A payment into a pension account is a " + PENSION_CONTRIBUTION + ".");
    }
    boolean sameCurrency = sameCurrency(account, counterparty);
    if (sameCurrency && request.counterpartyAmount() != null) {
      throw unprocessable(
          "counterpartyAmount is only for accounts in different currencies; the other account"
              + " receives the same amount.");
    }
    if (!sameCurrency && request.counterpartyAmount() == null) {
      throw unprocessable(
          "The other account is in "
              + counterparty.getNativeCurrency()
              + ": give counterpartyAmount, what it receives.");
    }
    return counterparty;
  }

  /**
   * Writes the incoming leg of a two-sided transfer whose debit was just saved: on the other
   * account, in its own currency, linked back to the debit by {@code related_transaction_id},
   * flagged like the debit. It goes through categorization like every new row and like the debit
   * (US-08-01), which leaves it uncategorized today: transfer types are not in {@code
   * CategorizationService.CATEGORIZED_TYPES}.
   */
  Transaction recordCreditLeg(
      Transaction debit,
      Account counterparty,
      CreateTransactionRequest request,
      AuthenticatedUserPrincipal actor) {
    Transaction credit = new Transaction();
    credit.setWorkspace(debit.getWorkspace());
    credit.setAccount(counterparty);
    credit.setTransactionType(debit.getTransactionType());
    credit.setBookingDate(debit.getBookingDate());
    credit.setAmount(
        sameCurrency(debit.getAccount(), counterparty)
            ? debit.getAmount().negate()
            : request.counterpartyAmount());
    credit.setCurrency(counterparty.getNativeCurrency());
    credit.setMerchantDescription(debit.getMerchantDescription());
    credit.setNotes(debit.getNotes());
    credit.setSource(MANUAL);
    credit.setInternalTransfer(true);
    credit.setCounterpartyAccountId(debit.getAccount().getId());
    credit.setRelatedTransactionId(debit.getId());
    credit.setCreatedBy(actor.userId());
    Transaction saved = transactionRepository.saveAndFlush(credit);
    categorizationService.categorize(saved);
    return saved;
  }

  private static boolean sameCurrency(Account account, Account counterparty) {
    return counterparty.getNativeCurrency().equals(account.getNativeCurrency());
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }
}
