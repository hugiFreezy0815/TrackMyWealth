package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountPositionsResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountPositionsService;
import com.trackmywealth.backend.web.ProblemCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-15-05: latest holdings of a position-capable account. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/positions")
@Tag(name = "Account values")
public class AccountPositionsController {

  private final AccountPositionsService accountPositionsService;

  public AccountPositionsController(AccountPositionsService accountPositionsService) {
    this.accountPositionsService = accountPositionsService;
  }

  @Operation(
      operationId = "getAccountPositions",
      summary = "Read positions from the account's newest holdings snapshot")
  @ProblemCodes(ProblemCode.POSITIONS_NOT_APPLICABLE)
  @GetMapping
  public AccountPositionsResponse getPositions(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return accountPositionsService.get(accountId, actor);
  }
}
