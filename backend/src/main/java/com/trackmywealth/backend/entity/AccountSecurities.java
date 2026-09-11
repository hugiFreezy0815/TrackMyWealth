package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Maps {@code account_securities} (V5) - shared-primary-key extension of {@link Account} for {@code
 * SECURITIES} and (V23) {@code MANAGED_MANDATE} account types (FR-ACC-011, DM-20: the two differ
 * only by {@link Account#isDiscretionary()}, not by shape).
 */
@Entity
@Table(name = "account_securities")
public class AccountSecurities extends AccountExtension {

  @Column(name = "default_cost_basis_method", nullable = false)
  private String defaultCostBasisMethod = "FIFO";

  @Column(name = "fees_included_in_cost_basis", nullable = false)
  private boolean feesIncludedInCostBasis = true;

  public String getDefaultCostBasisMethod() {
    return defaultCostBasisMethod;
  }

  public void setDefaultCostBasisMethod(String defaultCostBasisMethod) {
    this.defaultCostBasisMethod = defaultCostBasisMethod;
  }

  public boolean isFeesIncludedInCostBasis() {
    return feesIncludedInCostBasis;
  }

  public void setFeesIncludedInCostBasis(boolean feesIncludedInCostBasis) {
    this.feesIncludedInCostBasis = feesIncludedInCostBasis;
  }
}
