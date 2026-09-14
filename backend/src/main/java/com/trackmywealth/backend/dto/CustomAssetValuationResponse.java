package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomAssetValuationResponse(
    UUID id,
    UUID accountId,
    LocalDate valuationDate,
    BigDecimal value,
    String currency,
    String source,
    OffsetDateTime createdAt) {}
