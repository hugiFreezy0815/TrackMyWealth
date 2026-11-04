package com.trackmywealth.backend.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A shared security-master record (US-12-01). Identical for every workspace - it carries no tenant
 * information (NFR-LIC-007). {@code isin} is {@code null} for a security identified only by its
 * {@code syntheticKey}.
 */
public record SecurityResponse(
    UUID id,
    @Nullable String isin,
    @Nullable String syntheticKey,
    String displayName,
    String legalName,
    String denominationCurrency,
    @Nullable String instrumentType,
    @Nullable String assetClass,
    @Nullable String securityCountry,
    @Nullable String issuerCountry,
    String state,
    SecurityCompleteness completeness) {}
