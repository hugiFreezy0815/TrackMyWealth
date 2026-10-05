package com.trackmywealth.backend.pdf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The columns of a statement's booking table, located by the labels of its header line (#268): not
 * by fixed coordinates, since a layout moves between pages and versions. Each label spans from the
 * left edge of its first word to the right edge of its last, and a word of a booking line belongs
 * to the label it overlaps most - an amount is right-aligned under its label and may reach past
 * either edge, but always overlaps its own label more than its neighbour's. A word that overlaps
 * none belongs to the nearest.
 */
public final class PdfColumns {

  // Where each label is on the page, from the left edge of its first word to the right of its last.
  private record Span(float left, float right) {}

  private final List<Span> spans;

  private PdfColumns(List<Span> spans) {
    this.spans = List.copyOf(spans);
  }

  /**
   * The columns {@code line} heads, when it is a header line of {@code labels}: it holds each
   * label's words, compared ignoring case, one label after the other in order. Other words may sit
   * between them (e.g. a currency after a balance label).
   */
  public static Optional<PdfColumns> of(PdfTextLine line, List<String> labels) {
    List<String> words = line.words().stream().map(PdfWord::text).toList();
    int[] starts = labelStarts(words, labels);
    if (starts.length == 0) {
      return Optional.empty();
    }
    List<Span> spans = new ArrayList<>(labels.size());
    for (int label = 0; label < labels.size(); label++) {
      int last = starts[label] + wordsOf(labels.get(label)).size() - 1;
      spans.add(new Span(line.words().get(starts[label]).left(), line.words().get(last).right()));
    }
    return Optional.of(new PdfColumns(spans));
  }

  /**
   * Whether {@code text} holds a header line of {@code labels}, by its words alone: detection's
   * test, on text without positions.
   */
  public static boolean isHeaderLine(String text, List<String> labels) {
    return labelStarts(wordsOf(text), labels).length > 0;
  }

  /** The column {@code word} belongs to, as an index into the labels. */
  public int columnOf(PdfWord word) {
    int best = 0;
    float bestOverlap = -1;
    float bestDistance = Float.MAX_VALUE;
    for (int column = 0; column < spans.size(); column++) {
      Span span = spans.get(column);
      float overlap = word.overlap(span.left(), span.right());
      float distance = word.distance(span.left(), span.right());
      if (overlap > bestOverlap || overlap == bestOverlap && distance < bestDistance) {
        best = column;
        bestOverlap = overlap;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** The words of {@code words} under each column, joined by a space; empty where none is. */
  public List<String> cells(List<PdfWord> words) {
    List<StringBuilder> cells = new ArrayList<>(spans.size());
    spans.forEach(span -> cells.add(new StringBuilder()));
    for (PdfWord word : words) {
      StringBuilder cell = cells.get(columnOf(word));
      if (!cell.isEmpty()) {
        cell.append(' ');
      }
      cell.append(word.text());
    }
    return cells.stream().map(StringBuilder::toString).toList();
  }

  // Where each label's first word is in words, or an empty array when not every label is there in
  // order.
  private static int[] labelStarts(List<String> words, List<String> labels) {
    int[] starts = new int[labels.size()];
    int from = 0;
    for (int label = 0; label < labels.size(); label++) {
      List<String> wanted = wordsOf(labels.get(label));
      int start = indexOf(words, wanted, from);
      if (wanted.isEmpty() || start < 0) {
        return new int[0];
      }
      starts[label] = start;
      from = start + wanted.size();
    }
    return starts;
  }

  private static int indexOf(List<String> words, List<String> wanted, int from) {
    for (int start = from; start + wanted.size() <= words.size(); start++) {
      boolean found = true;
      for (int i = 0; i < wanted.size() && found; i++) {
        found = words.get(start + i).toLowerCase(Locale.ROOT).equals(wanted.get(i));
      }
      if (found) {
        return start;
      }
    }
    return -1;
  }

  // The lower-cased words of text, split at white space.
  private static List<String> wordsOf(String text) {
    String stripped = text.strip().toLowerCase(Locale.ROOT);
    return stripped.isEmpty() ? List.of() : Arrays.asList(stripped.split("\\s+"));
  }
}
