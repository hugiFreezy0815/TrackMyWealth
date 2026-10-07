package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.ImportRollbackValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * US-07-05: how the rollback reports the modified transactions. The rollback itself runs against a
 * real database in {@code ImportRollbackControllerTest}.
 */
class ImportRollbackServiceTest {

  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void eachModifiedTransactionIsListedOnceInTheBatchsOrderWithAllItsCriteria() {
    Map<String, List<UUID>> byCriterion = new LinkedHashMap<>();
    byCriterion.put(ImportRollbackValues.CORRECTED, List.of(THIRD));
    byCriterion.put(ImportRollbackValues.USER_CATEGORY_OVERRIDE, List.of(FIRST, THIRD));
    byCriterion.put(ImportRollbackValues.REFERENCED, List.of(THIRD));

    Map<UUID, List<String>> modified =
        ImportRollbackService.modifiedInBatchOrder(byCriterion, List.of(THIRD, SECOND, FIRST));

    assertThat(modified.keySet()).containsExactly(THIRD, FIRST);
    assertThat(modified.get(THIRD))
        .containsExactly(
            ImportRollbackValues.CORRECTED,
            ImportRollbackValues.USER_CATEGORY_OVERRIDE,
            ImportRollbackValues.REFERENCED);
    assertThat(modified.get(FIRST)).containsExactly(ImportRollbackValues.USER_CATEGORY_OVERRIDE);
  }

  @Test
  void aBatchNoCriterionNamesIsUnmodified() {
    assertThat(ImportRollbackService.modifiedInBatchOrder(Map.of(), List.of(FIRST, SECOND)))
        .isEmpty();
  }
}
