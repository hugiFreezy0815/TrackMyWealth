package com.trackmywealth.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * ISO 6166: two-letter prefix, nine alphanumerics, one check digit. The check digit is the Luhn
 * digit of the string with every letter expanded to its two-digit value (A=10 ... Z=35). Rejecting
 * a typo here matters more than usual: {@code security} is global and its ISIN unique (V7), so a
 * mistyped ISIN would create a second master record that identity resolution (FR-SMD-008) would
 * later have to merge.
 */
public class IsinValidator implements ConstraintValidator<ValidIsin, String> {

  // Luhn: a doubled digit above 9 contributes the sum of its two digits, i.e. d - 9.
  private static final int LUHN_MAX_DIGIT = 9;

  private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || isValidIsin(value);
  }

  public static boolean isValidIsin(String isin) {
    if (isin == null || !FORMAT.matcher(isin).matches()) {
      return false;
    }
    StringBuilder digits = new StringBuilder();
    for (char c : isin.toCharArray()) {
      digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
    }
    int sum = 0;
    boolean doubleIt = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubleIt) {
        d *= 2;
        if (d > LUHN_MAX_DIGIT) {
          d -= LUHN_MAX_DIGIT;
        }
      }
      sum += d;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }
}
