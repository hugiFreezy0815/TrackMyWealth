package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A settlement match as returned by the API (US-09-02).
 *
 * @param paymentAccountId the card's settlement-source account, which holds {@code
 *     paymentTransactionId}
 * @param cardTransactionId the matching credit on the card - {@code null} for a one-sided
 *     candidate, where only the payment has been recorded so far (FR-CF-005)
 * @param amount the settled amount as a positive figure (the payment's magnitude)
 * @param status {@link SettlementMatchValues#PROPOSED} needs a member's decision; {@link
 *     SettlementMatchValues#CONFIRMED} is applied; {@link SettlementMatchValues#REJECTED} is
 *     declined and will not be proposed again
 * @param matchBasis {@link SettlementMatchValues#LEG_PAIR} or {@link
 *     SettlementMatchValues#BALANCE_EQUALS_PAYMENT}
 * @param decidedAt when the match left {@code PROPOSED}; {@code null} while it is still proposed
 */
public record SettlementMatchResponse(
    UUID id,
    UUID cardAccountId,
    UUID paymentAccountId,
    UUID paymentTransactionId,
    UUID cardTransactionId,
    BigDecimal amount,
    String currency,
    LocalDate paymentBookingDate,
    String status,
    String matchBasis,
    OffsetDateTime decidedAt,
    OffsetDateTime createdAt) {}
