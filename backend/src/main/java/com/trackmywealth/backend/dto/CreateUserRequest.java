package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import com.trackmywealth.backend.validation.ValidWorkspaceName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Workspace settings apply to new Standard Users. */
public record CreateUserRequest(
    @NotBlank @Email String email,
    // FR-AUT-007: minimum-length-led policy, not composition rules - same minimum as
    // SetupAdministratorRequest, for the same reasoning.
    @NotBlank @Size(min = 12) String password,
    @NotBlank @Pattern(regexp = UserRoleValues.PATTERN) String role,
    @NotBlank @Pattern(regexp = "EN|DE") String language,
    @ValidWorkspaceName String workspaceName,
    @Schema(
            description =
                "Initial workspace, Personal Assets, and user reporting currency. Defaults to CHF for a Standard User.")
        @ValidCurrencyCode
        String currencyCode) {

  public CreateUserRequest(String email, String password, String role, String language) {
    this(email, password, role, language, null, null);
  }
}
