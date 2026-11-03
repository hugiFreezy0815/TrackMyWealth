package com.trackmywealth.backend.dto;

import java.util.regex.Pattern;

/**
 * The {@code account_type} value set, shared by every request DTO that validates it ({@link
 * CreateAccountRequest}, {@link UpdateAccountRequest}) so there is exactly one regex to update, not
 * one per DTO. Kept in sync by hand with {@code AccountService}'s {@code
 * applyCapabilityDefaults}/{@code createExtensionRowIfNeeded} switches and V4's {@code
 * account_type} CHECK constraint - all three, plus this regex, must be updated together when a 12th
 * account type is ever added.
 */
public final class AccountTypeValues {

  private AccountTypeValues() {}

  static final String PATTERN =
      "CASH|SAVINGS|SECURITIES|MANAGED_MANDATE|PENSION|VESTED_BENEFITS|CREDIT_CARD"
          + "|MORTGAGE|LOAN|CRYPTO|CUSTOM_ASSET";

  private static final Pattern KNOWN = Pattern.compile(PATTERN);

  /** Whether {@code accountType} is one of the values, exactly as written (#380's filter). */
  public static boolean isKnown(String accountType) {
    return KNOWN.matcher(accountType).matches();
  }
}
