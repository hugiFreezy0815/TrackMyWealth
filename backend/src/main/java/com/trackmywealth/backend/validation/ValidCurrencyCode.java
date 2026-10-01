package com.trackmywealth.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A non-null value must be a real ISO 4217 currency code recognized by {@link
 * java.util.Currency#getInstance(String)}. {@code null} passes - pair with {@code @NotNull}/
 * {@code @NotBlank} where the field is unconditionally required; several currency-code fields in
 * this codebase (e.g. {@code CreateFinancialInstitutionRequest.containerCurrency}) are optional
 * because the service layer can derive a default.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CurrencyCodeValidator.class)
public @interface ValidCurrencyCode {

  String message() default "{tmw.validation.currency}";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
