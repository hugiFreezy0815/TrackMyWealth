package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The card's current statement cycle (US-09-03, FR-CC-008/009): the most recently closed period as
 * of today, its closing balance and due date, and whether it has been paid.
 *
 * @param periodStart the first day of the period, inclusive
 * @param periodEnd the closing date, inclusive - a transaction booked on this day belongs to this
 *     period, not the next one
 * @param dueDate {@code periodEnd} plus the card's {@code due_date_offset_days}
 * @param closingBalance the amount owed as of {@code periodEnd}, in {@code currency} - the same
 *     non-negative-by-convention magnitude as {@link AccountValuation#value()} (zero or negative
 *     only when the card is not in debt / is in credit)
 * @param paid {@code true} when nothing was owed, or a confirmed settlement for exactly {@code
 *     closingBalance} was booked between {@code periodEnd} and {@code dueDate} - see {@code
 *     CardStatementService} for why this is a documented reading, not one the story's acceptance
 *     criteria test directly
 */
public record CardStatementResponse(
    UUID cardAccountId,
    LocalDate periodStart,
    LocalDate periodEnd,
    LocalDate dueDate,
    BigDecimal closingBalance,
    String currency,
    boolean paid) {}
