package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.TransactionCorrectionService;
import com.trackmywealth.backend.service.TransactionRemovalService;
import com.trackmywealth.backend.service.TransactionService;
import com.trackmywealth.backend.service.TransferResolutionService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.Paged.SortField;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SuccessStatus;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-09-01 (credit-card purchases), checked per account by {@code AccessControlService} (US-03-06).
 * The resulting balance is read from {@link AccountBalanceController}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@Tag(name = "Transactions")
public class TransactionController {

  private final TransactionService transactionService;
  private final TransactionCorrectionService transactionCorrectionService;
  private final TransactionRemovalService transactionRemovalService;
  private final TransferResolutionService transferResolutionService;

  public TransactionController(
      TransactionService transactionService,
      TransactionCorrectionService transactionCorrectionService,
      TransactionRemovalService transactionRemovalService,
      TransferResolutionService transferResolutionService) {
    this.transactionService = transactionService;
    this.transactionCorrectionService = transactionCorrectionService;
    this.transactionRemovalService = transactionRemovalService;
    this.transferResolutionService = transferResolutionService;
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "recordTransaction", summary = "Record a transaction")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.RETRY,
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE
  })
  @PostMapping("/transactions")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionResponse> recordTransaction(
      @PathVariable UUID accountId,
      @Valid @RequestBody CreateTransactionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionResponse response = transactionService.recordTransaction(accountId, request, actor);
    return VersionedResponse.created(response, response.version());
  }

  /**
   * US-07-06: corrects one ledger row. Financial changes never update committed financial fields:
   * the original is removed according to provenance and a linked replacement is inserted. A
   * merchant-description/notes-only change stays on the row. Needs access to the account and the
   * current ETag. The response and its ETag describe the effective row - after a financial
   * correction that is the replacement, which has a new id (see {@link
   * TransactionCorrectionResponse}).
   */
  @Operation(operationId = "correctTransaction", summary = "Correct a transaction")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.ACCOUNT_ARCHIVED,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.RECONCILIATION_ADJUSTMENT_LOCKED
  })
  @PutMapping("/transactions/{transactionId}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionCorrectionResponse> correctTransaction(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody CorrectTransactionRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionCorrectionResponse response =
        transactionCorrectionService.correct(
            accountId, transactionId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  /**
   * Newest booking first unless {@code sort} asks for {@code bookingDate} or {@code amount} - the
   * latter by value in the account's currency, so a foreign-currency booking sorts by what it cost
   * the account. The order always ends with the id, so pages never overlap. {@code
   * uncategorized=true} lists only the rows still in UNCATEGORIZED (FR-CAT-013).
   */
  @Operation(operationId = "listAccountTransactions", summary = "List an account's transactions")
  @GetMapping("/transactions")
  public PageResponse<TransactionResponse> listTransactions(
      @PathVariable UUID accountId,
      @RequestParam(defaultValue = "false") boolean uncategorized,
      @Paged(
              sort = {
                // Same-day bookings in the order they were entered.
                @SortField(
                    name = "bookingDate",
                    properties = {"bookingDate", "createdAt"}),
                // By value: the amount in the account's currency, whatever the booking's.
                @SortField(name = "amount", properties = "amountInAccountCurrency")
              },
              defaultSort = "bookingDate,desc")
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(
        transactionService.listTransactions(accountId, uncategorized, pageable, actor));
  }

  /**
   * US-08-02: the member's own category for this transaction, which no automatic run replaces.
   * Needs access to the account.
   */
  @Operation(
      operationId = "overrideTransactionCategory",
      summary = "Set a transaction's category by hand")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.RECONCILIATION_ADJUSTMENT_LOCKED
  })
  @PutMapping("/transactions/{transactionId}/category")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionResponse> overrideCategory(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @Valid @RequestBody SetTransactionCategoryRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionResponse response =
        transactionService.overrideCategory(
            accountId,
            transactionId,
            request.categoryId(),
            IfMatchVersionParser.parse(ifMatch),
            actor);
    return VersionedResponse.ok(response, response.version());
  }

  /** US-08-02 "reset to automatic": gives up the override and categorizes the row again. */
  @Operation(
      operationId = "resetTransactionCategory",
      summary = "Return a transaction to its automatic category")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.RECONCILIATION_ADJUSTMENT_LOCKED})
  @DeleteMapping("/transactions/{transactionId}/category")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionResponse> resetCategory(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionResponse response =
        transactionService.resetCategory(
            accountId, transactionId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  /**
   * US-07-02: removes the transaction the way its provenance requires - a manual one is
   * soft-deleted (restorable for 30 days), an imported one voided and reversed, which needs a
   * {@code reason}. Each row's {@code removal} says which applies. Needs access to the account.
   */
  @Operation(operationId = "removeTransaction", summary = "Remove a transaction")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.RECONCILIATION_ADJUSTMENT_LOCKED
  })
  @DeleteMapping("/transactions/{transactionId}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionRemovalResponse> removeTransaction(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @RequestParam(required = false) String reason,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionRemovalResponse response =
        transactionRemovalService.remove(
            accountId, transactionId, reason, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  /**
   * US-07-02/07-07, FR-LIF-006: restores a soft-deleted or voided transaction within 30 days. A
   * soft-deleted row comes back in place; a void is restored by a new copy of the original, listed
   * in the response's {@code restored} (the voided row and its reversal stay as history). The
   * {@code ETag} is then the unchanged voided row's version; each copy carries its own.
   */
  @Operation(operationId = "restoreTransaction", summary = "Restore a removed transaction")
  @ProblemCodes({
    ProblemCode.CONFLICT,
    ProblemCode.UNPROCESSABLE,
    ProblemCode.RECONCILIATION_ADJUSTMENT_LOCKED
  })
  @PostMapping("/transactions/{transactionId}/restore")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionRemovalResponse> restoreTransaction(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionRemovalResponse response =
        transactionRemovalService.restore(
            accountId, transactionId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  /**
   * US-07-02/07-07: the account's transactions that can still be restored - soft-deleted or voided
   * within the window, not yet restored or corrected.
   */
  @Operation(
      operationId = "listRemovedTransactions",
      summary = "List an account's removed transactions",
      description =
          "Fixed newest-removal-first order (COALESCE(deletedAt, voidedAt) descending, id"
              + " descending); only page and size are accepted, not sort.")
  @GetMapping("/transactions/deleted")
  public PageResponse<TransactionResponse> listRestorableTransactions(
      @PathVariable UUID accountId,
      @Paged(
              sort = @SortField(name = "removedAt", properties = "deletedAt"),
              defaultSort = "removedAt,desc",
              allowSort = false)
          Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return PageResponse.of(transactionRemovalService.listRestorable(accountId, pageable, actor));
  }

  /**
   * US-10-01: confirms a one-sided transfer leg as money moved to or from an own account not
   * tracked here - it then counts as neither income nor spending. Needs access to the account.
   */
  @Operation(
      operationId = "confirmUntrackedTransfer",
      summary = "Mark a transaction as a transfer to an untracked account")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.UNPROCESSABLE})
  @PostMapping("/transactions/{transactionId}/untracked-transfer")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionResponse> confirmUntrackedTransfer(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionResponse response =
        transferResolutionService.confirmUntracked(
            accountId, transactionId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }

  /** US-10-01: undoes that confirmation; the leg is pending review again. */
  @Operation(
      operationId = "undoUntrackedTransfer",
      summary = "Undo marking a transaction as an untracked transfer")
  @ProblemCodes({ProblemCode.CONFLICT, ProblemCode.UNPROCESSABLE})
  @DeleteMapping("/transactions/{transactionId}/untracked-transfer")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<TransactionResponse> undoUntrackedTransfer(
      @PathVariable UUID accountId,
      @PathVariable UUID transactionId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    TransactionResponse response =
        transferResolutionService.undoUntracked(
            accountId, transactionId, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
