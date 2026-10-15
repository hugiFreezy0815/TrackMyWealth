package com.trackmywealth.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * US-07-11: what a statement suggests for an account that does not exist yet. Advisory only: the
 * member picks one of the workspace's institutions (matching {@code institutionCatalogueId} where
 * one does), confirms or changes the type and currency, names the account and creates it with
 * {@code POST /api/v1/accounts}. No account, file or identifier is created or changed by this
 * response. Any field is {@code null} when the file does not tell.
 */
public record ImportAccountProposalResponse(
    @Schema(description = "The template version the file was read with.") UUID templateId,
    @Schema(
            description =
                "The institution catalogue entry of the template, not a workspace institution.")
        UUID institutionCatalogueId,
    @Schema(description = "That catalogue entry's name, for display.") String institutionName,
    @Schema(description = "CREDIT_CARD for a card statement, otherwise CASH.")
        String suggestedAccountType,
    @Schema(description = "The one currency every readable row is in.") String suggestedCurrency,
    @Schema(
            description =
                "The account identifier the file names, masked: an IBAN's last four characters, or"
                    + " a card number as masked as the statement had it.")
        String maskedIdentifier) {}
