package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.SpreadsheetCell;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.testsupport.CamtFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** #273: the bounded workbook reader behind the spreadsheet holdings templates. */
class SpreadsheetImportReaderServiceTest {

  private final SpreadsheetImportReaderService reader = new SpreadsheetImportReaderService();

  @Test
  void anXlsxAndAnXlsAreWorkbooksButACamtArchiveOrATextFileIsNot() throws Exception {
    assertThat(
            SpreadsheetImportReaderService.isWorkbook(
                ShippedTemplateFixtures.postfinancePositionsWorkbook()))
        .isTrue();
    assertThat(
            SpreadsheetImportReaderService.isWorkbook(
                ShippedTemplateFixtures.postfinanceCashWorkbook()))
        .isTrue();
    assertThat(
            SpreadsheetImportReaderService.isWorkbook(
                CamtFixtures.zip("<Document/>".getBytes(StandardCharsets.UTF_8))))
        .isFalse();
    assertThat(
            SpreadsheetImportReaderService.isWorkbook(
                "Datum;Betrag\n".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
  }

  @Test
  void cellsAreReadAsTextNumbersAndDatesWithTrailingBlanksDropped() throws Exception {
    byte[] content =
        workbook(
            sheet -> {
              Row row = sheet.createRow(0);
              row.createCell(0).setCellValue("Symbol");
              row.createCell(1).setCellValue(0.1 + 0.2);
              row.createCell(3).setCellValue(LocalDate.of(2031, 12, 31));
              row.getCell(3).setCellStyle(dateStyle(sheet.getWorkbook()));
              row.createCell(5).setCellValue("");
              // Row 1 is left out of the sheet; row 2 holds a formula's cached result.
              Row formula = sheet.createRow(2);
              formula.createCell(0).setCellFormula("1+1");
              sheet.getWorkbook().getCreationHelper().createFormulaEvaluator().evaluateAll();
            });

    List<List<SpreadsheetCell>> rows = reader.readRows(content);

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0))
        .extracting(SpreadsheetCell::text)
        .containsExactly("Symbol", "0.30000000000000004", "", "2031-12-31");
    assertThat(rows.get(0).get(1).number())
        .isEqualByComparingTo(new BigDecimal("0.30000000000000004"));
    assertThat(rows.get(0).get(3).number()).isNull();
    assertThat(rows.get(1)).isEmpty();
    assertThat(rows.get(2).get(0).number()).isEqualByComparingTo("2");
  }

  @Test
  void aSheetPastTheLimitsIsRefused() throws Exception {
    byte[] tooLong =
        workbook(sheet -> sheet.createRow(SpreadsheetImportReaderService.MAX_ROWS).createCell(0));
    assertCode(tooLong, ApiErrorCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED);
    byte[] tooWide =
        workbook(
            sheet ->
                sheet
                    .createRow(0)
                    .createCell(SpreadsheetImportReaderService.MAX_COLUMNS)
                    .setCellValue("x"));
    assertCode(tooWide, ApiErrorCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED);
    byte[] longText =
        workbook(
            sheet ->
                sheet
                    .createRow(0)
                    .createCell(0)
                    .setCellValue("x".repeat(SpreadsheetImportReaderService.MAX_CELL_LENGTH + 1)));
    assertCode(longText, ApiErrorCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED);
  }

  @Test
  void aDamagedWorkbookOrAnotherFileIsMalformed() throws Exception {
    byte[] workbook = ShippedTemplateFixtures.postfinancePositionsWorkbook();
    byte[] truncated = Arrays.copyOf(workbook, workbook.length / 2);
    assertCode(truncated, ApiErrorCode.IMPORT_FILE_MALFORMED);
    assertCode("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), ApiErrorCode.IMPORT_FILE_MALFORMED);
  }

  @Test
  void aReadWaitsForATurnAndIsA503WhenNoneComesFree() throws Exception {
    Semaphore none = new Semaphore(0);
    SpreadsheetImportReaderService busy = new SpreadsheetImportReaderService(none, 1);
    byte[] content = ShippedTemplateFixtures.postfinanceCashWorkbook();

    assertThatThrownBy(() -> busy.readRows(content))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_SPREADSHEET_BUSY));
    // A turn taken is given back, whatever the read's outcome.
    Semaphore one = new Semaphore(1);
    SpreadsheetImportReaderService reading = new SpreadsheetImportReaderService(one, 1);
    reading.readRows(content);
    assertRefused(reading, "not a workbook".getBytes(StandardCharsets.US_ASCII));
    assertThat(one.availablePermits()).isEqualTo(1);
  }

  private void assertCode(byte[] content, String code) {
    assertThatThrownBy(() -> reader.readRows(content))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
  }

  private static void assertRefused(SpreadsheetImportReaderService reader, byte[] content) {
    assertThatThrownBy(() -> reader.readRows(content)).isInstanceOf(ApiException.class);
  }

  private static CellStyle dateStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
    return style;
  }

  private static byte[] workbook(SheetWriter writer) throws IOException {
    try (Workbook workbook = new XSSFWorkbook()) {
      writer.write(workbook.createSheet("Sheet1"));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      workbook.write(output);
      return output.toByteArray();
    }
  }

  @FunctionalInterface
  private interface SheetWriter {
    void write(Sheet sheet);
  }
}
