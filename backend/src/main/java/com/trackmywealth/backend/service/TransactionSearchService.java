package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.PagingValues;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.TransactionSearchFilter;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-35 (#382): one transaction list for the whole workspace - search, filters and the
 * uncategorized inbox across accounts. Every active member sees every row of the workspace
 * (US-03-06); another workspace's ids match nothing, so the answer is the empty page an unknown id
 * gets. Rows have the per-account list's shape ({@link TransactionService#toResponses}).
 */
@Service
public class TransactionSearchService {

  private static final String PAGE_TIE_BREAKER = "id";
  private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

  private final AccessControlService accessControlService;
  private final TransactionRepository transactionRepository;
  private final CategorizationService categorizationService;
  private final TransactionService transactionService;

  public TransactionSearchService(
      AccessControlService accessControlService,
      TransactionRepository transactionRepository,
      CategorizationService categorizationService,
      TransactionService transactionService) {
    this.accessControlService = accessControlService;
    this.transactionRepository = transactionRepository;
    this.categorizationService = categorizationService;
    this.transactionService = transactionService;
  }

  /**
   * The workspace's rows matching {@code filter}, in {@code pageable}'s order: the controller's
   * sort whitelist ending with the id as a unique tie-breaker (#373). An administrator who is not
   * the workspace's sole active member gets the audited 404 (FR-TEN-007).
   */
  @Transactional(readOnly = true)
  public Page<TransactionResponse> listWorkspaceTransactions(
      TransactionSearchFilter filter, Pageable pageable, AuthenticatedUserPrincipal actor) {
    // Authorization first: a non-member gets the same 404 whatever filters it sends.
    accessControlService.requireWorkspaceAccess(actor, actor.workspaceId());
    requireValid(filter);
    Page<Transaction> page =
        transactionRepository.findAll(
            TransactionRepository.searchedBy(
                actor.workspaceId(),
                filter,
                filter.uncategorized() ? categorizationService.uncategorizedCategoryId() : null),
            PagingValues.requireBounded(pageable, PAGE_TIE_BREAKER));
    return new PageImpl<>(
        transactionService.toResponses(page.getContent()),
        page.getPageable(),
        page.getTotalElements());
  }

  // A filter that cannot match anything by construction is the client's mistake, said as a 400
  // rather than answered with an empty page that would look like "no such transactions".
  static void requireValid(TransactionSearchFilter filter) {
    for (List<?> values :
        List.of(filter.accountIds(), filter.portfolioIds(), filter.categoryIds())) {
      if (values.size() > TransactionSearchFilter.MAX_VALUES_PER_FILTER) {
        throw badRequest(
            "A filter takes at most " + TransactionSearchFilter.MAX_VALUES_PER_FILTER + " values.");
      }
    }
    if (filter.bookingDateFrom() != null
        && filter.bookingDateTo() != null
        && filter.bookingDateFrom().isAfter(filter.bookingDateTo())) {
      throw badRequest("bookingDateFrom must not be after bookingDateTo.");
    }
    if (filter.amountMin() != null
        && filter.amountMax() != null
        && filter.amountMin().compareTo(filter.amountMax()) > 0) {
      throw badRequest("amountMin must not be greater than amountMax.");
    }
    if (filter.q() != null && filter.q().length() > TransactionSearchFilter.MAX_QUERY_LENGTH) {
      throw badRequest(
          "q must be at most " + TransactionSearchFilter.MAX_QUERY_LENGTH + " characters long.");
    }
    if (filter.currency() != null && !CURRENCY_CODE.matcher(filter.currency()).matches()) {
      throw badRequest("currency must be a three-letter ISO 4217 code such as CHF.");
    }
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
