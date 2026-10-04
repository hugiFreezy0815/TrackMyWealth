package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.OpeningBalanceRequest;
import com.trackmywealth.backend.dto.OpeningBalanceResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ExistingResourceConflictException;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.DatabaseClockRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-25-04/FR-REC-007: an account's dated opening balance, for an account whose transaction history
 * starts later than the account itself. It is stored as the account's one {@code account_snapshot}
 * with {@code is_opening_balance} ({@code MANUAL}, no holdings; V58 allows one per account), and
 * {@link AccountValuationService} values the account from it - this service only records it.
 *
 * <p>Rules, in the order they are checked:
 *
 * <ul>
 *   <li>Writes need {@code EDIT}, reads {@code READ} on the account (as for snapshots, US-25-01): a
 *       {@code BALANCE_ONLY} grant sees the resulting balance, not the opening balance itself.
 *   <li>An account with a value source of its own takes none - {@code hasAmortisation} (loans,
 *       mortgages) and {@code manualValuation} (custom assets): 422 {@code
 *       OPENING_BALANCE_NOT_APPLICABLE}. Decided by capability flag, never by {@code account_type}.
 *   <li>The currency is the account's own (a card's billing currency, {@link
 *       AccountCurrencyService}); any other is a 422, as a conversion is not an opening balance.
 *   <li>The date is not in the future and lies within the account's {@code opened_at}/{@code
 *       closed_at}, where those are known (422), like a snapshot's.
 *   <li>A regular manual snapshot on the same date is a 409 naming it ({@code existingSnapshotId}):
 *       both would be {@code MANUAL} rows for one account and date.
 *   <li>Live transactions booked before the date are a 409 {@code
 *       OPENING_BALANCE_AFTER_FIRST_TRANSACTION} with their count and earliest booking date, unless
 *       the request acknowledges them. Acknowledged, they are left out of the balance and the
 *       account carries {@code TRANSACTIONS_BEFORE_OPENING_BALANCE} - never double-counted
 *       silently.
 * </ul>
 *
 * <p>{@link #record} creates it (a second one is a 409 naming the first); {@link #replace} and
 * {@link #delete} need {@code If-Match} (ADR 0004).
 */
@Service
public class OpeningBalanceService {

  private static final String MANUAL = "MANUAL";
  private static final String RESOURCE_NAME = "opening balance";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final BusinessDateService businessDateService;
  private final AccountSnapshotRepository snapshotRepository;
  private final TransactionRepository transactionRepository;
  private final AccountCurrencyService accountCurrencyService;
  private final AccountDataQualityService accountDataQualityService;
  private final VersionPreconditionService versionPreconditionService;
  private final ReconciliationService reconciliationService;
  private final DatabaseClockRepository databaseClock;

  public OpeningBalanceService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      BusinessDateService businessDateService,
      AccountSnapshotRepository snapshotRepository,
      TransactionRepository transactionRepository,
      AccountCurrencyService accountCurrencyService,
      AccountDataQualityService accountDataQualityService,
      VersionPreconditionService versionPreconditionService,
      ReconciliationService reconciliationService,
      DatabaseClockRepository databaseClock) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.businessDateService = businessDateService;
    this.snapshotRepository = snapshotRepository;
    this.transactionRepository = transactionRepository;
    this.accountCurrencyService = accountCurrencyService;
    this.accountDataQualityService = accountDataQualityService;
    this.versionPreconditionService = versionPreconditionService;
    this.reconciliationService = reconciliationService;
    this.databaseClock = databaseClock;
  }

  @Transactional(readOnly = true)
  public OpeningBalanceResponse get(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    return toResponse(findOrThrow(accountId));
  }

  @Transactional
  public OpeningBalanceResponse record(
      UUID accountId, OpeningBalanceRequest request, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    snapshotRepository
        .findByAccountIdAndOpeningBalanceTrue(accountId)
        .ifPresent(
            existing -> {
              throw new ExistingResourceConflictException(
                  "This account already has an opening balance. Replace it instead.",
                  "existingOpeningBalanceId",
                  existing.getId());
            });
    String currency = validate(account, request, null);

    AccountSnapshot snapshot = new AccountSnapshot();
    snapshot.setWorkspace(account.getWorkspace());
    snapshot.setAccount(account);
    snapshot.setSource(MANUAL);
    snapshot.setOpeningBalance(true);
    snapshot.setCreatedBy(actor.userId());
    apply(snapshot, request, currency);
    // flush, not a plain save: a concurrent second opening balance's unique violation (V58)
    // surfaces here, as a 409 from GlobalExceptionHandler, rather than at commit.
    AccountSnapshot saved = snapshotRepository.saveAndFlush(snapshot);
    reconciliationService.reconcileLatest(account, actor.userId());
    return toResponse(saved);
  }

  @Transactional
  public OpeningBalanceResponse replace(
      UUID accountId,
      OpeningBalanceRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    AccountSnapshot snapshot = findOrThrow(accountId);
    versionPreconditionService.requireCurrent(
        expectedVersion, snapshot.getVersion(), RESOURCE_NAME);
    String currency = validate(account, request, snapshot.getId());

    apply(snapshot, request, currency);
    // The database's clock, not the application's: when the figure was stated decides whether a
    // reconciliation adjustment on its date is already contained in it, compared against that
    // row's created_at (AccountSnapshot#getStatedAt, US-25-03). One clock, so no skew between two
    // machines can reorder them.
    snapshot.setUpdatedAt(databaseClock.now());
    snapshot.setUpdatedBy(actor.userId());
    AccountSnapshot saved = snapshotRepository.saveAndFlush(snapshot);
    reconciliationService.reconcileLatest(account, actor.userId());
    return toResponse(saved);
  }

  /**
   * Removes the opening balance: the account goes back to the value source it had without one - for
   * most types, unknown. A hard delete, like replacing it: it is the member's own starting point,
   * not an institution's record, and nothing references it.
   */
  @Transactional
  public void delete(UUID accountId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    AccountSnapshot snapshot = findOrThrow(accountId);
    versionPreconditionService.requireCurrent(
        expectedVersion, snapshot.getVersion(), RESOURCE_NAME);
    snapshotRepository.delete(snapshot);
    snapshotRepository.flush();
    reconciliationService.reconcileLatest(account, actor.userId());
  }

  // Returns the currency to store: the account's own, which the request must match.
  private String validate(Account account, OpeningBalanceRequest request, UUID ownSnapshotId) {
    if (account.isHasAmortisation() || account.isManualValuation()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ApiErrorCode.OPENING_BALANCE_NOT_APPLICABLE,
          "This account takes no opening balance: its value comes from its own source (the"
              + " loan's principal or the asset's valuations).");
    }
    String currency = accountCurrencyService.ownCurrency(account);
    if (!currency.equals(request.currency())) {
      throw unprocessable(
          "The opening balance must be in the account's own currency, "
              + currency
              + "; convert it first.");
    }

    LocalDate date = request.date();
    if (date.isAfter(businessDateService.today())) {
      throw unprocessable("date cannot be in the future.");
    }
    if (account.getOpenedAt() != null && date.isBefore(account.getOpenedAt())) {
      throw unprocessable("date cannot be before the account was opened.");
    }
    if (account.getClosedAt() != null && date.isAfter(account.getClosedAt())) {
      throw unprocessable("date cannot be after the account was closed.");
    }

    snapshotRepository
        .findByAccountIdAndSnapshotDateAndSource(account.getId(), date, MANUAL)
        .filter(existing -> !existing.getId().equals(ownSnapshotId))
        .ifPresent(
            existing -> {
              throw new ExistingResourceConflictException(
                  "A manual snapshot for this account and date already exists. Choose another"
                      + " date for the opening balance, or update that snapshot instead.",
                  "existingSnapshotId",
                  existing.getId());
            });

    if (!request.acknowledgesEarlierTransactions()) {
      requireNoEarlierTransactions(account.getId(), date);
    }
    return currency;
  }

  private void requireNoEarlierTransactions(UUID accountId, LocalDate date) {
    Optional<LocalDate> earliest = transactionRepository.findEarliestLiveBookingDate(accountId);
    if (earliest.isEmpty() || !earliest.get().isBefore(date)) {
      return;
    }
    long count = transactionRepository.countLiveBookedBefore(accountId, date);
    ApiException conflict =
        new ApiException(
            HttpStatus.CONFLICT,
            ApiErrorCode.OPENING_BALANCE_AFTER_FIRST_TRANSACTION,
            count
                + " transaction(s) are booked before the opening date, the earliest on "
                + earliest.get()
                + ". Date the opening balance before that so they count, or confirm with"
                + " acknowledgeEarlierTransactions to leave them out of the balance.");
    conflict.getBody().setProperty("transactionCount", count);
    conflict.getBody().setProperty("earliestBookingDate", earliest.get().toString());
    throw conflict;
  }

  private static void apply(
      AccountSnapshot snapshot, OpeningBalanceRequest request, String currency) {
    snapshot.setSnapshotDate(request.date());
    // The column scale (V11), so a write answers with the same figure a later read returns. The
    // request's @Digits already caps the fraction at it, so this never rounds.
    snapshot.setBalance(request.balance().setScale(FxRateService.MONEY_SCALE));
    snapshot.setCurrency(currency);
  }

  private AccountSnapshot findOrThrow(UUID accountId) {
    return snapshotRepository
        .findByAccountIdAndOpeningBalanceTrue(accountId)
        .orElseThrow(OpeningBalanceService::notRecorded);
  }

  // Reached only after the caller was cleared for the account: "it has none" is no authorization
  // decision and reveals nothing the caller may not see (ArchitectureTest reviews it). Its own
  // code, so a client can tell "record one" from "no such account" (#241 review).
  private static ApiException notRecorded() {
    return new ApiException(
        HttpStatus.NOT_FOUND,
        ApiErrorCode.OPENING_BALANCE_NOT_RECORDED,
        "This account has no opening balance.");
  }

  private OpeningBalanceResponse toResponse(AccountSnapshot snapshot) {
    return new OpeningBalanceResponse(
        snapshot.getId(),
        snapshot.getAccount().getId(),
        snapshot.getSnapshotDate(),
        snapshot.getBalance(),
        snapshot.getCurrency(),
        snapshot.getCreatedAt(),
        snapshot.getUpdatedAt(),
        VersionPreconditionService.persistedVersion(snapshot.getVersion(), RESOURCE_NAME),
        accountDataQualityService.warningsFor(snapshot.getAccount().getId()));
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }
}
