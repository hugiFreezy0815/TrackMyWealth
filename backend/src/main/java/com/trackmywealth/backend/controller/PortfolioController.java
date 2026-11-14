package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.PortfolioListItemResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioSummaryResponse;
import com.trackmywealth.backend.dto.PortfolioViewResponse;
import com.trackmywealth.backend.dto.UpdatePortfolioRequest;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.PortfolioService;
import com.trackmywealth.backend.service.PortfolioViewService;
import com.trackmywealth.backend.service.ValueHistoryService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import com.trackmywealth.backend.web.SuccessStatus;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * US-04-01/03/06: the workspace's portfolios. Every active member of the workspace has full access
 * (US-03-06); the services check membership, RLS keeps workspaces apart. Listing them is US-04-05
 * (#381); the institution catalogue a portfolio may link to is searched through {@link
 * InstitutionCatalogueController}.
 */
@RestController
@RequestMapping("/api/v1/portfolios")
@Tag(name = "Portfolios")
public class PortfolioController {

  private final PortfolioService portfolioService;
  private final PortfolioViewService portfolioViewService;
  private final ValueHistoryService historyService;

  public PortfolioController(
      PortfolioService portfolioService,
      PortfolioViewService portfolioViewService,
      ValueHistoryService historyService) {
    this.portfolioService = portfolioService;
    this.portfolioViewService = portfolioViewService;
    this.historyService = historyService;
  }

  /**
   * US-04-05 (#381): every active portfolio of the workspace with its totals, in tab order. Not
   * paged: a workspace is expected to hold a handful of portfolios. Nothing enforces that bound
   * yet, so the list is a known exception in docs/api/conventions.md until #570 decides between
   * paging and an enforced bound.
   */
  @ProblemCodes({ProblemCode.VALIDATION_FAILED, ProblemCode.NOT_FOUND})
  @Operation(
      operationId = "listPortfolios",
      summary = "List the workspace's portfolios with their totals",
      description =
          "Every active portfolio of the workspace, the Personal Assets default included, in"
              + " creation order. Not paged: a workspace holds a handful of portfolios. Each row"
              + " has the number of active accounts and their signed total (assets positive,"
              + " liabilities negative) in the portfolio's and the workspace's currency, from the"
              + " same valuation as the Portfolio View and net worth.")
  @GetMapping
  public List<PortfolioListItemResponse> listPortfolios(
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return portfolioService.listPortfolios(actor);
  }

  @SuccessStatus(HttpStatus.CREATED)
  @ProblemCodes({ProblemCode.NOT_FOUND, ProblemCode.CONFLICT, ProblemCode.UNPROCESSABLE})
  @Operation(operationId = "createPortfolio", summary = "Create a portfolio")
  @PostMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<PortfolioResponse> createPortfolio(
      @Valid @RequestBody CreatePortfolioRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    PortfolioResponse created = portfolioService.createPortfolio(request, actor);
    return VersionedResponse.created(created, created.version());
  }

  @Operation(operationId = "getPortfolio", summary = "Read a portfolio")
  @GetMapping("/{portfolioId}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<PortfolioResponse> getPortfolio(
      @PathVariable UUID portfolioId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    PortfolioResponse portfolio = portfolioService.getPortfolio(portfolioId, actor);
    return VersionedResponse.ok(portfolio, portfolio.version());
  }

  @ProblemCodes({ProblemCode.CONFLICT})
  @Operation(
      operationId = "updatePortfolio",
      summary = "Rename a portfolio, describe its institution or set its type")
  @PutMapping("/{portfolioId}")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<PortfolioResponse> updatePortfolio(
      @PathVariable UUID portfolioId,
      @Valid @RequestBody UpdatePortfolioRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    PortfolioResponse updated =
        portfolioService.updatePortfolio(
            portfolioId, request, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(updated, updated.version());
  }

  @Operation(
      operationId = "getPortfolioSummary",
      summary = "Total assets, liabilities and net value of a portfolio")
  @GetMapping("/{portfolioId}/summary")
  public PortfolioSummaryResponse getSummary(
      @PathVariable UUID portfolioId,
      @RequestParam(required = false) String currency,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return portfolioService.getSummary(portfolioId, actor, currency);
  }

  @Operation(
      operationId = "getPortfolioHistory",
      summary = "Historical value and period change in the portfolio currency")
  @GetMapping("/{portfolioId}/history")
  public ValueHistoryResponse getHistory(
      @PathVariable UUID portfolioId,
      // Documented, not schema-restricted: turning the existing string into an enum is a breaking
      // contract change under ADR 0006. The response's period field carries the enum.
      @Parameter(
              description =
                  "The history window: 1W, 1M, YTD, 1Y or MAX; any other value is a 400"
                      + " VALIDATION_FAILED.")
          @RequestParam(defaultValue = "1M")
          String period,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return historyService.portfolioHistory(portfolioId, period, actor);
  }

  @Operation(
      operationId = "getPortfolioView",
      summary = "A portfolio's tab: its total and a section per kind of account")
  @GetMapping("/{portfolioId}/view")
  public PortfolioViewResponse getView(
      @PathVariable UUID portfolioId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return portfolioViewService.getView(portfolioId, actor);
  }
}
