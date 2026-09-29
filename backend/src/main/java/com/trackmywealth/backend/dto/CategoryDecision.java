package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What automatic categorization concluded for one transaction (US-08-01/02), before anything is
 * written: the category and how it was found. {@code assignedBy} is {@code null} for the
 * UNCATEGORIZED fallback, which is recorded without a log row; {@code ruleId} is set for a rule,
 * {@code confidence} for a fuzzy match. Internal to {@code CategorizationService}: not part of any
 * API response. It lives here rather than as a nested type of the service because {@code
 * ArchitectureTest} requires every class in {@code ..service..} to be a {@code @Service}.
 */
public record CategoryDecision(
    UUID categoryId, String assignedBy, UUID ruleId, BigDecimal confidence) {}
