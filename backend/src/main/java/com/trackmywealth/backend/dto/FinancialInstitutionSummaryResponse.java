package com.trackmywealth.backend.dto;

import java.util.UUID;

public record FinancialInstitutionSummaryResponse(
    UUID id,
    UUID catalogueInstitutionId,
    String name,
    String country,
    String institutionType,
    String identifier,
    String logoUrl,
    String containerCurrency,
    boolean personalAssetsDefault,
    String status) {}
