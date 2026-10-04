package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for accepting or dismissing a reconciliation difference (US-25-03): every decision
 * carries the member's reason, so no difference is ever closed without an explanation (FR-REC-004).
 */
public record ReconciliationDecisionRequest(@NotBlank @Size(max = 500) String note) {}
