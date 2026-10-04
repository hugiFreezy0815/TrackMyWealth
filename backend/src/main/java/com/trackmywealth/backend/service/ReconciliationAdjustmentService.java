package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ReconciliationAdjustmentValues;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-25-03: the ledger's view of a reconciliation adjustment - the row an accepted difference
 * books, owned by its result through {@link Transaction#getReconciliationResultId()} (V63). The
 * type alone does not make a row one: {@code VALUATION_ADJUSTMENT} is a general ledger type, and a
 * row of it that no result booked is an ordinary row.
 *
 * <p>The transaction services ask here whether a row may be corrected, removed, restored or
 * categorized ({@link #requireNotAdjustment}) and where an adjustment stands ({@link
 * #adjustmentStates}); the engine and the decisions that create and withdraw one stay in {@link
 * ReconciliationService} and {@link ReconciliationDecisionService}, and whether its result is final
 * comes from {@link ReconciliationHistoryService}.
 */
@Service
public class ReconciliationAdjustmentService {

  private final ReconciliationHistoryService historyService;
  private final ReconciliationResultRepository resultRepository;

  public ReconciliationAdjustmentService(
      ReconciliationHistoryService historyService,
      ReconciliationResultRepository resultRepository) {
    this.historyService = historyService;
    this.resultRepository = resultRepository;
  }

  /**
   * The {@link ReconciliationAdjustmentValues} state of every reconciliation adjustment among
   * {@code transactions}, by transaction id; other rows have none. One query for the owning
   * results, plus one per account that carries a live adjustment.
   */
  public Map<UUID, String> adjustmentStates(Collection<Transaction> transactions) {
    List<Transaction> adjustments =
        transactions.stream().filter(Transaction::isReconciliationAdjustment).toList();
    if (adjustments.isEmpty()) {
      return Map.of();
    }
    Map<UUID, ReconciliationResult> owners =
        resultRepository
            .findAllById(
                adjustments.stream()
                    .map(Transaction::getReconciliationResultId)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(ReconciliationResult::getId, Function.identity()));
    Map<UUID, Optional<UUID>> latestByAccount = new HashMap<>();
    Map<UUID, String> states = new HashMap<>();
    for (Transaction adjustment : adjustments) {
      states.put(adjustment.getId(), state(adjustment, owners, latestByAccount));
    }
    return states;
  }

  private String state(
      Transaction adjustment,
      Map<UUID, ReconciliationResult> owners,
      Map<UUID, Optional<UUID>> latestByAccount) {
    if (adjustment.getDeletedAt() != null) {
      return ReconciliationAdjustmentValues.WITHDRAWN;
    }
    ReconciliationResult owner = owners.get(adjustment.getReconciliationResultId());
    if (owner == null || !adjustment.getId().equals(owner.getResolutionTransactionId())) {
      // Withdrawing soft-deletes the row and clears the result's link together, so a live row its
      // result no longer points at cannot be taken back by a reopen: it is history.
      return ReconciliationAdjustmentValues.FINALIZED;
    }
    Optional<UUID> latest =
        latestByAccount.computeIfAbsent(
            adjustment.getAccount().getId(), historyService::latestSnapshotId);
    return ReconciliationHistoryService.isFinalized(owner, latest)
        ? ReconciliationAdjustmentValues.FINALIZED
        : ReconciliationAdjustmentValues.REOPENABLE;
  }

  /**
   * A reconciliation adjustment belongs to its result, so correcting, removing, restoring or
   * categorizing it directly is a 409 {@code RECONCILIATION_ADJUSTMENT_LOCKED}. Taken back on its
   * own, the result would claim a closed gap that is open again; and it is neither spending nor
   * income, so it has no category. The detail, and the {@code reconciliationAdjustment} property,
   * say what still works.
   */
  void requireNotAdjustment(Transaction transaction) {
    if (!transaction.isReconciliationAdjustment()) {
      return;
    }
    String state = adjustmentStates(List.of(transaction)).get(transaction.getId());
    String detail =
        switch (state) {
          case ReconciliationAdjustmentValues.FINALIZED ->
              "This adjusting entry belongs to a reconciliation that a newer snapshot has"
                  + " finalized. Correct the difference in the latest reconciliation instead.";
          case ReconciliationAdjustmentValues.WITHDRAWN ->
              "This adjusting entry was withdrawn with its reconciliation decision and cannot"
                  + " come back on its own.";
          default ->
              "This is the adjusting entry of an accepted reconciliation difference. Reopen the"
                  + " reconciliation result to take it back.";
        };
    ApiException locked =
        new ApiException(
            HttpStatus.CONFLICT, ApiErrorCode.RECONCILIATION_ADJUSTMENT_LOCKED, detail);
    locked.getBody().setProperty("reconciliationAdjustment", state);
    throw locked;
  }
}
