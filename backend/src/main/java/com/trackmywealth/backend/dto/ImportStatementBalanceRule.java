package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * US-07-12: one dated balance observation from a CSV preamble or PDF balance marker. The RE2
 * pattern has exactly two groups: statement date, then signed balance. The date uses the
 * template's dateFormat and the amount uses its decimal/thousands separators.
 */
public record ImportStatementBalanceRule(
    @Schema(allowableValues = {"PREAMBLE_LINE", "PDF_BALANCE_LINE"}) String source,
    @Schema(description = "RE2 pattern with two capture groups: date and amount.")
        String pattern) {}
