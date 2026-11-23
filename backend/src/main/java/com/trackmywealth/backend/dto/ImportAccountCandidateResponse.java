package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * US-07-34 (#365): an account a statement uploaded without an account may belong to, one the member
 * may import into. {@code reason} says why it is listed: {@code IDENTIFIER} when the file names it
 * by a masked card number (never certain: several cards can share the visible digits), {@code
 * INSTITUTION} when it is at the bank whose template read the file.
 */
public record ImportAccountCandidateResponse(
    UUID accountId,
    String accountName,
    String accountType,
    String nativeCurrency,
    UUID portfolioId,
    String portfolioName,
    @Schema(description = "The identifier registered on the account, masked.")
        @Nullable String maskedIdentifier,
    @Schema(
            allowableValues = {
              ImportRoutingValues.REASON_IDENTIFIER,
              ImportRoutingValues.REASON_INSTITUTION
            })
        String reason) {}
