package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountListFilter;
import com.trackmywealth.backend.dto.AccountListItemResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.LinkSavingsPocketRequest;
import com.trackmywealth.backend.dto.ReassignAccountPortfolioRequest;
import com.trackmywealth.backend.dto.UpdateAccountRequest;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountListService;
import com.trackmywealth.backend.service.AccountService;
import com.trackmywealth.backend.service.SavingsPocketService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.SuccessStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
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
 * US-05-01, US-05-02, US-05-03, US-04-04, US-03-06 (workspace membership checked by {@code
 * AccessControlService}), and the account list of US-05-06 (#380). Every operation here is under
 * the Accounts tag, as each controller's operations are under one tag (#380 moved them there from
 * springdoc's default {@code account-controller}). The savings pocket link is #325's follow-up.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

  private final AccountService accountService;
  private final AccountListService accountListService;
  private final SavingsPocketService savingsPocketService;

  public AccountController(
      AccountService accountService,
      AccountListService accountListService,
      SavingsPocketService savingsPocketService) {
    this.accountService = accountService;
    this.accountListService = accountListService;
    this.savingsPocketService = savingsPocketService;
  }

  /**
   * US-05-06 (#380): every account of the workspace with its current value, oldest first. A bounded
   * list, deliberately not paged: a workspace holds tens of accounts, not thousands.
   */
  @ProblemCodes({ProblemCode.NOT_FOUND})
  @Operation(
      operationId = "listAccounts",
      summary = "List the workspace's accounts with their current value",
      description =
          "Every active account of the workspace, or every archived one with archived=true, oldest"
              + " first. Not paged: a workspace holds tens of accounts. Amounts are signed (assets"
              + " positive, liabilities negative) and come from the same valuation as net worth.")
  @GetMapping
  public List<AccountListItemResponse> listAccounts(
      @RequestParam(required = false) UUID portfolioId,
      @RequestParam(required = false) String accountType,
      @RequestParam(defaultValue = "false") boolean archived,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    // An empty parameter (?accountType=) is a filter left unset, not an unknown type.
    String type = StringUtils.hasText(accountType) ? accountType : null;
    return accountListService.listAccounts(
        new AccountListFilter(portfolioId, type, archived), actor);
  }

  @SuccessStatus(HttpStatus.CREATED)
  @Operation(operationId = "createAccount", summary = "Create an account")
  @ProblemCodes({ProblemCode.NOT_FOUND, ProblemCode.CONFLICT})
  @PostMapping
  public ResponseEntity<AccountSummaryResponse> createAccount(
      @Valid @RequestBody CreateAccountRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse created = accountService.createAccount(request, actor);
    return withEtag(ResponseEntity.status(HttpStatus.CREATED), created);
  }

  @Operation(operationId = "getAccount", summary = "Read an account")
  @GetMapping("/{accountId}")
  public ResponseEntity<AccountSummaryResponse> getAccount(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse account = accountService.getAccount(accountId, actor);
    return withEtag(ResponseEntity.ok(), account);
  }

  @Operation(operationId = "updateAccount", summary = "Change an account's name and details")
  @ProblemCodes({ProblemCode.IMMUTABLE_FIELD})
  @PutMapping("/{accountId}")
  public ResponseEntity<AccountSummaryResponse> updateAccount(
      @PathVariable UUID accountId,
      @Valid @RequestBody UpdateAccountRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse updated =
        accountService.updateAccount(
            accountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), updated);
  }

  @Operation(operationId = "archiveAccount", summary = "Archive an account")
  @ProblemCodes({ProblemCode.CONFLICT})
  @PostMapping("/{accountId}/archive")
  public ResponseEntity<AccountSummaryResponse> archiveAccount(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse archived =
        accountService.archiveAccount(accountId, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), archived);
  }

  @Operation(operationId = "restoreAccount", summary = "Restore an archived account")
  @ProblemCodes({ProblemCode.CONFLICT})
  @PostMapping("/{accountId}/restore")
  public ResponseEntity<AccountSummaryResponse> restoreAccount(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse restored =
        accountService.restoreAccount(accountId, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), restored);
  }

  @Operation(
      operationId = "reassignAccountPortfolio",
      summary = "Move an account to another portfolio")
  @ProblemCodes({ProblemCode.CONFLICT})
  @PostMapping("/{accountId}/reassign-portfolio")
  public ResponseEntity<AccountSummaryResponse> reassignPortfolio(
      @PathVariable UUID accountId,
      @Valid @RequestBody ReassignAccountPortfolioRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse reassigned =
        accountService.reassignPortfolio(
            accountId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return withEtag(ResponseEntity.ok(), reassigned);
  }

  @Operation(
      operationId = "linkAccountSavingsPocket",
      summary = "Link another account as the account's savings pocket",
      description =
          "The next import commits book the account's savings pocket moves on this account, e.g."
              + " after the pocket an import created was archived. The pocket must be an active"
              + " account in the account's currency that holds transactions, is no card or loan"
              + " and has no pocket of its own (422 SAVINGS_POCKET_UNSUITABLE), and no other"
              + " account's pocket (409 SAVINGS_POCKET_IN_USE). A card, a loan, an account without"
              + " transactions or another account's pocket can have none (422"
              + " SAVINGS_POCKET_NOT_APPLICABLE). A link is replaced, never removed.")
  @ProblemCodes({
    ProblemCode.SAVINGS_POCKET_NOT_APPLICABLE,
    ProblemCode.SAVINGS_POCKET_UNSUITABLE,
    ProblemCode.SAVINGS_POCKET_IN_USE,
    ProblemCode.CONFLICT
  })
  @PutMapping("/{accountId}/savings-pocket")
  public ResponseEntity<AccountSummaryResponse> linkSavingsPocket(
      @PathVariable UUID accountId,
      @Valid @RequestBody LinkSavingsPocketRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    AccountSummaryResponse linked =
        savingsPocketService.linkPocket(
            accountId,
            request.savingsPocketAccountId(),
            IfMatchVersionParser.parse(ifMatch),
            actor);
    return withEtag(ResponseEntity.ok(), linked);
  }

  private static ResponseEntity<AccountSummaryResponse> withEtag(
      ResponseEntity.BodyBuilder builder, AccountSummaryResponse body) {
    return builder.eTag(IfMatchVersionParser.toEtag(body.version())).body(body);
  }
}
