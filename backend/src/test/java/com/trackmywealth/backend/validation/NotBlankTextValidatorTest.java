package com.trackmywealth.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link NotBlankText} reads blank as {@link String#strip()} does, Unicode whitespace included. */
class NotBlankTextValidatorTest {

  private final NotBlankTextValidator validator = new NotBlankTextValidator();

  @Test
  void textWithACharacterLeftAfterStrippingIsValid() {
    assertThat(validator.isValid("Wrong account", null)).isTrue();
    assertThat(validator.isValid(" x ", null)).isTrue();
  }

  @Test
  void nullEmptyAndAnyWhitespaceAloneAreBlank() {
    assertThat(validator.isValid(null, null)).isFalse();
    assertThat(validator.isValid("", null)).isFalse();
    assertThat(validator.isValid(" \t\n", null)).isFalse();
    // An em space and an ideographic space: String#trim keeps them, @NotBlank let them through.
    assertThat(validator.isValid(" 　", null)).isFalse();
  }
}
