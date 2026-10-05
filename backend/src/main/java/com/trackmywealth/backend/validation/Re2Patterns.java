package com.trackmywealth.backend.validation;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one check of a regular expression a member writes (#268, PDF import layouts). RE2 matches in
 * linear time, but linear in the size of its compiled program, and RE2/J has no limit on that size
 * (unlike RE2's {@code max_mem}). Counted repeats multiply it: the 17 characters {@code
 * (?:a{1000}){1000}} compile to a million instructions, about 60 MB of heap. So the program is
 * estimated before the pattern is compiled, and one over {@value #MAX_PROGRAM_COST} is refused.
 * That is far above any real statement line's pattern: a 50-column row pattern costs a few hundred.
 * A group costs its capture instructions even when it is empty, so {@code ((){1000}){1000}}, which
 * matches nothing, is refused too: it compiled to millions of empty instructions, retaining about
 * 115 MB and overflowing the stack of the thread that matched it. RE2/J follows a chain of empty
 * instructions (captures, alternatives, {@code ^}, {@code \b}) recursively, and the budget also
 * bounds that chain: at most five hundred empty groups (RE2/J itself refuses a count over a
 * thousand), which match on half of a request thread's default 1 MB stack.
 *
 * <p>Every member pattern is compiled here, also on the import path: the check then holds for a
 * template stored before it existed, and the last {@value #CACHE_SIZE} patterns are compiled once,
 * not once per template and request (detection tries every PDF template on each upload).
 */
public final class Re2Patterns {

  /** The largest estimated program a member's pattern may compile to. */
  public static final long MAX_PROGRAM_COST = 2_000;

  private static final long CAP = MAX_PROGRAM_COST + 1;
  private static final int MAX_REPEAT_DIGITS = 6;
  private static final char ESCAPE = '\\';
  private static final char CLASS_END = ']';
  private static final char QUOTE = 'Q';
  private static final char REPEAT_START = '{';
  private static final char REPEAT_END = '}';
  private static final char REPEAT_SEPARATOR = ',';
  private static final String QUOTE_END = "\\E";
  private static final String NAMED_CLASS_START = "[:";
  private static final String NAMED_CLASS_END = ":]";
  // A group compiles to a capture start and end, a chain RE2/J follows recursively (see the class
  // comment). Counted twice, so an empty group's share of the budget matches its stack use.
  private static final long GROUP_COST = 4;
  // An accepted pattern retains at most a few hundred kilobytes, so the cache at most ~15 MB.
  private static final int CACHE_SIZE = 64;
  // Compiled RE2/J patterns are immutable and thread-safe; access order makes it least recently
  // used.
  private static final Map<String, Pattern> CACHE =
      Collections.synchronizedMap(
          new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
              return size() > CACHE_SIZE;
            }
          });

  private Re2Patterns() {}

  /**
   * {@code pattern} compiled, or the copy compiled before.
   *
   * @throws IllegalArgumentException with a user-facing reason when it is not valid RE2 or its
   *     program would exceed {@link #MAX_PROGRAM_COST}
   */
  public static Pattern compile(String pattern) {
    Pattern cached = CACHE.get(pattern);
    if (cached != null) {
      return cached;
    }
    Pattern compiled = compileChecked(pattern);
    CACHE.put(pattern, compiled);
    return compiled;
  }

  private static Pattern compileChecked(String pattern) {
    if (programCost(pattern) > MAX_PROGRAM_COST) {
      throw new IllegalArgumentException(
          "The pattern repeats more than a statement line needs; use fewer or smaller counted"
              + " repeats such as {n}.");
    }
    try {
      return Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("The pattern is not a valid RE2 regular expression.", e);
    }
  }

  /**
   * An upper estimate of the instructions RE2 compiles {@code pattern} into: one per literal,
   * escape or character class (a quoted {@code \Q...\E} run one per character), a group the sum of
   * its content plus {@value #GROUP_COST} (even an empty one), an alternative and an operator such
   * as {@code *} one more each, and a counted repeat the cost of what it repeats (at least one)
   * times its largest count. Saturates just above {@link #MAX_PROGRAM_COST}, so it never overflows.
   * The syntax is not checked here; compiling does that.
   */
  static long programCost(String pattern) {
    Deque<Long> enclosing = new ArrayDeque<>();
    long total = 0;
    // The cost of the item a following operator or counted repeat applies to.
    long last = 0;
    int i = 0;
    while (i < pattern.length() && total < CAP) {
      switch (pattern.charAt(i)) {
        case '(' -> {
          enclosing.push(total);
          total = 0;
          last = 0;
          i++;
        }
        case ')' -> {
          long group = add(total, GROUP_COST);
          total = add(enclosing.isEmpty() ? 0 : enclosing.pop(), group);
          last = group;
          i++;
        }
        case '|' -> {
          total = add(total, 1);
          last = 0;
          i++;
        }
        case '*', '+', '?' -> {
          total = add(total, 1);
          last = add(last, 1);
          i++;
        }
        default -> {
          Optional<Repeat> repeat = countedRepeat(pattern, i);
          if (repeat.isPresent()) {
            total = add(total, multiply(last, repeat.get().count() - 1));
            last = multiply(last, repeat.get().count());
            i = repeat.get().end();
          } else {
            int end = atomEnd(pattern, i);
            last = isQuotedRun(pattern, i, end) ? end - i : 1;
            total = add(total, last);
            i = end;
          }
        }
      }
    }
    while (!enclosing.isEmpty()) {
      total = add(total, enclosing.pop());
    }
    return Math.min(total, CAP);
  }

  /** A counted repeat ending before {@code end}, making at most {@code count} copies. */
  private record Repeat(int end, long count) {}

  // {n}, {n,} or {n,m} at i ({n,} compiles n copies and a loop); empty when the brace, or the
  // character, is a literal.
  private static Optional<Repeat> countedRepeat(String pattern, int i) {
    if (pattern.charAt(i) != REPEAT_START) {
      return Optional.empty();
    }
    int j = i + 1;
    int digits = digitsAt(pattern, j);
    if (digits == 0) {
      return Optional.empty();
    }
    long min = Long.parseLong(pattern.substring(j, j + digits));
    j += digits;
    long count = min;
    if (j < pattern.length() && pattern.charAt(j) == REPEAT_SEPARATOR) {
      j++;
      int maxDigits = digitsAt(pattern, j);
      count =
          maxDigits == 0
              ? min + 1
              : Math.max(min, Long.parseLong(pattern.substring(j, j + maxDigits)));
      j += maxDigits;
    }
    if (j >= pattern.length() || pattern.charAt(j) != REPEAT_END) {
      return Optional.empty();
    }
    return Optional.of(new Repeat(j + 1, Math.max(1, count)));
  }

  private static int digitsAt(String pattern, int start) {
    int end = start;
    while (end < pattern.length()
        && end - start <= MAX_REPEAT_DIGITS
        && Character.isDigit(pattern.charAt(end))) {
      end++;
    }
    return end - start > MAX_REPEAT_DIGITS ? 0 : end - start;
  }

  private static boolean isQuotedRun(String pattern, int start, int end) {
    return end - start > 2 && pattern.charAt(start) == ESCAPE && pattern.charAt(start + 1) == QUOTE;
  }

  private static long add(long a, long b) {
    return Math.min(CAP, a + b);
  }

  // a is at least one: even a repeat of nothing compiles one instruction per copy.
  private static long multiply(long a, long b) {
    if (b <= 0) {
      return 0;
    }
    long item = Math.max(1, a);
    return item > CAP / b ? CAP : Math.min(CAP, item * b);
  }

  // The index after the atom at i: an escape (with its {...} argument, or a whole \Q...\E run),
  // a character class (with escapes and [:name:] inside), or a single character.
  private static int atomEnd(String pattern, int i) {
    return switch (pattern.charAt(i)) {
      case '\\' -> escapeEnd(pattern, i);
      case '[' -> classEnd(pattern, i);
      default -> i + 1;
    };
  }

  private static int escapeEnd(String pattern, int i) {
    if (i + 1 >= pattern.length()) {
      return pattern.length();
    }
    return switch (pattern.charAt(i + 1)) {
      case 'p', 'P', 'x' -> {
        if (pattern.startsWith("{", i + 2)) {
          int close = pattern.indexOf(REPEAT_END, i + 3);
          yield close < 0 ? pattern.length() : close + 1;
        }
        yield i + 2;
      }
      case 'Q' -> {
        int close = pattern.indexOf(QUOTE_END, i + 2);
        yield close < 0 ? pattern.length() : close + QUOTE_END.length();
      }
      default -> i + 2;
    };
  }

  private static int classEnd(String pattern, int i) {
    int j = i + 1;
    if (pattern.startsWith("^", j)) {
      j++;
    }
    if (pattern.startsWith("]", j)) {
      j++;
    }
    while (j < pattern.length()) {
      char c = pattern.charAt(j);
      if (c == CLASS_END) {
        return j + 1;
      }
      if (c == ESCAPE) {
        j = escapeEnd(pattern, j);
      } else if (pattern.startsWith(NAMED_CLASS_START, j)) {
        int close = pattern.indexOf(NAMED_CLASS_END, j + 2);
        j = close < 0 ? j + 1 : close + NAMED_CLASS_END.length();
      } else {
        j++;
      }
    }
    return pattern.length();
  }
}
