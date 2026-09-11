package com.trackmywealth.backend.dto;

import com.trackmywealth.backend.validation.ValidCurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * Request body for {@code PUT /api/v1/accounts/{accountId}} (US-05-02).
 *
 * <p>Full-replacement (PUT, not JSON-merge-patch) semantics over the account's mutable attributes:
 * every field here is written as given, including {@code null} clearing an optional one. {@code
 * accountType} and {@code nativeCurrency} are included so a caller attempting to change either gets
 * a clear, structured rejection (FR-ACC-005/G5 and FR-ACC-002 respectively, both enforced by a
 * BEFORE UPDATE trigger - V4's {@code trg_account_type_immutable} and V24's {@code
 * trg_account_currency_immutable} - and translated by {@link
 * com.trackmywealth.backend.web.GlobalExceptionHandler}) rather than the field being silently
 * ignored or a raw 500; sending the account's own current value is a no-op.
 *
 * <p>Deliberately excludes {@code financialInstitutionId} (institution reassignment is its own
 * story, US-04-04), {@code status} (archive/restore is US-05-03), and the capability flags (their
 * own governance is US-05-04) - none of those are "ordinary" mutable attributes the way the fields
 * here are.
 */
public record UpdateAccountRequest(
    @NotBlank String name,
    // See AccountTypeValues' Javadoc for the full list of places a 12th account type touches.
    @NotBlank @Pattern(regexp = AccountTypeValues.PATTERN) String accountType,
    @NotBlank @ValidCurrencyCode String nativeCurrency,
    String identifierMasked,
    @Pattern(regexp = "[A-Z]{2}") String jurisdiction,
    LocalDate openedAt,
    LocalDate closedAt) {

  public UpdateAccountRequest {
    name = RequestStrings.blankToNull(name);
    accountType = RequestStrings.blankToNull(accountType);
    nativeCurrency = RequestStrings.blankToNull(nativeCurrency);
    identifierMasked = RequestStrings.blankToNull(identifierMasked);
    jurisdiction = RequestStrings.blankToNull(jurisdiction);
  }
}
