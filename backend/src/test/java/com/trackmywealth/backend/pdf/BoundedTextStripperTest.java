package com.trackmywealth.backend.pdf;

import static com.trackmywealth.backend.service.SyntheticStatements.at;
import static com.trackmywealth.backend.service.SyntheticStatements.endingAt;
import static com.trackmywealth.backend.service.SyntheticStatements.strayBlank;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.trackmywealth.backend.service.SyntheticStatements;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * PR #281 review: the lines {@link BoundedTextStripper} rebuilds are exactly the lines of {@link
 * PDFTextStripper#getText} - every PDF layout saved under #267 cuts its rows, preamble and trailing
 * summary by those lines - and their words carry their positions on the page. The one exception is
 * #322's: a blank glyph a visible glyph of the same line covers is no word separator.
 */
class BoundedTextStripperTest {

  /** Several pages, a table shifted between them, right-aligned amounts and spaced words. */
  @Test
  void aStatementsLinesAreGetTextsLines() throws IOException {
    assertSameLinesAsGetText(SyntheticStatements.yuhStatement(false));
  }

  /** An empty page, an empty line and a paragraph gap between lines read the same way too. */
  @Test
  void emptyPagesAndGapsAreGetTextsLinesToo() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, "Invented Bank")),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(at(40, "02.01.2031 Rate"), endingAt(500, "250,00")),
                    List.of(at(60, "Erika Beispiel"))),
                List.of(),
                List.of(List.of(at(300, "Seite 3 von 3")))));

    assertSameLinesAsGetText(statement);
  }

  /**
   * PR #281 review: a word drawn with a ligature glyph reads as the line's text writes it, ligature
   * resolved - so a header label is found in a line's words as detection finds it in its text.
   */
  @Test
  void aWordWithALigatureReadsAsTheLineWritesIt() throws IOException {
    try (PDDocument document = new PDDocument();
        InputStream ttf =
            PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDType0Font font = PDType0Font.load(document, ttf);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(font, 10);
        content.newLineAtOffset(40, 700);
        content.showText("Pro\uFB01l Betrag");
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);

      try (PDDocument read = Loader.loadPDF(out.toByteArray())) {
        PdfTextLine line = stripper().readLines(read, 1).get(0);

        assertThat(line.text()).isEqualTo("Profil Betrag");
        assertThat(line.words()).extracting(PdfWord::text).containsExactly("Profil", "Betrag");
      }
    }
  }

  /**
   * #322: a Sparkasse statement draws two invisible spaces, in smaller type a little below the
   * baseline, over the first digit of a booking date. getText reads them as separators; the
   * stripper drops them, so the date is one word.
   */
  @Test
  void blanksDrawnOverADateDoNotSplitIt() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(
                        at(40, "31.03.2031"),
                        strayBlank(39.6f),
                        strayBlank(41.55f),
                        at(100, "Darlehensleistung")))));

    try (PDDocument document = Loader.loadPDF(statement)) {
      PDFTextStripper plain = new PDFTextStripper();
      plain.setSortByPosition(true);
      assertThat(plain.getText(document)).as("the fixture reproduces #322").contains("3 1.03.2031");

      PdfTextLine line = stripper().readLines(document, 1).get(0);

      assertThat(line.text()).isEqualTo("31.03.2031 Darlehensleistung");
      assertThat(line.words())
          .extracting(PdfWord::text)
          .containsExactly("31.03.2031", "Darlehensleistung");
    }
  }

  /**
   * #322: a blank below the baseline also stretched the line down to the next one, which PDFBox
   * then read as part of it; without it, the next line is a line of its own.
   */
  @Test
  void aDroppedBlankNoLongerJoinsTheLineBelow() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(
                List.of(
                    List.of(at(40, "Darlehen 0000000000"), strayBlank(39.6f)),
                    List.of(at(330, "Tilgungsform: Annuitaet")))));

    try (PDDocument document = Loader.loadPDF(statement)) {
      List<PdfTextLine> lines = stripper().readLines(document, 1);

      assertThat(lines)
          .extracting(PdfTextLine::text)
          .containsExactly("Darlehen 0000000000", "Tilgungsform: Annuitaet");
    }
  }

  /**
   * #322: a space of a heading's second line, in large type, may sit right under a letter of the
   * first; it is on another line, so it still separates its words.
   */
  @Test
  void aSpaceUnderTheLineAboveInLargeTypeStillSeparatesWords() throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(font, 19);
        content.newLineAtOffset(40, 700);
        content.showText("Jahreskontoauszug");
        content.newLineAtOffset(0, -14);
        content.showText("Ihr Darlehen");
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);

      try (PDDocument read = Loader.loadPDF(out.toByteArray())) {
        assertThat(stripper().readLines(read, 1))
            .extracting(PdfTextLine::text)
            .containsExactly("Jahreskontoauszug", "Ihr Darlehen");
      }
    }
  }

  @Test
  void eachWordHasItsPlaceOnThePage() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(
            List.of(List.of(List.of(at(40, "Zahlung von"), endingAt(500, "1'000.00")))));

    try (PDDocument document = Loader.loadPDF(statement)) {
      List<PdfTextLine> lines = stripper().readLines(document, 1);

      assertThat(lines).hasSize(1);
      assertThat(lines.get(0).page()).isEqualTo(1);
      List<PdfWord> words = lines.get(0).words();
      assertThat(words).extracting(PdfWord::text).containsExactly("Zahlung", "von", "1'000.00");
      assertThat(words.get(0).left()).isEqualTo(40, offset(0.5f));
      assertThat(words.get(0).right()).isLessThan(words.get(1).left());
      assertThat(words.get(2).right()).isEqualTo(500, offset(0.5f));
      // Its baseline 780 points up a letter-size page (792 points high): 12 from the top edge.
      assertThat(lines.get(0).fromTop()).isEqualTo(12, offset(0.5f));
      assertThat(lines.get(0).fromBottom()).isEqualTo(780, offset(0.5f));
    }
  }

  /**
   * PR #281 review: on a page turned a quarter, the page is read across its width, so a line's
   * distances from the top and bottom edges add up to the page's width.
   */
  @Test
  void aTurnedPageMeasuresTheLineAcrossItsWidth() throws IOException {
    byte[] statement =
        SyntheticStatements.statement(List.of(List.of(List.of(at(40, "Zahlung von")))));

    try (PDDocument document = Loader.loadPDF(statement)) {
      document.getPage(0).setRotation(90);
      PdfTextLine line = stripper().readLines(document, 1).get(0);

      assertThat(line.fromTop() + line.fromBottom())
          .isEqualTo(document.getPage(0).getCropBox().getWidth(), offset(0.5f));
    }
  }

  private static void assertSameLinesAsGetText(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      BoundedTextStripper stripper = stripper();
      PDFTextStripper plain = new PDFTextStripper();
      plain.setSortByPosition(true);
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        plain.setStartPage(page);
        plain.setEndPage(page);
        List<String> expected = plain.getText(document).lines().toList();

        List<PdfTextLine> lines = stripper.readLines(document, page);

        assertThat(lines).extracting(PdfTextLine::text).as("page %d", page).isEqualTo(expected);
        int onPage = page;
        assertThat(lines).allSatisfy(line -> assertThat(line.page()).isEqualTo(onPage));
      }
    }
  }

  private static BoundedTextStripper stripper() {
    BoundedTextStripper stripper =
        new BoundedTextStripper(
            new PdfReadBudget(Long.MAX_VALUE, Long.MAX_VALUE, Duration.ofMinutes(1)));
    stripper.setSortByPosition(true);
    return stripper;
  }
}
