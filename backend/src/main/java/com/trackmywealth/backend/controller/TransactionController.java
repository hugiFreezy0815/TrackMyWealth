package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CorrectTransactionRequest;
import com.trackmywealth.backend.dto.CreateTransactionRequest;
import com.trackmywealth.backend.dto.SetTransactionCategoryRequest;
import com.trackmywealth.backend.dto.TransactionCorrectionResponse;
import com.trackmywealth.backend.dto.TransactionRemovalResponse;
import com.trackmywealth.backend.dto.TransactionResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.TransactionCorrectionService;
import com.trackmywealth.backend.service.TransactionRemovalService;
import com.trackmywealth.backend.service.TransactionService;
import com.trackmywealth.backend.service.TransferResolutionService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * US-09-01 (credit-card purchases), gated per account by {@code AccessControlService} (US-03-03).
 * The resulting balance is read from {@link AccountBalanceController}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
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

  @PostMapping("/transactions")
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
   * merchant-description/notes-only change stays on the row. Needs EDIT and the current ETag.
   */
  @PutMapping("/transactions/{transactionId}")
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
   * Newest booking first; the order is fixed server-side, only {@code page}/{@code size} apply.
   * {@code uncategorized=true} lists only the rows still in UNCATEGORIZED (FR-CAT-013).
   */
  @GetMapping("/transactions")
  public Page<TransactionResponse> listTransactions(
      @PathVariable UUID accountId,
      @RequestParam(defaultValue = "false") boolean uncategorized,
      @PageableDefault(size = 50) Pageable pageable,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return transactionService.listTransactions(accountId, uncategorized, pageable, actor);
  }

  /**
   * US-08-02: the member's own category for this transaction, which no automatic run replaces.
   * Needs EDIT on the account.
   */
  @PutMapping("/transactions/{transactionId}/category")
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
  @DeleteMapping("/transactions/{transactionId}/category")
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
   * {@code reason}. Each row's {@code removal} says which applies. Needs EDIT on the account.
   */
  @DeleteMapping("/transactions/{transactionId}")
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

  /** US-07-02/FR-LIF-006: brings back a soft-deleted transaction within 30 days. */
  @PostMapping("/transactions/{transactionId}/restore")
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

  /** US-07-02: the account's soft-deleted transactions that can still be restored. */
  @GetMapping("/transactions/deleted")
  public List<TransactionResponse> listRestorableTransactions(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return transactionRemovalService.listRestorable(accountId, actor);
  }

  /**
   * US-10-01: confirms a one-sided transfer leg as money moved to or from an own account not
   * tracked here - it then counts as neither income nor spending. Needs EDIT on the account.
   */
  @PostMapping("/transactions/{transactionId}/untracked-transfer")
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
  @DeleteMapping("/transactions/{transactionId}/untracked-transfer")
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
