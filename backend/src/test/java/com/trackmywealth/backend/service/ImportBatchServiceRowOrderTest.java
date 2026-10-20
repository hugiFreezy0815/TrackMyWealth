package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.entity.ImportRowRaw;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PR #388 review: the re-checked import rows are ordered in memory, so a sort the rows list may
 * offer one day must fail here rather than be ignored.
 */
class ImportBatchServiceRowOrderTest {

  @Test
  void theRowsFollowTheRowNumberInEitherDirection() {
    List<ImportRowRaw> rows = List.of(row(2), row(3), row(1));

    assertThat(rows.stream().sorted(ImportBatchService.inRowNumberOrder(Sort.by("rowNumber"))))
        .extracting(ImportRowRaw::getRowNumber)
        .containsExactly(1, 2, 3);
    assertThat(
            rows.stream()
                .sorted(ImportBatchService.inRowNumberOrder(Sort.by(Order.desc("rowNumber")))))
        .extracting(ImportRowRaw::getRowNumber)
        .containsExactly(3, 2, 1);
  }

  @Test
  void aSortOnAnythingButTheRowNumberFailsInsteadOfBeingIgnored() {
    assertThatThrownBy(
            () -> ImportBatchService.inRowNumberOrder(Sort.by("bookingDate", "rowNumber")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rowNumber only");
    assertThatThrownBy(() -> ImportBatchService.inRowNumberOrder(Sort.by("amount")))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ImportRowRaw row(int rowNumber) {
    ImportRowRaw row = new ImportRowRaw();
    ReflectionTestUtils.setField(row, "rowNumber", rowNumber);
    return row;
  }
}
