package com.trackmywealth.backend.dto;

/**
 * What parsing a file for one account depends on (US-07-18): the account's currency (for a template
 * without a currency column) and what kind of account takes the rows without a mapped type - on a
 * credit card ({@code has_statement_cycle}) they are card purchases and settlements, on a loan or
 * mortgage ({@code has_amortisation}, US-07-21) interest charged and instalments, rather than
 * expenses and income.
 */
public record ImportAccountContext(String currency, boolean creditCard, boolean loan) {}
