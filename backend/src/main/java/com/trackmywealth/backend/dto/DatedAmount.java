package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** #607: an external flow of a period return, signed (into the scope is positive), on its day. */
public record DatedAmount(LocalDate date, BigDecimal amount) {}
