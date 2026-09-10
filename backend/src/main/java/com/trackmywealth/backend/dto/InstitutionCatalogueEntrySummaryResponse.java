package com.trackmywealth.backend.dto;

import java.util.UUID;

public record InstitutionCatalogueEntrySummaryResponse(
    UUID id,
    String name,
    String country,
    String institutionType,
    String identifier,
    String logoUrl) {}
