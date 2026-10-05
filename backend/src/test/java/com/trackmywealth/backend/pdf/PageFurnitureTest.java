package com.trackmywealth.backend.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** #268: the page header and footer a statement repeats on its pages. */
class PageFurnitureTest {

  @Test
  void aLineAtTheTopOrBottomOfEveryPageIsFurnitureItsNumbersAside() {
    List<PdfTextLine> lines = new ArrayList<>();
    page(lines, 1, "Invented Bank", "03.01.2031 Zahlung", "Erika Beispiel", "x", "Seite 1 von 3");
    page(lines, 2, "Invented Bank", "05.01.2031 Zahlung", "y", "z", "Seite 2 von 3");
    page(lines, 3, "Invented Bank", "07.01.2031 Zahlung", "w", "v", "Seite 3 von 3");

    // The headers, the footers, and - its dates aside - "Zahlung" at the top of every page: the
    // caller never drops a booking line, only the lines between bookings.
    assertThat(PageFurniture.indexes(lines))
        .containsExactlyInAnyOrder(0, 1, 4, 5, 6, 9, 10, 11, 14);
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

  private static void page(List<PdfTextLine> lines, int page, String... texts) {
    for (String text : texts) {
      lines.add(new PdfTextLine(page, text, List.of()));
    }
  }
}
