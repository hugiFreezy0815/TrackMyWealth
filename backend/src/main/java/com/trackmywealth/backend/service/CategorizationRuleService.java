package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.CategorizationRuleResponse;
import com.trackmywealth.backend.dto.CreateCategorizationRuleRequest;
import com.trackmywealth.backend.entity.CategorizationRule;
import com.trackmywealth.backend.repository.CategorizationRuleRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-08-01/FR-CAT-007: a workspace's categorization rules, applied by {@link CategorizationService}
 * to every newly recorded cash or card transaction before the shipped mappings. Rules are shared by
 * the workspace like its taxonomy, so reading needs membership and creating or deactivating needs
 * EDIT on the workspace. A rule is never edited - its log rows must keep meaning what they meant -
 * only deactivated (idempotent) and replaced.
 */
@Service
public class CategorizationRuleService {

  private static final int DEFAULT_PRIORITY = 100;
  // A MERCHANT rule is a case-insensitive "contains": one or two letters would match almost every
  // merchant and silently take over the whole ledger. Three matches the brand-word length the
  // fuzzy match uses.
  static final int MIN_MERCHANT_VALUE_LENGTH = 3;
  private static final Pattern SOURCE_CODE_VALUE =
      Pattern.compile(
          "MCC:\\d{4}|ISO20022_PURPOSE:[A-Z0-9]{4}|ISO20022_BTC:[A-Z]{4}-[A-Z]{4}-[A-Z]{4}");

  private final CategorizationRuleRepository ruleRepository;
  private final CategoryService categoryService;
  private final AccessControlService accessControlService;

  public CategorizationRuleService(
      CategorizationRuleRepository ruleRepository,
      CategoryService categoryService,
      AccessControlService accessControlService) {
    this.ruleRepository = ruleRepository;
    this.categoryService = categoryService;
    this.accessControlService = accessControlService;
  }

  /** In evaluation order: lowest priority first, then the older rule. */
  @Transactional(readOnly = true)
  public List<CategorizationRuleResponse> list(
      boolean includeInactive, AuthenticatedUserPrincipal actor) {
    accessControlService.requireActingMember(actor);
    List<CategorizationRule> rules =
        includeInactive
            ? ruleRepository.findByWorkspaceIdOrderByPriorityAscCreatedAtAscIdAsc(
                actor.workspaceId())
            : ruleRepository.findByWorkspaceIdAndActiveTrueOrderByPriorityAscCreatedAtAscIdAsc(
                actor.workspaceId());
    return rules.stream().map(CategorizationRuleService::toResponse).toList();
  }

  @Transactional
  public CategorizationRuleResponse create(
      CreateCategorizationRuleRequest request, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    String value = normalizedValue(request.matchType(), request.matchValue());
    // 404 for a category the workspace cannot see, 422 for an inactive one.
    categoryService.requireAssignable(request.categoryId(), workspaceId, actor);

    CategorizationRule rule = new CategorizationRule();
    rule.setWorkspaceId(workspaceId);
    rule.setMatchType(request.matchType());
    rule.setMatchValue(value);
    rule.setCategoryId(request.categoryId());
    rule.setPriority(request.priority() == null ? DEFAULT_PRIORITY : request.priority());
    return toResponse(ruleRepository.saveAndFlush(rule));
  }

  /** Idempotent: an already inactive rule is returned unchanged. */
  @Transactional
  public CategorizationRuleResponse deactivate(UUID id, AuthenticatedUserPrincipal actor) {
    UUID workspaceId = requireEditor(actor);
    CategorizationRule rule =
        ruleRepository
            .findByIdAndWorkspaceId(id, workspaceId)
            .orElseThrow(
                () ->
                    accessControlService.denyAsNotFound(
                        actor, "CategorizationRule", id));
    if (rule.isActive()) {
      rule.setActive(false);
      rule = ruleRepository.saveAndFlush(rule);
    }
    return toResponse(rule);
  }

  // Stored the way CategorizationService compares it, so a rule's value reads back as it matches.
  private static String normalizedValue(String matchType, String matchValue) {
    if (CategorizationService.MERCHANT.equals(matchType)) {
      String merchant = CategorizationService.normalizeMerchant(matchValue);
      if (merchant == null || merchant.length() < MIN_MERCHANT_VALUE_LENGTH) {
        throw unprocessable(
            "matchValue for a MERCHANT rule needs at least "
                + MIN_MERCHANT_VALUE_LENGTH
                + " characters; a shorter one would match almost every merchant.");
      }
      return merchant;
    }
    if (CategorizationService.SOURCE_CODE.equals(matchType)) {
      String code = matchValue.strip().toUpperCase(Locale.ROOT);
      if (!SOURCE_CODE_VALUE.matcher(code).matches()) {
        throw unprocessable(
            "matchValue for a SOURCE_CODE rule must be MCC:<4 digits>, ISO20022_PURPOSE:<4"
                + " characters> or ISO20022_BTC:<DOMAIN-FAMILY-SUBFAMILY>, e.g. MCC:5812.");
      }
      return code;
    }
    throw unprocessable(
        "matchType must be MERCHANT or SOURCE_CODE; COUNTERPARTY_IBAN and AMOUNT_PATTERN rules"
            + " are not supported yet.");
  }

  private UUID requireEditor(AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    accessControlService.requireWorkspaceAccess(
        memberId, actor.workspaceId(), AccessLevelValues.EDIT);
    return actor.workspaceId();
  }

  private static CategorizationRuleResponse toResponse(CategorizationRule rule) {
    return new CategorizationRuleResponse(
        rule.getId(),
        rule.getMatchType(),
        rule.getMatchValue(),
        rule.getCategoryId(),
        rule.getPriority(),
        rule.isActive(),
        rule.getCreatedAt());
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }
}
