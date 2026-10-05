package com.trackmywealth.backend.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #268: the page header and footer a statement repeats on its pages. */
class PageFurnitureTest {

  @Test
  void repeatedHeadersAndExplicitPageNumbersAreFurniture() {
    List<PdfTextLine> lines = new ArrayList<>();
    page(lines, 1, "Invented Bank", "03.01.2031 Zahlung", "Erika Beispiel", "x", "Seite 1 von 3");
    page(lines, 2, "Invented Bank", "05.01.2031 Zahlung", "y", "z", "Seite 2 von 3");
    page(lines, 3, "Invented Bank", "07.01.2031 Zahlung", "w", "v", "Seite 3 von 3");

    // Only headers and pagination repeat; booking dates retain their digits.
    assertThat(PageFurniture.indexes(lines)).containsExactlyInAnyOrder(0, 4, 5, 9, 10, 14);
  }

  /** The first page often has a header of its own; every page but one is enough. */
  @Test
  void everyPageButOneIsEnough() {
    List<PdfTextLine> lines = new ArrayList<>();
    page(lines, 1, "Summary", "a", "b", "c", "d", "e", "f");
    page(lines, 2, "Invented Bank", "g", "h", "i", "j", "k", "l");
    page(lines, 3, "Invented Bank", "m", "n", "o", "p", "q", "r");

    assertThat(PageFurniture.indexes(lines)).containsExactlyInAnyOrder(7, 14);
  }

  /** Text in the middle of a page, or on one page only, is never furniture. */
  @Test
  void aSinglePageOrALineInTheMiddleIsNoFurniture() {
    List<PdfTextLine> single = new ArrayList<>();
    page(single, 1, "Invented Bank", "a", "b", "c", "Seite 1 von 1");
    assertThat(PageFurniture.indexes(single)).isEmpty();

    List<PdfTextLine> lines = new ArrayList<>();
    page(lines, 1, "a", "b", "c", "Erika Beispiel", "d", "e", "f");
    page(lines, 2, "g", "h", "i", "Erika Beispiel", "j", "k", "l");
    assertThat(PageFurniture.indexes(lines)).isEmpty();
  }

  /**
   * PR #281 review: two different IBANs ending a booking at the bottom of each page are no footer,
   * though they differ only in digits; only explicit pagination ignores numbers.
   */
  @Test
  void linesDifferingInLongNumbersAreNoFurniture() {
    List<PdfTextLine> lines = new ArrayList<>();
    page(lines, 1, "Invented Bank", "a", "b", "c", "CH93 0076 2011 6238 5295 7", "Seite 1 von 2");
    page(lines, 2, "Invented Bank", "d", "e", "f", "CH56 0483 5012 3456 7800 9", "Seite 2 von 2");

    assertThat(PageFurniture.indexes(lines)).containsExactlyInAnyOrder(0, 5, 6, 11);
  }

  /**
   * PR #281 review: where the lines' places are known, a header or footer sits at the same place on
   * every page; the same text a line or more apart on each page (a counterparty under the page's
   * last booking) is no furniture.
   */
  @Test
  void furnitureSitsAtTheSamePlaceOnEveryPage() {
    List<PdfTextLine> lines = new ArrayList<>();
    lines.add(new PdfTextLine(1, "Invented Bank", List.of(), 40, 752));
    lines.add(new PdfTextLine(1, "Vermieter GmbH", List.of(), 700, 92));
    lines.add(new PdfTextLine(1, "Seite 1 von 2", List.of(), 760, 32));
    lines.add(new PdfTextLine(2, "Invented Bank", List.of(), 41, 751));
    lines.add(new PdfTextLine(2, "Vermieter GmbH", List.of(), 520, 272));
    lines.add(new PdfTextLine(2, "Seite 2 von 2", List.of(), 760, 32));

    assertThat(PageFurniture.indexes(lines)).containsExactlyInAnyOrder(0, 2, 3, 5);
  }

  /**
   * PR #281 review: on a portrait first page before landscape ones, a footer keeps its distance
   * from the bottom edge, not from the top, and a header its distance from the top edge.
   */
  @Test
  void aFooterKeepsItsPlaceFromTheBottomOnPagesOfAnotherHeight() {
    List<PdfTextLine> lines = new ArrayList<>();
    lines.add(new PdfTextLine(1, "Invented Bank", List.of(), 40, 802));
    lines.add(new PdfTextLine(1, "a", List.of(), 400, 442));
    lines.add(new PdfTextLine(1, "Invented Bank AG, Beispielweg 1", List.of(), 795, 47));
    lines.add(new PdfTextLine(2, "Invented Bank", List.of(), 40, 555));
    lines.add(new PdfTextLine(2, "b", List.of(), 300, 295));
    lines.add(new PdfTextLine(2, "Invented Bank AG, Beispielweg 1", List.of(), 550, 43));

    assertThat(PageFurniture.indexes(lines)).containsExactlyInAnyOrder(0, 2, 3, 5);
  }

  /**
   * Only page numbers set their digits aside, in the forms banks print them, anywhere in a line.
   */
  @Test
  void theKeySetsAsideOnlyPageNumbers() {
    assertThat(PageFurniture.key("  Seite 12 von  140 ")).isEqualTo("Seite 0 von 0");
    assertThat(PageFurniture.key("Page 2 of 12")).isEqualTo("Page 0 of 0");
    assertThat(PageFurniture.key("Page 2 / 12")).isEqualTo("Page 0 / 0");
    assertThat(PageFurniture.key("Seite: 2/3")).isEqualTo("Seite: 0/0");
    assertThat(PageFurniture.key("Kontoauszug 1/2031 - Blatt 2 von 3"))
        .isEqualTo("Kontoauszug 1/2031 - Blatt 0 von 0");
    assertThat(PageFurniture.key("Invented Bank AG, S. 2")).isEqualTo("Invented Bank AG, S. 0");
    assertThat(PageFurniture.key("2/3")).isEqualTo("0/0");
    assertThat(PageFurniture.key("- 2 -")).isEqualTo("- 0 -");

    assertThat(PageFurniture.key("Auszug 03.01.2031")).isEqualTo("Auszug 03.01.2031");
    assertThat(PageFurniture.key("Reference 111")).isEqualTo("Reference 111");
    assertThat(PageFurniture.key("CH93 0076 2011")).isEqualTo("CH93 0076 2011");
    assertThat(PageFurniture.key("Kontos. 5")).isEqualTo("Kontos. 5");
    assertThat(PageFurniture.key("Pages 2")).isEqualTo("Pages 2");
  }

  private static void page(List<PdfTextLine> lines, int page, String... texts) {
    for (String text : texts) {
      lines.add(new PdfTextLine(page, text, List.of()));
    }
  }
}
