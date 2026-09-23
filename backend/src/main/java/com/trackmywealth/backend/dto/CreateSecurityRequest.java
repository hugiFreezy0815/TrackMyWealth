package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import com.trackmywealth.backend.validation.ValidIsin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/securities} (US-12-01): the minimal, hand-entered master
 * record that works with no external data provider (NFR-LIC-004/008).
 *
 * <p>{@code isin} is optional; without one the server generates a synthetic key (V7's {@code CHECK
 * (isin IS NOT NULL OR synthetic_key IS NOT NULL)}). If given it is upper-cased and must carry a
 * correct ISO 6166 check digit. {@code assetClass} is not a column of {@code security}: it is
 * stored as a single 100% {@code security_asset_class_weight} row flagged estimated.
 *
 * <p>If the ISIN already exists, the existing record is returned unchanged and every other field
 * here is ignored - shared reference data is never edited by a later caller (NFR-LIC-007).
 */
public record CreateSecurityRequest(
    @ValidIsin String isin,
    @NotBlank @Size(max = 255) String displayName,
    @NotBlank @ValidCurrencyCode String denominationCurrency,
    @NotBlank @Pattern(regexp = "EQUITY|ETF|FUND|BOND|CRYPTO|DERIVATIVE|OTHER")
        String instrumentType,
    @NotBlank
        @Pattern(
            regexp =
                "EQUITY|FIXED_INCOME|CASH_EQUIVALENT|REAL_ESTATE|COMMODITY|PRECIOUS_METAL"
                    + "|CRYPTOCURRENCY|ALTERNATIVES|MULTI_ASSET|DERIVATIVES|OTHER")
        String assetClass,
    @Pattern(regexp = "[A-Z]{2}") String securityCountry,
    @Pattern(regexp = "[A-Z]{2}") String issuerCountry) {

  public CreateSecurityRequest {
    isin = RequestStrings.blankToNull(isin);
    if (isin != null) {
      isin = isin.trim().toUpperCase(java.util.Locale.ROOT);
    }
    displayName = RequestStrings.blankToNull(displayName);
    denominationCurrency = RequestStrings.blankToNull(denominationCurrency);
    instrumentType = RequestStrings.blankToNull(instrumentType);
    assetClass = RequestStrings.blankToNull(assetClass);
    securityCountry = RequestStrings.blankToNull(securityCountry);
    issuerCountry = RequestStrings.blankToNull(issuerCountry);
  }
}
