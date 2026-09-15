package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AccountOwnershipResponse(
    UUID id,
    UUID accountId,
    UUID workspaceMemberId,
    BigDecimal share,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
