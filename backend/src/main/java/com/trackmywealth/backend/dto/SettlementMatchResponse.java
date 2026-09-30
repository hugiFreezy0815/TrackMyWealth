package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A settlement match as returned by the API (US-09-02), or an own-account transfer match (US-10-01,
 * {@code matchKind = TRANSFER}).
 *
 * <p>The {@code card*}/{@code payment*} fields are named for the card settlement they were built
 * for; on a transfer match {@code cardAccountId} and {@code cardTransactionId} are the credited
 * account and its leg. The {@code debit*}/{@code credit*} fields carry the same ids under names
 * that fit either kind - a card settlement's payment is its debit and the card-side credit its
 * credit - and are what a client should read for a transfer.
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
 * @param matchKind {@link SettlementMatchValues#CARD_SETTLEMENT} or {@link
 *     SettlementMatchValues#TRANSFER}
 * @param debitAccountId the account money left - same as {@code paymentAccountId}
 * @param debitTransactionId the outgoing leg - same as {@code paymentTransactionId}
 * @param creditAccountId the account money entered - same as {@code cardAccountId}
 * @param creditTransactionId the incoming leg, {@code null} for a one-sided candidate - same as
 *     {@code cardTransactionId}
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
    OffsetDateTime createdAt,
    String matchKind,
    UUID debitAccountId,
    UUID debitTransactionId,
    UUID creditAccountId,
    UUID creditTransactionId) {}
