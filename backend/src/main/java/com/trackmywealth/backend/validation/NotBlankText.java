package com.trackmywealth.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @NotBlank} by {@link String#isBlank()}: the value must not be {@code null} and must keep a
 * character after {@link String#strip()}. {@code @NotBlank} trims with {@link String#trim()}, which
 * leaves Unicode whitespace such as an em space (U+2003) in place, so a value of only such
 * characters passed it and then became empty where the service strips it - a check constraint's
 * error instead of a 400 (US-07-05's rollback reason). Use it on any text a service strips.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBlankTextValidator.class)
public @interface NotBlankText {

  String message() default "{tmw.validation.notBlankText}";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
