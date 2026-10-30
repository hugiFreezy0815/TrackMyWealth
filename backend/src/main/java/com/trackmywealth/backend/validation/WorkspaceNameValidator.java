package com.trackmywealth.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class WorkspaceNameValidator implements ConstraintValidator<ValidWorkspaceName, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return WorkspaceNames.isValid(value);
  }
}
