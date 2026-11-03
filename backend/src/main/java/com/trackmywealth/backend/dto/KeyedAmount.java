package com.trackmywealth.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of a batched sum (#380): the id the sum is grouped by - an account's or a snapshot's, as
 * the query documents - and the sum itself.
 */
public record KeyedAmount(UUID key, BigDecimal amount) {}
