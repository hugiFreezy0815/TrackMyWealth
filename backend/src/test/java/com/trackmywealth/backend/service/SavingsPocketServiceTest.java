package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-22 (#325): when an account's savings pocket is created or used, and what the preview says.
 * The pocket belongs to the workspace like every account (US-03-06), so whichever member commits
 * has it created - there is no owner to ask.
 */
class SavingsPocketServiceTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AccountService accountService = mock(AccountService.class);
  private final AccountLookupService lookups = mock(AccountLookupService.class);
  private final ImportLockService locks = mock(ImportLockService.class);
  private final AccessControlService access = mock(AccessControlService.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final SavingsPocketService pockets =
      new SavingsPocketService(
          accounts,
          accountService,
          lookups,
          access,
          new VersionPreconditionService(),
          locks,
          entityManager);
  private final AuthenticatedUserPrincipal actor = mock(AuthenticatedUserPrincipal.class);

  private final UUID accountId = UUID.randomUUID();
  private final UUID pocketId = UUID.randomUUID();
  private final Account account = mock(Account.class);

  @BeforeEach
  void anOrdinaryAccountWithoutAPocket() {
    when(account.getId()).thenReturn(accountId);
    when(account.getNativeCurrency()).thenReturn("CHF");
    when(account.isHasTransactions()).thenReturn(true);
    when(accounts.findSavingsPocketAccountId(accountId)).thenReturn(Optional.empty());
  }

  @Test
  void anyMemberHasThePocketCreated() {
    Account created = mock(Account.class);
    when(accountService.createSavingsPocket(account, "CHF")).thenReturn(created);

    assertThat(pockets.previewWarning(account))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_CREATED);
    assertThat(pockets.pocketFor(account)).contains(created);
  }

  @Test
  void anExistingPocketIsUsedWithoutAWarning() {
    Account pocket = pocket("ACTIVE", "CHF");
    when(account.getSavingsPocketAccountId()).thenReturn(pocketId);
    when(accounts.findSavingsPocketAccountId(accountId)).thenReturn(Optional.of(pocketId));

    assertThat(pockets.previewWarning(account)).isEmpty();
    assertThat(pockets.pocketFor(account)).contains(pocket);
    verify(accountService, never()).createSavingsPocket(any(), any());
  }

  @Test
  void anArchivedPocketTakesNoMoveAndNoSecondPocketIsCreated() {
    pocket("ARCHIVED", "CHF");
    when(account.getSavingsPocketAccountId()).thenReturn(pocketId);
    when(accounts.findSavingsPocketAccountId(accountId)).thenReturn(Optional.of(pocketId));

    assertThat(pockets.previewWarning(account))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    assertThat(pockets.pocketFor(account)).isEmpty();
    verify(accountService, never()).createSavingsPocket(any(), any());
  }

  @Test
  void aPocketInAnotherCurrencyTakesNoMove() {
    pocket("ACTIVE", "EUR");
    when(accounts.findSavingsPocketAccountId(accountId)).thenReturn(Optional.of(pocketId));

    assertThat(pockets.pocketFor(account)).isEmpty();
  }

  @Test
  void aCardHasNoPocket() {
    when(account.isHasStatementCycle()).thenReturn(true);

    assertThat(pockets.previewWarning(account))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    assertThat(pockets.pocketFor(account)).isEmpty();
    verify(accountService, never()).createSavingsPocket(any(), any());
  }

  // #325 follow-up: the member links another account as the pocket, e.g. after archiving the one
  // the import created. Both accounts are cleared before anything is locked, then both import
  // locks are taken and both rows read again under their row locks, before any rule is checked.
  @Test
  void anActiveAccountInTheSameCurrencyBecomesThePocket() {
    Account pocket = linkable(pocket("ACTIVE", "CHF"));
    AccountSummaryResponse linked = mock(AccountSummaryResponse.class);
    when(accountService.getAccount(accountId, actor)).thenReturn(linked);

    assertThat(pockets.linkPocket(accountId, pocketId, 3, actor)).isSameAs(linked);

    InOrder order = inOrder(access, locks, entityManager, accounts);
    order.verify(access).requireAccountAccess(actor, account);
    order.verify(access).requireAccountAccess(actor, pocket);
    order.verify(locks).lockAccounts(List.of(accountId, pocketId));
    order.verify(entityManager).refresh(account, LockModeType.PESSIMISTIC_WRITE);
    order.verify(accounts).relinkSavingsPocket(accountId, pocketId);
    verify(entityManager).refresh(pocket, LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  void linkingThePocketItAlreadyHasWritesNothing() {
    linkable(pocket("ACTIVE", "CHF"));
    when(account.getSavingsPocketAccountId()).thenReturn(pocketId);

    pockets.linkPocket(accountId, pocketId, 3, actor);

    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  // Security: an account the member cannot see is never locked, nor is it linked.
  @Test
  void anAccountOrPocketTheMemberCannotSeeIsNeverLocked() {
    Account pocket = linkable(pocket("ACTIVE", "CHF"));
    ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND);
    doThrow(notFound).when(access).requireAccountAccess(actor, pocket);

    assertThatThrownBy(() -> pockets.linkPocket(accountId, pocketId, 3, actor)).isSameAs(notFound);

    doThrow(notFound).when(access).requireAccountAccess(actor, account);
    assertThatThrownBy(() -> pockets.linkPocket(accountId, pocketId, 3, actor)).isSameAs(notFound);
    verify(locks, never()).lockAccounts(any());
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void aPocketThatCannotTakeTheMovesIsRefused() {
    linkable(pocket("ARCHIVED", "CHF"));
    assertCode(ProblemCode.SAVINGS_POCKET_UNSUITABLE);

    linkable(pocket("ACTIVE", "EUR"));
    assertCode(ProblemCode.SAVINGS_POCKET_UNSUITABLE);

    Account nested = linkable(pocket("ACTIVE", "CHF"));
    when(nested.getSavingsPocketAccountId()).thenReturn(UUID.randomUUID());
    assertCode(ProblemCode.SAVINGS_POCKET_UNSUITABLE);

    Account card = linkable(pocket("ACTIVE", "CHF"));
    when(card.isHasStatementCycle()).thenReturn(true);
    assertCode(ProblemCode.SAVINGS_POCKET_UNSUITABLE);

    Account loan = linkable(pocket("ACTIVE", "CHF"));
    when(loan.isHasAmortisation()).thenReturn(true);
    assertCode(ProblemCode.SAVINGS_POCKET_UNSUITABLE);

    assertThatThrownBy(() -> pockets.linkPocket(accountId, accountId, 3, actor))
        .isInstanceOfSatisfying(
            ApiException.class,
            refused ->
                assertThat(refused.getCode()).isEqualTo(ProblemCode.SAVINGS_POCKET_UNSUITABLE));
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void aCardOrALoanCanHaveNoPocketLinked() {
    linkable(pocket("ACTIVE", "CHF"));
    when(account.isHasStatementCycle()).thenReturn(true);
    assertCode(ProblemCode.SAVINGS_POCKET_NOT_APPLICABLE);

    when(account.isHasStatementCycle()).thenReturn(false);
    when(account.isHasAmortisation()).thenReturn(true);
    assertCode(ProblemCode.SAVINGS_POCKET_NOT_APPLICABLE);
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  // Pockets never nest: an account that is another account's pocket gains none of its own, which
  // also keeps two accounts from becoming each other's pocket.
  @Test
  void anAccountThatIsItselfAPocketCanHaveNoPocketLinked() {
    linkable(pocket("ACTIVE", "CHF"));
    when(accounts.existsBySavingsPocketAccountId(accountId)).thenReturn(true);

    assertCode(ProblemCode.SAVINGS_POCKET_NOT_APPLICABLE);
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void anotherAccountsPocketIsAConflict() {
    linkable(pocket("ACTIVE", "CHF"));
    when(accounts.existsBySavingsPocketAccountIdAndIdNot(pocketId, accountId)).thenReturn(true);

    ApiException refused = assertCode(ProblemCode.SAVINGS_POCKET_IN_USE);
    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void aStaleVersionChangesNothing() {
    linkable(pocket("ACTIVE", "CHF"));

    assertThatThrownBy(() -> pockets.linkPocket(accountId, pocketId, 2, actor))
        .hasMessageContaining("changed after you read it");
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  // The account at version 3 and the pocket, both found by their ids.
  private Account linkable(Account pocket) {
    when(account.getVersion()).thenReturn(3);
    when(lookups.findAccountOrThrow(accountId, actor)).thenReturn(account);
    when(pocket.getId()).thenReturn(pocketId);
    when(lookups.findAccountOrThrow(pocketId, actor)).thenReturn(pocket);
    return pocket;
  }

  private ApiException assertCode(ProblemCode code) {
    ApiException refused =
        catchThrowableOfType(
            ApiException.class, () -> pockets.linkPocket(accountId, pocketId, 3, actor));
    assertThat(refused).as("refused with %s", code).isNotNull();
    assertThat(refused.getCode()).isEqualTo(code);
    return refused;
  }

  private Account pocket(String status, String currency) {
    Account pocket = mock(Account.class);
    when(pocket.getStatus()).thenReturn(status);
    when(pocket.isHasTransactions()).thenReturn(true);
    when(pocket.getNativeCurrency()).thenReturn(currency);
    when(accounts.findById(pocketId)).thenReturn(Optional.of(pocket));
    return pocket;
  }
}
