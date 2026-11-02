package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioSummaryResponse;
import com.trackmywealth.backend.dto.PortfolioViewResponse;
import com.trackmywealth.backend.dto.UpdatePortfolioRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.PortfolioService;
import com.trackmywealth.backend.service.PortfolioViewService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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

  public PortfolioController(
      PortfolioService portfolioService, PortfolioViewService portfolioViewService) {
    this.portfolioService = portfolioService;
    this.portfolioViewService = portfolioViewService;
  }

  @Operation(operationId = "createPortfolio", summary = "Create a portfolio")
  @PostMapping
  public ResponseEntity<PortfolioResponse> createPortfolio(
      @Valid @RequestBody CreatePortfolioRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    PortfolioResponse created = portfolioService.createPortfolio(request, actor);
    return VersionedResponse.created(created, created.version());
  }

  @Operation(operationId = "getPortfolio", summary = "Read a portfolio")
  @GetMapping("/{portfolioId}")
  public ResponseEntity<PortfolioResponse> getPortfolio(
      @PathVariable UUID portfolioId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    PortfolioResponse portfolio = portfolioService.getPortfolio(portfolioId, actor);
    return VersionedResponse.ok(portfolio, portfolio.version());
  }

  @Operation(
      operationId = "updatePortfolio",
      summary = "Rename a portfolio, describe its institution or set its type")
  @PutMapping("/{portfolioId}")
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
      operationId = "getPortfolioView",
      summary = "A portfolio's tab: its total and a section per kind of account")
  @GetMapping("/{portfolioId}/view")
  public PortfolioViewResponse getView(
      @PathVariable UUID portfolioId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return portfolioViewService.getView(portfolioId, actor);
  }
}
