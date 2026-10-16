package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for creating ({@code POST}) or replacing ({@code PUT}) one entry of a loan's or
 * mortgage's amortisation schedule (US-10-02, FR-NW-006): what the lender's schedule states for one
 * due date, in the loan's own currency. The amounts are magnitudes, never negative; interest plus
 * principal is the instalment, which must be more than zero ({@code AmortisationScheduleService}).
 */
public record AmortisationScheduleEntryRequest(
    @NotNull LocalDate dueDate,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal interestComponent,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal principalComponent,
    @NotNull @Digits(integer = 16, fraction = 4) BigDecimal remainingBalance) {}
