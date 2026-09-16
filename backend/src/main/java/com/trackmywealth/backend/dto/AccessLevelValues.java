package com.trackmywealth.backend.dto;

/**
 * The {@code sharing_grant.access_level} value set (V6's own {@code CHECK} constraint), shared by
 * every DTO/service that validates or compares it - these are deliberately plain {@code String}
 * constants, matching every other status/type column in this codebase ({@code
 * WorkspaceMember.status}, {@code AppUser.role}, {@code Account.accountType}, ...). Kept in sync by
 * hand with V6's {@code CHECK} constraint (and with the nested {@link Rank} enum's constant names,
 * which must match these strings exactly) - both must be updated together if a level is ever added.
 */
public final class AccessLevelValues {

  private AccessLevelValues() {}

  public static final String NO_ACCESS = "NO_ACCESS";
  public static final String BALANCE_ONLY = "BALANCE_ONLY";
  public static final String READ = "READ";
  public static final String EDIT = "EDIT";
  public static final String FULL = "FULL";

  static final String PATTERN = "NO_ACCESS|BALANCE_ONLY|READ|EDIT|FULL";

  // Ranking only, used by AccessControlService - the persisted/DTO representation of an access
  // level stays a validated String (the constants above), matching every other status/type column
  // in this codebase. Nested here rather than in AccessControlService itself: every class in
  // ..service.. must be @Service-annotated and end with "Service" (ArchitectureTest), which a
  // ranking enum can't satisfy - and colocating it with the String constants it ranks means a
  // renamed/added level can't drift between the two representations without touching one file.
  // Rank.valueOf(level) also throws loudly on a malformed value instead of silently ranking it as
  // NO_ACCESS.
  public enum Rank {
    NO_ACCESS,
    BALANCE_ONLY,
    READ,
    EDIT,
    FULL
  }
}
