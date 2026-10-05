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
