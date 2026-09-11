package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

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
 * constructor via {@link RequestStrings#blankToNull} - see its Javadoc for why.
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
    name = RequestStrings.blankToNull(name);
    country = RequestStrings.blankToNull(country);
    institutionType = RequestStrings.blankToNull(institutionType);
    identifier = RequestStrings.blankToNull(identifier);
    logoUrl = RequestStrings.blankToNull(logoUrl);
    containerCurrency = RequestStrings.blankToNull(containerCurrency);
  }
}
