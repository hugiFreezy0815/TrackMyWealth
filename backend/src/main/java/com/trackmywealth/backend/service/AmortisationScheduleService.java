package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AmortisationScheduleEntryRequest;
import com.trackmywealth.backend.dto.AmortisationScheduleEntryResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AmortisationScheduleEntry;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ExistingResourceConflictException;
import com.trackmywealth.backend.repository.AmortisationScheduleEntryRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-10-02/FR-NW-006: a loan's or mortgage's amortisation schedule, as the member enters it from
 * the lender's - one entry per due date, stating that instalment's interest and principal and the
 * balance left after it. {@link LoanRepaymentService} splits a payment by the entry of its period
 * ({@link #entryForPayment}).
 *
 * <p>Rules, in the order they are checked:
 *
 * <ul>
 *   <li>Reads and writes need membership of the account's workspace, like any account-scoped data
 *       (US-03-06); an entry of another account is the one generic 404 (US-28-02).
 *   <li>Only an account with {@code hasAmortisation} (a loan or mortgage) has a schedule: 422
 *       otherwise. Decided by capability flag, never by {@code account_type}.
 *   <li>An archived loan's schedule is read-only (409 {@code ACCOUNT_ARCHIVED}), as the loan takes
 *       no repayment either.
 *   <li>No amount is negative, and an instalment of zero splits nothing (422); V82 enforces the
 *       same for every writer.
 *   <li>A second entry for the same due date is a 409 naming the first ({@code existingEntryId}).
 * </ul>
 *
 * <p>Amounts are in the loan's own currency, so none is client input. Replacing or deleting an
 * entry needs {@code If-Match} (ADR 0004). A payment already split by an entry keeps its split -
 * {@code loan_payment} records it - when the entry changes or goes.
 */
@Service
public class AmortisationScheduleService {

  private static final String RESOURCE_NAME = "amortisation schedule entry";
  private static final String ENTITY_TYPE = "AmortisationScheduleEntry";
  private static final String ACTIVE = "ACTIVE";

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AmortisationScheduleEntryRepository entryRepository;
  private final VersionPreconditionService versionPreconditionService;

  public AmortisationScheduleService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AmortisationScheduleEntryRepository entryRepository,
      VersionPreconditionService versionPreconditionService) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.entryRepository = entryRepository;
    this.versionPreconditionService = versionPreconditionService;
  }

  /** The schedule, earliest due date first. */
  @Transactional(readOnly = true)
  public List<AmortisationScheduleEntryResponse> list(
      UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = requireLoan(accountId, false, actor);
    return entryRepository.findByAccountIdOrderByDueDateAsc(account.getId()).stream()
        .map(AmortisationScheduleService::toResponse)
        .toList();
  }

  @Transactional
  public AmortisationScheduleEntryResponse create(
      UUID accountId, AmortisationScheduleEntryRequest request, AuthenticatedUserPrincipal actor) {
    Account account = requireLoan(accountId, true, actor);
    validate(request);
    requireDueDateFree(account, request.dueDate(), null);
    AmortisationScheduleEntry entry = new AmortisationScheduleEntry();
    entry.setAccount(account);
    apply(entry, request);
    // flush, not a plain save: a concurrent entry for the same due date fails V5's unique
    // constraint here, as a 409 from GlobalExceptionHandler, rather than at commit.
    return toResponse(entryRepository.saveAndFlush(entry));
  }

  @Transactional
  public AmortisationScheduleEntryResponse replace(
      UUID accountId,
      UUID entryId,
      AmortisationScheduleEntryRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireLoan(accountId, true, actor);
    AmortisationScheduleEntry entry = findOrThrow(account, entryId, actor);
    versionPreconditionService.requireCurrent(expectedVersion, entry.getVersion(), RESOURCE_NAME);
    validate(request);
    requireDueDateFree(account, request.dueDate(), entry.getId());
    apply(entry, request);
    return toResponse(entryRepository.saveAndFlush(entry));
  }

  @Transactional
  public void delete(
      UUID accountId, UUID entryId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = requireLoan(accountId, true, actor);
    AmortisationScheduleEntry entry = findOrThrow(account, entryId, actor);
    versionPreconditionService.requireCurrent(expectedVersion, entry.getVersion(), RESOURCE_NAME);
    entryRepository.delete(entry);
    entryRepository.flush();
  }

  /**
   * US-10-02: the entry that splits a payment booked on {@code bookingDate} - one due in the same
   * calendar month, the nearest to the booking date when there are several (the earlier one on a
   * tie). Empty when none is due that month. Checks no access: only {@link LoanRepaymentService},
   * which has checked it, calls this - hence package-private, and inside the caller's transaction.
   */
  Optional<AmortisationScheduleEntry> entryForPayment(UUID accountId, LocalDate bookingDate) {
    YearMonth month = YearMonth.from(bookingDate);
    return entryRepository
        .findByAccountIdAndDueDateBetweenOrderByDueDateAsc(
            accountId, month.atDay(1), month.atEndOfMonth())
        .stream()
        .min(
            Comparator.comparingLong(
                (AmortisationScheduleEntry entry) ->
                    Math.abs(ChronoUnit.DAYS.between(bookingDate, entry.getDueDate()))));
  }

  /**
   * US-10-02: the latest entry due before the month of {@code bookingDate} - the lender's last
   * stated balance when no entry of that month splits the payment ({@link #entryForPayment}).
   * Checks no access, as {@link #entryForPayment}.
   */
  Optional<AmortisationScheduleEntry> latestEntryBefore(UUID accountId, LocalDate bookingDate) {
    return entryRepository.findFirstByAccountIdAndDueDateBeforeOrderByDueDateDesc(
        accountId, YearMonth.from(bookingDate).atDay(1));
  }

  // forChange: the schedule is about to be written, which an archived loan refuses; reading it
  // stays possible.
  private Account requireLoan(UUID accountId, boolean forChange, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    if (!account.isHasAmortisation()) {
      throw unprocessable("Only a loan or mortgage has an amortisation schedule.");
    }
    if (forChange && !ACTIVE.equals(account.getStatus())) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          ApiErrorCode.ACCOUNT_ARCHIVED,
          "Cannot change the amortisation schedule of an archived loan.");
    }
    return account;
  }

  private AmortisationScheduleEntry findOrThrow(
      Account account, UUID entryId, AuthenticatedUserPrincipal actor) {
    return entryRepository
        .findByIdAndAccountId(entryId, account.getId())
        .orElseThrow(() -> accessControlService.denyAsNotFound(actor, ENTITY_TYPE, entryId));
  }

  private static void validate(AmortisationScheduleEntryRequest request) {
    if (request.interestComponent().signum() < 0
        || request.principalComponent().signum() < 0
        || request.remainingBalance().signum() < 0) {
      throw unprocessable(
          "interestComponent, principalComponent and remainingBalance cannot be negative: they are"
              + " what the lender's schedule states, not signed ledger amounts.");
    }
    if (request.interestComponent().add(request.principalComponent()).compareTo(BigDecimal.ZERO)
        == 0) {
      throw unprocessable("An instalment of zero splits nothing: give its interest or principal.");
    }
  }

  private void requireDueDateFree(Account account, LocalDate dueDate, UUID ownId) {
    entryRepository
        .findByAccountIdAndDueDateBetweenOrderByDueDateAsc(account.getId(), dueDate, dueDate)
        .stream()
        .filter(existing -> !existing.getId().equals(ownId))
        .findFirst()
        .ifPresent(
            existing -> {
              throw new ExistingResourceConflictException(
                  "This schedule already has an entry due on " + dueDate + ". Replace it instead.",
                  "existingEntryId",
                  existing.getId());
            });
  }

  private static void apply(
      AmortisationScheduleEntry entry, AmortisationScheduleEntryRequest request) {
    entry.setDueDate(request.dueDate());
    entry.setInterestComponent(request.interestComponent());
    entry.setPrincipalComponent(request.principalComponent());
    entry.setRemainingBalance(request.remainingBalance());
  }

  private static AmortisationScheduleEntryResponse toResponse(AmortisationScheduleEntry entry) {
    return new AmortisationScheduleEntryResponse(
        entry.getId(),
        entry.getAccount().getId(),
        entry.getDueDate(),
        entry.getInterestComponent(),
        entry.getPrincipalComponent(),
        entry.getRemainingBalance(),
        entry.getAccount().getNativeCurrency(),
        VersionPreconditionService.persistedVersion(entry.getVersion(), RESOURCE_NAME),
        entry.getCreatedAt());
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }
}
