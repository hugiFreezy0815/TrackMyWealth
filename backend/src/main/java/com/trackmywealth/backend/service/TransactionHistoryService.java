package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.dto.TransactionHistoryEntryResponse;
import com.trackmywealth.backend.dto.TransactionHistoryEvent;
import com.trackmywealth.backend.dto.TransactionImportBatchResponse;
import com.trackmywealth.backend.repository.TransactionHistoryRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-38: one paged, RLS-scoped timeline for a transaction and the rows it corrects or restores.
 *
 * <p>Only the requested transaction is authorized, by the same account-scoped read as #479; the
 * repository binds every older row of the chain to that account and workspace and joins actor names
 * through that workspace's membership, never by a global user lookup. Read-only: nothing in the
 * history can be edited.
 */
@Service
public class TransactionHistoryService {

  static final String SORT_FIELD = "occurredAt";

  private final TransactionHistoryRepository historyRepository;
  private final TransactionService transactionService;

  public TransactionHistoryService(
      TransactionHistoryRepository historyRepository, TransactionService transactionService) {
    this.historyRepository = historyRepository;
    this.transactionService = transactionService;
  }

  @Transactional(readOnly = true)
  public PageResponse<TransactionHistoryEntryResponse> history(
      UUID accountId, UUID transactionId, Pageable requested, AuthenticatedUserPrincipal actor) {
    // The request's sort is occurredAt with the web layer's id tie-breaker appended; the query's
    // own unique tie-breaker (ordinal, event id) stands in for that id.
    Pageable pageable = PagingValues.requireBounded(requested, "id");
    UUID id = transactionService.requireReadableTransaction(accountId, transactionId, actor);
    UUID workspaceId = actor.workspaceId();

    long total =
        historyRepository.countEvents(
            id, accountId, workspaceId, CategorizationService.CATEGORIZED_TYPES);
    Sort.Order order = pageable.getSort().getOrderFor(SORT_FIELD);
    List<TransactionHistoryEntryResponse> entries =
        historyRepository
            .findEvents(
                id,
                accountId,
                workspaceId,
                CategorizationService.CATEGORIZED_TYPES,
                order != null && order.isAscending(),
                pageable.getPageSize(),
                pageable.getOffset())
            .stream()
            .map(TransactionHistoryService::toEntry)
            .toList();
    return PageResponse.of(new PageImpl<>(entries, pageable, total));
  }

  static TransactionHistoryEntryResponse toEntry(TransactionHistoryEvent event) {
    TransactionImportBatchResponse batch =
        event.importBatchId() == null
            ? null
            : new TransactionImportBatchResponse(
                event.importBatchId(),
                TransactionReadContextService.formatOf(
                    event.importTemplateFormat(), event.importSourceKind()),
                event.importUploadedAt());
    return new TransactionHistoryEntryResponse(
        event.occurredAt(),
        event.kind(),
        event.actorDisplayName(),
        event.fromCategoryId(),
        event.toCategoryId(),
        event.relatedTransactionId(),
        batch,
        event.reason());
  }
}
