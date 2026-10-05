package com.trackmywealth.backend.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines a statement repeats at the top or bottom of its pages - a page header or footer, page
 * numbers included (#268). Such a line is no part of a booking: left in, a footer would be read as
 * the continuation of the page's last booking. A line counts when the same text is among the first
 * or last {@value #ZONE} lines of every page, or every page but one, and of at least two.
 *
 * <p>Where the lines' places on the page are known (a text layer), the line must also sit at the
 * same distance from the page's top edge, or from its bottom edge, on those pages, within {@value
 * #SAME_PLACE} points: a page header or footer is printed at a fixed place, while a counterparty
 * that happens to end a booking at the foot of each page moves with the bookings above it, a line
 * apart or more, and stays in its booking (PR #281 review). Both edges count, since a footer keeps
 * its distance from the bottom edge on pages of another height (a portrait first page before
 * landscape ones), and a header its distance from the top edge.
 *
 * <p>Only page numbers set their digits aside - {@code Seite 2 von 3}, {@code Page 2/3}, {@code
 * Blatt 2}, {@code S. 2} anywhere in the line, or a line that is just {@code 2/3} or {@code - 2 -}.
 * Dates, references and account identifiers must repeat exactly, so two IBANs or references ending
 * a booking at the foot of each page are no footer.
 */
public final class PageFurniture {

  static final int ZONE = 3;
  // Less than half the line spacing of a statement, so a line one line apart is never at the same
  // place; more than the few points a footer moves between pages of different heights.
  static final float SAME_PLACE = 6;
  // Furniture repeats: one page alone has none.
  private static final int MIN_PAGES = 2;
  private static final Pattern WHITE_SPACE = Pattern.compile("\\s+");
  // Applied to text whose white space is collapsed to single spaces, so each " ?" takes at most one
  // character and the match stays linear in the line's length.
  private static final Pattern PAGE_NUMBER =
      Pattern.compile("(?i)\\b(?:page|seite|blatt|s\\.) ?:? ?\\d+(?: ?(?:of|von|/) ?\\d+)?");
  private static final Pattern PAGE_NUMBER_LINE = Pattern.compile("\\d+ ?/ ?\\d+|- ?\\d+ ?-");
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  // A line in a page's top or bottom zone, by the text it is compared on.
  private record ZoneLine(int index, int page, float fromTop, float fromBottom) {}

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
    Map<String, List<ZoneLine>> zoneLinesByKey = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      PdfTextLine line = lines.get(i);
      if (inZone(i, pageBounds.get(line.page()))) {
        String key = key(line.text());
        if (!key.isEmpty()) {
          zoneLinesByKey
              .computeIfAbsent(key, k -> new ArrayList<>())
              .add(new ZoneLine(i, line.page(), line.fromTop(), line.fromBottom()));
        }
      }
    }
    int needed = Math.max(MIN_PAGES, pageBounds.size() - 1);
    Set<Integer> furniture = new HashSet<>();
    for (List<ZoneLine> sameText : zoneLinesByKey.values()) {
      for (ZoneLine line : sameText) {
        if (pagesAtItsPlace(line, sameText) >= needed) {
          furniture.add(line.index());
        }
      }
    }
    return furniture;
  }

  // The pages on which a line of the same text sits where line does, from the top or the bottom
  // edge; a line without a known place matches any.
  private static int pagesAtItsPlace(ZoneLine line, List<ZoneLine> sameText) {
    Set<Integer> pages = new HashSet<>();
    for (ZoneLine other : sameText) {
      if (samePlace(line.fromTop(), other.fromTop())
          || samePlace(line.fromBottom(), other.fromBottom())) {
        pages.add(other.page());
      }
    }
    return pages.size();
  }

  private static boolean samePlace(float distance, float other) {
    return Float.isNaN(distance) || Float.isNaN(other) || Math.abs(distance - other) <= SAME_PLACE;
  }

  private static boolean inZone(int index, int... bounds) {
    return index - bounds[0] < ZONE || bounds[1] - index < ZONE;
  }

  // The text with its white space collapsed and the digits of its page numbers set to 0.
  static String key(String text) {
    String collapsed = WHITE_SPACE.matcher(text.strip()).replaceAll(" ");
    if (PAGE_NUMBER_LINE.matcher(collapsed).matches()) {
      return DIGITS.matcher(collapsed).replaceAll("0");
    }
    return PAGE_NUMBER
        .matcher(collapsed)
        .replaceAll(
            number -> Matcher.quoteReplacement(DIGITS.matcher(number.group()).replaceAll("0")));
  }
}
