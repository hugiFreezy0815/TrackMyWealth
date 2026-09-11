package com.trackmywealth.backend.dto;

/**
 * The {@code account_type} value set, shared by every request DTO that validates it ({@link
 * CreateAccountRequest}, {@link UpdateAccountRequest}) so there is exactly one regex to update, not
 * one per DTO. Kept in sync by hand with {@code AccountService}'s {@code
 * applyCapabilityDefaults}/{@code createExtensionRowIfNeeded} switches and V4's {@code
 * account_type} CHECK constraint - all three, plus this regex, must be updated together when a 12th
 * account type is ever added.
 */
final class AccountTypeValues {

  private AccountTypeValues() {}

  static final String PATTERN =
      "CASH|SAVINGS|SECURITIES|MANAGED_MANDATE|PENSION|VESTED_BENEFITS|CREDIT_CARD"
          + "|MORTGAGE|LOAN|CRYPTO|CUSTOM_ASSET";
}
