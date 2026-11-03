package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US-07-35: a savings pocket's leg (US-07-22) mirrors the very booking the import recorded, so it
 * carries that booking's description and counterparty name - unlike a transfer's credit leg, whose
 * other side is named by {@code counterparty_account_id}.
 */
class TransferRecordingServicePocketLegTest {

  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final TransferRecordingService service =
      new TransferRecordingService(
          mock(AccountLookupService.class),
          mock(AccessControlService.class),
          transactionRepository,
          mock(CategorizationService.class));

  @Test
  void thePocketLegCarriesTheMovesDescriptionAndCounterpartyName() {
    when(transactionRepository.saveAndFlush(any(Transaction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Account main = account();
    Account pocket = account();
    Transaction move = new Transaction();
    move.setAccount(main);
    move.setTransactionType("TRANSFER");
    move.setBookingDate(LocalDate.of(2031, 1, 5));
    move.setAmount(new BigDecimal("-17.35"));
    move.setCurrency("CHF");
    move.setMerchantDescription("Spareinlage");
    move.setCounterpartyName("Yuh Sparziel");
    UUID batch = UUID.randomUUID();

    Transaction leg = service.recordPocketLeg(move, pocket, batch, actor());

    assertThat(leg.getAccount()).isSameAs(pocket);
    assertThat(leg.getAmount()).isEqualByComparingTo("17.35");
    assertThat(leg.getMerchantDescription()).isEqualTo("Spareinlage");
    assertThat(leg.getCounterpartyName()).isEqualTo("Yuh Sparziel");
    assertThat(leg.getImportBatchId()).isEqualTo(batch);
  }

  private static Account account() {
    Account account = new Account();
    account.setNativeCurrency("CHF");
    return account;
  }

  private static AuthenticatedUserPrincipal actor() {
    return new AuthenticatedUserPrincipal(
        UUID.randomUUID(), "MEMBER", UUID.randomUUID(), UUID.randomUUID(), "EN");
  }
}
