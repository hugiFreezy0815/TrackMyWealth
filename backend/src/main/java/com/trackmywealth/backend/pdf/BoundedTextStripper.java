package com.trackmywealth.backend.pdf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.text.TextPositionComparator;
import org.apache.pdfbox.util.IterativeMergeSort;

/**
 * A text stripper that runs each drawing operation, and keeps each character, only while its {@link
 * PdfReadBudget} allows (#268): reading the text layer of a page runs its content stream, and every
 * form it draws, again for each time it is drawn, and keeps an object per character until the page
 * is done. Its caller checks the budget after each page.
 *
 * <p>It reads a page into {@link PdfTextLine}s: each line's text exactly as {@link
 * PDFTextStripper#getText} writes it (words in reading order, sorted by position when asked), and
 * its words with their horizontal positions, which locate a statement's columns, and how far it
 * sits from the page's edges, which tells a page header or footer from text that merely repeats
 * (#268).
 *
 * <p>#322: a blank glyph mostly covered by a visible glyph of the same line separates nothing and
 * is dropped before the page's lines are built ({@link #dropCoveredBlanks}). A statement may draw
 * invisible spaces over its own text - a Sparkasse loan statement draws two, a little below the
 * baseline, over the first digit of a booking date, which PDFBox would otherwise read as {@code " 3
 * 1.03.2031"}. PDFBox's own {@code removeContainedSpaces} drops only a space that lies wholly
 * inside the glyph before it, which these do not: they sit lower and one starts before the digit.
 */
public final class BoundedTextStripper extends PDFTextStripper {

  private static final int HALF_TURN = 180;
  // How much of a blank glyph's width a visible neighbour must cover for the blank to be dropped:
  // a real space sits between two words and overlaps neither.
  private static final float COVERED_SHARE = 0.5f;
  // How far apart, as a share of the smaller font size, two baselines may be on one line.
  private static final float SAME_LINE_SHARE = 0.4f;
  private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");

  private final PdfReadBudget budget;
  private final List<PdfTextLine> lines = new ArrayList<>();
  // The pieces of the line and of the word being read, joined once each ends.
  private final List<String> lineText = new ArrayList<>();
  private final List<PdfWord> lineWords = new ArrayList<>();
  private final List<String> word = new ArrayList<>();
  private float wordLeft;
  private float wordRight;
  // How far the line's first word sits from the page's top and bottom edges; NaN until it has one.
  private float lineTop = Float.NaN;
  private float lineBottom = Float.NaN;
  private int page;

  public BoundedTextStripper(PdfReadBudget budget) {
    super();
    this.budget = budget;
  }

  /** The lines of the 1-based {@code page} of {@code document}, in reading order. */
  public List<PdfTextLine> readLines(PDDocument document, int page) throws IOException {
    this.page = page;
    lines.clear();
    lineText.clear();
    word.clear();
    lineWords.clear();
    lineTop = Float.NaN;
    lineBottom = Float.NaN;
    setStartPage(page);
    setEndPage(page);
    writeText(document, Writer.nullWriter());
    return List.copyOf(lines);
  }

  // Every word of a line comes through here; separators and the page's end through the methods
  // below, so each line is rebuilt as getText would have written it.
  @Override
  protected void writeString(String text, List<TextPosition> positions) {
    lineText.add(text);
    int firstWord = lineWords.size();
    for (TextPosition position : positions) {
      String unicode = position.getUnicode();
      if (unicode == null || unicode.isBlank()) {
        endWord();
      } else {
        if (word.isEmpty()) {
          wordLeft = position.getXDirAdj();
        }
        if (Float.isNaN(lineTop)) {
          lineTop = position.getYDirAdj();
          lineBottom = pageHeight(position) - lineTop;
        }
        word.add(unicode);
        wordRight = position.getXDirAdj() + position.getWidthDirAdj();
      }
    }
    endWord();
    nameAsWritten(text, firstWord);
  }

  // The words from firstWord on take the text PDFBox writes for them - a ligature resolved, as in
  // the line's text - whenever it splits into as many words. A header label is then found in a
  // line's words exactly as detection finds it in the line's text (PR #281 review).
  private void nameAsWritten(String text, int firstWord) {
    String stripped = text.strip();
    if (stripped.isEmpty()) {
      return;
    }
    String[] written = WHITE_SPACE.split(stripped);
    if (written.length != lineWords.size() - firstWord) {
      return;
    }
    for (int i = 0; i < written.length; i++) {
      PdfWord read = lineWords.get(firstWord + i);
      lineWords.set(firstWord + i, new PdfWord(written[i], read.left(), read.right()));
    }
  }

  @Override
  protected void writeWordSeparator() {
    lineText.add(getWordSeparator());
  }

  @Override
  protected void writeLineSeparator() {
    endLine();
  }

  @Override
  protected void writePageEnd() {
    endLine();
  }

  private void endWord() {
    if (!word.isEmpty()) {
      lineWords.add(new PdfWord(String.join("", word), wordLeft, wordRight));
      word.clear();
    }
  }

