package com.trackmywealth.backend.dto;

import java.util.List;

/**
 * The {@code sharing_grant.access_level} value set (V6's own {@code CHECK} constraint), shared by
 * every DTO/service that validates or compares it - see {@code AccessLevelValues.ORDER} for how
 * {@code AccessControlService} compares two levels ("at least READ", etc.). Kept in sync by hand
 * with V6's {@code CHECK} constraint - both must be updated together if a level is ever added.
 */
public final class AccessLevelValues {

  private AccessLevelValues() {}

  public static final String NO_ACCESS = "NO_ACCESS";
  public static final String BALANCE_ONLY = "BALANCE_ONLY";
  public static final String READ = "READ";
  public static final String EDIT = "EDIT";
  public static final String FULL = "FULL";

  static final String PATTERN = "NO_ACCESS|BALANCE_ONLY|READ|EDIT|FULL";

  // Weakest to strongest - AccessControlService.atLeast() compares two levels by index into this
  // list, not lexically (the CHECK constraint's declaration order happens to already be
  // weakest-to-strongest, but that's a coincidence this list makes explicit and no longer
  // dependent on).
  public static final List<String> ORDER = List.of(NO_ACCESS, BALANCE_ONLY, READ, EDIT, FULL);
}
