package com.trackmywealth.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.ImportRollbackValues;
import org.junit.jupiter.api.Test;

/**
 * US-07-05: every criterion code a client can be told has exactly one query, asked in the
 * documented order. What each query finds is proven against a real database in {@code
 * ImportRollbackControllerTest}.
 */
class ImportRollbackRepositoryTest {

  @Test
  void everyCriterionHasItsQueryInTheDocumentedOrder() {
    assertThat(ImportRollbackRepository.CRITERIA.keySet())
        .containsExactlyElementsOf(ImportRollbackValues.CRITERIA);
    assertThat(ImportRollbackRepository.CRITERIA.values())
        .allSatisfy(predicate -> assertThat(predicate).startsWith(" AND "));
  }
}
