package com.trackmywealth.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CurrencyCodeValidator implements ConstraintValidator<ValidCurrencyCode, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || CurrencyCodes.isValid(value);
  }
}
