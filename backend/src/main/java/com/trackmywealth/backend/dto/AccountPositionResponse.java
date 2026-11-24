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
    @Schema(
            description =
                "Cost basis as the snapshot holds it (printed or entered), or else as the orders"
                    + " give it (costBasisSource); never converted.")
        @Nullable BigDecimal costBasis,
    @Schema(description = "Currency of costBasis; null when unknown (#527).")
        @Nullable String costBasisCurrency,
    @Schema(
            description =
                "For a cost basis in another currency: its cost in the account currency at the"
                    + " purchase-date rate(s), as reported. Never derived at today's rate.")
        @Nullable BigDecimal costBasisInAccountCurrency,
    boolean costBasisEstimated,
    @Schema(
            description =
                "Where costBasis comes from (US-15-15): STATEMENT as an imported statement printed"
                    + " it, ENTERED by the member, ORDERS calculated at the average price of the"
                    + " account's buys and sells (fees excluded) when the snapshot has none. Null"
                    + " without a cost basis.")
        @Nullable CostBasisSource costBasisSource,
    @Schema(
            description =
                "Gain in unrealisedGainCurrency, the cost basis currency: valueInAccountCurrency"
                    + " minus costBasis for a cost basis in the account currency, value minus"
                    + " costBasis for one in priceCurrency. Null without a cost basis, its"
                    + " currency or a value, and for a cost basis in a third currency.")
        @Nullable BigDecimal unrealisedGain,
    @Schema(description = "unrealisedGain in percent of costBasis; null when unrealisedGain is.")
        @Nullable BigDecimal unrealisedGainPercent,
    @Schema(description = "Currency of unrealisedGain; null when unrealisedGain is.")
        @Nullable String unrealisedGainCurrency,
    @Schema(
            description =
                "Gain in the account currency: valueInAccountCurrency minus the cost in the account"
                    + " currency (costBasis when it is in it, else costBasisInAccountCurrency)."
                    + " Includes the FX change since purchase. Null when that cost is unknown.")
        @Nullable BigDecimal unrealisedGainInAccountCurrency,
    @Schema(
            description =
                "unrealisedGainInAccountCurrency in percent of the cost in the account currency;"
                    + " null when that gain is.")
        @Nullable BigDecimal unrealisedGainInAccountCurrencyPercent,
    ValueStatus valueStatus) {}
