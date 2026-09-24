package com.trackmywealth.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A 12-character ISIN whose format and ISO 6166 check digit are both correct. {@code null} is
 * valid, exactly like {@link ValidCurrencyCode}: pair with {@code @NotBlank} where required.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IsinValidator.class)
public @interface ValidIsin {

  String message() default "must be a valid ISIN (12 characters, ISO 6166 check digit)";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
