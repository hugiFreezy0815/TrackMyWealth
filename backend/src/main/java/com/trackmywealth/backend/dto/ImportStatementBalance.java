package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One statement observation, not a ledger transaction or an opening balance. */
public record ImportStatementBalance(LocalDate date, BigDecimal amount) {}
