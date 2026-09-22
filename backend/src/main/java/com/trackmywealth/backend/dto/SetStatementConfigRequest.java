package com.trackmywealth.backend.dto;

/**
 * Request body for {@code PUT /api/v1/accounts/{cardAccountId}/statement-config} (US-09-03,
 * FR-CC-008). Full-replacement semantics, same convention as {@link SetSettlementSourceRequest}:
 * {@code null} clears a field rather than leaving it untouched. Both fields are independent -
 * {@code statementDay} can be set without {@code dueDateOffsetDays} and vice versa - but the
 * statement view needs both before it can compute a due date.
 *
 * @param statementDay day of the month the statement closes (1-31); {@code null} clears it
 * @param dueDateOffsetDays days from the closing date to the payment due date; {@code null} clears
 *     it
 */
public record SetStatementConfigRequest(Integer statementDay, Integer dueDateOffsetDays) {}
