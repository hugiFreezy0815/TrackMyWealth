package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * US-15-15 (#714): one buy or sell in effect on an account's ledger, as the cost basis from orders
 * reads it.
 *
 * @param quantity signed as the ledger stores it: positive for a buy, negative for a sell
 * @param unitPrice per unit, in {@code currency} (the trade currency)
 * @param tradeDate the trade date, or the booking date for a row without one
 */
public record TradeLeg(
    UUID accountId,
    UUID securityId,
    BigDecimal quantity,
    BigDecimal unitPrice,
    String currency,
    LocalDate tradeDate) {}
