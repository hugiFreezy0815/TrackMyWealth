package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * US-07-04: the uploaded file's name as stored. The rest of {@link ImportBatchService} runs against
 * a real database in {@code ImportBatchControllerTest}.
 */
class ImportBatchServiceTest {

  @Test
  void onlyTheNameIsKeptNeverAClientsDirectoryPath() {
    assertThat(ImportBatchService.safeFileName("statement.csv")).isEqualTo("statement.csv");
    assertThat(ImportBatchService.safeFileName("/home/me/statement.csv"))
        .isEqualTo("statement.csv");
    assertThat(ImportBatchService.safeFileName("C:\\Users\\me\\..\\statement.csv"))
        .isEqualTo("statement.csv");
    assertThat(ImportBatchService.safeFileName("a/b\\c.csv")).isEqualTo("c.csv");
  }

  @Test
  void controlCharactersAreDroppedRatherThanFailingTheUpload() {
    // Path.of rejects a NUL; a client may still send one in the multipart file name.
    assertThat(ImportBatchService.safeFileName("state\u0000ment\r\n.csv"))
        .isEqualTo("statement.csv");
  }

  @Test
  void aNameWithNothingLeftIsNoName() {
    assertThat(ImportBatchService.safeFileName(null)).isNull();
    assertThat(ImportBatchService.safeFileName("  ")).isNull();
    assertThat(ImportBatchService.safeFileName("/home/me/")).isNull();
    assertThat(ImportBatchService.safeFileName("\u0000")).isNull();
  }

  @Test
  void aLongNameIsCutTo255Characters() {
    assertThat(ImportBatchService.safeFileName("x".repeat(300) + ".csv")).hasSize(255);
  }
}
