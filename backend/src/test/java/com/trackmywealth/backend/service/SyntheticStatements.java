package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * #268: synthetic PDF statements whose words sit at given places on the page, as a bank's table
 * does - the layouts of docs/architecture/import-source-analysis.md (6.8), with invented values
 * only. A real statement is never used.
 */
public final class SyntheticStatements {

  public static final String YUH_MARKER = "Invented Bank Kontoauszug";
  public static final List<String> YUH_LABELS =
      List.of(
          "DATUM", "INFORMATION", "REFERENZ", "BELASTUNG", "GUTSCHRIFT", "VALUTA-DATUM", "SALDO");

  private static final float FONT_SIZE = 8;
  private static final float TOP = 780;
  // Where a page's footer starts, as a bank prints it: at the same place on every page.
  private static final float FOOT = 60;
  private static final float LEADING = 12;
  // Where each YUH label starts on the first page.
  private static final float[] YUH_X = {40, 100, 220, 300, 360, 430, 500};
  private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

  private SyntheticStatements() {}

  /**
   * A piece of text on a line: from x on, or ending at x when right-aligned. A line whose first
   * cell is {@code atFoot} is part of the page's footer.
   */
  public record Cell(String text, float x, boolean rightAligned, boolean atFoot) {}

  public static Cell at(float x, String text) {
    return new Cell(text, x, false, false);
  }

  public static Cell endingAt(float x, String text) {
    return new Cell(text, x, true, false);
  }

  /**
   * A footer line's text from x on: printed at the foot of the page, wherever the text above ends.
   */
  public static Cell atFoot(float x, String text) {
    return new Cell(text, x, false, true);
  }

