package com.trackmywealth.backend.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US-07-38: a member's reset is attributed, not an override, and keeps its decision's source. */
class TransactionCategorizationLogTest {

  private static final UUID TRANSACTION = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();

  @Test
  void aResetToARuleKeepsTheRuleAndConfidenceAndNamesTheMember() {
    UUID category = UUID.randomUUID();
    UUID rule = UUID.randomUUID();

    TransactionCategorizationLog log =
        TransactionCategorizationLog.ofMemberReset(
            TRANSACTION, category, "RULE", rule, new BigDecimal("0.875"), MEMBER);

    assertThat(log.isReset()).isTrue();
    assertThat(log.isUserOverride()).isFalse();
    assertThat(log.getAssignedByUserId()).isEqualTo(MEMBER);
    assertThat(log.getTransactionId()).isEqualTo(TRANSACTION);
    assertThat(log.getCategoryId()).isEqualTo(category);
    assertThat(log.getAssignedBy()).isEqualTo("RULE");
    assertThat(log.getRuleId()).isEqualTo(rule);
    assertThat(log.getConfidence()).isEqualByComparingTo("0.875");
  }

  @Test
  void aResetThatAssignedNothingIsRecordedAsReset() {
    TransactionCategorizationLog log =
        TransactionCategorizationLog.ofMemberReset(TRANSACTION, null, null, null, null, MEMBER);

    assertThat(log.getAssignedBy()).isEqualTo(TransactionCategorizationLog.ASSIGNED_BY_RESET);
    assertThat(log.getCategoryId()).isNull();
    assertThat(log.getRuleId()).isNull();
    assertThat(log.isReset()).isTrue();
  }
}
