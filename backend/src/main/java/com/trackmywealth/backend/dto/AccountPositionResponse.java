package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-15-05: one holding from a bank's newest holdings statement. Values from the statement are
 * never re-priced. A missing reported value or missing FX rate gives an unknown account value.
 */
public record AccountPositionResponse(
    UUID securityId,
    String displayName,
    @Nullable String isin,
    BigDecimal quantity,
    @Nullable BigDecimal price,
    @Schema(description = "Currency of price and value, as the statement printed them.")
        @Nullable String priceCurrency,
    @Nullable BigDecimal value,
    @Nullable BigDecimal valueInAccountCurrency,
    @Schema(description = "Cost basis as the statement printed it, never converted.")
        @Nullable BigDecimal costBasis,
    boolean costBasisEstimated,
    @Schema(
            description =
                "valueInAccountCurrency minus costBasis. Null without a cost basis or value, and"
                    + " when priceCurrency differs from the account currency: the cost basis"
                    + " carries no currency, so the gain would not be certain.")
        @Nullable BigDecimal unrealisedGain,
    @Schema(description = "unrealisedGain in percent of costBasis; null when unrealisedGain is.")
        @Nullable BigDecimal unrealisedGainPercent,
    ValueStatus valueStatus) {}
