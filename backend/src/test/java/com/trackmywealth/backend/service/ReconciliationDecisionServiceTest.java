package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ReconciliationDecisionServiceTest {

  private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 9, 30);
  private static final AuthenticatedUserPrincipal ACTOR =
      new AuthenticatedUserPrincipal(
          UUID.randomUUID(), "STANDARD_USER", UUID.randomUUID(), UUID.randomUUID(), "EN");

  private final AccountLookupService accountLookupService = mock(AccountLookupService.class);
  private final AccessControlService accessControlService = mock(AccessControlService.class);
  private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
  private final ReconciliationResultRepository resultRepository =
      mock(ReconciliationResultRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final ReconciliationDecisionService service =
      new ReconciliationDecisionService(
          accountLookupService,
          accessControlService,
          reconciliationService,
          resultRepository,
          transactionRepository,
          mock(SettlementDetectionService.class),
          mock(TransferDetectionService.class),
          new VersionPreconditionService());

  private Account account;
  private ReconciliationResult result;

  @BeforeEach
  void setUp() {
    account = new Account();
    ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(account, "nature", "ASSET");
    account.setWorkspace(new Workspace());
    AccountSnapshot snapshot = new AccountSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
    snapshot.setAccount(account);
    snapshot.setSnapshotDate(SNAPSHOT_DATE);
    snapshot.setCurrency("CHF");
    result = new ReconciliationResult();
    ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(result, "version", 3);
    result.setAccount(account);
    result.setSnapshot(snapshot);
    result.setStatus("OPEN");
    result.setDifferenceAmount(new BigDecimal("15.0000"));

    when(accountLookupService.findAccountOrThrow(account.getId(), ACTOR)).thenReturn(account);
    when(reconciliationService.findResultOrThrow(account, result.getId(), ACTOR))
        .thenReturn(result);
    when(reconciliationService.comparesLatestSnapshot(result)).thenReturn(true);
    when(reconciliationService.now()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC));
    when(transactionRepository.saveAndFlush(any(Transaction.class)))
        .thenAnswer(
            invocation -> {
              Transaction saved = invocation.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
              return saved;
            });
  }

  @Test
  void acceptingOnALiabilityBooksTheAdjustmentInLedgerDirection() {
    // A card statement owes 15.00 more than the ledger explains: the ledger misses a -15.00 row.
    ReflectionTestUtils.setField(account, "nature", "LIABILITY");

    service.accept(account.getId(), result.getId(), "Annual card fee", 3, ACTOR);

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).saveAndFlush(captor.capture());
    Transaction adjustment = captor.getValue();
    assertThat(adjustment.getAmount()).isEqualByComparingTo("-15.00");
    assertThat(adjustment.getTransactionType()).isEqualTo("VALUATION_ADJUSTMENT");
    assertThat(adjustment.getCurrency()).isEqualTo("CHF");
    assertThat(adjustment.getCreatedBy()).isEqualTo(ACTOR.userId());
    assertThat(result.getStatus()).isEqualTo("ACCEPTED");
    assertThat(result.getResolutionTransactionId()).isEqualTo(adjustment.getId());
    assertThat(result.getResolvedBy()).isEqualTo(ACTOR.userId());
  }

  @Test
  void aDifferenceThatChangesOnRefreshIsStaleAndBooksNothing() {
    doAnswer(
            invocation -> {
              result.setDifferenceAmount(new BigDecimal("20.0000"));
              return null;
            })
        .when(reconciliationService)
        .reconcileLatest(account, ACTOR.userId());

    assertThatThrownBy(() -> service.accept(account.getId(), result.getId(), "note", 3, ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            conflict -> {
              assertThat(conflict.getCode()).isEqualTo(ApiErrorCode.RECONCILIATION_STALE);
              assertThat(conflict.getBody().getProperties())
                  .containsEntry("differenceAmount", "20.0000");
            });
    verify(transactionRepository, never()).saveAndFlush(any());
  }

  @Test
  void theVersionIsCheckedOnlyAfterTheAccountAccess() {
    assertThatThrownBy(() -> service.dismiss(account.getId(), result.getId(), "note", 2, ACTOR))
        .isInstanceOfSatisfying(
            ApiException.class,
            conflict -> assertThat(conflict.getCode()).isEqualTo(ApiErrorCode.VERSION_CONFLICT));
    verify(accessControlService).requireAccountAccess(ACTOR, account, AccessLevelValues.EDIT);
    assertThat(result.getStatus()).isEqualTo("OPEN");
  }
}
