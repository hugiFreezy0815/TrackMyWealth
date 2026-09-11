package com.trackmywealth.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;

public class CurrencyCodeValidator implements ConstraintValidator<ValidCurrencyCode, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    try {
      Currency.getInstance(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
