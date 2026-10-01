package com.trackmywealth.backend.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountSummaryResponse(
    UUID id,
    UUID financialInstitutionId,
    String name,
    String accountType,
    String nativeCurrency,
    String nature,
    boolean holdsPositions,
    boolean hasTransactions,
    boolean hasStatementCycle,
    boolean hasAmortisation,
    boolean hasContributionLimit,
    boolean discretionary,
    boolean manualValuation,
    boolean countsAsSaving,
    String status,
    OffsetDateTime archivedAt,
    int version) {}
