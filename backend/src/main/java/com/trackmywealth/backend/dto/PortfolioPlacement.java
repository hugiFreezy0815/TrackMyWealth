package com.trackmywealth.backend.dto;

/**
 * Where an account is shown in the Portfolio View (US-04-06): its section, and its type for the
 * client to label it. Decided by {@code AccountService#portfolioPlacementsOf}, the only place
 * allowed to read {@code account_type} (US-05-04).
 */
public record PortfolioPlacement(String accountType, PortfolioSection section) {}
