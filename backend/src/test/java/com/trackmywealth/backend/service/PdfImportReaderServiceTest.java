package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.CanonicalImportRow;
import com.trackmywealth.backend.dto.ImportPdfBookingLine;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportRowErrorValues;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.pdf.PdfLimitException;
import com.trackmywealth.backend.pdf.PdfStreamBudget;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;

/**
 * #268: PDF statements through the template's line layout, without Spring - text layer and OCR,
 * every file-level rejection and the layout rules. The PDFs are generated here with invented
 * values; OCR is a mock, so no Tesseract is needed.
 */
class PdfImportReaderServiceTest {

  private static final String MARKER = "Invented Bank statement";

  private final LocalOcrService ocr = mock(LocalOcrService.class);
  private final PdfImportReaderService reader = new PdfImportReaderService(ocr);
  private final ImportFileParserService parser = new ImportFileParserService(reader);

  @Test
  void textLayerBookingLinesParseThroughTheTemplateRules() throws IOException {
    byte[] pdf =
        pdf(
            MARKER,
            "Date Amount Text",
            "04.01.2031 -1.234,50 Invented expense",
            "05.01.2031 987,65 Invented income",
            "Closing balance 9.999,99");

    List<ParsedImportRow> rows = parser.parse(pdf, template("PDF_TEXT"), null).rows();

    assertThat(rows).allMatch(ParsedImportRow::isParsed);
    assertThat(rows)
        .extracting(ParsedImportRow::canonical)
        .extracting(CanonicalImportRow::bookingDate, CanonicalImportRow::amount)
        .containsExactly(
            tuple(LocalDate.of(2031, 1, 4), new BigDecimal("-1234.50")),
            tuple(LocalDate.of(2031, 1, 5), new BigDecimal("987.65")));
    assertThat(rows.get(0).canonical().description()).isEqualTo("Invented expense");
    assertThat(rows.get(0).rawData()).containsEntry("Amount", "-1.234,50");
    verifyNoInteractions(ocr);
  }

  /** A booking line the row pattern does not match is an error row, never silently dropped. */
  @Test
  void aBookingLineTheRowPatternMissesIsAnErrorRow() throws IOException {
    byte[] pdf = pdf(MARKER, "04.01.2031 -12,50 Invented expense", "05.01.2031 unreadable");

    List<ParsedImportRow> rows = parser.parse(pdf, template("PDF_TEXT"), null).rows();

    assertThat(rows.get(0).isParsed()).isTrue();
    assertThat(rows.get(1).errorCode()).isEqualTo(ImportRowErrorValues.LINE_UNMATCHED);
    assertThat(rows.get(1).errorArgs()).containsEntry("value", "05.01.2031 unreadable");
  }

