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
    assertThat(WorkspaceNames.isValid("a\u200Db")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u2028b")).isFalse();
    assertThat(WorkspaceNames.isValid("a\u2029b")).isFalse();
    assertThat(WorkspaceNames.isValid("Älterer Übertrag")).isTrue();
  }

  @Test
  void countsUnicodeCodePointsInsteadOfUtf16Units() {
    assertThat(WorkspaceNames.isValid("🪙".repeat(60))).isTrue();
    assertThat(WorkspaceNames.isValid("🪙".repeat(61))).isFalse();
  }
}
