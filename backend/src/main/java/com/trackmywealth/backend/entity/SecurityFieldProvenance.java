package com.trackmywealth.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Maps {@code security_field_provenance} (V7, FR-SMD-012): where a master-data field's value came
 * from. {@code source} is deliberately just {@code MANUAL} for a hand-entered value - never a user
 * or workspace id, since shared reference data must not be traceable to the tenant that caused it
 * to exist (NFR-LIC-007).
 */
@Entity
@Table(name = "security_field_provenance")
public class SecurityFieldProvenance {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "security_id", nullable = false, columnDefinition = "uuid")
  private UUID securityId;

  @Column(name = "field_name", nullable = false)
  private String fieldName;

  @Column(nullable = false)
  private String source;

  private String confidence;

  public UUID getId() {
    return id;
  }

  public UUID getSecurityId() {
    return securityId;
  }

  public void setSecurityId(UUID securityId) {
    this.securityId = securityId;
  }

  public String getFieldName() {
    return fieldName;
  }

  public void setFieldName(String fieldName) {
    this.fieldName = fieldName;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getConfidence() {
    return confidence;
  }

  public void setConfidence(String confidence) {
    this.confidence = confidence;
  }
}