  @Test
  void aScannedPdfIsReadOnlyThroughLocalOcrOnePageAtATimeInGrey() throws IOException {
    List<BufferedImage> rendered = new ArrayList<>();
    when(ocr.recognizePages(anyInt(), any()))
        .thenAnswer(
            invocation -> {
              IntFunction<BufferedImage> render = invocation.getArgument(1);
              rendered.add(render.apply(0));
              return MARKER + "\n04.01.2031 2.000,00 Invented income\n";
            });

    List<ParsedImportRow> rows = parser.parse(pdf(), template("PDF_OCR"), null).rows();

    assertThat(rows).singleElement().satisfies(r -> assertThat(r.isParsed()).isTrue());
    assertThat(rows.get(0).canonical().amount()).isEqualTo(new BigDecimal("2000.00"));
    verify(ocr).recognizePages(eq(1), any());
    assertThat(rendered)
        .singleElement()
        .satisfies(image -> assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY));
  }

  @Test
  void aFileOfAnotherKindOrLayoutIsAMismatch() throws IOException {
    assertFileError(
        pdf("Other statement", "04.01.2031 1,00 x"), ApiErrorCode.IMPORT_TEMPLATE_MISMATCH);
    assertFileError(
        "date,amount\n2031-01-04,1\n".getBytes(StandardCharsets.UTF_8),
        ApiErrorCode.IMPORT_TEMPLATE_MISMATCH);
  }

  @Test
  void anUnreadablePdfIsMalformed() {
    assertFileError(
        "%PDF-broken".getBytes(StandardCharsets.US_ASCII), ApiErrorCode.IMPORT_FILE_MALFORMED);
  }

  @Test
  void aPdfWithoutTextLayerNeedsAnOcrTemplate() throws IOException {
    assertFileError(pdf(), ApiErrorCode.IMPORT_PDF_NO_TEXT);
  }

  @Test
  void aStatementWithoutBookingLinesHasNoDataRows() throws IOException {
    assertFileError(
        pdf(MARKER, "No bookings in this period"), ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS);
  }

  @Test
  void morePagesThanTheLimitAreRejected() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (int i = 0; i <= PdfImportReaderService.MAX_PAGES; i++) {
        document.addPage(new PDPage());
      }
      document.save(output);

      ApiException error =
          assertFileError(output.toByteArray(), ApiErrorCode.IMPORT_FILE_TOO_MANY_PAGES);
      assertThat(error.getBody().getProperties())
          .containsEntry("maxPages", PdfImportReaderService.MAX_PAGES);
    }
  }

  @Test
  void anOversizedPageIsNotRenderedForOcr() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage(new PDRectangle(2000, 2000)));
      document.save(output);

      assertThatThrownBy(() -> parser.parse(output.toByteArray(), template("PDF_OCR"), null))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_FILE_MALFORMED));
      verifyNoInteractions(ocr);
    }
  }

  /** Detection's test: the marker and at least one booking line, on text read once. */
  @Test
  void aStatementIsOfALayoutWithItsMarkerAndABookingLine() throws IOException {
    String text = reader.readTextLayer(pdf(MARKER, "04.01.2031 1,00 x"));

    assertThat(PdfImportReaderService.isLayoutOf(text, layout())).isTrue();
    assertThat(
            PdfImportReaderService.isLayoutOf(reader.readTextLayer(pdf(MARKER, "none")), layout()))
        .isFalse();
    assertThat(
            PdfImportReaderService.isLayoutOf(
                reader.readTextLayer(pdf("Other bank", "04.01.2031 1,00 x")), layout()))
        .isFalse();
  }

  @Test
  void anEncryptedPdfIsMalformed() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("invented-owner", "", new AccessPermission());
      document.protect(policy);
      document.save(output);

      assertFileError(output.toByteArray(), ApiErrorCode.IMPORT_FILE_MALFORMED);
    }
  }

  /**
   * B3 of the PR #267 review: PDFBox decodes a whole stream into memory before it reads it, so a
   * small file that inflates past MAX_STREAM_BYTES is refused before PDFBox reads any of it.
   */
  @Test
  void aStreamThatInflatesPastTheLimitIsMalformed() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDStream contents = new PDStream(document);
      byte[] spaces = new byte[1 << 20];
      Arrays.fill(spaces, (byte) ' ');
      try (OutputStream stream = contents.createOutputStream(COSName.FLATE_DECODE)) {
        for (long written = 0; written <= PdfStreamBudget.MAX_STREAM_BYTES; ) {
          stream.write(spaces);
          written += spaces.length;
        }
      }
      page.setContents(contents);
      document.save(output);
      assertThat(output.size()).as("a small file").isLessThan(1_000_000);

      assertFileError(output.toByteArray(), ApiErrorCode.IMPORT_FILE_MALFORMED);
    }
  }

  @Test
  void anImageOfMorePixelsThanAStatementNeedsIsMalformed() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      PDStream image = new PDStream(document, new ByteArrayInputStream(new byte[] {0}));
      image.getCOSObject().setItem(COSName.TYPE, COSName.XOBJECT);
      image.getCOSObject().setItem(COSName.SUBTYPE, COSName.IMAGE);
      image.getCOSObject().setInt(COSName.WIDTH, 100_000);
      image.getCOSObject().setInt(COSName.HEIGHT, 100_000);
      document.getPage(0).getCOSObject().setItem(COSName.getPDFName("InventedImage"), image);
      document.save(output);

      assertFileError(output.toByteArray(), ApiErrorCode.IMPORT_FILE_MALFORMED);
    }
  }

  /**
   * Second PR #267 review: an inline image is no stream of the document, and PDFBox decodes its
   * whole data as soon as rendering reaches it. A few kilobytes of Flate data inflating past the
   * stream limit are refused before that.
   */
  @Test
  void anInlineImageThatInflatesPastTheLimitIsNotRenderedForOcr() throws IOException {
    ByteArrayOutputStream inflated = new ByteArrayOutputStream();
    try (OutputStream deflating = new DeflaterOutputStream(inflated)) {
      byte[] zeros = new byte[1 << 20];
      for (long written = 0; written <= PdfStreamBudget.MAX_STREAM_BYTES; ) {
        deflating.write(zeros);
        written += zeros.length;
      }
    }
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    content.write(
        "q 100 0 0 100 50 50 cm BI /W 10 /H 10 /BPC 8 /CS /G /F /Fl ID "
            .getBytes(StandardCharsets.US_ASCII));
    content.write(inflated.toByteArray());
    content.write("\nEI Q\n".getBytes(StandardCharsets.US_ASCII));
    assertThat(content.size()).as("a small file").isLessThan(100_000);

    assertOcrRefused(pageWithContent(content.toByteArray()), "decodes to more than allowed");
  }

  /** A small inline image is still drawn: the check refuses only what is over a limit. */
  @Test
  void aSmallInlineImageIsRenderedForOcr() throws IOException {
    byte[] content =
        "q 100 0 0 100 50 50 cm BI /W 2 /H 2 /BPC 8 /CS /G /F /AHx ID 00FF00FF> EI Q\n"
            .getBytes(StandardCharsets.US_ASCII);
    when(ocr.recognizePages(anyInt(), any()))
        .thenAnswer(
            invocation -> {
              IntFunction<BufferedImage> render = invocation.getArgument(1);
              render.apply(0);
              return MARKER;
            });

    assertThat(reader.readScannedText(pageWithContent(content))).isEqualTo(MARKER);
  }

  /**
   * PDFBox decodes JPEG data at the size in its own header, not at the size the image declares.
   * Rendering refuses one whose header holds too many pixels; reading the text layer decodes no
   * image and is unaffected. The abbreviated filter name is a JPEG too.
   */
  @Test
  void aJpegLargerThanItDeclaresIsNotRenderedForOcr() throws IOException {
    for (COSName filter : List.of(COSName.DCT_DECODE, COSName.DCT_DECODE_ABBREVIATION)) {
      byte[] statement =
          withImage(
              jpegClaiming(20_000, 20_000),
              filter,
              image -> image.setItem(COSName.CS, COSName.DEVICEGRAY));

      assertOcrRefused(statement, "more pixels than a statement needs");
      assertThat(reader.readTextLayer(statement)).contains(MARKER);
    }
  }

  /** PDFBox allocates CCITT data by its decode parameters' Columns and Rows. */
  @Test
  void ccittDataOfMorePixelsThanAStatementNeedsIsNotRenderedForOcr() throws IOException {
    byte[] statement =
        withImage(
            new byte[] {0},
            COSName.CCITTFAX_DECODE,
            image -> {
              COSDictionary decode = new COSDictionary();
              decode.setInt(COSName.COLUMNS, 100_000);
              decode.setInt(COSName.ROWS, 100_000);
              image.setItem(COSName.DECODE_PARMS, decode);
            });

    assertOcrRefused(statement, "more pixels than a statement needs");
    assertThat(reader.readTextLayer(statement)).contains(MARKER);
  }

  @Test
  void moreTextThanTheLimitIsRejectedPageByPage() throws IOException {
    String longLine = "x".repeat(PdfImportReaderService.MAX_TEXT / 4);
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (int i = 0; i < 5; i++) {
        addPage(document, MARKER, longLine);
      }
      document.save(output);

      ApiException error =
          assertFileError(output.toByteArray(), ApiErrorCode.IMPORT_FILE_MALFORMED);
      assertThat(error.getBody().getDetail()).contains("more text");
    }
  }

  /**
   * Skipped lines count over the whole document; a header repeated on every page is not a booking
   * line, so it needs no skipping.
   */
  @Test
  void skippedLinesAndRepeatedPageHeadersSpanPages() throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      addPage(
          document,
          "01.01.2031 0,00 Invented opening line",
          MARKER,
          "Date Amount Text",
          "04.01.2031 -1,00 First");
      addPage(
          document,
          MARKER,
          "Date Amount Text",
          "05.01.2031 -2,00 Second",
          "31.01.2031 -3,00 Invented closing line");
      document.save(output);

      ImportTemplateDefinition template =
          new ImportFileParserServiceTest.Template()
              .pdf("PDF_TEXT", layout())
              .dateFormat("dd.MM.yyyy")
              .decimal(",")
              .thousands(".")
              .preamble(1)
              .trailing(1)
              .mapping(
                  ImportFileParserServiceTest.mapping("Date", "Amount").description("Text").build())
              .build();

      assertThat(parser.parse(output.toByteArray(), template, null).rows())
          .extracting(r -> r.canonical().description())
          .containsExactly("First", "Second");
    }
  }

  /** The SPK-6 layout of the import source analysis: the booking date glued to the text. */
  @Test
  void aDateGluedToTheTextIsCutByTheRowPattern() throws IOException {
    ImportPdfLayout glued =
        new ImportPdfLayout(
            List.of("Date", "Text", "Amount"),
            "(\\d{2}\\.\\d{2}\\.\\d{4})(.+?)\\s+(-?[\\d.]+,\\d{2})",
            MARKER,
            "^\\d{2}\\.\\d{2}\\.\\d{4}");
    byte[] pdf =
        pdf(
            MARKER,
            "04.01.2031Invented card payment -1.234,50",
            "05.01.2031Invented salary 987,65");

    List<ParsedImportRow> rows = parser.parse(pdf, germanTemplate(glued).build(), null).rows();

    assertThat(rows)
        .extracting(ParsedImportRow::canonical)
        .extracting(
            CanonicalImportRow::bookingDate,
            CanonicalImportRow::amount,
            CanonicalImportRow::description)
        .containsExactly(
            tuple(LocalDate.of(2031, 1, 4), new BigDecimal("-1234.50"), "Invented card payment"),
            tuple(LocalDate.of(2031, 1, 5), new BigDecimal("987.65"), "Invented salary"));
  }

  /** The SPK-7 layout: booking and value date glued together. */
  @Test
  void twoGluedDatesAreBookingAndValueDate() throws IOException {
    ImportPdfLayout twoDates =
        new ImportPdfLayout(
            List.of("Date", "Value", "Text", "Amount"),
            "(\\d{2}\\.\\d{2}\\.\\d{4})(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(.+?)\\s+(-?[\\d.]+,\\d{2})",
            MARKER,
            "^\\d{2}\\.\\d{2}\\.\\d{4}\\d{2}\\.");
    byte[] pdf = pdf(MARKER, "04.01.203106.01.2031 Invented loan rate -500,00");

    ParsedImportRow row =
        parser
            .parse(
                pdf,
                germanTemplate(twoDates)
                    .mapping(
                        ImportFileParserServiceTest.mapping("Date", "Amount")
                            .valueDate("Value")
                            .description("Text")
                            .build())
                    .build(),
                null)
            .rows()
            .get(0);

    assertThat(row.canonical().bookingDate()).isEqualTo(LocalDate.of(2031, 1, 4));
    assertThat(row.canonical().valueDate()).isEqualTo(LocalDate.of(2031, 1, 6));
    assertThat(row.canonical().amount()).isEqualTo(new BigDecimal("-500.00"));
    assertThat(row.canonical().description()).isEqualTo("Invented loan rate");
  }

  /**
   * D2 of the PR #267 review: with a one-column layout, an unmatched line has as many cells as a
   * matched one, so the line itself - not its cell count - says whether it matched.
   */
  @Test
  void aOneColumnLayoutStillKnowsAnUnmatchedLine() throws IOException {
    ImportPdfLayout oneColumn =
        new ImportPdfLayout(List.of("Line"), "(\\d{2}\\.\\d{2}\\. .+)", MARKER, "^\\d");
    ImportTemplateDefinition template =
        new ImportFileParserServiceTest.Template().pdf("PDF_TEXT", oneColumn).build();

    List<ImportPdfBookingLine> lines =
        reader.readBookingLines(pdf(MARKER, "04.01. Invented", "05.01.Invented"), template);

    assertThat(lines).extracting(ImportPdfBookingLine::matched).containsExactly(true, false);
    assertThat(lines.get(1).cells()).containsExactly("05.01.Invented");
  }

  @Test
  void anUnmatchedLineIsQuotedOnlyUpToTheEchoLimit() throws IOException {
    String longLine = "05.01.2031 " + "y".repeat(300);

    ParsedImportRow row =
        parser.parse(pdf(MARKER, longLine), template("PDF_TEXT"), null).rows().get(0);

    assertThat(row.errorCode()).isEqualTo(ImportRowErrorValues.LINE_UNMATCHED);
    assertThat(row.errorArgs().get("value"))
        .hasSize(ImportFileParserService.MAX_ECHOED_VALUE_LENGTH);
  }

  @Test
  void layoutRulesNameTheField() {
    ImportPdfLayout good = layout();
    assertInvalid(pdfTemplate("PDF_TEXT", null), "pdfLayout");
    assertInvalid(
        pdfTemplate("PDF_TEXT", new ImportPdfLayout(List.of(), good.rowPattern(), MARKER, "^\\d")),
        "pdfLayout.columns");
    assertInvalid(
        pdfTemplate(
            "PDF_TEXT",
            new ImportPdfLayout(
                List.of("Date", "date", "Text"), good.rowPattern(), MARKER, "^\\d")),
        "pdfLayout.columns");
    assertInvalid(
        pdfTemplate(
            "PDF_TEXT", new ImportPdfLayout(good.columns(), good.rowPattern(), " ", "^\\d")),
        "pdfLayout.documentMarker");
    assertInvalid(
        pdfTemplate("PDF_TEXT", new ImportPdfLayout(good.columns(), good.rowPattern(), MARKER, "")),
        "pdfLayout.recordStartPattern");
    assertInvalid(
        pdfTemplate("PDF_TEXT", new ImportPdfLayout(good.columns(), "(\\S+", MARKER, "^\\d")),
        "pdfLayout.rowPattern");
    assertInvalid(
        pdfTemplate("PDF_TEXT", new ImportPdfLayout(good.columns(), "(\\S+) (.+)", MARKER, "^\\d")),
        "pdfLayout.rowPattern");
    // RE2 has no backreferences: a pattern that could backtrack exponentially is refused.
    assertInvalid(
        pdfTemplate("PDF_TEXT", new ImportPdfLayout(good.columns(), "(a)(b)(\\1)", MARKER, "^\\d")),
        "pdfLayout.rowPattern");
    // F3 of the PR #267 review: linear time, but in a program this pattern makes a million
    // instructions (about 60 MB) of.
    assertInvalid(
        pdfTemplate(
            "PDF_TEXT",
            new ImportPdfLayout(good.columns(), good.rowPattern(), MARKER, "(?:a{1000}){1000}")),
        "pdfLayout.recordStartPattern");
    assertInvalid(pdfTemplate("CSV", good), "pdfLayout");
    assertInvalid(pdfTemplate("XLSX", null), "fileFormat");
    assertInvalid(
        new ImportFileParserServiceTest.Template()
            .pdf("PDF_TEXT", good)
            .mapping(ImportFileParserServiceTest.mapping("Date", "Betrag").build())
            .build(),
        "columnMapping.amount");
  }

  /** A PDF template has no header row to read; asking for one is a 422, never a 500. */
  @Test
  void aPdfTemplateHasNoHeaderToRead() throws IOException {
    byte[] statement = pdf(MARKER, "04.01.2031 -1,00 Invented");

    assertThatThrownBy(() -> parser.readHeader(statement, template("PDF_TEXT")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_UNSUPPORTED);
              assertThat(e.getStatusCode().value()).isEqualTo(422);
            });
  }

  /**
   * F2 of the PR #267 review: each read may take tens of megabytes, so only so many run at once. A
   * further upload waits briefly for a turn, then is a 503 - and once a turn is free, it is read.
   */
  @Test
  void aServerReadingTheMostStatementsItMayAnswersBusy() throws Exception {
    PdfImportReaderService busyReader = new PdfImportReaderService(ocr, 100);
    CountDownLatch reading = new CountDownLatch(PdfImportReaderService.MAX_CONCURRENT_READS);
    CountDownLatch release = new CountDownLatch(1);
    when(ocr.recognizePages(anyInt(), any()))
        .thenAnswer(
            invocation -> {
              reading.countDown();
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
              return MARKER + "\n";
            });
    byte[] scanned = pdf();
    byte[] statement = pdf(MARKER, "04.01.2031 -1,00 Invented");
    ExecutorService callers =
        Executors.newFixedThreadPool(PdfImportReaderService.MAX_CONCURRENT_READS);
    try {
      for (int i = 0; i < PdfImportReaderService.MAX_CONCURRENT_READS; i++) {
        callers.submit(() -> busyReader.readScannedText(scanned));
      }
      assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> busyReader.readTextLayer(statement))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_PDF_BUSY);
                assertThat(e.getStatusCode().value()).isEqualTo(503);
                assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(String.valueOf(PdfImportReaderService.BUSY_RETRY_AFTER_SECONDS));
              });
    } finally {
      release.countDown();
      callers.shutdown();
      assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(busyReader.readTextLayer(statement)).contains(MARKER);
  }

  /**
   * Third PR #267 review: loading a PDF decoded its cross-reference stream whole, before any check
   * after loading could run - a 300 KB file inflated to 300 MB inside {@code Loader.loadPDF}. The
   * parser now checks every stream as it parses it.
   */
  @Test
  void aCrossReferenceStreamThatInflatesPastTheLimitIsMalformed() throws IOException {
    byte[] statement = handwrittenPdf(false, PdfStreamBudget.MAX_STREAM_BYTES + 1L);
    assertThat(statement.length).as("a small file").isLessThan(100_000);

    assertTextLayerRefused(statement, "decodes to more than allowed");
  }

  /** An object stream is decoded whole as soon as one of its objects is read. */
  @Test
  void anObjectStreamThatInflatesPastTheLimitIsMalformed() throws IOException {
    byte[] statement = handwrittenPdf(true, PdfStreamBudget.MAX_STREAM_BYTES + 1L);
    assertThat(statement.length).as("a small file").isLessThan(100_000);

    assertTextLayerRefused(statement, "decodes to more than allowed");
  }

  /** The same files within the limits are read: what is refused is only the size. */
  @Test
  void crossReferenceAndObjectStreamsWithinTheLimitsAreRead() throws IOException {
    assertFileError(handwrittenPdf(false, 1000), ApiErrorCode.IMPORT_PDF_NO_TEXT);
    assertFileError(handwrittenPdf(true, 1000), ApiErrorCode.IMPORT_PDF_NO_TEXT);
  }

  /**
   * Strict parsing: a lenient parse repairs a damaged file with a parser of its own, past the
   * stream checks, so a damaged file is refused instead.
   */
  @Test
  void aDamagedFileIsRefusedNotRepaired() throws IOException {
    String statement =
        new String(pdf(MARKER, "04.01.2031 -1,00 Invented"), StandardCharsets.ISO_8859_1);
    int offset = statement.lastIndexOf("startxref") + "startxref".length();
    String damaged = statement.substring(0, offset) + "\n17\n%%EOF\n";

    assertFileError(
        damaged.getBytes(StandardCharsets.ISO_8859_1), ApiErrorCode.IMPORT_FILE_MALFORMED);
  }

  /**
   * Third PR #267 review: the stream limits bound how much a stream decodes to, not how often it
   * runs. Forms nested five deep, each drawing the next twenty times, make a two-kilobyte file run
   * 3.2 million text operations (minutes per page at depth six). The read stops at the operation
   * limit instead - reading the text layer and rendering for OCR alike.
   */
  @Test
  @Timeout(60)
  void formsDrawingEachOtherOverAndOverAreRefused() throws IOException {
    byte[] statement = nestedForms(5, 20);
    assertThat(statement.length).as("a small file").isLessThan(10_000);

    assertTextLayerRefused(statement, "draws more than a statement needs");
    assertOcrRefused(statement, "draws more than a statement needs");
  }

  /** Forms that draw each other a few hundred times, as a real layout might, are read. */
  @Test
  void formsDrawnAFewHundredTimesAreRead() throws IOException {
    assertThat(reader.readTextLayer(nestedForms(2, 20))).contains(MARKER);
  }

  // --- helpers -------------------------------------------------------------------------------

  // An OCR read of content is a 422 IMPORT_FILE_MALFORMED for reason, from this class's own limits.
  private void assertOcrRefused(byte[] content, String reason) {
    doAnswer(
            invocation -> {
              IntFunction<BufferedImage> render = invocation.getArgument(1);
              render.apply(0);
              return MARKER;
            })
        .when(ocr)
        .recognizePages(anyInt(), any());
    assertThatThrownBy(() -> reader.readScannedText(content))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_FILE_MALFORMED);
              assertThat(e.getStatusCode().value()).isEqualTo(422);
            })
        .rootCause()
        .hasMessageContaining(reason);
  }

  // A PDF read through the text layer is a 422 IMPORT_FILE_MALFORMED for reason, from the limits.
  private void assertTextLayerRefused(byte[] content, String reason) {
    assertThatThrownBy(() -> parser.parse(content, template("PDF_TEXT"), null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_FILE_MALFORMED);
              assertThat(e.getStatusCode().value()).isEqualTo(422);
            })
        .rootCause()
        .isInstanceOf(PdfLimitException.class)
        .hasMessageContaining(reason);
  }

  /**
   * A one-page PDF written by hand, as PDFBox cannot save one: a cross-reference stream and, with
   * objectStream, an object stream holding one object. The cross-reference stream (or the object
   * stream) is followed by padding that inflates to padding bytes.
   */
  private static byte[] handwrittenPdf(boolean objectStream, long padding) throws IOException {
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    long[] offsets = new long[7];
    ascii(file, "%PDF-1.7\n");
    offsets[1] = file.size();
    ascii(file, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
    offsets[2] = file.size();
    ascii(file, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
    offsets[3] = file.size();
    ascii(file, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>\nendobj\n");
    int size = 5;
    if (objectStream) {
      byte[] data = deflated("6 0 << /Invented 1 >>", padding);
      offsets[5] = file.size();
      ascii(
          file,
          "5 0 obj\n<< /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode /Length "
              + data.length
              + " >>\nstream\n");
      file.write(data);
      ascii(file, "\nendstream\nendobj\n");
      size = 7;
    }
    offsets[4] = file.size();
    ByteArrayOutputStream entries = new ByteArrayOutputStream();
    for (int i = 0; i < size; i++) {
      // Type 0 (free), 1 (at an offset) or 2 (in object stream 5); fields of 1, 4 and 2 bytes.
      int type = i == 0 ? 0 : i == 6 ? 2 : 1;
      long field = type == 2 ? 5 : offsets[i];
      int last = i == 0 ? 0xFFFF : 0;
      entries.write(type);
      entries.write(ByteBuffer.allocate(4).putInt((int) field).array());
      entries.write(last >> 8);
      entries.write(last);
    }
    byte[] xref =
        deflated(entries.toString(StandardCharsets.ISO_8859_1), objectStream ? 0 : padding);
    ascii(
        file,
        "4 0 obj\n<< /Type /XRef /Size "
            + size
            + " /W [1 4 2] /Root 1 0 R /Filter /FlateDecode /Length "
            + xref.length
            + " >>\nstream\n");
    file.write(xref);
    ascii(file, "\nendstream\nendobj\nstartxref\n" + offsets[4] + "\n%%EOF\n");
    return file.toByteArray();
  }

  // text, then padding zero bytes, Flate-compressed.
  private static byte[] deflated(String text, long padding) throws IOException {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (OutputStream deflating = new DeflaterOutputStream(compressed)) {
      deflating.write(text.getBytes(StandardCharsets.ISO_8859_1));
      byte[] zeros = new byte[1 << 20];
      for (long written = 0; written < padding; written += zeros.length) {
        deflating.write(zeros, 0, (int) Math.min(zeros.length, padding - written));
      }
    }
    return compressed.toByteArray();
  }

  private static void ascii(ByteArrayOutputStream file, String text) {
    file.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  // A statement page that draws a form depth levels deep, each level drawing the next fan times.
  private static byte[] nestedForms(int depth, int fan) throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      addPage(document, MARKER);
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDFormXObject form = null;
      for (int level = 0; level <= depth; level++) {
        PDFormXObject next = new PDFormXObject(document);
        next.setBBox(new PDRectangle(100, 100));
        PDResources resources = new PDResources();
        String content =
            form == null
                ? "BT /" + resources.add(font).getName() + " 1 Tf (x) Tj ET\n"
                : ("/" + resources.add(form).getName() + " Do\n").repeat(fan);
        next.setResources(resources);
        try (OutputStream stream = next.getContentStream().createOutputStream()) {
          stream.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        form = next;
      }
      PDPage page = document.getPage(0);
      try (PDPageContentStream stream =
          new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true)) {
        stream.drawForm(form);
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  // A one-page PDF whose page content is exactly content, unfiltered.
  private static byte[] pageWithContent(byte[] content) throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      page.setContents(new PDStream(document, new ByteArrayInputStream(content)));
      document.save(output);
      return output.toByteArray();
    }
  }

  // A statement page with the marker that also holds an image XObject of this raw data and filter.
  private static byte[] withImage(byte[] data, COSName filter, Consumer<COSDictionary> settings)
      throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      addPage(document, MARKER);
      PDStream image = new PDStream(document, new ByteArrayInputStream(data));
      COSDictionary dictionary = image.getCOSObject();
      dictionary.setItem(COSName.TYPE, COSName.XOBJECT);
      dictionary.setItem(COSName.SUBTYPE, COSName.IMAGE);
      dictionary.setInt(COSName.WIDTH, 8);
      dictionary.setInt(COSName.HEIGHT, 8);
      dictionary.setInt(COSName.BITS_PER_COMPONENT, 8);
      dictionary.setItem(COSName.FILTER, filter);
      settings.accept(dictionary);
      document.getPage(0).getCOSObject().setItem(COSName.getPDFName("InventedImage"), image);
      document.save(output);
      return output.toByteArray();
    }
  }

  // An 8 x 8 grey JPEG whose frame header claims width x height.
  private static byte[] jpegClaiming(int width, int height) throws IOException {
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    assertThat(ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), "jpeg", jpeg))
        .isTrue();
    byte[] bytes = jpeg.toByteArray();
    for (int i = 0; i + 8 < bytes.length; i++) {
      if (bytes[i] == (byte) 0xFF && bytes[i + 1] == (byte) 0xC0) {
        // SOF0: marker, length (2), precision (1), height (2), width (2).
        bytes[i + 5] = (byte) (height >> 8);
        bytes[i + 6] = (byte) height;
        bytes[i + 7] = (byte) (width >> 8);
        bytes[i + 8] = (byte) width;
        return bytes;
      }
    }
    throw new IllegalStateException("no SOF0 frame header");
  }

  private static ImportFileParserServiceTest.Template germanTemplate(ImportPdfLayout layout) {
    return new ImportFileParserServiceTest.Template()
        .pdf("PDF_TEXT", layout)
        .dateFormat("dd.MM.yyyy")
        .decimal(",")
        .thousands(".")
        .mapping(ImportFileParserServiceTest.mapping("Date", "Amount").description("Text").build());
  }

  private static ImportPdfLayout layout() {
    return new ImportPdfLayout(
        List.of("Date", "Amount", "Text"), "(\\S+)\\s+(\\S+)\\s+(.+)", MARKER, "^\\d{2}\\.");
  }

  private static ImportTemplateDefinition template(String format) {
    return new ImportFileParserServiceTest.Template()
        .pdf(format, layout())
        .dateFormat("dd.MM.yyyy")
        .decimal(",")
        .thousands(".")
        .mapping(ImportFileParserServiceTest.mapping("Date", "Amount").description("Text").build())
        .build();
  }

  private static ImportTemplateDefinition pdfTemplate(String format, ImportPdfLayout layout) {
    return new ImportFileParserServiceTest.Template()
        .pdf(format, layout)
        .mapping(ImportFileParserServiceTest.mapping("Date", "Amount").build())
        .build();
  }

  private void assertInvalid(ImportTemplateDefinition template, String field) {
    assertThatThrownBy(() -> parser.validateTemplate(template, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(ApiErrorCode.IMPORT_TEMPLATE_INVALID);
              assertThat(e.getBody().getProperties()).containsEntry("field", field);
            });
  }

  private ApiException assertFileError(byte[] content, String code) {
    ThrowingCallable parse = () -> parser.parse(content, template("PDF_TEXT"), null);
    ApiException[] caught = new ApiException[1];
    assertThatThrownBy(parse)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(code);
              assertThat(e.getStatusCode().value()).isEqualTo(422);
              caught[0] = e;
            });
    return caught[0];
  }

  /** A one-page PDF with these text lines; no lines gives a page without a text layer. */
  static byte[] pdf(String... lines) throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      addPage(document, lines);
      document.save(output);
      return output.toByteArray();
    }
  }

  private static void addPage(PDDocument document, String... lines) throws IOException {
    PDPage page = new PDPage();
    document.addPage(page);
    if (lines.length == 0) {
      return;
    }
    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
      stream.beginText();
      stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
      stream.setLeading(16);
      stream.newLineAtOffset(50, 720);
      for (String line : lines) {
        stream.showText(line);
        stream.newLine();
      }
      stream.endText();
    }
  }
}
