package com.trackmywealth.backend.dto;

/**
 * The {@code app_user.role} value set (V2's own {@code CHECK} constraint) - kept in sync by hand
 * with that constraint and with the role checks in {@code SecurityConfig}.
 */
public final class UserRoleValues {

  private UserRoleValues() {}

  public static final String SYSTEM_ADMINISTRATOR = "SYSTEM_ADMINISTRATOR";
  public static final String STANDARD_USER = "STANDARD_USER";

  static final String PATTERN = "SYSTEM_ADMINISTRATOR|STANDARD_USER";
}
