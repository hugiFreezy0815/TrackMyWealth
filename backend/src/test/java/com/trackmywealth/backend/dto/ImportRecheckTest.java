package com.trackmywealth.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** #308: a preview's counts as its commit would leave them, from the counts of its stored rows. */
class ImportRecheckTest {

  private static final ImportBatchCountsResponse STORED =
      new ImportBatchCountsResponse(10, 4, 4, 1, 1, 2, 5, 0);

  @Test
  void nothingRecheckedLeavesTheCountsAsStored() {
    assertThat(ImportRecheck.NONE.applyTo(STORED)).isEqualTo(STORED);
    assertThat(ImportRecheck.NONE.duplicateDelta()).isZero();
  }

  @Test
  void aDuplicateThatBecameNewIsANewIncludedRow() {
    ImportRecheck recheck = of(ImportRowRecheck.becameNew(false));

    assertThat(recheck.applyTo(STORED))
        .isEqualTo(new ImportBatchCountsResponse(10, 5, 3, 1, 1, 2, 6, 0));
    assertThat(recheck.duplicateDelta()).isEqualTo(-1);
  }

  @Test
  void aForcedInDuplicateThatBecameNewWasCountedAsIncludedAlready() {
    assertThat(of(ImportRowRecheck.becameNew(true)).applyTo(STORED))
        .isEqualTo(new ImportBatchCountsResponse(10, 5, 3, 1, 1, 2, 5, 0));
  }

  @Test
  void aRepointedDuplicateChangesNoCount() {
    ImportRecheck recheck =
        of(
            ImportRowRecheck.repointed(UUID.randomUUID(), true),
            ImportRowRecheck.repointed(UUID.randomUUID(), false));

    assertThat(recheck.applyTo(STORED)).isEqualTo(STORED);
    assertThat(recheck.duplicateDelta()).isZero();
  }

  @Test
  void anIncludedNewRowThatBecameADuplicateIsAnExcludedDuplicate() {
    ImportRecheck recheck =
        of(
            ImportRowRecheck.becameDuplicate(UUID.randomUUID()),
            ImportRowRecheck.becameDuplicate(UUID.randomUUID()),
            ImportRowRecheck.becameNew(false));

    assertThat(recheck.applyTo(STORED))
        .isEqualTo(new ImportBatchCountsResponse(10, 3, 5, 1, 1, 2, 4, 0));
    assertThat(recheck.duplicateDelta()).isEqualTo(1);
  }

  private static ImportRecheck of(ImportRowRecheck... rows) {
    Map<UUID, ImportRowRecheck> byRow = new LinkedHashMap<>();
    for (ImportRowRecheck row : rows) {
      byRow.put(UUID.randomUUID(), row);
    }
    return new ImportRecheck(byRow);
  }
}
