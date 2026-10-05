package com.trackmywealth.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
