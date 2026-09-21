package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/accounts/{cardAccountId}/settlement-source} (US-09-02): the
 * account that pays this card's statement. {@code null} clears it, which stops further matching for
 * the card without touching matches already made.
 */
public record SetSettlementSourceRequest(UUID settlementSourceAccountId) {}
