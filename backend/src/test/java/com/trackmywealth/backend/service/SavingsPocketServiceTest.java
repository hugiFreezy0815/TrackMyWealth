package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.repository.AccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US-07-22 (#325): when an account's savings pocket is created or used, and what the preview says.
 * The pocket belongs to the workspace like every account (US-03-06), so whichever member commits
 * has it created - there is no owner to ask.
 */
class SavingsPocketServiceTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AccountService accountService = mock(AccountService.class);
  private final SavingsPocketService pockets = new SavingsPocketService(accounts, accountService);

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

  private Account pocket(String status, String currency) {
    Account pocket = mock(Account.class);
    when(pocket.getStatus()).thenReturn(status);
    when(pocket.isHasTransactions()).thenReturn(true);
    when(pocket.getNativeCurrency()).thenReturn(currency);
    when(accounts.findById(pocketId)).thenReturn(Optional.of(pocket));
    return pocket;
  }
}