  private void endLine() {
    lines.add(new PdfTextLine(page, String.join("", lineText), lineWords, lineTop, lineBottom));
    lineText.clear();
    lineWords.clear();
    lineTop = Float.NaN;
    lineBottom = Float.NaN;
  }

  // The page's height in the direction its text is read: its width when it is turned a quarter.
  private static float pageHeight(TextPosition position) {
    return position.getRotation() % HALF_TURN == 0
        ? position.getPageHeight()
        : position.getPageWidth();
  }

  // With sortByPosition, PDFBox sorts each article and then builds its lines; the covered blanks
  // are dropped from the same order first. Sorting the sorted list again in super is cheap.
  @Override
  protected void writePage() throws IOException {
    if (getSortByPosition()) {
      for (List<TextPosition> article : charactersByArticle) {
        sortAsPdfBoxDoes(article);
        dropCoveredBlanks(article);
      }
    }
    super.writePage();
  }

  // The order PDFTextStripper#writePage reads an article in: its comparator is not transitive, so
  // a sort the JDK refuses is done by PDFBox's own merge sort, as there.
  private static void sortAsPdfBoxDoes(List<TextPosition> article) {
    TextPositionComparator comparator = new TextPositionComparator();
    try {
      Collections.sort(article, comparator);
    } catch (IllegalArgumentException e) {
      IterativeMergeSort.sort(article, comparator);
    }
  }

  /**
   * Removes from {@code glyphs}, in reading order, every blank glyph that its previous or next
   * visible glyph on the same line covers for more than half of its width. One pass, comparing each
   * blank with its two visible neighbours only, so a page of any size costs linear time.
   */
  static void dropCoveredBlanks(List<TextPosition> glyphs) {
    List<TextPosition> kept = new ArrayList<>(glyphs.size());
    TextPosition previousVisible = null;
    int nextVisible = 0;
    boolean dropped = false;
    for (int i = 0; i < glyphs.size(); i++) {
      TextPosition glyph = glyphs.get(i);
      if (!isBlank(glyph)) {
        previousVisible = glyph;
        kept.add(glyph);
        continue;
      }
      if (nextVisible <= i) {
        nextVisible = i + 1;
        while (nextVisible < glyphs.size() && isBlank(glyphs.get(nextVisible))) {
          nextVisible++;
        }
      }
      TextPosition next = nextVisible < glyphs.size() ? glyphs.get(nextVisible) : null;
      if (covers(previousVisible, glyph) || covers(next, glyph)) {
        dropped = true;
      } else {
        kept.add(glyph);
      }
    }
    if (dropped) {
      glyphs.clear();
      glyphs.addAll(kept);
    }
  }

  // A glyph's height above its baseline, in the page's text space; its font size when unknown.
  private static float heightOf(TextPosition glyph) {
    return glyph.getHeightDir() > 0 ? glyph.getHeightDir() : glyph.getFontSizeInPt();
  }

  private static boolean isBlank(TextPosition glyph) {
    String unicode = glyph.getUnicode();
    return unicode == null || unicode.isBlank();
  }

  // Whether the visible glyph is on the blank's line and covers most of the blank's width. On one
  // line means both: their glyph boxes overlap vertically by at least half the lower box, and their
  // baselines are less than 0.4 of the smaller font size apart. Either alone is not enough: PDFBox
  // measures glyph height differently per font, and may report a scaled font's size many times its
  // drawn height (an LBS statement: 140 for text 4 high). The blanks over a Sparkasse booking date
  // overlap the digits by 0.72 of their height and sit 0.27 of the smaller size lower; the lines of
  // a heading in large type overlap by at most half and are half their size or more apart.
  private static boolean covers(TextPosition visible, TextPosition blank) {
    if (visible == null || blank.getWidthDirAdj() <= 0) {
      return false;
    }
    float visibleHeight = heightOf(visible);
    float blankHeight = heightOf(blank);
    float verticalOverlap =
        Math.min(visible.getYDirAdj(), blank.getYDirAdj())
            - Math.max(visible.getYDirAdj() - visibleHeight, blank.getYDirAdj() - blankHeight);
    if (verticalOverlap < COVERED_SHARE * Math.min(visibleHeight, blankHeight)) {
      return false;
    }
    float smallerSize = Math.min(visible.getFontSizeInPt(), blank.getFontSizeInPt());
    if (Math.abs(visible.getYDirAdj() - blank.getYDirAdj()) >= smallerSize * SAME_LINE_SHARE) {
      return false;
    }
    float blankLeft = blank.getXDirAdj();
    float blankRight = blankLeft + blank.getWidthDirAdj();
    float overlap =
        Math.min(blankRight, visible.getXDirAdj() + visible.getWidthDirAdj())
            - Math.max(blankLeft, visible.getXDirAdj());
    return overlap > COVERED_SHARE * blank.getWidthDirAdj();
  }

  @Override
  protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
    if (budget.allowOperation()) {
      super.processOperator(operator, operands);
    }
  }

  @Override
  protected void processTextPosition(TextPosition text) {
    if (budget.allowCharacter()) {
      super.processTextPosition(text);
    }
  }
}
