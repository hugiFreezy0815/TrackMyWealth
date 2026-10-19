package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Maps {@code workspace_security_ticker} (V99, #273): the security a ticker means on one
 * workspace's statements. Read-only here: rows are written by {@code
 * WorkspaceSecurityTickerRepository#map}, an upsert, when a member creates a security with a
 * ticker. RLS-protected by its own {@code workspace_id}.
 */
@Entity
@Table(name = "workspace_security_ticker")
public class WorkspaceSecurityTicker {

  private static final String UUID_COLUMN = "uuid";

  @Id
  @Column(columnDefinition = UUID_COLUMN)
  private UUID id;

  @Column(name = "workspace_id", columnDefinition = UUID_COLUMN, nullable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private String ticker;

  @Column(name = "security_id", columnDefinition = UUID_COLUMN, nullable = false)
  private UUID securityId;

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public String getTicker() {
    return ticker;
  }

  public UUID getSecurityId() {
    return securityId;
  }
}
