package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * Request body for {@code POST /api/v1/accounts} (US-05-01).
 *
 * <p>{@code financialInstitutionId} is optional - omitted, it defaults to the workspace's own
 * "Personal Assets" container (FR-INS-011). {@code holdsPositions} is an optional override of the
 * {@code account_type}-driven default - meaningful for any type, but the one case that actually
 * needs it today is {@code PENSION}: whether a Pillar 3a account holds positions depends on the
 * provider (a VIAC account does, a PostFinance one may not), not on the type alone (US-05-04).
 *
 * <p>The remaining fields are extension-table attributes that are only required for specific {@code
 * account_type}s, because their underlying columns are {@code NOT NULL} with no sensible type-wide
 * default (unlike e.g. {@code account_securities}' columns, which are all optional or
 * DB-defaulted): {@code originalPrincipal}/{@code interestRatePercent} for {@code MORTGAGE}, {@code
 * originalPrincipal} for {@code LOAN}, {@code pensionScheme} for {@code PENSION}, {@code
 * customAssetType} for {@code CUSTOM_ASSET}. {@code billingCurrency} (for {@code CREDIT_CARD}) is
 * the one exception with a sensible default - it falls back to {@code nativeCurrency} when omitted,
 * since a card's own currency is a reasonable default for its billing currency. All of this is
 * validated in {@code AccountService}, not here, since requiredness depends on {@code accountType}.
 *
 * <p>Every optional {@code String} field is normalized blank-to-{@code null} in the compact
 * constructor - see {@code CreateFinancialInstitutionRequest} for why.
 */
public record CreateAccountRequest(
    UUID financialInstitutionId,
    @NotBlank String name,
    @NotBlank
        @Pattern(
            regexp =
                "CASH|SAVINGS|SECURITIES|MANAGED_MANDATE|PENSION|VESTED_BENEFITS|CREDIT_CARD"
                    + "|MORTGAGE|LOAN|CRYPTO|CUSTOM_ASSET")
        String accountType,
    @NotBlank @ValidCurrencyCode String nativeCurrency,
    Boolean holdsPositions,
    @ValidCurrencyCode String billingCurrency,
    BigDecimal originalPrincipal,
    BigDecimal interestRatePercent,
    @Pattern(regexp = "CH_PILLAR_3A|CH_PILLAR_2_VESTED_BENEFITS|DE_RIESTER|DE_RUERUP|DE_BAV|OTHER")
        String pensionScheme,
    @Pattern(regexp = "REAL_ESTATE|VEHICLE|PRECIOUS_METAL|COLLECTIBLE|OTHER")
        String customAssetType) {

  public CreateAccountRequest {
    name = blankToNull(name);
    accountType = blankToNull(accountType);
    nativeCurrency = blankToNull(nativeCurrency);
    billingCurrency = blankToNull(billingCurrency);
    pensionScheme = blankToNull(pensionScheme);
    customAssetType = blankToNull(customAssetType);
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }
}
