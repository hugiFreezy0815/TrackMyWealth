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
import com.trackmywealth.backend.pdf.PageFurniture;
import com.trackmywealth.backend.pdf.PdfBooking;
import com.trackmywealth.backend.pdf.PdfColumns;
import com.trackmywealth.backend.pdf.PdfLimitException;
import com.trackmywealth.backend.pdf.PdfReadBudget;
import com.trackmywealth.backend.pdf.PdfStreamBudget;
import com.trackmywealth.backend.pdf.PdfTextLine;
import com.trackmywealth.backend.validation.Re2Patterns;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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
 * showing at most {@value #MAX_TEXT} characters (counted one by one as they are shown), and for OCR
 * pages of at most {@value #MAX_OCR_PAGE_POINTS} points a side, rendered one at a time in grey,
 * each inline image checked before it is decoded.
 *
 * <p>Those limits bound one read; at most {@value #MAX_CONCURRENT_READS} run at a time, so they
 * bound the heap all uploads together can take (a PDF loaded, one stream decoded, its text or one
 * rendered page: tens of megabytes each). A further upload waits up to {@value #READ_WAIT_SECONDS}
 * s for a turn, then is a 503 {@code IMPORT_PDF_BUSY}. The turns are counted per server process:
 * every instance of the backend reads its own {@value #MAX_CONCURRENT_READS} at a time.
 *
 * <p>An OCR read gives its turn back once its pages are checked: recognition takes up to minutes,
 * and {@link LocalOcrService} bounds it with slots of its own. Its document stays loaded only while
 * it holds one of those slots (a further one is refused at once), so at most {@value
 * #MAX_CONCURRENT_READS} reads plus {@value LocalOcrService#MAX_CONCURRENT} recognitions hold a
 * document at a time (fifth PR #267 review).
 */
@Service
public class PdfImportReaderService {

  static final int MAX_PAGES = 20;
  // The longest sample of the import source analysis holds about 20,000 characters, its densest
  // page about 5,400: twenty such pages fit. Each character is an object until its page is read.
  static final int MAX_TEXT = 200_000;
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
  // The names of the layout's patterns, as a row error names the one that also found a line.
  private static final String SECTION_PATTERN = "sectionPattern";
  private static final String BALANCE_LINE_PATTERN = "balanceLinePattern";
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private final LocalOcrService ocr;
  private final Semaphore reads;
  private final long readWaitMillis;

  @Autowired
  public PdfImportReaderService(LocalOcrService ocr) {
    this(ocr, new Semaphore(MAX_CONCURRENT_READS), TimeUnit.SECONDS.toMillis(READ_WAIT_SECONDS));
  }

  // Tests hold the turns themselves and shorten the wait for one.
  PdfImportReaderService(LocalOcrService ocr, Semaphore reads, long readWaitMillis) {
    this.ocr = ocr;
    this.reads = reads;
    this.readWaitMillis = readWaitMillis;
  }

  /**
   * Every booking line of {@code content} (a line on which the layout's record-start pattern finds
   * a match), in document order, after the template's skipped lines, with the cells, continuation
   * lines, section and stated balance its layout reads (see {@link ImportPdfLayout}).
   *
   * @throws ApiException an {@code IMPORT_*} file-level code when the file is not a readable PDF of
   *     this template's layout, or has no booking line
   */
  public List<ImportPdfBookingLine> readBookingLines(
      byte[] content, ImportTemplateDefinition template) {
    ImportPdfLayout layout = template.pdfLayout();
    List<PdfTextLine> lines = readLines(content, template.isOcr());
    if (!hasMarker(joined(lines), layout)) {
      throw mismatch("The PDF does not contain this template's document marker.");
    }
    List<PdfBooking> walked =
        walk(
            lines,
            template.preambleRowCount(),
            lines.size() - template.trailingSummaryRowCount(),
            layout);
    if (walked.isEmpty()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS, "The statement has no booking lines.");
    }
    Pattern row = Re2Patterns.compile(layout.rowPattern());
    return walked.stream().map(booking -> bookingLine(booking, layout, row)).toList();
  }

  // The bookings of lines from (inclusive) to (exclusive), line by line: a section's start, a
  // header line, a balance line, a booking line, page furniture, then a line continuing the
  // booking before it, unless the continuation end pattern finds it or the booking has as many
  // continuation lines as one may have. Each kind is never one of the later ones. A balance line
  // below a booking of the same section is also kept with that booking, so the parser can check it
  // (PR #281 review).
  //
  // A section's start or a balance line that the record-start pattern finds too is also kept, as
  // a line that is no booking for sure, so the parser reports it: read as the other kind, a booking
  // a too-broad pattern takes would otherwise vanish without a trace. A balance line is exempt when
  // the layout checks a balance column: the balance the next booking or balance line of its section
  // states then catches a booking lost that way (PR #281 review).
  //
  // A balance line pauses the booking above it rather than ending it: a carry-forward line at the
  // top of the next page carries a booking split across the page over to there, so its
  // continuation lines below still continue it (PR #281 review).
  //
  // A header line is found before the first section too, so one table header above all sections
  // sets their columns. A section marker of the section's own value at the top of a later page
  // (no booking on that page before it) repeats the section's title there: the section goes on,
  // its running balance and a booking carried over from the page before with it. A balance line
  // between such a marker and the next booking only states the balance that booking starts from,
  // as at a section's start, since it may also open a new section of the same value (PR #281
  // review).
  private static List<PdfBooking> walk(
      List<PdfTextLine> lines, int from, int to, ImportPdfLayout layout) {
    Pattern recordStart = Re2Patterns.compile(layout.recordStartPattern());
    Pattern section = optionalPattern(layout.sectionPattern());
    Pattern balance = optionalPattern(layout.balanceLinePattern());
    Pattern continuationEnd = optionalPattern(layout.continuationEndPattern());
    int continuationLabel = labelIndex(layout, layout.continuationColumn());
    Set<Integer> furniture = PageFurniture.indexes(lines);
    List<PdfBooking> bookings = new ArrayList<>();
    // The last booking, open while lines may still continue it; null before the first.
    PdfBooking current = null;
    PdfColumns columns = null;
    String sectionValue = null;
    boolean sectionStart = false;
    // Whether a repeated section marker came since the last booking.
    boolean sectionRepeated = false;
    Optional<String> statedBalance = Optional.empty();
    // The pages of the last header line, section marker and booking; 0 before one.
    int headerPage = 0;
    int sectionPage = 0;
    int bookingPage = 0;
    for (int i = Math.max(0, from); i < Math.min(to, lines.size()); i++) {
      PdfTextLine line = lines.get(i);
      String text = line.text().strip();
      if (text.isEmpty()) {
        continue;
      }
      Matcher sectionMatch = section == null ? null : section.matcher(text);
      if (sectionMatch != null && sectionMatch.find()) {
        if (recordStart.matcher(text).find()) {
          bookings.add(PdfBooking.ambiguous(line, SECTION_PATTERN));
        }
        String value = firstGroup(sectionMatch).orElse("");
        if (value.equals(sectionValue) && line.page() > sectionPage && line.page() > bookingPage) {
          sectionRepeated = true;
        } else {
          sectionValue = value;
          sectionStart = true;
          statedBalance = Optional.empty();
          end(current);
        }
        sectionPage = line.page();
        continue;
      }
      Optional<PdfColumns> header =
          layout.hasHeaderLabels() ? PdfColumns.of(line, layout.headerLabels()) : Optional.empty();
      if (header.isPresent()) {
        columns = header.get();
        // The table header repeated at the top of the next page does not end a booking that
        // continues there: its cells keep their own page's columns, its continuation lines are
        // placed by this page's. Any other header line - on the booking's own page, or a second
        // table on the next - ends it (PR #281 review).
        if (current != null
            && (current.line().page() == line.page() || headerPage == line.page())) {
          end(current);
        }
        headerPage = line.page();
        continue;
      }
      if (section != null && sectionValue == null) {
        // Before the first section: a summary, no booking. A line the record-start pattern finds
        // below a table header line, and that states no balance, is reported: it is a booking of a
        // section whose title the section pattern missed, which would otherwise vanish without a
        // trace. A dated line of the summary above any table header stays no row (PR #281 review).
        if (columns != null
            && recordStart.matcher(text).find()
            && (balance == null || !balance.matcher(text).find())) {
          bookings.add(PdfBooking.beforeFirstSection(line));
        }
        continue;
      }
      Matcher balanceMatch = balance == null ? null : balance.matcher(text);
      if (balanceMatch != null && balanceMatch.find()) {
        if (layout.balanceColumn() == null && recordStart.matcher(text).find()) {
          bookings.add(PdfBooking.ambiguous(line, BALANCE_LINE_PATTERN));
        }
        Optional<String> stated = firstGroup(balanceMatch);
        if (stated.isPresent()) {
          statedBalance = stated;
          // Below the section's last booking; at a section's start, the previous section's.
          if (current != null && !sectionStart && !sectionRepeated) {
            current.addBalanceAfter(line, stated.get());
          }
        }
        if (current != null) {
          current.balanceLine(line);
        }
      } else if (recordStart.matcher(text).find()) {
        end(current);
        current =
            new PdfBooking(line, columns, sectionValue, sectionStart, statedBalance.orElse(null));
        bookings.add(current);
        sectionStart = false;
        sectionRepeated = false;
        statedBalance = Optional.empty();
        bookingPage = line.page();
      } else if (!furniture.contains(i) && current != null) {
        current.resumeFor(line);
        if (continuationEnd != null && continuationEnd.matcher(text).find()
            || !continues(line, current, columns, layout, continuationLabel)) {
          current.end();
        } else if (current.continuationCount() >= ImportPdfLayout.MAX_CONTINUATION_LINES) {
          current.endOverflowing();
        } else {
          current.continueWith(line);
        }
      }
    }
    return bookings;
  }

  // Whether line continues booking: the layout has a continuation column and, when that is a
  // header label and the line's positions are known, the line starts in that column.
  private static boolean continues(
      PdfTextLine line,
      PdfBooking booking,
      PdfColumns columns,
      ImportPdfLayout layout,
      int continuationLabel) {
    if (layout.continuationColumn() == null || !booking.isOpen()) {
      return false;
    }
    if (continuationLabel < 0 || columns == null || line.words().isEmpty()) {
      return true;
    }
    return columns.columnOf(line.words().get(0)) == continuationLabel;
  }

  private static void end(PdfBooking booking) {
    if (booking != null) {
      booking.end();
    }
  }

  // The cells of a walked booking, in the order of ImportPdfLayout#cellNames(): the row pattern's
  // groups, the words under each header label, the section's value, with the continuation lines
  // appended to the continuation column's cell. A line the row pattern misses keeps the whole
  // line as its one cell.
  private static ImportPdfBookingLine bookingLine(
      PdfBooking booking, ImportPdfLayout layout, Pattern row) {
    String line = booking.line().text().strip();
    if (booking.alsoFoundBy().isPresent()) {
      return ImportPdfBookingLine.ambiguous(line, booking.alsoFoundBy().get());
    }
    if (booking.beforeFirstSection()) {
      return ImportPdfBookingLine.beforeFirstSection(line);
    }
    Matcher match = row.matcher(line);
    boolean matched = match.matches();
    List<String> cells = new ArrayList<>();
    if (matched) {
      for (int group = 1; group <= match.groupCount(); group++) {
        String cell = match.group(group);
        cells.add(cell == null ? "" : cell);
      }
      if (layout.hasHeaderLabels()) {
        cells.addAll(
            booking
                .columns()
                .map(columns -> columns.cells(booking.line().words()))
                .orElseGet(() -> Collections.nCopies(layout.headerLabels().size(), "")));
      }
      if (layout.sectionColumn() != null) {
        cells.add(booking.section().orElse(""));
      }
      appendContinuation(cells, booking, layout);
    } else {
      cells.add(line);
    }
    return ImportPdfBookingLine.builder(line, cells, matched)
        .withContinuation(booking.continuation().stream().map(next -> next.text().strip()).toList())
        .withSectionStart(booking.sectionStart())
        .withStatedBalance(booking.statedBalance().orElse(null))
        .withBalancesAfter(
            booking.balancesAfter().stream()
                .map(below -> new ImportPdfBookingLine.BalanceLine(below.text(), below.balance()))
                .toList())
        .withHeaded(booking.columns().isPresent())
        .withContinuationOverflow(booking.overflowing())
        .build();
  }

  // Each continuation line goes to the continuation column whole: its text may run on under the
  // next column (a long IBAN under the reference), and where it starts already decided it is one.
  private static void appendContinuation(
      List<String> cells, PdfBooking booking, ImportPdfLayout layout) {
    int cell = cellIndex(layout, layout.continuationColumn());
    if (cell < 0) {
      return;
    }
    StringBuilder value = new StringBuilder(cells.get(cell));
    for (PdfTextLine next : booking.continuation()) {
      String text = next.text().strip();
      if (!value.isEmpty()) {
        value.append(' ');
      }
      value.append(text);
    }
    cells.set(cell, value.toString());
  }

  // Where name is among the layout's cells, compared as the column mapping compares header cells;
  // -1 for none.
  private static int cellIndex(ImportPdfLayout layout, String name) {
    return indexOfName(layout.cellNames(), name);
  }

  // Where name is among the header labels; -1 for none (also for a row pattern column).
  private static int labelIndex(ImportPdfLayout layout, String name) {
    return indexOfName(layout.headerLabels(), name);
  }

  private static int indexOfName(List<String> names, String name) {
    if (name == null) {
      return -1;
    }
    for (int i = 0; i < names.size(); i++) {
      if (names.get(i) != null && names.get(i).strip().equalsIgnoreCase(name.strip())) {
        return i;
      }
    }
    return -1;
  }

  private static Pattern optionalPattern(String pattern) {
    return pattern == null ? null : Re2Patterns.compile(pattern);
  }

  private static Optional<String> firstGroup(Matcher match) {
    return match.groupCount() == 0 ? Optional.empty() : Optional.ofNullable(match.group(1));
  }

  /**
   * The text layer of {@code content}, within this class's limits - read once per upload for
   * detection, which then tries every PDF template on it through {@link #isLayoutOf}.
   *
   * @throws ApiException an {@code IMPORT_*} file-level code when it is not a readable PDF, 503
   *     {@code IMPORT_PDF_BUSY} when no turn to read it comes free in time
   */
  public String readTextLayer(byte[] content) {
    return joined(readLines(content, false));
  }

  /**
   * The text local OCR recognizes on {@code content}'s rendered pages, within this class's limits.
   *
   * @throws ApiException as {@link #readTextLayer}, and as {@link LocalOcrService#recognizePages}
   */
  public String readScannedText(byte[] content) {
    return joined(readLines(content, true));
  }

  // The lines one below the other, as read.
  private static String joined(List<PdfTextLine> lines) {
    return String.join("\n", lines.stream().map(PdfTextLine::text).toList());
  }

  private List<PdfTextLine> readLines(byte[] content, boolean ocr) {
    if (!isPdf(content)) {
      throw mismatch("The file is not a PDF, but this template reads PDF statements.");
    }
    acquireRead();
    AtomicBoolean turn = new AtomicBoolean(true);
    try {
      return readLinesHoldingTurn(content, ocr, turn);
    } finally {
      releaseRead(turn);
    }
  }

  // Gives the turn back once, whether recognition already did or not.
  private void releaseRead(AtomicBoolean turn) {
    if (turn.getAndSet(false)) {
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

  private List<PdfTextLine> readLinesHoldingTurn(byte[] content, boolean ocr, AtomicBoolean turn) {
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
              MAX_TEXT,
              Duration.ofSeconds(
                  ocr ? LocalOcrService.DOCUMENT_TIMEOUT_SECONDS : READ_SECONDS_TEXT_LAYER));
      return ocr ? recognize(document, budget, turn) : layerLines(document, budget);
    } catch (PdfLimitException e) {
      throw overLimit(e);
    } catch (UncheckedIOException e) {
      throw e.getCause() instanceof PdfLimitException limit
          ? overLimit(limit)
          : malformed("The file is not a readable PDF.", e);
    } catch (IOException e) {
      // Also an InvalidPasswordException (a PDF that needs a password to open).
      throw malformed("The file is not a readable PDF.", e);
    }
  }

  /**
   * Whether {@code text} is a statement of {@code layout}: it holds the document marker and at
   * least one line the record-start pattern finds. Detection's test - a weaker signal than a CSV
   * file's header row; {@link #hasHeaderLine} is the stronger one.
   */
  public static boolean isLayoutOf(String text, ImportPdfLayout layout) {
    if (!hasMarker(text, layout)) {
      return false;
    }
    Pattern recordStart = Re2Patterns.compile(layout.recordStartPattern());
    return text.lines().anyMatch(line -> recordStart.matcher(line.strip()).find());
  }

  /**
   * Whether {@code text} holds the header line of {@code layout}'s booking table: a line holding
   * every header label in order; false for a layout without header labels. For a statement that
   * {@link #isLayoutOf} accepts, detection's equivalent of a CSV file's exact header fingerprint. A
   * statement holding the header line but no booking line is no match at all (PR #281 review), so
   * detection asks {@link #isLayoutOf} first, and each statement's text is scanned once for each.
   */
  public static boolean hasHeaderLine(String text, ImportPdfLayout layout) {
    return layout.hasHeaderLabels()
        && text.lines().anyMatch(line -> PdfColumns.isHeaderLine(line, layout.headerLabels()));
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

  // Page by page, so a document with too much text, or too much to draw, stops at the limit.
  private List<PdfTextLine> layerLines(PDDocument document, PdfReadBudget budget)
      throws IOException {
    BoundedTextStripper stripper = new BoundedTextStripper(budget);
    stripper.setSortByPosition(true);
    List<PdfTextLine> lines = new ArrayList<>();
    long length = 0;
    boolean blank = true;
    for (int page = 1; page <= document.getNumberOfPages(); page++) {
      List<PdfTextLine> pageLines = stripper.readLines(document, page);
      budget.requireWithinLimits();
      for (PdfTextLine line : pageLines) {
        length += line.text().length() + 1;
        blank &= line.text().isBlank();
      }
      if (length > MAX_TEXT) {
        throw overLimit(new PdfLimitException("The PDF holds more text than one statement may."));
      }
      lines.addAll(pageLines);
    }
    if (blank) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_PDF_NO_TEXT,
          ocr.isEnabled()
              ? "The PDF has no text layer (a scanned document). Use a template that reads it by"
                  + " OCR."
              : "The PDF has no text layer (a scanned document), and this server does not read"
                  + " scanned statements.");
    }
    return lines;
  }

  // Every page is checked before the first is rendered, and the read's turn is given back;
  // LocalOcrService then renders one page at a time, and only once it holds a recognition slot.
  // OCR gives text, no positions: every line is page 1's, without words or place on the page.
  private List<PdfTextLine> recognize(
      PDDocument document, PdfReadBudget budget, AtomicBoolean turn) {
    for (int i = 0; i < document.getNumberOfPages(); i++) {
      PDRectangle page = document.getPage(i).getCropBox();
      if (page.getWidth() > MAX_OCR_PAGE_POINTS || page.getHeight() > MAX_OCR_PAGE_POINTS) {
        throw overLimit(new PdfLimitException("A page is too large to be read by OCR."));
      }
    }
    releaseRead(turn);
    PDFRenderer renderer = new BoundedPdfRenderer(document, budget);
    String text =
        ocr.recognizePages(document.getNumberOfPages(), page -> render(renderer, budget, page));
    return text.lines().map(line -> new PdfTextLine(1, line, List.of())).toList();
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

  // Readable, but more than one statement may hold: a shorter export helps, unlike for a damaged
  // file. The detail says which limit, never anything of the document.
  private static ImportFileRejectedException overLimit(PdfLimitException limit) {
    return new ImportFileRejectedException(
        ApiErrorCode.IMPORT_PDF_LIMIT_EXCEEDED, limit.getMessage(), limit);
  }

  private static ImportFileRejectedException malformed(String detail, Throwable cause) {
    return new ImportFileRejectedException(ApiErrorCode.IMPORT_FILE_MALFORMED, detail, cause);
  }
}
