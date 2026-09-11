package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Request body for {@code POST /api/v1/institutions} (US-04-01).
 *
 * <p>When {@code catalogueInstitutionId} is set, {@code name}/{@code country}/{@code
 * institutionType}/{@code identifier}/{@code logoUrl} are taken from the catalogue entry itself -
 * any values supplied here for those fields are ignored; only {@code containerCurrency} may still
 * be supplied to override the country-derived default (FR-INS-004). When {@code
 * catalogueInstitutionId} is {@code null} (a custom institution, FR-INS-003), {@code name} and
 * {@code containerCurrency} are both required - there is no catalogue country to derive a currency
 * default from.
 *
 * <p>Every optional {@code String} field is normalized blank-to-{@code null} in the compact
 * constructor - Jakarta Validation's built-in constraints (like {@code @Pattern}/{@link
 * ValidCurrencyCode} below) already treat {@code null} as valid by convention, deferring "required"
 * to a separate {@code @NotNull}/{@code @NotBlank}, but a client that sends {@code ""} instead of
 * omitting an ignored-in-this-flow field (a common default for an unset form field) would otherwise
 * fail validation for a value the request contract says doesn't matter.
 */
public record CreateFinancialInstitutionRequest(
    UUID catalogueInstitutionId,
    String name,
    // ISO 3166-1 alpha-2 - financial_institution.country has no DB-level CHECK constraint (unlike
    // institution_catalogue.country), but is a fixed-width CHAR(2); an unvalidated, wrong-length
    // custom value would otherwise reach the database and surface as a raw, unhandled 500.
    @Pattern(regexp = "[A-Z]{2}") String country,
    // PERSONAL_ASSETS deliberately excluded - reserved for the one system-created default
    // container per workspace (V19's trigger); a user can never create one via this endpoint.
    @Pattern(
            regexp =
                "BANK|BROKER|PENSION_PROVIDER|PENSION_FUND|ASSET_MANAGER|CARD_ISSUER"
                    + "|CRYPTO_EXCHANGE|INSURER|PLATFORM|OTHER")
        String institutionType,
    String identifier,
    String logoUrl,
    @ValidCurrencyCode String containerCurrency) {

  public CreateFinancialInstitutionRequest {
    name = blankToNull(name);
    country = blankToNull(country);
    institutionType = blankToNull(institutionType);
    identifier = blankToNull(identifier);
    logoUrl = blankToNull(logoUrl);
    containerCurrency = blankToNull(containerCurrency);
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }
}
