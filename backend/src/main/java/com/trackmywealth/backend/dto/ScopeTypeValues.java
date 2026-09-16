package com.trackmywealth.backend.dto;

/**
 * The {@code sharing_grant.scope_type} value set (V6's own {@code CHECK} constraint) - kept in sync
 * by hand with that constraint and with {@code SharingGrantService}'s scope-resolution switch.
 */
public final class ScopeTypeValues {

  private ScopeTypeValues() {}

  public static final String ACCOUNT = "ACCOUNT";
  public static final String INSTITUTION = "INSTITUTION";
  public static final String WORKSPACE = "WORKSPACE";

  static final String PATTERN = "ACCOUNT|INSTITUTION|WORKSPACE";
}
