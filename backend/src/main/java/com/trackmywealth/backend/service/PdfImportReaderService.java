package com.trackmywealth.backend.service;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.pdf.BoundedPdfParser;
import com.trackmywealth.backend.pdf.BoundedPdfRenderer;
import com.trackmywealth.backend.pdf.BoundedTextStripper;
import com.trackmywealth.backend.pdf.PdfLimitException;
import com.trackmywealth.backend.pdf.PdfReadBudget;
import com.trackmywealth.backend.pdf.PdfStreamBudget;
import com.trackmywealth.backend.validation.Re2Patterns;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * #268: turns a PDF statement into booking lines through its template's {@link ImportPdfLayout}, so
 * {@link ImportFileParserService} parses them exactly like the records of a CSV file. The text
 * comes from the PDF's text layer ({@code PDF_TEXT}) or, for a scanned PDF, from local OCR ({@code
 * PDF_OCR}). Everything happens in memory; the file and its text are never stored or logged.
 *
 * <p>Bounded: at most {@value #MAX_PAGES} pages and no encrypted document. {@link BoundedPdfParser}
 * checks every stream against a {@link PdfStreamBudget} as it parses it, before anything decodes
 * it. Reading the pages runs at most {@value #MAX_OPERATIONS} drawing operations within {@value
 * #READ_SECONDS_TEXT_LAYER} s ({@link PdfReadBudget}; a real statement page runs a few thousand),
 * then {@value #MAX_TEXT} characters of text (read page by page, stopping at the limit), and for
 * OCR pages of at most {@value #MAX_OCR_PAGE_POINTS} points a side, rendered one at a time in grey,
 * each inline image checked before it is decoded.
 *
 * <p>Those limits bound one read; at most {@value #MAX_CONCURRENT_READS} run at a time, so they
 * bound the heap all uploads together can take (a PDF loaded, one stream decoded, its text or one
 * rendered page: tens of megabytes each). A further upload waits up to {@value #READ_WAIT_SECONDS}
 * s for a turn, then is a 503 {@code IMPORT_PDF_BUSY}. The turns are counted per server process:
 * every instance of the backend reads its own {@value #MAX_CONCURRENT_READS} at a time.
 */
@Service
public class PdfImportReaderService {

  static final int MAX_PAGES = 20;
  static final int MAX_TEXT = 2_000_000;
  static final int MAX_OCR_PAGE_POINTS = 1500;
  static final int MAX_CONCURRENT_READS = 4;
  static final int READ_WAIT_SECONDS = 5;
  // The busiest page of the import source analysis' samples runs about 5,000 operations; twenty
  // such pages fit ten times over. Rendering runs the same operations as reading the text.
  static final long MAX_OPERATIONS = 1_000_000;
  // Far above a real statement (well under a second), so only a pathological file reaches it. OCR
  // keeps its own time limits on top (LocalOcrService).
  static final int READ_SECONDS_TEXT_LAYER = 30;
  // The Retry-After of IMPORT_PDF_BUSY: about as long as the reads ahead take.
  static final long BUSY_RETRY_AFTER_SECONDS = 10;
  private static final int OCR_DPI = 200;
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private final LocalOcrService ocr;
  private final Semaphore reads = new Semaphore(MAX_CONCURRENT_READS);
  private final long readWaitMillis;

  @Autowired
  public PdfImportReaderService(LocalOcrService ocr) {
    this(ocr, TimeUnit.SECONDS.toMillis(READ_WAIT_SECONDS));
  }

  // Tests shorten the wait for a turn.
  PdfImportReaderService(LocalOcrService ocr, long readWaitMillis) {
    this.ocr = ocr;
    this.readWaitMillis = readWaitMillis;
  }

  /**
   * Every booking line of {@code content} (a line on which the layout's record-start pattern finds
   * a match), in document order, after the template's skipped lines.
   *
   * @throws ApiException an {@code IMPORT_*} file-level code when the file is not a readable PDF of
   *     this template's layout, or has no booking line
   */
  public List<ImportPdfBookingLine> readBookingLines(
      byte[] content, ImportTemplateDefinition template) {
    ImportPdfLayout layout = template.pdfLayout();
    String text = readText(content, template.isOcr());
    if (!hasMarker(text, layout)) {
      throw mismatch("The PDF does not contain this template's document marker.");
    }
    List<String> lines = text.lines().toList();
    Pattern recordStart = Re2Patterns.compile(layout.recordStartPattern());
    Pattern row = Re2Patterns.compile(layout.rowPattern());
    int end = lines.size() - template.trailingSummaryRowCount();
    List<ImportPdfBookingLine> bookings = new ArrayList<>();
    for (int i = template.preambleRowCount(); i < end; i++) {
      String line = lines.get(i).strip();
      if (recordStart.matcher(line).find()) {
        bookings.add(bookingLine(line, row));
      }
    }
    if (bookings.isEmpty()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS, "The statement has no booking lines.");
    }
    return bookings;
  }

  /**
   * The text layer of {@code content}, within this class's limits - read once per upload for
   * detection, which then tries every PDF template on it through {@link #isLayoutOf}.
   *
   * @throws ApiException an {@code IMPORT_*} file-level code when it is not a readable PDF, 503
   *     {@code IMPORT_PDF_BUSY} when no turn to read it comes free in time
   */
  public String readTextLayer(byte[] content) {
    return readText(content, false);
  }

  /**
   * The text local OCR recognizes on {@code content}'s rendered pages, within this class's limits.
   *
   * @throws ApiException as {@link #readTextLayer}, and as {@link LocalOcrService#recognizePages}
   */
  public String readScannedText(byte[] content) {
    return readText(content, true);
  }

  private String readText(byte[] content, boolean ocr) {
    if (!isPdf(content)) {
      throw mismatch("The file is not a PDF, but this template reads PDF statements.");
    }
    acquireRead();
    try {
      return readTextHoldingTurn(content, ocr);
    } finally {
      reads.release();
    }
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
              ApiErrorCode.IMPORT_PDF_BUSY,
              "The server is reading other statements. Try again in a moment.")
          .withRetryAfter(BUSY_RETRY_AFTER_SECONDS);
    }
  }

  private String readTextHoldingTurn(byte[] content, boolean ocr) {
    try (PDDocument document = BoundedPdfParser.load(content, new PdfStreamBudget(ocr))) {
      if (document.isEncrypted()) {
        throw malformed("The PDF is encrypted; export the statement without a password.", null);
      }
      int pages = document.getNumberOfPages();
      if (pages > MAX_PAGES) {
        throw new ImportFileRejectedException(
                ApiErrorCode.IMPORT_FILE_TOO_MANY_PAGES,
                "An import PDF may have at most " + MAX_PAGES + " pages.")
            .withProperty("maxPages", MAX_PAGES);
      }
      // OCR's own time limits bound a scanned document; its clock runs while Tesseract reads too.
      PdfReadBudget budget =
          new PdfReadBudget(
              MAX_OPERATIONS,
              Duration.ofSeconds(
                  ocr ? LocalOcrService.DOCUMENT_TIMEOUT_SECONDS : READ_SECONDS_TEXT_LAYER));
      return ocr ? recognize(document, budget) : layerText(document, budget);
    } catch (PdfLimitException e) {
      throw malformed("The PDF holds more than one statement may.", e);
    } catch (UncheckedIOException e) {
      throw e.getCause() instanceof PdfLimitException limit
          ? malformed("The PDF holds more than one statement may.", limit)
          : malformed("The file is not a readable PDF.", e);
    } catch (IOException e) {
      // Also an InvalidPasswordException (a PDF that needs a password to open).
      throw malformed("The file is not a readable PDF.", e);
    }
  }

  /**
   * Whether {@code text} is a statement of {@code layout}: it holds the document marker and at
   * least one line the record-start pattern finds. Detection's test - a weaker signal than a CSV
   * file's header row, so a PDF template is never an exact header match (#268 adds one).
   */
  public static boolean isLayoutOf(String text, ImportPdfLayout layout) {
    if (!hasMarker(text, layout)) {
      return false;
    }
    Pattern recordStart = Re2Patterns.compile(layout.recordStartPattern());
    return text.lines().anyMatch(line -> recordStart.matcher(line.strip()).find());
  }

  /** Whether {@code content} starts like a PDF file. */
  public static boolean isPdf(byte[] content) {
    if (content.length < PDF_MAGIC.length) {
      return false;
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (content[i] != PDF_MAGIC[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasMarker(String text, ImportPdfLayout layout) {
    return text.contains(layout.documentMarker());
  }

  private static ImportPdfBookingLine bookingLine(String line, Pattern row) {
    Matcher match = row.matcher(line);
    if (!match.matches()) {
      return ImportPdfBookingLine.unmatched(line);
    }
    List<String> cells = new ArrayList<>(match.groupCount());
    for (int group = 1; group <= match.groupCount(); group++) {
      String cell = match.group(group);
      cells.add(cell == null ? "" : cell);
    }
    return new ImportPdfBookingLine(line, cells, true);
  }

  // Page by page, so a document with too much text, or too much to draw, stops at the limit.
  private static String layerText(PDDocument document, PdfReadBudget budget) throws IOException {
    PDFTextStripper stripper = new BoundedTextStripper(budget);
    stripper.setSortByPosition(true);
    StringBuilder text = new StringBuilder();
    for (int page = 1; page <= document.getNumberOfPages(); page++) {
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      text.append(stripper.getText(document));
      budget.requireWithinLimits();
      if (text.length() > MAX_TEXT) {
        throw malformed("The PDF holds more text than one statement may.", null);
      }
    }
    if (text.toString().isBlank()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_PDF_NO_TEXT,
          "The PDF has no text layer (a scanned document). Use a template that reads it by OCR.");
    }
    return text.toString();
  }

  // Every page is checked before the first is rendered; LocalOcrService then renders one page at
  // a time, and only once it holds a recognition slot.
  private String recognize(PDDocument document, PdfReadBudget budget) {
    for (int i = 0; i < document.getNumberOfPages(); i++) {
      PDRectangle page = document.getPage(i).getCropBox();
      if (page.getWidth() > MAX_OCR_PAGE_POINTS || page.getHeight() > MAX_OCR_PAGE_POINTS) {
        throw malformed("A page is too large to be read by OCR.", null);
      }
    }
    PDFRenderer renderer = new BoundedPdfRenderer(document, budget);
    return ocr.recognizePages(document.getNumberOfPages(), page -> render(renderer, budget, page));
  }

  private static BufferedImage render(PDFRenderer renderer, PdfReadBudget budget, int page) {
    try {
      BufferedImage image = renderer.renderImageWithDPI(page, OCR_DPI, ImageType.GRAY);
      budget.requireWithinLimits();
      return image;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ImportFileRejectedException mismatch(String detail) {
    return new ImportFileRejectedException(ApiErrorCode.IMPORT_TEMPLATE_MISMATCH, detail)
        .withProperty("missingColumns", List.of());
  }

  private static ImportFileRejectedException malformed(String detail, Throwable cause) {
    return new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_MALFORMED, detail, cause);
  }
}
