package com.trackmywealth.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Shared validation for workspace setup and renaming; null and blank names are allowed. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = WorkspaceNameValidator.class)
public @interface ValidWorkspaceName {

  String message() default "{tmw.validation.workspaceName}";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
