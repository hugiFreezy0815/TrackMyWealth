package com.trackmywealth.backend.dto;

/**
 * What parsing a file for one account depends on (US-07-18): the account's currency (for a template
 * without a currency column) and whether it is a credit card ({@code has_statement_cycle}), whose
 * rows without a mapped type are card purchases and settlements rather than expenses and income.
 */
public record ImportAccountContext(String currency, boolean creditCard) {}
