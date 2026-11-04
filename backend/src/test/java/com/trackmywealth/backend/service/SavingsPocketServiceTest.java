package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  private final SavingsPocketService pockets =
      new SavingsPocketService(
          accounts,
          accountService,
          lookups,
          mock(AccessControlService.class),
          new VersionPreconditionService(),
          locks);
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
  // the import created; the account is read under its import lock.
  @Test
  void anActiveAccountInTheSameCurrencyBecomesThePocket() {
    linkable(pocket("ACTIVE", "CHF"));
    AccountSummaryResponse linked = mock(AccountSummaryResponse.class);
    when(accountService.getAccount(accountId, actor)).thenReturn(linked);

    assertThat(pockets.linkPocket(accountId, pocketId, 3, actor)).isSameAs(linked);
    verify(locks).lockAccount(accountId);
    verify(accounts).relinkSavingsPocket(accountId, pocketId);
  }

  @Test
  void linkingThePocketItAlreadyHasWritesNothing() {
    linkable(pocket("ACTIVE", "CHF"));
    when(account.getSavingsPocketAccountId()).thenReturn(pocketId);

    pockets.linkPocket(accountId, pocketId, 3, actor);

    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void aPocketThatCannotTakeTheMovesIsRefused() {
    linkable(pocket("ARCHIVED", "CHF"));
    assertStatus(
        () -> pockets.linkPocket(accountId, pocketId, 3, actor), HttpStatus.UNPROCESSABLE_CONTENT);

    linkable(pocket("ACTIVE", "EUR"));
    assertStatus(
        () -> pockets.linkPocket(accountId, pocketId, 3, actor), HttpStatus.UNPROCESSABLE_CONTENT);

    Account nested = linkable(pocket("ACTIVE", "CHF"));
    when(nested.getSavingsPocketAccountId()).thenReturn(UUID.randomUUID());
    assertStatus(
        () -> pockets.linkPocket(accountId, pocketId, 3, actor), HttpStatus.UNPROCESSABLE_CONTENT);

    assertStatus(
        () -> pockets.linkPocket(accountId, accountId, 3, actor), HttpStatus.UNPROCESSABLE_CONTENT);
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void aCardCanHaveNoPocketLinked() {
    linkable(pocket("ACTIVE", "CHF"));
    when(account.isHasStatementCycle()).thenReturn(true);

    assertStatus(
        () -> pockets.linkPocket(accountId, pocketId, 3, actor), HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void anotherAccountsPocketIsAConflict() {
    linkable(pocket("ACTIVE", "CHF"));
    when(accounts.existsBySavingsPocketAccountIdAndIdNot(pocketId, accountId)).thenReturn(true);

    assertStatus(() -> pockets.linkPocket(accountId, pocketId, 3, actor), HttpStatus.CONFLICT);
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  @Test
  void aStaleVersionChangesNothing() {
    linkable(pocket("ACTIVE", "CHF"));

    assertThatThrownBy(() -> pockets.linkPocket(accountId, pocketId, 2, actor))
        .hasMessageContaining("changed after you read it");
    verify(accounts, never()).relinkSavingsPocket(any(), any());
  }

  // The account at version 3, found under its lock, and the pocket found by its id.
  private Account linkable(Account pocket) {
    when(account.getVersion()).thenReturn(3);
    when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(pocket.getId()).thenReturn(pocketId);
    when(lookups.findAccountOrThrow(pocketId, actor)).thenReturn(pocket);
    return pocket;
  }

  private static void assertStatus(Runnable call, HttpStatus status) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            refused -> assertThat(refused.getStatusCode()).isEqualTo(status));
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
