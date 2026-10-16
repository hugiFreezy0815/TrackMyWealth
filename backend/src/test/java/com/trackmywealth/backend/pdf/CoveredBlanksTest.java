package com.trackmywealth.backend.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

/**
 * PR #333 review: the bounds of {@link BoundedTextStripper#dropCoveredBlanks} (#322), on glyphs
 * placed by hand. A blank is dropped only when a visible neighbour on its own line covers more than
 * half of its width; anything less, and it still separates two words.
 */
class CoveredBlanksTest {

  private static final float PAGE_WIDTH = 612;
  private static final float PAGE_HEIGHT = 792;
  private static final int SIZE = 8;
  // A glyph's height above its baseline at SIZE, and a space's width there (Helvetica: 278/1000).
  private static final float HEIGHT = 6;
  private static final float SPACE = 2.224f;
  private static final float BASELINE = 100;

  @Test
  void aBlankCoveredForMoreThanHalfItsWidthIsDropped() {
    TextPosition digit = visible("3", 40, 4.448f);
    // The digit ends at 44.448; the blank starts 0.2 before half of its width lies under it.
    TextPosition blank = blank(44.448f - SPACE / 2 - 0.2f, SPACE);

    assertThat(dropCoveredBlanks(digit, blank)).containsExactly(digit);
  }

  /** A space kerning has narrowed, but less than halfway, still separates its words. */
  @Test
  void aBlankCoveredForLessThanHalfItsWidthIsKept() {
    TextPosition word = visible("o", 40, 4.448f);
    TextPosition blank = blank(44.448f - SPACE / 2 + 0.2f, SPACE);
    TextPosition next = visible("N", 44.448f + SPACE / 2 + 0.2f, 5.776f);

    assertThat(dropCoveredBlanks(word, blank, next)).containsExactly(word, blank, next);
  }

  @Test
  void aBlankTheNextGlyphCoversIsDroppedToo() {
    TextPosition blank = blank(39.6f, SPACE);
    TextPosition digit = visible("3", 40, 4.448f);

    assertThat(dropCoveredBlanks(blank, digit)).containsExactly(digit);
  }

  /** Each of two blanks in a row is measured against its own visible neighbours. */
  @Test
  void blanksInARowAreEachMeasuredOnTheirOwn() {
    TextPosition uncovered = blank(30, SPACE);
    TextPosition covered = blank(39.6f, SPACE);
    TextPosition digit = visible("3", 40, 4.448f);
    TextPosition after = blank(44.448f, SPACE);
    TextPosition word = visible("D", 60, 5.776f);

    assertThat(dropCoveredBlanks(uncovered, covered, digit, after, word))
        .containsExactly(uncovered, digit, after, word);
  }

  /**
   * A blank whose baseline is 0.4 of the font size or more away is on another line, even when its
   * box, tall as PDFBox may measure it, overlaps the glyph's.
   */
  @Test
  void aBlankOnAnotherBaselineIsKept() {
    TextPosition digit = visible("3", 40, 4.448f);
    TextPosition below = glyph(" ", 40, SPACE, BASELINE + 0.4f * SIZE, 2 * HEIGHT);

    assertThat(dropCoveredBlanks(digit, below)).containsExactly(digit, below);
  }

  /** Neither is a blank whose box barely reaches the glyph's, however close the baselines. */
  @Test
  void aBlankThatHardlyOverlapsTheGlyphVerticallyIsKept() {
    TextPosition digit = visible("3", 40, 4.448f);
    TextPosition flat = glyph(" ", 40, SPACE, BASELINE + 3, 1);

    assertThat(dropCoveredBlanks(digit, flat)).containsExactly(digit, flat);
  }

  @Test
  void aBlankWithoutWidthIsLeftToPdfBox() {
    TextPosition digit = visible("3", 40, 4.448f);
    TextPosition empty = blank(41, 0);

    assertThat(dropCoveredBlanks(digit, empty)).containsExactly(digit, empty);
  }

  /** A glyph without a character is no word: it separates words as a space does. */
  @Test
  void aGlyphWithoutACharacterIsABlank() {
    TextPosition digit = visible("3", 40, 4.448f);
    TextPosition unmapped = glyph(null, 40.5f, SPACE, BASELINE, HEIGHT);

    assertThat(dropCoveredBlanks(digit, unmapped)).containsExactly(digit);
  }

  @Test
  void blanksWithNothingVisibleAroundThemAreKept() {
    TextPosition first = blank(40, SPACE);
    TextPosition second = blank(40.5f, SPACE);

    assertThat(dropCoveredBlanks(first, second)).containsExactly(first, second);
    assertThat(dropCoveredBlanks()).isEmpty();
  }

  /**
   * A sort the JDK refuses, because PDFBox's comparator is not transitive, is finished by PDFBox's
   * own merge sort, as {@code PDFTextStripper#writePage} does.
   */
  @Test
  void aSortTheJdkRefusesIsDoneByPdfBoxsMergeSort() {
    TextPosition first = visible("a", 10, 4);
    TextPosition second = visible("b", 20, 4);
    TextPosition third = visible("c", 30, 4);
    List<TextPosition> article = new ArrayList<>(List.of(third, first, second));
    AtomicBoolean refused = new AtomicBoolean();
    Comparator<TextPosition> byX = Comparator.comparingDouble(TextPosition::getXDirAdj);
    Comparator<TextPosition> refusedOnce =
        (left, right) -> {
          if (refused.compareAndSet(false, true)) {
            throw new IllegalArgumentException("Comparison method violates its general contract!");
          }
          return byX.compare(left, right);
        };

    BoundedTextStripper.sortAsPdfBoxDoes(article, refusedOnce);

    assertThat(refused).isTrue();
    assertThat(article).containsExactly(first, second, third);
  }

  private static List<TextPosition> dropCoveredBlanks(TextPosition... glyphs) {
    List<TextPosition> article = new ArrayList<>(List.of(glyphs));
    BoundedTextStripper.dropCoveredBlanks(article);
    return article;
  }

  private static TextPosition visible(String text, float left, float width) {
    return glyph(text, left, width, BASELINE, HEIGHT);
  }

  private static TextPosition blank(float left, float width) {
    return glyph(" ", left, width, BASELINE, HEIGHT);
  }

  // A glyph on an upright page, its baseline measured from the page's top as PDFBox reads it.
  private static TextPosition glyph(
      String unicode, float left, float width, float baseline, float height) {
    float y = PAGE_HEIGHT - baseline;
    return new TextPosition(
        0,
        PAGE_WIDTH,
        PAGE_HEIGHT,
        new Matrix(SIZE, 0, 0, SIZE, left, y),
        left + width,
        y,
        height,
        width,
        SPACE,
        unicode,
        new int[] {' '},
        null,
        SIZE,
        SIZE);
  }
}
