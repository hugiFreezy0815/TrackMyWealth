package com.trackmywealth.backend.pdf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lines a statement repeats at the top or bottom of its pages - a page header or footer, page
 * numbers included (#268). Such a line is no part of a booking: left in, a footer would be read as
 * the continuation of the page's last booking. A line counts when the same text, its digits aside,
 * is among the first or last {@value #ZONE} lines of every page, or of every page but one (the
 * first page of a statement often has a header of its own), and of at least two.
 */
public final class PageFurniture {

  static final int ZONE = 3;
  // Furniture repeats: one page alone has none.
  private static final int MIN_PAGES = 2;

  private PageFurniture() {}

  /** The indexes into {@code lines} (every page, in order) of the lines that are page furniture. */
  public static Set<Integer> indexes(List<PdfTextLine> lines) {
    Map<Integer, int[]> pageBounds = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      int[] bounds = pageBounds.computeIfAbsent(lines.get(i).page(), page -> new int[] {-1, -1});
      if (bounds[0] < 0) {
        bounds[0] = i;
      }
      bounds[1] = i;
    }
    if (pageBounds.size() < MIN_PAGES) {
      return Set.of();
    }
    Map<String, Set<Integer>> pagesByKey = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      if (inZone(i, pageBounds.get(lines.get(i).page()))) {
        String key = key(lines.get(i).text());
        if (!key.isEmpty()) {
          pagesByKey.computeIfAbsent(key, k -> new HashSet<>()).add(lines.get(i).page());
        }
      }
    }
    int needed = Math.max(MIN_PAGES, pageBounds.size() - 1);
    Set<Integer> furniture = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      Set<Integer> pages = pagesByKey.get(key(lines.get(i).text()));
      if (pages != null
          && pages.size() >= needed
          && inZone(i, pageBounds.get(lines.get(i).page()))) {
        furniture.add(i);
      }
    }
    return furniture;
  }

  private static boolean inZone(int index, int... bounds) {
    return index - bounds[0] < ZONE || bounds[1] - index < ZONE;
  }

  // The text with every digit a 0 and its white space collapsed: a page number or date aside.
  private static String key(String text) {
    return text.strip().replaceAll("\\d", "0").replaceAll("\\s+", " ");
  }
}
