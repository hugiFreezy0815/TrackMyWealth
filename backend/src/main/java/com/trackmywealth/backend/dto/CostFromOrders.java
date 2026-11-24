package com.trackmywealth.backend.dto;

import java.math.BigDecimal;

/** US-15-15 (#714): a position's cost basis calculated from its orders, in the orders' currency. */
public record CostFromOrders(BigDecimal costBasis, String currency) {}
