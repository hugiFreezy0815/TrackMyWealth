package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/portfolios} (US-04-01, US-04-06).
 *
 * <p>When {@code catalogueInstitutionId} is set, {@code country}/{@code institutionType}/{@code
 * identifier}/{@code logoUrl} are taken from the catalogue entry itself - any values supplied here
 * for those fields are ignored. {@code name} names the portfolio (FR-INS-001, e.g. "VIAC 3a") and
 * defaults to the catalogue entry's name; {@code portfolioCurrency} overrides the country-derived
 * default (FR-INS-004). When {@code catalogueInstitutionId} is {@code null} (a portfolio without an
 * institution behind it, FR-INS-003), {@code name} and {@code portfolioCurrency} are both required
 * - there is no catalogue country to derive a currency default from.
 *
 * <p>{@code name} and {@code logoUrl} are bounded ({@link #MAX_NAME_LENGTH}, {@link
 * #MAX_LOGO_URL_LENGTH}): both columns are {@code TEXT}. A logo is an {@code https} URL the client
 * loads, never a script or an inline {@code data:} payload ({@link #LOGO_URL_PATTERN}).
 *
 * <p>Every optional {@code String} field is normalized blank-to-{@code null} in the compact
 * constructor via {@link RequestStrings#blankToNull} - see its Javadoc for why.
 */
public record CreatePortfolioRequest(
    UUID catalogueInstitutionId,
    @Size(max = MAX_NAME_LENGTH) String name,
    // ISO 3166-1 alpha-2 - portfolio.country has no DB-level CHECK constraint (unlike
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
    @Size(max = MAX_LOGO_URL_LENGTH) @Pattern(regexp = LOGO_URL_PATTERN) String logoUrl,
    @ValidCurrencyCode String portfolioCurrency) {

  /** The longest portfolio name a member may give, e.g. "PostFinance Vorsorge 3a". */
  public static final int MAX_NAME_LENGTH = 100;

  /** The longest logo URL accepted. */
  public static final int MAX_LOGO_URL_LENGTH = 2048;

  /** An absolute {@code https} URL without whitespace. */
  public static final String LOGO_URL_PATTERN = "https://\\S+";

  public CreatePortfolioRequest {
    name = RequestStrings.blankToNull(name);
    country = RequestStrings.blankToNull(country);
    institutionType = RequestStrings.blankToNull(institutionType);
    identifier = RequestStrings.blankToNull(identifier);
    logoUrl = RequestStrings.blankToNull(logoUrl);
    portfolioCurrency = RequestStrings.blankToNull(portfolioCurrency);
  }
}
