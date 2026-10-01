package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CategorizationRuleResponse;
import com.trackmywealth.backend.dto.CreateCategorizationRuleRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CategorizationRuleService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.VersionedResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-08-01/FR-CAT-007: the workspace's categorization rules. Rules in {@link
 * CategorizationRuleService}.
 */
@RestController
@RequestMapping("/api/v1/categorization-rules")
public class CategorizationRuleController {

  private final CategorizationRuleService ruleService;

  public CategorizationRuleController(CategorizationRuleService ruleService) {
    this.ruleService = ruleService;
  }

  /** Evaluation order; inactive rules only with {@code includeInactive=true}. */
  @GetMapping
  public List<CategorizationRuleResponse> list(
      @RequestParam(defaultValue = "false") boolean includeInactive,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ruleService.list(includeInactive, actor);
  }

  @PostMapping
  public ResponseEntity<CategorizationRuleResponse> create(
      @Valid @RequestBody CreateCategorizationRuleRequest request,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategorizationRuleResponse response = ruleService.create(request, actor);
    return VersionedResponse.created(response, response.version());
  }

  @PostMapping("/{id}/deactivate")
  public ResponseEntity<CategorizationRuleResponse> deactivate(
      @PathVariable UUID id,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    CategorizationRuleResponse response =
        ruleService.deactivate(id, IfMatchVersionParser.parse(ifMatch), actor);
    return VersionedResponse.ok(response, response.version());
  }
}
