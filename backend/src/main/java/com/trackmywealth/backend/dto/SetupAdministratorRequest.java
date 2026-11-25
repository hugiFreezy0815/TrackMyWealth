package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import com.trackmywealth.backend.validation.ValidWorkspaceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bootstrap request. Currency initializes the administrator's profile preference. */
public record SetupAdministratorRequest(
    @NotBlank @Email String email,
    // FR-AUT-007: minimum-length-led policy, not composition rules.
    @NotBlank @Size(min = 12) String password,
    @Schema(
            deprecated = true,
            description =
                "Legacy setup field, validated but ignored: setup creates no workspace. Create a"
                    + " Standard User to provision one.")
        @ValidWorkspaceName
        String workspaceName,
    // US-04-01: validated against java.util.Currency (via @ValidCurrencyCode), not just
    // "3 uppercase letters" - the two currency-accepting endpoints in the app would otherwise
    // disagree on what a valid currency code is.
    @NotBlank @ValidCurrencyCode String currencyCode) {}
