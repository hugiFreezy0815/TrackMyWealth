package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.dto.TransactionSearchFilter;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.TransactionSearchService;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-07-35 (#382): every transaction of the workspace in one paged list, across its accounts -
 * search, filters and the uncategorized inbox ({@link TransactionSearchFilter} says what each
 * filter matches). The per-account ledger stays at {@code /api/v1/accounts/{id}/transactions}; both
 * answer the same row shape.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions")
public class WorkspaceTransactionController {

  private final TransactionSearchService transactionSearchService;

  public WorkspaceTransactionController(TransactionSearchService transactionSearchService) {
    this.transactionSearchService = transactionSearchService;
  }

  @Operation(
      operationId = "listWorkspaceTransactions",
      summary = "List the workspace's transactions across all accounts")
  @GetMapping
  public PageResponse<TransactionResponse> listWorkspaceTransactions(
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds,
      @RequestParam(name = "portfolioId", required = false) List<UUID> portfolioIds,
      @RequestParam(name = "categoryId", required = false) List<UUID> categoryIds,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate bookingDateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate bookingDateTo,
      @RequestParam(defaultValue = "false") boolean uncategorized,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) BigDecimal amountMin,
      @RequestParam(required = false) BigDecimal amountMax,
      @RequestParam(required = false) String currency,
      @Paged(
              sort = {
                // Same-day bookings in the order they were entered, as in the per-account list.
                @SortField(
                    name = "bookingDate",
                    properties = {"bookingDate", "createdAt"}),
                // By value: the amount in the account's currency, whatever the booking's.
                @SortField(name = "amount", properties = "amountInAccountCurrency")
              },
              defaultSort = "bookingDate,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionSearchFilter filter =
        new TransactionSearchFilter(
            Objects.requireNonNullElse(accountIds, List.of()),
            Objects.requireNonNullElse(portfolioIds, List.of()),
            Objects.requireNonNullElse(categoryIds, List.of()),
            bookingDateFrom,
            bookingDateTo,
            uncategorized,
            q,
            amountMin,
            amountMax,
            currency);
    return PageResponse.of(
        transactionSearchService.listWorkspaceTransactions(filter, pageable, actor));
  }
}
