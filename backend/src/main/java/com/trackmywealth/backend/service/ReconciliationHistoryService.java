package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ReconciliationResultResponse;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-25-02/03: the read side of an account's cash reconciliation results - its history, one result,
 * the audited lookup a decision starts from, and whether a decision is still the newest comparison
 * or already final. It writes nothing; the engine that computes results is {@link
 * ReconciliationService}, the member's decisions are {@link ReconciliationDecisionService}.
 */
@Service
public class ReconciliationHistoryService {

  static final String RESOURCE_NAME = "reconciliation result";
  private static final String RESOURCE_LABEL = "Reconciliation result";
  private static final int MAX_PAGE_SIZE = 200;

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountSnapshotRepository snapshotRepository;
  private final ReconciliationResultRepository resultRepository;

  public ReconciliationHistoryService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountSnapshotRepository snapshotRepository,
      ReconciliationResultRepository resultRepository) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.snapshotRepository = snapshotRepository;
    this.resultRepository = resultRepository;
  }

  /** Detailed history is transaction-sensitive and therefore requires READ, not BALANCE_ONLY. */
  @Transactional(readOnly = true)
  public Page<ReconciliationResultResponse> list(
      UUID accountId, Pageable pageable, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    Pageable bounded =
        PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
    Optional<UUID> latest = latestSnapshotId(accountId);
    return resultRepository
        .findByAccountIdAndAffectedSecurityIdIsNullOrderByCreatedAtDesc(accountId, bounded)
        .map(result -> toResponse(result, latest));
  }

  /** One result of the account's history, for a client about to decide on it (US-25-03). */
  @Transactional(readOnly = true)
  public ReconciliationResultResponse get(
      UUID accountId, UUID resultId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId, actor);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    return toResponse(findResultOrThrow(account, resultId, actor));
  }

  /**
   * The account's cash-scope result {@code resultId}. One of another account or workspace is the
   * same audited 404 as a missing one (US-28-02/03).
   */
  ReconciliationResult findResultOrThrow(
      Account account, UUID resultId, AuthenticatedUserPrincipal actor) {
    return resultRepository
        .findById(resultId)
        .filter(result -> result.getAccount().getId().equals(account.getId()))
        .filter(result -> result.getAffectedSecurityId() == null)
        .orElseThrow(() -> accessControlService.denyAsNotFound(actor, RESOURCE_LABEL, resultId));
  }

  /**
   * {@link #findResultOrThrow}'s audited 404 without loading the result. A decision answers an id
   * it may not use before it takes any lock, and reads the result itself - and so its version -
   * only once it holds them.
   */
  void requireResultExists(Account account, UUID resultId, AuthenticatedUserPrincipal actor) {
    if (!resultRepository.existsByIdAndAccountIdAndAffectedSecurityIdIsNull(
        resultId, account.getId())) {
      throw accessControlService.denyAsNotFound(actor, RESOURCE_LABEL, resultId);
    }
  }

  /** Whether {@code result} compares the account's newest observed snapshot. */
  boolean comparesLatestSnapshot(ReconciliationResult result) {
    return comparesSnapshot(result, latestSnapshotId(result.getAccount().getId()));
  }

  /**
   * A member's decision a newer snapshot has overtaken is final. That snapshot was compared against
   * a ledger containing the decision - an accepted one's adjusting entry included - so taking it
   * back would rewrite a comparison that is already closed. Derived rather than stored, so it
   * follows a snapshot whose date is edited later.
   */
  boolean isFinalized(ReconciliationResult result) {
    return isFinalized(result, latestSnapshotId(result.getAccount().getId()));
  }

  /** {@link #isFinalized(ReconciliationResult)} against an already known newest snapshot. */
  static boolean isFinalized(ReconciliationResult result, Optional<UUID> latestSnapshotId) {
    return List.of(ReconciliationResultValues.ACCEPTED, ReconciliationResultValues.DISMISSED)
            .contains(result.getStatus())
        && !comparesSnapshot(result, latestSnapshotId);
  }

  /** The id of the account's newest observed balance snapshot, if one exists. */
  Optional<UUID> latestSnapshotId(UUID accountId) {
    return snapshotRepository
        .findFirstByAccountIdAndOpeningBalanceFalseAndBalanceIsNotNullOrderBySnapshotDateDescCreatedAtDesc(
            accountId)
        .map(AccountSnapshot::getId);
  }

  ReconciliationResultResponse toResponse(ReconciliationResult result) {
    return toResponse(result, latestSnapshotId(result.getAccount().getId()));
  }

  private static boolean comparesSnapshot(
      ReconciliationResult result, Optional<UUID> latestSnapshotId) {
    return latestSnapshotId.map(id -> id.equals(result.getSnapshot().getId())).orElse(false);
  }

  private static ReconciliationResultResponse toResponse(
      ReconciliationResult result, Optional<UUID> latestSnapshotId) {
    return new ReconciliationResultResponse(
        result.getId(),
        result.getAccount().getId(),
        result.getSnapshot().getId(),
        result.getSnapshot().getSnapshotDate(),
        result.getDifferenceAmount(),
        result.getSnapshot().getCurrency(),
        result.getProbableCause(),
        result.getStatus(),
        result.getResolvedAt(),
        result.getCreatedAt(),
        result.getResolutionNote(),
        result.getResolutionTransactionId(),
        VersionPreconditionService.persistedVersion(result.getVersion(), RESOURCE_NAME),
        isFinalized(result, latestSnapshotId));
  }
}
