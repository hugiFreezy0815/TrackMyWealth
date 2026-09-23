package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A shared security-master record (US-12-01). Identical for every workspace - it carries no tenant
 * information (NFR-LIC-007). {@code isin} is {@code null} for a security identified only by its
 * {@code syntheticKey}.
 */
public record SecurityResponse(
    UUID id,
    String isin,
    String syntheticKey,
    String displayName,
    String legalName,
    String denominationCurrency,
    String instrumentType,
    String assetClass,
    String securityCountry,
    String issuerCountry,
    String state,
    SecurityCompleteness completeness) {}
