package com.trackmywealth.backend.pdf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

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
 */
public final class BoundedTextStripper extends PDFTextStripper {

  private static final int HALF_TURN = 180;
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
