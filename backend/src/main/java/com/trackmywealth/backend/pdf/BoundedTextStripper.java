package com.trackmywealth.backend.pdf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
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
 * its words with their horizontal positions, which locate a statement's columns (#268).
 */
public final class BoundedTextStripper extends PDFTextStripper {

  private final PdfReadBudget budget;
  private final List<PdfTextLine> lines = new ArrayList<>();
  // The pieces of the line and of the word being read, joined once each ends.
  private final List<String> lineText = new ArrayList<>();
  private final List<PdfWord> lineWords = new ArrayList<>();
  private final List<String> word = new ArrayList<>();
  private float wordLeft;
  private float wordRight;
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
    for (TextPosition position : positions) {
      String unicode = position.getUnicode();
      if (unicode == null || unicode.isBlank()) {
        endWord();
      } else {
        if (word.isEmpty()) {
          wordLeft = position.getXDirAdj();
        }
        word.add(unicode);
        wordRight = position.getXDirAdj() + position.getWidthDirAdj();
      }
    }
    endWord();
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
    lines.add(new PdfTextLine(page, String.join("", lineText), lineWords));
    lineText.clear();
    lineWords.clear();
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
