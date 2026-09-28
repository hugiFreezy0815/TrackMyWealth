package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A workspace's categorization rule (US-08-01). {@code matchValue} is stored normalized: a merchant
 * value in lower case with single spaces, a source code in upper case.
 */
public record CategorizationRuleResponse(
    UUID id,
    String matchType,
    String matchValue,
    UUID categoryId,
    int priority,
    boolean active,
    OffsetDateTime createdAt) {}
