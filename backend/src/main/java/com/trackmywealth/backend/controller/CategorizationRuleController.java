package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.CategorizationRuleResponse;
import com.trackmywealth.backend.dto.CreateCategorizationRuleRequest;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.CategorizationRuleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    return ResponseEntity.status(HttpStatus.CREATED).body(ruleService.create(request, actor));
  }

  @PostMapping("/{id}/deactivate")
  public CategorizationRuleResponse deactivate(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return ruleService.deactivate(id, actor);
  }
}
