package com.trackmywealth.backend.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** #268: columns located by a header line's labels, and the column each word belongs to. */
class PdfColumnsTest {

  // DATUM 40-65, BELASTUNG 300-345, GUTSCHRIFT 360-407, SALDO 500-524.
  private static final PdfTextLine HEADER =
      line(
          word("Datum", 40, 65),
          word("BELASTUNG", 300, 345),
          word("GUTSCHRIFT", 360, 407),
          word("SALDO", 500, 524),
          word("(EUR)", 527, 548));
  private static final List<String> LABELS = List.of("DATUM", "BELASTUNG", "GUTSCHRIFT", "SALDO");

  @Test
  void aWordBelongsToTheLabelItOverlapsMost() {
    PdfColumns columns = PdfColumns.of(HEADER, LABELS).orElseThrow();

    assertThat(columns.columnOf(word("17.35", 324, 345))).isEqualTo(1);
    // A wide amount right-aligned under GUTSCHRIFT reaches past its start, towards BELASTUNG:
    // it still overlaps GUTSCHRIFT most.
    assertThat(columns.columnOf(word("12'345'678.90", 340, 407))).isEqualTo(2);
    // Overlapping no label: the nearest.
    assertThat(columns.columnOf(word("x", 470, 480))).isEqualTo(3);
    assertThat(columns.columnOf(word("x", 70, 80))).isEqualTo(0);
  }

  @Test
  void cellsJoinTheWordsUnderEachLabel() {
    PdfColumns columns = PdfColumns.of(HEADER, LABELS).orElseThrow();

    assertThat(
            columns.cells(
                List.of(
                    word("03.01.2031", 40, 80),
                    word("1'000.00", 377, 407),
                    word("1'263.40", 493, 524))))
        .containsExactly("03.01.2031", "", "1'000.00", "1'263.40");
  }

  /**
   * #326: a long text runs on towards the amount columns at word spacing and stays text; a word
   * after a wide gap is its column's value. Without a flowing column, a text word nearer to an
   * amount label than to the text label is read as that amount.
   */
  @Test
  void aFlowingTextColumnKeepsWordsAtWordSpacingAndLetsAWideGapEndIt() {
    // DATUM 40-65, TEXT 95-112, GUTSCHRIFT 300-337, LASTSCHRIFT 370-408.
    PdfColumns columns =
        PdfColumns.of(
                line(
                    word("Datum", 40, 65),
                    word("Text", 95, 112),
                    word("Gutschrift", 300, 337),
                    word("Lastschrift", 370, 408)),
                List.of("DATUM", "TEXT", "GUTSCHRIFT", "LASTSCHRIFT"))
            .orElseThrow();
    // Words 2 points apart (word spacing for 4-point characters), the last ending near GUTSCHRIFT;
    // then the debit amount after a gap of 70 points.
    List<PdfWord> words =
        List.of(
            word("05.03.31", 40, 70),
            word("Zahlung", 95, 123),
            word("an", 125, 133),
            word("Max", 135, 147),
            word("Muster", 149, 173),
            word("Beispielweg", 175, 219),
            word("12", 221, 229),
            word("Rechnung", 231, 263),
            word("41.19", 391, 408));

    assertThat(columns.cells(words, 1))
        .containsExactly("05.03.31", "Zahlung an Max Muster Beispielweg 12 Rechnung", "", "41.19");
    assertThat(columns.cells(words).get(2))
        .as("without a flowing column, the last words are nearer to GUTSCHRIFT")
        .isEqualTo("12 Rechnung");
  }

  /**
   * A header line holds every label's words in order, ignoring case; other words may sit between
   * them, such as the section's currency after a balance label.
   */
  @Test
  void aHeaderLineHoldsEveryLabelInOrder() {
    assertThat(PdfColumns.of(HEADER, LABELS)).isPresent();
    assertThat(PdfColumns.of(HEADER, List.of("SALDO", "DATUM"))).isEmpty();
    assertThat(PdfColumns.of(HEADER, List.of("DATUM", "BETRAG"))).isEmpty();
    assertThat(PdfColumns.isHeaderLine("Datum  Valuta Datum  Saldo", List.of("VALUTA DATUM")))
        .isTrue();
    assertThat(PdfColumns.isHeaderLine("Datum Valuta", List.of("VALUTA DATUM"))).isFalse();
  }

  static PdfWord word(String text, float left, float right) {
    return new PdfWord(text, left, right);
  }

  private static PdfTextLine line(PdfWord... words) {
    String text = String.join(" ", List.of(words).stream().map(PdfWord::text).toList());
    return new PdfTextLine(1, text, List.of(words));
  }
}