  /**
   * A PDF of these pages, each a list of lines, each a list of cells. Lines are laid out from the
   * top of the page one below the other, footer lines from {@link #FOOT} down.
   */
  public static byte[] statement(List<List<List<Cell>>> pages) throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (List<List<Cell>> lines : pages) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
          float y = TOP;
          float footY = FOOT;
          for (List<Cell> line : lines) {
            boolean foot = !line.isEmpty() && line.get(0).atFoot();
            for (Cell cell : line) {
              float x = cell.rightAligned() ? cell.x() - width(cell.text()) : cell.x();
              stream.beginText();
              stream.setFont(FONT, FONT_SIZE);
              stream.newLineAtOffset(x, foot ? footY : y);
              stream.showText(cell.text());
              stream.endText();
            }
            if (foot) {
              footY -= LEADING;
            } else {
              y -= LEADING;
            }
          }
        }
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  /**
   * A two-page statement in the YUH-1 layout: a summary page part before the first section, then a
   * CHF section (three bookings, the second page's table shifted 20 points to the right) and a EUR
   * section (one booking), unsigned amounts under BELASTUNG or GUTSCHRIFT, a running balance, an
   * opening balance in each section, continuation lines, a remark at the margin and a page header
   * and footer on every page. With {@code debitAsCredit}, the 17.35 debit sits in the credit
   * column.
   */
  public static byte[] yuhStatement(boolean debitAsCredit) throws IOException {
    List<List<Cell>> first = new ArrayList<>();
    first.add(List.of(at(40, YUH_MARKER)));
    first.add(List.of(at(40, "Kundennummer 0000001"), at(300, "Gesamtsaldo 1'308.55")));
    // A date in the summary, before any section: no booking.
    first.add(List.of(at(40, "01.01.2031 Kontoeroeffnung")));
    first.add(List.of(at(40, "Kontoauszug in CHF")));
    first.add(List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "263.40 CHF")));
    first.add(header(0));
    first.add(
        List.of(at(40, "01.01.2031"), at(100, "Anfangsbestand"), endingAt(right(6, 0), "263.40")));
    first.add(booking(0, "03.01.2031", "Zahlung von", "0000000001", null, "1'000.00", "1'263.40"));
    first.add(List.of(at(100, "Erika Beispiel")));
    first.add(List.of(at(100, "CH00 0000 0000 0000 0000 0")));
    first.add(
        debitAsCredit
            ? booking(0, "05.01.2031", "Spareinlage", "0000000002", null, "17.35", "1'246.05")
            : booking(0, "05.01.2031", "Spareinlage", "0000000002", "17.35", null, "1'246.05"));
    first.add(List.of(atFoot(40, "Seite 1 von 2")));
    first.add(List.of(atFoot(40, "Invented Bank AG, Beispielweg 1")));

    List<List<Cell>> second = new ArrayList<>();
    second.add(List.of(at(40, YUH_MARKER)));
    second.add(header(20));
    second.add(booking(20, "07.01.2031", "Dividende", "0000000003", null, "2.50", "1'248.55"));
    second.add(List.of(at(40, "Saldo per 31.01.2031"), endingAt(560, "1'248.55 CHF")));
    second.add(List.of(at(40, "Kontoauszug in EUR")));
    second.add(List.of(at(40, "Saldo per 01.01.2031"), endingAt(560, "100.00 EUR")));
    second.add(header(20));
    second.add(booking(20, "10.01.2031", "Zahlung an", "0000000004", "40.00", null, "60.00"));
    second.add(List.of(at(120, "Max Muster")));
    // At the margin, under DATUM: a remark, not part of the booking above.
    second.add(List.of(at(40, "Valuta gemaess Bedingungen")));
    second.add(List.of(at(40, "Saldo per 31.01.2031"), endingAt(560, "60.00 EUR")));
    second.add(List.of(atFoot(40, "Seite 2 von 2")));
    second.add(List.of(atFoot(40, "Invented Bank AG, Beispielweg 1")));
    return statement(List.of(first, second));
  }

  /**
   * The YUH-1 layout: every line is one cell (the row pattern reads nothing of its own), the words
   * under the header labels, the section's currency, continuation lines appended to INFORMATION,
   * opening and closing balances and the running balance under SALDO checked.
   */
  public static ImportPdfLayout yuhLayout() {
    return ImportPdfLayout.builder(
            List.of("Zeile"), "(.*)", YUH_MARKER, "^\\d{2}\\.\\d{2}\\.\\d{4}")
        .withHeaderLabels(YUH_LABELS)
        .withContinuationColumn("INFORMATION")
        .withSectionPattern("^Kontoauszug in ([A-Z]{3})$")
        .withSectionColumn("Waehrung")
        .withBalanceLinePattern("(?:^Saldo per \\S+|Anfangsbestand)\\s+([-\\d'.]+)")
        .withBalanceColumn("SALDO")
        .build();
  }

  /** The YUH-1 mapping: the sign by column, the currency by section. */
  public static ImportColumnMapping yuhMapping() {
    return new ImportColumnMapping(
        "DATUM",
        "VALUTA-DATUM",
        null,
        "BELASTUNG",
        "GUTSCHRIFT",
        "Waehrung",
        "INFORMATION",
        null,
        "REFERENZ",
        null,
        null,
        null,
        null);
  }

  static List<Cell> header(float shift) {
    List<Cell> cells = new ArrayList<>();
    for (int i = 0; i < YUH_LABELS.size(); i++) {
      cells.add(at(YUH_X[i] + shift, YUH_LABELS.get(i)));
    }
    return cells;
  }

  // A booking line: date, text and reference from their labels on, the amounts and the balance
  // ending where their labels end, the value date from its label on.
  static List<Cell> booking(
      float shift,
      String date,
      String text,
      String reference,
      String debit,
      String credit,
      String balance) {
    List<Cell> cells = new ArrayList<>();
    cells.add(at(YUH_X[0] + shift, date));
    cells.add(at(YUH_X[1] + shift, text));
    cells.add(at(YUH_X[2] + shift, reference));
    if (debit != null) {
      cells.add(endingAt(right(3, shift), debit));
    }
    if (credit != null) {
      cells.add(endingAt(right(4, shift), credit));
    }
    cells.add(at(YUH_X[5] + shift, date));
    cells.add(endingAt(right(6, shift), balance));
    return cells;
  }

  // Where label ends on a page shifted by shift.
  private static float right(int label, float shift) {
    try {
      return YUH_X[label] + shift + width(YUH_LABELS.get(label));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static float width(String text) throws IOException {
    return FONT.getStringWidth(text) / 1000 * FONT_SIZE;
  }
}
