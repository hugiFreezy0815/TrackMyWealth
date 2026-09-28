package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One earlier transaction a fuzzy categorization could learn from (US-08-01 FALLBACK_MATCH): its
 * category, merchant description and pg_trgm similarity to the new row's merchant, 0..1. A query
 * projection of {@code TransactionRepository#findFuzzyCandidates}; internal, not part of any API
 * response. It lives here because {@code ArchitectureTest} allows only repositories in {@code
 * ..repository..}.
 */
public interface FuzzyCategoryCandidate {
  UUID getCategoryId();

  String getMerchantDescription();

  BigDecimal getSimilarity();
}
