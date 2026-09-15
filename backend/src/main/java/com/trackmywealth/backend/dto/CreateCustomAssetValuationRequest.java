package com.trackmywealth.backend.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/accounts/{accountId}/valuations} (US-05-05).
 *
 * <p>Deliberately has no {@code currency} field: a valuation's currency is not an independent piece
 * of caller-supplied data, it's fully determined by the account it belongs to (V26/V27's {@code
 * custom_asset_valuation_guard_2_currency} trigger requires it match the account's own {@code
 * native_currency}). {@code CustomAssetValuationService} derives it from the {@link
 * com.trackmywealth.backend.entity.Account} it already loads to validate {@code accountId}, so a
 * caller can never send a value that only round-trips to the DB to be rejected.
 */
public record CreateCustomAssetValuationRequest(
    @NotNull LocalDate valuationDate, @NotNull BigDecimal value) {}
