package com.trackmywealth.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkspaceNamesTest {

  @Test
  void trimsAndClearsMissingNames() {
    assertThat(WorkspaceNames.normalize(null)).isNull();
    assertThat(WorkspaceNames.normalize(" \t ")).isNull();
    assertThat(WorkspaceNames.normalize("  Hugi  ")).isEqualTo("Hugi");
  }

  // String.strip() keeps no-break spaces; a name of them would look empty but not be NULL.
  @Test
  void treatsNoBreakAndIdeographicSpacesAsBlank() {
    assertThat(WorkspaceNames.normalize(" ")).isNull();
    assertThat(WorkspaceNames.normalize("   　")).isNull();
    assertThat(WorkspaceNames.normalize(" Hugi ")).isEqualTo("Hugi");
    assertThat(WorkspaceNames.normalize("Family Hug")).isEqualTo("Family Hug");
    assertThat(WorkspaceNames.isValid(" ")).isTrue();
  }

  @Test
  void rejectsCharactersThatRenderAsNothing() {
    assertThat(WorkspaceNames.isValid("ㅤ")).isFalse();
    assertThat(WorkspaceNames.isValid("ᅟᅠ")).isFalse();
    assertThat(WorkspaceNames.isValid("ﾠ")).isFalse();
    assertThat(WorkspaceNames.isValid("⠀")).isFalse();
  }

  @Test
  void rejectsLoneSurrogates() {
    assertThat(WorkspaceNames.isValid("Hugi\uD800")).isFalse();
    assertThat(WorkspaceNames.isValid("\uDC00Hugi")).isFalse();
  }

  @Test
  void enforcesLengthAndRejectsControlCharacters() {
    assertThat(WorkspaceNames.isValid(null)).isTrue();
    assertThat(WorkspaceNames.isValid(" ")).isTrue();
    assertThat(WorkspaceNames.isValid("  " + "a".repeat(60) + "  ")).isTrue();
    assertThat(WorkspaceNames.isValid("a".repeat(61))).isFalse();
    assertThat(WorkspaceNames.isValid("a\nb")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u0000b")).isFalse();
  }

  @Test
  void rejectsInvisibleUnicodeAndBidiOverrides() {
    assertThat(WorkspaceNames.isValid("abc\u202Edef")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u200Bb")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u2060b")).isFalse();
    assertThat(WorkspaceNames.isValid("a\uFEFFb")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u2028b")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u2029b")).isFalse();
    assertThat(WorkspaceNames.isValid("Älterer Übertrag")).isTrue();
  }

  @Test
  void countsUnicodeCodePointsInsteadOfUtf16Units() {
    assertThat(WorkspaceNames.isValid("🪙".repeat(60))).isTrue();
    assertThat(WorkspaceNames.isValid("🪙".repeat(61))).isFalse();
  }

  // U+200D joins emoji sequences and U+200C keeps Persian or Indic letters apart; both are text.
  @Test
  void keepsTheJoinersThatEmojiAndScriptsNeed() {
    String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
    assertThat(WorkspaceNames.isValid("Family Hug " + family)).isTrue();
    assertThat(WorkspaceNames.isValid("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645")).isTrue();
  }
}
