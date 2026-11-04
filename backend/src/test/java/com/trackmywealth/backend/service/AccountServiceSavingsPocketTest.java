package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Portfolio;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.AccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * US-07-22 (#325) follow-up: {@link AccountService#createSavingsPocket} never leaves an orphan
 * pocket. The import lock makes a second link impossible today; should a caller ever skip it, the
 * link changes no row and the creation fails, rolling the new account back with the transaction.
 * Every collaborator the creation of a {@code SAVINGS} account does not reach stays a bare mock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceSavingsPocketTest {

  @Mock private AccountRepository accountRepository;
  @InjectMocks private AccountService accountService;

  private final UUID parentId = UUID.randomUUID();
  private final Account parent = mock(Account.class);

  @BeforeEach
  void aParentAtAPortfolio() {
    Portfolio portfolio = mock(Portfolio.class);
    when(portfolio.getId()).thenReturn(UUID.randomUUID());
    when(parent.getId()).thenReturn(parentId);
    when(parent.getName()).thenReturn("Yuh");
    when(parent.getPortfolio()).thenReturn(portfolio);
    when(parent.getWorkspace()).thenReturn(mock(Workspace.class));
    when(accountRepository.saveAndFlush(any(Account.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void thePocketIsLinkedToItsParent() {
    when(accountRepository.linkSavingsPocket(eq(parentId), any())).thenReturn(1);

    Account pocket = accountService.createSavingsPocket(parent, "CHF");

    assertThat(pocket.getName()).isEqualTo("Yuh – Sparkonto");
    assertThat(pocket.getAccountType()).isEqualTo("SAVINGS");
    assertThat(pocket.getNativeCurrency()).isEqualTo("CHF");
  }

  @Test
  void aParentThatGainedAPocketMeanwhileFailsTheCreation() {
    when(accountRepository.linkSavingsPocket(eq(parentId), any())).thenReturn(0);

    assertThatThrownBy(() -> accountService.createSavingsPocket(parent, "CHF"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gained a savings pocket");
  }
}
