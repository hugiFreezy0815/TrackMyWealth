package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.SpreadsheetCell;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * #273: reads the first sheet of a spreadsheet import file - an {@code .xlsx} workbook or the older
 * binary {@code .xls} - into rows of cells, locally, through Apache POI. Nothing is evaluated: a
 * formula cell is read as the result the file has cached, so no formula, external link or macro is
 * ever run.
 *
 * <p>Bounded as the PDF reader is ({@link PdfImportReaderService}): an encrypted workbook is
 * refused; an {@code .xlsx} archive entry may inflate to at most {@value #MAX_ENTRY_BYTES} bytes
 * and at most {@code 1 / }{@value #MIN_INFLATE_RATIO} times its compressed size (a zip bomb); the
 * sheet may have at most {@value #MAX_ROWS} rows of {@value #MAX_COLUMNS} cells of {@value
 * #MAX_CELL_LENGTH} characters. The whole workbook is held in memory while it is read, so at most
 * {@value #MAX_CONCURRENT_READS} are read at once; a further one waits up to {@value
 * #READ_WAIT_SECONDS} s for a turn, then is a 503 {@code IMPORT_SPREADSHEET_BUSY}.
 */
@Service
public class SpreadsheetImportReaderService {

  static final int MAX_ROWS = 5_000;
  static final int MAX_COLUMNS = 100;
  static final int MAX_CELL_LENGTH = 1_000;
  static final int MAX_CONCURRENT_READS = 2;
  static final long READ_WAIT_SECONDS = 10;
  static final long BUSY_RETRY_AFTER_SECONDS = 5;
  static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
  static final double MIN_INFLATE_RATIO = 0.01;
  private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
  private static final byte[] OLE2_MAGIC = {
    (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
  };
  // Every OOXML package names its parts in this entry; a CAMT ZIP archive (#350) has none.
  private static final String OOXML_CONTENT_TYPES = "[Content_Types].xml";
  private static final String ZIP_BOMB = "Zip bomb";

  static {
    // POI's own zip bomb guard, process-wide: nothing else here opens OOXML files.
    ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
    ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
  }

  private final Semaphore reads;
  private final long readWaitMillis;

  @Autowired
  public SpreadsheetImportReaderService() {
    this(new Semaphore(MAX_CONCURRENT_READS), TimeUnit.SECONDS.toMillis(READ_WAIT_SECONDS));
  }

  // Tests hold the turns themselves and shorten the wait for one.
  SpreadsheetImportReaderService(Semaphore reads, long readWaitMillis) {
    this.reads = reads;
    this.readWaitMillis = readWaitMillis;
  }

  /**
   * Whether {@code content} is a workbook: a binary {@code .xls} (an OLE2 compound file) or an
   * {@code .xlsx} (a ZIP archive of an OOXML package). Checked before a ZIP archive is taken for a
   * CAMT statement.
   */
  public static boolean isWorkbook(byte[] content) {
    if (startsWith(content, OLE2_MAGIC)) {
      return true;
    }
    return startsWith(content, ZIP_MAGIC) && contains(content, OOXML_CONTENT_TYPES);
  }

  /**
   * The first sheet's rows, indexed by row number from 0 (a row the sheet leaves out is empty),
   * each row's cells by column from 0 with trailing blank cells dropped.
   *
   * @throws ImportFileRejectedException 422 {@code IMPORT_FILE_MALFORMED} for a file that is no
   *     readable workbook (damaged, encrypted, or a format POI does not read), {@code
   *     IMPORT_SPREADSHEET_LIMIT_EXCEEDED} past this class's limits
   * @throws ApiException 503 {@code IMPORT_SPREADSHEET_BUSY} when no turn comes free in time
   */
  public List<List<SpreadsheetCell>> readRows(byte[] content) {
    if (!isWorkbook(content)) {
      throw malformed("The file is no .xlsx or .xls workbook.", null);
    }
    acquireRead();
    try (Workbook workbook = open(content)) {
      if (workbook.getNumberOfSheets() == 0) {
        throw malformed("The workbook has no sheet.", null);
      }
      return rowsOf(workbook.getSheetAt(0));
    } catch (IOException e) {
      // Closing an in-memory workbook writes nothing back; a failure there is a damaged file.
      throw malformed("The workbook cannot be read.", e);
    } finally {
      reads.release();
    }
  }

  // POI reports a damaged file with its own unchecked exceptions (a broken record, a missing OOXML
  // part), and a zip bomb as an IOException naming ZipSecureFile's limits.
  private static Workbook open(byte[] content) {
    try {
      return WorkbookFactory.create(new ByteArrayInputStream(content));
    } catch (EncryptedDocumentException e) {
      throw malformed("The workbook is encrypted.", e);
    } catch (IOException e) {
      if (String.valueOf(e.getMessage()).contains(ZIP_BOMB)) {
        throw limitExceeded("An entry of the workbook inflates too much.", e);
      }
      throw malformed("The workbook cannot be read.", e);
    } catch (RuntimeException e) {
      throw malformed("The workbook cannot be read.", e);
    }
  }

  private static List<List<SpreadsheetCell>> rowsOf(Sheet sheet) {
    int last = sheet.getLastRowNum();
    if (last >= MAX_ROWS) {
      throw limitExceeded("A sheet may have at most " + MAX_ROWS + " rows.");
    }
    List<List<SpreadsheetCell>> rows = new ArrayList<>(Math.max(last + 1, 0));
    for (int index = 0; index <= last; index++) {
      Row row = sheet.getRow(index);
      rows.add(row == null ? List.of() : cellsOf(row));
    }
    return rows;
  }

  private static List<SpreadsheetCell> cellsOf(Row row) {
    int width = row.getLastCellNum();
    if (width > MAX_COLUMNS) {
      throw limitExceeded("A row may have at most " + MAX_COLUMNS + " cells.");
    }
    List<SpreadsheetCell> cells = new ArrayList<>(Math.max(width, 0));
    for (int column = 0; column < width; column++) {
      Cell cell = row.getCell(column);
      cells.add(cell == null ? SpreadsheetCell.BLANK : cellOf(cell));
    }
    while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
      cells.remove(cells.size() - 1);
    }
    return List.copyOf(cells);
  }

  private static SpreadsheetCell cellOf(Cell cell) {
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    if (type == CellType.NUMERIC) {
      if (DateUtil.isCellDateFormatted(cell)) {
        return new SpreadsheetCell(
            cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
            null);
      }
      double value = cell.getNumericCellValue();
      if (!Double.isFinite(value)) {
        return SpreadsheetCell.BLANK;
      }
      // The shortest decimal that is this double, as a spreadsheet displays it.
      BigDecimal number = BigDecimal.valueOf(value);
      return new SpreadsheetCell(number.toPlainString(), number);
    }
    if (type == CellType.STRING) {
      String text = cell.getStringCellValue();
      if (text.length() > MAX_CELL_LENGTH) {
        throw limitExceeded("A cell may hold at most " + MAX_CELL_LENGTH + " characters.");
      }
      return new SpreadsheetCell(text, null);
    }
    if (type == CellType.BOOLEAN) {
      return new SpreadsheetCell(String.valueOf(cell.getBooleanCellValue()), null);
    }
    // BLANK, ERROR, or a formula without a cached result.
    return SpreadsheetCell.BLANK;
  }

  private void acquireRead() {
    boolean acquired;
    try {
      acquired = reads.tryAcquire(readWaitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      throw new ApiException(
              HttpStatus.SERVICE_UNAVAILABLE,
              ApiErrorCode.IMPORT_SPREADSHEET_BUSY,
              "The server is reading other spreadsheets. Try again in a moment.")
          .withRetryAfter(BUSY_RETRY_AFTER_SECONDS);
    }
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    return content.length >= prefix.length
        && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
  }

  // Bytes as ISO-8859-1 characters, one each: a plain search for an ASCII name in a binary file.
  private static boolean contains(byte[] content, String part) {
    return new String(content, StandardCharsets.ISO_8859_1).contains(part);
  }

  private static ImportFileRejectedException malformed(String detail, Throwable cause) {
    return cause == null
        ? new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_MALFORMED, detail)
        : new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_MALFORMED, detail, cause);
  }

  private static ImportFileRejectedException limitExceeded(String detail) {
    return new ImportFileRejectedException(ApiErrorCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED, detail);
  }

  private static ImportFileRejectedException limitExceeded(String detail, Throwable cause) {
    return new ImportFileRejectedException(
        ApiErrorCode.IMPORT_SPREADSHEET_LIMIT_EXCEEDED, detail, cause);
  }
}
