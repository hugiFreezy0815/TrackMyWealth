package com.trackmywealth.backend.service;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.output.ThresholdingOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/**
 * #267: turns a PDF statement into booking lines through its template's {@link ImportPdfLayout}, so
 * {@link ImportFileParserService} parses them exactly like the records of a CSV file. The text
 * comes from the PDF's text layer ({@code PDF_TEXT}) or, for a scanned PDF, from local OCR ({@code
 * PDF_OCR}). Everything happens in memory; the file and its text are never stored or logged.
 *
 * <p>Bounded: at most {@value #MAX_PAGES} pages and no encrypted document. PDFBox decodes a whole
 * stream into memory before it reads it, and a few hundred kilobytes of Flate data can inflate to
 * gigabytes, so every stream is first decoded once into a counter that keeps nothing: at most
 * {@value #MAX_STREAM_BYTES} bytes per stream and {@value #MAX_DECODED_BYTES} in all, and an image
 * of at most {@value #MAX_IMAGE_PIXELS} pixels. Then {@value #MAX_TEXT} characters of text (read
 * page by page, stopping at the limit), and for OCR pages of at most {@value #MAX_OCR_PAGE_POINTS}
 * points a side, rendered one at a time in grey.
 */
@Service
public class PdfImportReaderService {

  static final int MAX_PAGES = 20;
  static final int MAX_TEXT = 2_000_000;
  static final int MAX_OCR_PAGE_POINTS = 1500;
  static final int MAX_STREAM_BYTES = 64 * 1024 * 1024;
  static final long MAX_DECODED_BYTES = 256L * 1024 * 1024;
  static final long MAX_IMAGE_PIXELS = 50_000_000L;
  // Image codecs are decoded to pixels, bounded by MAX_IMAGE_PIXELS; the filters before one count.
  private static final Set<COSName> IMAGE_CODECS =
      Set.of(COSName.DCT_DECODE, COSName.JPX_DECODE, COSName.JBIG2_DECODE, COSName.CCITTFAX_DECODE);
  private static final int OCR_DPI = 200;
  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private final LocalOcrService ocr;

  public PdfImportReaderService(LocalOcrService ocr) {
    this.ocr = ocr;
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
    Pattern recordStart = Pattern.compile(layout.recordStartPattern());
    Pattern row = Pattern.compile(layout.rowPattern());
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
   * The text layer of {@code content}, or with {@code ocr} its recognized text, within this class's
   * limits - read once per upload, e.g. for detection, which then tries every PDF template on it
   * through {@link #isLayoutOf}.
   *
   * @throws ApiException an {@code IMPORT_*} file-level code when it is not a readable PDF
   */
  public String readText(byte[] content, boolean ocr) {
    if (!isPdf(content)) {
      throw mismatch("The file is not a PDF, but this template reads PDF statements.");
    }
    try (PDDocument document = Loader.loadPDF(content)) {
      if (document.isEncrypted()) {
        throw malformed("The PDF is encrypted; export the statement without a password.", null);
      }
      requireBoundedStreams(document);
      int pages = document.getNumberOfPages();
      if (pages > MAX_PAGES) {
        throw new ImportFileRejectedException(
                ApiErrorCode.IMPORT_FILE_TOO_MANY_PAGES,
                "An import PDF may have at most " + MAX_PAGES + " pages.")
            .withProperty("maxPages", MAX_PAGES);
      }
      return ocr ? recognize(document) : layerText(document);
    } catch (IOException | UncheckedIOException e) {
      // Also an InvalidPasswordException (a PDF that needs a password to open) and a stream that
      // decodes to more than the limits allow.
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
    Pattern recordStart = Pattern.compile(layout.recordStartPattern());
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

  // Every stream of the document, before PDFBox decodes any of them into memory to read it. The
  // COSDocument and its streams belong to the PDDocument, which closes them.
  private static void requireBoundedStreams(PDDocument document) throws IOException {
    long total = 0;
    for (COSObjectKey key : List.copyOf(document.getDocument().getXrefTable().keySet())) {
      COSObject object = document.getDocument().getObjectFromPool(key);
      if (object != null && object.getObject() instanceof COSStream) {
        int limit = (int) Math.min(MAX_STREAM_BYTES, MAX_DECODED_BYTES - total);
        total += boundedLength((COSStream) object.getObject(), limit);
      }
    }
  }

  // The bytes the stream's filters produce, up to the first image codec, counted and discarded;
  // an IOException as soon as they pass limit. An image is bounded by its declared pixels instead.
  private static long boundedLength(COSStream stream, int limit) throws IOException {
    if (COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))
        && (long) stream.getInt(COSName.WIDTH, 0) * stream.getInt(COSName.HEIGHT, 0)
            > MAX_IMAGE_PIXELS) {
      throw new IOException("An image of the PDF has more pixels than a statement needs.");
    }
    List<COSName> filters = filterNames(stream.getFilters());
    int stages = 0;
    while (stages < filters.size() && !IMAGE_CODECS.contains(filters.get(stages))) {
      stages++;
    }
    if (stages == 0) {
      return stream.getLength();
    }
    long length = 0;
    byte[] previous = new byte[0];
    for (int stage = 0; stage < stages; stage++) {
      // Only an intermediate stage of a filter chain keeps its output, for the next stage.
      boolean last = stage == stages - 1;
      ByteArrayOutputStream kept = new ByteArrayOutputStream();
      try (InputStream input =
              stage == 0 ? stream.createRawInputStream() : new ByteArrayInputStream(previous);
          ThresholdingOutputStream counter =
              new ThresholdingOutputStream(
                  limit,
                  exceeded -> {
                    throw new IOException("A stream of the PDF decodes to more than allowed.");
                  },
                  exceeded -> last ? NullOutputStream.INSTANCE : kept)) {
        FilterFactory.INSTANCE.getFilter(filters.get(stage)).decode(input, counter, stream, stage);
        length = counter.getByteCount();
      }
      previous = kept.toByteArray();
    }
    return length;
  }

  private static List<COSName> filterNames(COSBase filters) {
    if (filters instanceof COSName name) {
      return List.of(name);
    }
    List<COSName> names = new ArrayList<>();
    if (filters instanceof COSArray array) {
      for (COSBase entry : array) {
        if (entry instanceof COSName name) {
          names.add(name);
        }
      }
    }
    return names;
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

  // Page by page, so a document with too much text stops at the limit instead of after it.
  private static String layerText(PDDocument document) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    StringBuilder text = new StringBuilder();
    for (int page = 1; page <= document.getNumberOfPages(); page++) {
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      text.append(stripper.getText(document));
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
  private String recognize(PDDocument document) {
    for (int i = 0; i < document.getNumberOfPages(); i++) {
      PDRectangle page = document.getPage(i).getCropBox();
      if (page.getWidth() > MAX_OCR_PAGE_POINTS || page.getHeight() > MAX_OCR_PAGE_POINTS) {
        throw malformed("A page is too large to be read by OCR.", null);
      }
    }
    PDFRenderer renderer = new PDFRenderer(document);
    return ocr.recognizePages(document.getNumberOfPages(), page -> render(renderer, page));
  }

  private static BufferedImage render(PDFRenderer renderer, int page) {
    try {
      return renderer.renderImageWithDPI(page, OCR_DPI, ImageType.GRAY);
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
