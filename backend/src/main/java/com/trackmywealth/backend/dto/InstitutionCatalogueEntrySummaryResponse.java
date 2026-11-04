package com.trackmywealth.backend.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record InstitutionCatalogueEntrySummaryResponse(
    UUID id,
    String name,
    String country,
    String institutionType,
    @Nullable String identifier,
    @Nullable String logoUrl) {}
