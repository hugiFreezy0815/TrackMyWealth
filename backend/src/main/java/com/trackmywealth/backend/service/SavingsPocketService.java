package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * AccountService#createSavingsPocket}). Once an account has one, its moves go there while the
 * pocket can take them: active, holding transactions, no card or loan, in the account's currency,
 * and editable by the committing member - the move writes into it. Otherwise the move is imported
 * as a one-sided transfer, pending the member's review; a second pocket is never created. The
 * preview warns about both cases ({@link #previewWarning}).
 */
@Service
public class SavingsPocketService {

  private static final String ACTIVE = "ACTIVE";

  private final AccountRepository accountRepository;
  private final AccountService accountService;
  private final AccessControlService accessControlService;

  public SavingsPocketService(
      AccountRepository accountRepository,
      AccountService accountService,
      AccessControlService accessControlService) {
    this.accountRepository = accountRepository;
    this.accountService = accountService;
    this.accessControlService = accessControlService;
  }

  /**
   * The warning a pocket move into or out of {@code account} carries in the preview: {@code
   * IMPORT_ROW_SAVINGS_POCKET_CREATED} when the commit will create the pocket, {@code
   * IMPORT_ROW_SAVINGS_POCKET_UNAVAILABLE} when the account's pocket cannot take it, else empty.
   */
  @Transactional(readOnly = true)
  public Optional<String> previewWarning(Account account, AuthenticatedUserPrincipal actor) {
    if (!canHavePocket(account)) {
      return Optional.of(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    }
    if (account.getSavingsPocketAccountId() == null) {
      return Optional.of(ImportBatchValues.WARNING_SAVINGS_POCKET_CREATED);
    }
    return usablePocket(account, account.getSavingsPocketAccountId(), actor).isPresent()
        ? Optional.empty()
        : Optional.of(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
  }

  /**
   * The pocket the commit books {@code account}'s pocket moves on: its own, created now if it has
   * none, or empty when its pocket cannot take them. The caller holds {@code account}'s import
   * lock, so two commits never both create one.
   */
  @Transactional
  public Optional<Account> pocketFor(Account account, AuthenticatedUserPrincipal actor) {
    if (!canHavePocket(account)) {
      return Optional.empty();
    }
    Optional<UUID> linked = accountRepository.findSavingsPocketAccountId(account.getId());
    if (linked.isEmpty()) {
      return Optional.of(
          accountService.createSavingsPocket(account, account.getNativeCurrency(), actor));
    }
    return usablePocket(account, linked.get(), actor);
  }

  // A pocket move is an own-account transfer: cards settle and loans are repaid instead.
  private static boolean canHavePocket(Account account) {
    return account.isHasTransactions()
        && !account.isHasStatementCycle()
        && !account.isHasAmortisation();
  }

  private Optional<Account> usablePocket(
      Account account, UUID pocketId, AuthenticatedUserPrincipal actor) {
    return accountRepository
        .findById(pocketId)
        .filter(pocket -> ACTIVE.equals(pocket.getStatus()))
        .filter(SavingsPocketService::canHavePocket)
        .filter(pocket -> pocket.getNativeCurrency().equals(account.getNativeCurrency()))
        .filter(
            pocket ->
                !accessControlService
                    .accountsWithAccess(
                        accessControlService.requireActingMember(actor),
                        List.of(pocket),
                        AccessLevelValues.EDIT)
                    .isEmpty());
  }
}
