package com.trackmywealth.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * F3 of the PR #267 review: a member's RE2 pattern is refused when its compiled program would be
 * too large, however short the pattern. Real statement patterns stay far below the limit.
 */
class Re2PatternsTest {

  private static final long OVER = Re2Patterns.MAX_PROGRAM_COST + 1;

  @Test
  void statementPatternsCompile() {
    assertThat(Re2Patterns.compile("(\\S+)\\s+(\\S+)\\s+(.+)").groupCount()).isEqualTo(3);
    assertThat(Re2Patterns.compile("^\\d{2}\\.\\d{2}\\.\\d{4}").matcher("04.01.2031 x").find())
        .isTrue();
    assertThat(Re2Patterns.compile("(?<currency>[A-Z]{3})").groupCount()).isOne();
    assertThat(Re2Patterns.programCost("(\\S+)\\s+".repeat(50))).isLessThan(500);
  }

  @Test
  void nestedCountedRepeatsAreRefusedHoweverShortThePattern() {
    assertThat(Re2Patterns.programCost("(?:a{1000}){1000}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("((a{100}){100}){100}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("(a{1000})*{1000}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("(?:x{40}){60}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("a{100,}{100}")).isEqualTo(OVER);
    assertThatThrownBy(() -> Re2Patterns.compile("(?:a{1000}){1000}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repeats more than a statement line needs");
  }

  /**
   * B1 of the second PR #267 review: an empty group compiles to capture instructions too. Costed at
   * nothing, {@code ((){1000}){1000}} passed, retained about 115 MB and overflowed the matching
   * thread's stack.
   */
  @Test
  void repeatsOfEmptyGroupsAndAlternativesAreRefusedToo() {
    assertThat(Re2Patterns.programCost("((){1000}){1000}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("((|){1000}){1000}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("(()){999}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("(?:\\b|){1000}")).isEqualTo(OVER);
    assertThat(Re2Patterns.programCost("(){3}")).isEqualTo(12);
    assertThat(Re2Patterns.programCost("(a|b)")).isEqualTo(7);
    assertThatThrownBy(() -> Re2Patterns.compile("((){1000}){1000}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repeats more than a statement line needs");
  }

  /**
   * The budget bounds the chain of empty instructions RE2/J follows recursively: the longest ones
   * it accepts match on a 512 KB stack, half of a request thread's default.
   */
  @Test
  void theLongestAcceptedChainsOfEmptyInstructionsMatchOnHalfTheDefaultStack()
      throws InterruptedException {
    List<String> longest =
        List.of("(){499}", "(|){399}", "(?:(){40}){12}", "()".repeat(500), "^{1000}", "\\b{1000}");
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread matcher =
        new Thread(
            null,
            () -> {
              for (String pattern : longest) {
                try {
                  Re2Patterns.compile(pattern).matcher("x".repeat(100)).find();
                } catch (RuntimeException | StackOverflowError e) {
                  failures.add(e);
                }
              }
            },
            "re2-small-stack",
            512 * 1024);
    matcher.start();
    matcher.join();

    assertThat(longest).allSatisfy(p -> assertThat(Re2Patterns.programCost(p)).isLessThan(OVER));
    assertThat(failures).isEmpty();
  }

  @Test
  void aCompiledPatternIsReused() {
    String pattern = "(\\d{2})\\.(\\d{2}) reused";

    assertThat(Re2Patterns.compile(pattern)).isSameAs(Re2Patterns.compile(pattern));
  }

  @Test
  void aQuotedRunCountsEveryCharacter() {
    String quoted = "\\Q" + "x".repeat(900) + "\\E";

    assertThat(Re2Patterns.programCost(quoted)).isGreaterThan(900);
    assertThat(Re2Patterns.programCost("(?:" + quoted + "){3}")).isEqualTo(OVER);
  }

  @Test
  void bracesThatAreNoRepeatCountAsLiterals() {
    assertThat(Re2Patterns.programCost("a{,5}")).isEqualTo(5);
    assertThat(Re2Patterns.programCost("[a-z{}]{3}")).isEqualTo(3);
    assertThat(Re2Patterns.programCost("\\p{Greek}{3}")).isEqualTo(3);
    assertThat(Re2Patterns.programCost("[[:alpha:]\\]]{2}")).isEqualTo(2);
    assertThat(Re2Patterns.programCost("a{1234567}")).isEqualTo(10);
  }

  @Test
  void invalidSyntaxIsRefusedWithItsOwnReason() {
    assertThatThrownBy(() -> Re2Patterns.compile("(a)(b)(\\1)"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid RE2");
    assertThatThrownBy(() -> Re2Patterns.compile("(unclosed"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid RE2");
  }
}
