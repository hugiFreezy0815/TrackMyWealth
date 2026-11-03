package com.trackmywealth.backend.dto;

import java.util.UUID;

/**
 * The filters of {@code GET /api/v1/accounts} (US-05-06, #380); each one left out matches every
 * account.
 *
 * @param portfolioId only the accounts of this portfolio; one of another workspace matches nothing,
 *     exactly like an unknown id
 * @param accountType only accounts of this type, one of the {@code account_type} values
 * @param archived {@code true} lists the archived accounts instead of the active ones
 */
public record AccountListFilter(UUID portfolioId, String accountType, boolean archived) {}
