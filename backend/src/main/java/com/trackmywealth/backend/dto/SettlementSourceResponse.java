package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * A card's settlement-source account (US-09-02). {@code settlementSourceAccountId} is {@code null}
 * when none is set - or when it is set but the caller may not see that account, so the response
 * never reveals an account id the caller has no access to.
 */
public record SettlementSourceResponse(
    UUID cardAccountId, UUID settlementSourceAccountId, int version) {}
