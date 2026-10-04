package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ReconciliationAdjustmentValues;
import com.trackmywealth.backend.dto.TransactionRemovalValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US-25-03: a row is a reconciliation adjustment through its owner link (V63), never through its
 * type alone - {@code VALUATION_ADJUSTMENT} stays an ordinary ledger type for any other writer.
 */
class ReconciliationAdjustmentServiceTest {

  private final ReconciliationService reconciliationService = mock(ReconciliationService.class);
  private final ReconciliationResultRepository resultRepository =
      mock(ReconciliationResultRepository.class);
  private final ReconciliationAdjustmentService service =
      new ReconciliationAdjustmentService(reconciliationService, resultRepository);

  private Account account;
  private AccountSnapshot newest;

  @BeforeEach
  void setUp() {
    account = new Account();
    ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
    newest = snapshot();
    when(reconciliationService.latestSnapshotId(account.getId()))
        .thenReturn(Optional.of(newest.getId()));
  }

  @Test
  void anAdjustmentsStateFollowsItsResultAndTheNewestSnapshot() {
    ReconciliationResult onNewest = result("ACCEPTED", newest);
    ReconciliationResult onOlder = result("ACCEPTED", snapshot());
    ReconciliationResult reopened = result("OPEN", newest);
    Transaction ordinary = transaction("INCOME", null);
    Transaction current = adjustment(onNewest);
    Transaction older = adjustment(onOlder);
    // Its result was decided again: it points at a newer entry, not this one.
    Transaction superseded = transaction("VALUATION_ADJUSTMENT", onNewest.getId());
    Transaction withdrawn = transaction("VALUATION_ADJUSTMENT", reopened.getId());
    withdrawn.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
    when(resultRepository.findAllById(any())).thenReturn(List.of(onNewest, onOlder, reopened));

    assertThat(service.adjustmentStates(List.of(ordinary, current, older, superseded, withdrawn)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                current.getId(), ReconciliationAdjustmentValues.REOPENABLE,
                older.getId(), ReconciliationAdjustmentValues.FINALIZED,
                // Nothing can take it back any more, yet it counts: it is history.
                superseded.getId(), ReconciliationAdjustmentValues.FINALIZED,
                withdrawn.getId(), ReconciliationAdjustmentValues.WITHDRAWN));
    assertThat(service.adjustmentStates(List.of(ordinary))).isEmpty();
  }

  @Test
  void aValuationAdjustmentNoResultBookedIsAnOrdinaryRow() {
    Transaction unowned = transaction("VALUATION_ADJUSTMENT", null);
    unowned.setSource("MANUAL");

    assertThat(service.adjustmentStates(List.of(unowned))).isEmpty();
    service.requireNotAdjustment(unowned);
    assertThat(TransactionService.removalOf(unowned))
        .isEqualTo(TransactionRemovalValues.SOFT_DELETE);
  }

  @Test
  void anOwnedAdjustmentIsLockedAndSaysWhatStillWorks() {
    ReconciliationResult owner = result("ACCEPTED", newest);
    Transaction adjustment = adjustment(owner);
    adjustment.setSource("MANUAL");
    when(resultRepository.findAllById(any())).thenReturn(List.of(owner));

    assertThat(TransactionService.removalOf(adjustment)).isNull();
    assertThatThrownBy(() -> service.requireNotAdjustment(adjustment))
        .isInstanceOfSatisfying(
            ApiException.class,
            locked -> {
              assertThat(locked.getCode()).isEqualTo(ApiErrorCode.RECONCILIATION_ADJUSTMENT_LOCKED);
              assertThat(locked.getBody().getProperties())
                  .containsEntry(
                      "reconciliationAdjustment", ReconciliationAdjustmentValues.REOPENABLE);
              assertThat(locked.getBody().getDetail()).contains("Reopen");
            });
  }

  private Transaction adjustment(ReconciliationResult owner) {
    Transaction value = transaction("VALUATION_ADJUSTMENT", owner.getId());
    owner.setResolutionTransactionId(value.getId());
    return value;
  }

  private Transaction transaction(String type, UUID owner) {
    Transaction value = new Transaction();
    ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
    value.setAccount(account);
    value.setTransactionType(type);
    value.setReconciliationResultId(owner);
    return value;
  }

  private AccountSnapshot snapshot() {
    AccountSnapshot value = new AccountSnapshot();
    ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
    value.setAccount(account);
    return value;
  }

  private ReconciliationResult result(String status, AccountSnapshot snapshot) {
    ReconciliationResult value = new ReconciliationResult();
    ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
    value.setStatus(status);
    value.setSnapshot(snapshot);
    value.setAccount(account);
    return value;
  }
}
