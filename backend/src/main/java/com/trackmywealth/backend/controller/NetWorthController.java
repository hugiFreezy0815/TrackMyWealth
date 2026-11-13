package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.NetWorthService;
import com.trackmywealth.backend.service.ValueHistoryService;
import com.trackmywealth.backend.web.ProblemCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** US-09-01 and US-06-05: workspace net worth with an optional ad-hoc display currency. */
@RestController
@RequestMapping("/api/v1/net-worth")
@Tag(name = "Net worth")
public class NetWorthController {

  private final NetWorthService netWorthService;
  private final ValueHistoryService historyService;

  public NetWorthController(NetWorthService netWorthService, ValueHistoryService historyService) {
    this.netWorthService = netWorthService;
    this.historyService = historyService;
  }

  @Operation(
      operationId = "getNetWorthHistory",
      summary = "Historical workspace net worth and period change")
  @ProblemCodes({ProblemCode.NOT_FOUND})
  @GetMapping("/history")
  public ValueHistoryResponse getHistory(
      @Parameter(
              description = "The history window; 400 VALIDATION_FAILED for any other value.",
              schema =
                  @Schema(
                      allowableValues = {"1W", "1M", "YTD", "1Y", "MAX"},
                      defaultValue = "1M"))
          @RequestParam(defaultValue = "1M")
          String period,
      @RequestParam(required = false) String currency,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return historyService.netWorthHistory(period, currency, actor);
  }

  @Operation(operationId = "getNetWorth", summary = "The workspace's net worth")
  @ProblemCodes({ProblemCode.NOT_FOUND})
  @GetMapping
  public NetWorthResponse getNetWorth(
      @RequestParam(required = false) String currency,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return netWorthService.getNetWorth(actor, currency);
  }
}
