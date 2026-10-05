package com.trackmywealth.backend.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The lines a statement repeats at the top or bottom of its pages - a page header or footer, page
 * numbers included (#268). Such a line is no part of a booking: left in, a footer would be read as
 * the continuation of the page's last booking. A line counts when the same text, its short numbers
 * aside, is among the first or last {@value #ZONE} lines of every page, or of every page but one
 * (the first page of a statement often has a header of its own), and of at least two.
 *
 * <p>Only numbers of up to {@value #MAX_IGNORED_DIGITS} digits are set aside, such as a page number
 * or a day and month: longer ones must match exactly. Otherwise two IBANs (four-digit groups) that
 * happen to end a booking at the bottom of each page would read as one footer and be dropped from
 * their bookings (PR #281 review).
 */
public final class PageFurniture {

  static final int ZONE = 3;
  static final int MAX_IGNORED_DIGITS = 3;
  // Furniture repeats: one page alone has none.
  private static final int MIN_PAGES = 2;
  private static final Pattern SHORT_NUMBER =
      Pattern.compile("(?<!\\d)\\d{1," + MAX_IGNORED_DIGITS + "}(?!\\d)");
  private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");

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
    // The key of each line in a page's zone; null elsewhere, where no line is furniture.
    List<String> keys = new ArrayList<>(lines.size());
    Map<String, Set<Integer>> pagesByKey = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      String key = inZone(i, pageBounds.get(lines.get(i).page())) ? key(lines.get(i).text()) : null;
      keys.add(key);
      if (key != null && !key.isEmpty()) {
        pagesByKey.computeIfAbsent(key, k -> new HashSet<>()).add(lines.get(i).page());
      }
    }
    int needed = Math.max(MIN_PAGES, pageBounds.size() - 1);
    Set<Integer> furniture = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      Set<Integer> pages = keys.get(i) == null ? null : pagesByKey.get(keys.get(i));
      if (pages != null && pages.size() >= needed) {
        furniture.add(i);
      }
    }
    return furniture;
  }

  private static boolean inZone(int index, int... bounds) {
    return index - bounds[0] < ZONE || bounds[1] - index < ZONE;
  }

  // The text with every short number a 0 and its white space collapsed: a page number aside.
  static String key(String text) {
    String numbersAside = SHORT_NUMBER.matcher(text.strip()).replaceAll("0");
    return WHITE_SPACE.matcher(numbersAside).replaceAll(" ");
  }
}
