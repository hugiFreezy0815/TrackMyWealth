package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Maps {@code savings_rate_methodology} (V14, RLS since V55) - a workspace's explicit savings-rate
 * settings (FR-BUD-009). Read-only so far: US-10-02 reads {@code include_mortgage_principal}; the
 * settings endpoint and the other toggles are US-10-05's. A workspace without a row has the
 * defaults the table declares.
 */
@Entity
@Table(name = "savings_rate_methodology")
public class SavingsRateMethodology {

  /** The table's own default for a workspace without a row. */
  public static final boolean DEFAULT_INCLUDE_MORTGAGE_PRINCIPAL = true;

  @Id
  @Column(name = "workspace_id", columnDefinition = "uuid")
  private UUID workspaceId;

  @Column(name = "include_mortgage_principal", nullable = false)
  private boolean includeMortgagePrincipal = DEFAULT_INCLUDE_MORTGAGE_PRINCIPAL;

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public boolean isIncludeMortgagePrincipal() {
    return includeMortgagePrincipal;
  }
}
