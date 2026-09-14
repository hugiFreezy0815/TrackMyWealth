package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/accounts/{accountId}/valuations} (US-05-05).
 *
 * <p>{@code currency} must match the account's own {@code native_currency} - enforced by V26's
 * {@code custom_asset_valuation_currency_guard} trigger, not here, since the account isn't loaded
 * until the service layer resolves {@code accountId} from the path.
 */
public record CreateCustomAssetValuationRequest(
    @NotNull LocalDate valuationDate,
    @NotNull BigDecimal value,
    @NotNull @ValidCurrencyCode String currency) {}
