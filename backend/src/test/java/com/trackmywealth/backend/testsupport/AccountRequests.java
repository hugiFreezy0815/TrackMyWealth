package com.trackmywealth.backend.testsupport;

import com.trackmywealth.backend.dto.CreateAccountRequest;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Builds {@link CreateAccountRequest}s for tests by name rather than position: a test sets only the
 * fields it is about, and a new request field does not touch every test that creates an account.
 * Kept here rather than on the request record, so the production contract stays free of test
 * conveniences (same as {@link TransactionRequests}).
 */
public final class AccountRequests {

  private AccountRequests() {}

  /** An account with the three fields every type needs; everything else starts {@code null}. */
  public static Builder account(String name, String accountType, String nativeCurrency) {
    return new Builder(name, accountType, nativeCurrency);
  }

  /** The optional fields of a {@link CreateAccountRequest}, each {@code null} unless set. */
  public static final class Builder {

    private final String name;
    private final String accountType;
    private final String nativeCurrency;
    private UUID financialInstitutionId;
    private Boolean holdsPositions;
    private String billingCurrency;
    private BigDecimal originalPrincipal;
    private BigDecimal interestRatePercent;
    private String pensionScheme;
    private String customAssetType;
    private Boolean countsAsSaving;

    private Builder(String name, String accountType, String nativeCurrency) {
      this.name = name;
      this.accountType = accountType;
      this.nativeCurrency = nativeCurrency;
    }

    public Builder financialInstitutionId(UUID financialInstitutionId) {
      this.financialInstitutionId = financialInstitutionId;
      return this;
    }

    public Builder holdsPositions(Boolean holdsPositions) {
      this.holdsPositions = holdsPositions;
      return this;
    }

    public Builder billingCurrency(String billingCurrency) {
      this.billingCurrency = billingCurrency;
      return this;
    }

    public Builder originalPrincipal(BigDecimal originalPrincipal) {
      this.originalPrincipal = originalPrincipal;
      return this;
    }

    public Builder interestRatePercent(BigDecimal interestRatePercent) {
      this.interestRatePercent = interestRatePercent;
      return this;
    }

    public Builder pensionScheme(String pensionScheme) {
      this.pensionScheme = pensionScheme;
      return this;
    }

    public Builder customAssetType(String customAssetType) {
      this.customAssetType = customAssetType;
      return this;
    }

    public Builder countsAsSaving(Boolean countsAsSaving) {
      this.countsAsSaving = countsAsSaving;
      return this;
    }

    public CreateAccountRequest build() {
      return new CreateAccountRequest(
          financialInstitutionId,
          name,
          accountType,
          nativeCurrency,
          holdsPositions,
          billingCurrency,
          originalPrincipal,
          interestRatePercent,
          pensionScheme,
          customAssetType,
          countsAsSaving);
    }
  }
}
