package com.trackmywealth.backend.dto;

import java.util.UUID;

/** Account and portfolio labels bulk-loaded for transaction read responses. */
public record TransactionReadAccountDetails(String name, UUID portfolioId, String portfolioName) {}
