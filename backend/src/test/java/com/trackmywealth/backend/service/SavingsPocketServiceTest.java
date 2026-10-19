package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountOwnership;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AccountOwnershipRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US-07-22 (#325): who may have an account's savings pocket created, and what the preview says. The
 * pocket gets the account's owners, so a member who edits the account only through a sharing grant
 * must not create it: they could neither use it nor roll their import back.
 */
class SavingsPocketServiceTest {

  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AccountService accountService = mock(AccountService.class);
  private final AccessControlService access = mock(AccessControlService.class);
  private final AccountOwnershipRepository ownerships = mock(AccountOwnershipRepository.class);
  private final SavingsPocketService pockets =
      new SavingsPocketService(accounts, accountService, access, ownerships);

  private final UUID accountId = UUID.randomUUID();
  private final UUID member = UUID.randomUUID();
  private final Account account = mock(Account.class);
  private final AuthenticatedUserPrincipal actor = mock(AuthenticatedUserPrincipal.class);

  @BeforeEach
  void anOrdinaryAccountWithoutAPocket() {
    when(account.getId()).thenReturn(accountId);
    when(account.getNativeCurrency()).thenReturn("CHF");
    when(account.isHasTransactions()).thenReturn(true);
    when(access.requireActingMember(actor)).thenReturn(member);
    when(accounts.findSavingsPocketAccountId(accountId)).thenReturn(Optional.empty());
  }

  @Test
  void anOwnerHasThePocketCreated() {
    ownedBy(member, UUID.randomUUID());
    Account created = mock(Account.class);
    when(accountService.createSavingsPocket(account, "CHF", actor)).thenReturn(created);

    assertThat(pockets.previewWarning(account, actor))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_CREATED);
    assertThat(pockets.pocketFor(account, actor)).contains(created);
  }

  @Test
  void anyMemberHasThePocketOfAnUnownedAccountCreated() {
    ownedBy();
    Account created = mock(Account.class);
    when(accountService.createSavingsPocket(account, "CHF", actor)).thenReturn(created);

    assertThat(pockets.pocketFor(account, actor)).contains(created);
  }

  @Test
  void aMemberWhoIsNoOwnerCreatesNoPocketAndIsWarned() {
    ownedBy(UUID.randomUUID());

    assertThat(pockets.previewWarning(account, actor))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    assertThat(pockets.pocketFor(account, actor)).isEmpty();
    verify(accountService, never()).createSavingsPocket(any(), any(), any());
  }

  @Test
  void aCardHasNoPocket() {
    when(account.isHasStatementCycle()).thenReturn(true);
    ownedBy(member);

    assertThat(pockets.previewWarning(account, actor))
        .contains(ImportBatchValues.WARNING_SAVINGS_POCKET_UNAVAILABLE);
    assertThat(pockets.pocketFor(account, actor)).isEmpty();
    verify(accountService, never()).createSavingsPocket(any(), any(), any());
  }

  private void ownedBy(UUID... members) {
    List<AccountOwnership> owners =
        Arrays.stream(members)
            .map(
                id -> {
                  WorkspaceMember owner = mock(WorkspaceMember.class);
                  when(owner.getId()).thenReturn(id);
                  AccountOwnership ownership = mock(AccountOwnership.class);
                  when(ownership.getWorkspaceMember()).thenReturn(owner);
                  return ownership;
                })
            .toList();
    when(ownerships.findByAccountIdAndEffectiveToIsNull(accountId)).thenReturn(owners);
  }
}
