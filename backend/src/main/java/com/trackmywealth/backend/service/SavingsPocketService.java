package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-22 (#325): an account's savings pocket - Yuh's "Sparkonto", money the bank sets aside
 * within the account - kept as a savings account of its own, so that a move into it is saving, not
 * spending. A row of a template's {@code savingsPocketPattern} is such a move: a {@code TRANSFER}
 * in the account's own currency, whose commit books the other leg on the pocket ({@link
 * ImportTransferLinkService}).
 *
 * <p>The first commit with a pocket move creates the pocket ({@link
 * AccountService#createSavingsPocket}), whichever member commits: like every account, the pocket
 * belongs to the workspace, so every member may use it (US-03-06, FR-HOU-007). The caller has
 * already cleared the committing member for the account itself, and V101 keeps a pocket in its
 * account's workspace, so no further access check is needed here. Once an account has one, its
 * moves go there while the pocket can take them: active, holding transactions, no card or loan, in
 * the account's currency. Otherwise the move is imported as a one-sided transfer, pending the
 * member's review; a second pocket is never created. The preview warns about both cases ({@link
 * #previewWarning}).
 *
 * <p>The member can link another account as the pocket ({@link #linkPocket}), e.g. when the one the
 * import created was archived: the PO's "until the member links a pocket" (#325, decision 1). Only
 * a pocket that can take the moves is accepted, and pockets never nest: a pocket has no pocket of
 * its own. A link is never removed, so an import never creates a second pocket on its own.
 */
@Service
public class SavingsPocketService {

  private static final String ACTIVE = "ACTIVE";
  private static final String VERSIONED_RESOURCE = "account";

  private final AccountRepository accountRepository;
  private final AccountService accountService;
  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final VersionPreconditionService versionPreconditionService;
  private final ImportLockService importLockService;
  private final EntityManager entityManager;

  public SavingsPocketService(
      AccountRepository accountRepository,
      AccountService accountService,
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      VersionPreconditionService versionPreconditionService,
      ImportLockService importLockService,
      EntityManager entityManager) {
    this.accountRepository = accountRepository;
    this.accountService = accountService;
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.versionPreconditionService = versionPreconditionService;
    this.importLockService = importLockService;
    this.entityManager = entityManager;
  }

  /**
   * The warning a pocket move into or out of {@code account} carries in the preview: {@code
   * IMPORT_ROW_SAVINGS_POCKET_CREATED} when the commit will create the pocket, {@code
   * IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE} when the account's pocket cannot take it, else empty.
   */
  @Transactional(readOnly = true)
  public Optional<String> previewWarning(Account account) {
    if (!canHavePocket(account)) {
      return Optional.of(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    }
    if (account.getSavingsPocketAccountId() == null) {
      return Optional.of(ImportBatchValues.WARNING_SAVINGS_POCKET_CREATED);
    }
    return usablePocket(account, account.getSavingsPocketAccountId()).isPresent()
        ? Optional.empty()
        : Optional.of(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
  }

  /**
   * The pocket the commit books {@code account}'s pocket moves on: its own, created now if it has
   * none, or empty when its pocket cannot take them. The caller holds {@code account}'s import
   * lock, so two commits never both create one.
   */
  @Transactional
  public Optional<Account> pocketFor(Account account) {
    if (!canHavePocket(account)) {
      return Optional.empty();
    }
    Optional<UUID> linked = accountRepository.findSavingsPocketAccountId(account.getId());
    if (linked.isEmpty()) {
      return Optional.of(accountService.createSavingsPocket(account, account.getNativeCurrency()));
    }
    return usablePocket(account, linked.get());
  }

  /**
   * #325 follow-up: links {@code pocketAccountId} as the savings pocket of {@code accountId}, in
   * place of the one it has, if any. The next commits book the account's pocket moves there; moves
   * imported before stay as they are.
   *
   * <p>Both accounts are cleared for the member before anything is locked, so no caller can hold
   * the lock of an account it cannot see. Then it takes both import locks, in id order as every
   * writer does: a commit books onto either the old pocket or the new one, never half and half, and
   * two links that share an account run one after the other, so neither a second account can take
   * the same pocket nor two accounts become each other's pocket. Both are read again under their
   * row locks before any rule is checked.
   *
   * @throws ApiException 422 {@code SAVINGS_POCKET_NOT_APPLICABLE} when the account can have no
   *     pocket (a card, a loan, no transactions, or itself another account's pocket); 422 {@code
   *     SAVINGS_POCKET_UNSUITABLE} when the pocket cannot take its moves (the account itself, not
   *     active, no transactions, a card or loan, another currency, or it has a pocket of its own);
   *     409 {@code SAVINGS_POCKET_IN_USE} when the pocket is already another account's
   */
  @Transactional
  public AccountSummaryResponse linkPocket(
      UUID accountId,
      UUID pocketAccountId,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account);
    if (accountId.equals(pocketAccountId)) {
      throw unsuitable("An account cannot be its own savings pocket.");
    }
    Account pocket = accountLookupService.findAccountOrThrow(pocketAccountId, actor);
    accessControlService.requireAccountAccess(actor, pocket);

    importLockService.lockAccounts(List.of(accountId, pocketAccountId));
    // Read before the locks, so possibly stale: read both again, the lower id first.
    Stream.of(account, pocket)
        .sorted(Comparator.comparing(Account::getId))
        .forEach(locked -> entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE));

    versionPreconditionService.requireCurrent(
        expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    if (!canHavePocket(account) || accountRepository.existsBySavingsPocketAccountId(accountId)) {
      throw new ApiException(
          ProblemCode.SAVINGS_POCKET_NOT_APPLICABLE,
          "A card, a loan, an account without transactions or the savings pocket of another"
              + " account has no savings pocket.");
    }
    if (usablePocket(account, pocket).isEmpty() || pocket.getSavingsPocketAccountId() != null) {
      throw unsuitable(
          "The savings pocket must be an active account in the account's currency that holds"
              + " transactions, is no card or loan and has no savings pocket of its own.");
    }
    if (accountRepository.existsBySavingsPocketAccountIdAndIdNot(pocketAccountId, accountId)) {
      throw new ApiException(
          ProblemCode.SAVINGS_POCKET_IN_USE,
          "This account is already the savings pocket of another account.");
    }
    if (!pocketAccountId.equals(account.getSavingsPocketAccountId())) {
      accountRepository.relinkSavingsPocket(accountId, pocketAccountId);
    }
    return accountService.getAccount(accountId, actor);
  }

  // A pocket move is an own-account transfer: cards settle and loans are repaid instead.
  private static boolean canHavePocket(Account account) {
    return account.isHasTransactions()
        && !account.isHasStatementCycle()
        && !account.isHasAmortisation();
  }

  private Optional<Account> usablePocket(Account account, UUID pocketId) {
    return accountRepository.findById(pocketId).flatMap(pocket -> usablePocket(account, pocket));
  }

  private static Optional<Account> usablePocket(Account account, Account pocket) {
    return Optional.of(pocket)
        .filter(candidate -> ACTIVE.equals(candidate.getStatus()))
        .filter(SavingsPocketService::canHavePocket)
        .filter(candidate -> candidate.getNativeCurrency().equals(account.getNativeCurrency()));
  }

  private static ApiException unsuitable(String detail) {
    return new ApiException(ProblemCode.SAVINGS_POCKET_UNSUITABLE, detail);
  }
}
