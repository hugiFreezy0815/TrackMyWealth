package com.trackmywealth.backend.security;

import java.util.List;

/**
 * Which API paths need no token and which only a system administrator may call: the access rules
 * {@code SecurityConfig} enforces. Kept here, in the security layer, so that what reads them for
 * documentation (the OpenAPI contract's security section and the problem codes an operation
 * implies, {@code OperationProblemCodes}) can never change who may call what (#374 review).
 */
public final class ApiAccessPaths {

  /** The operations a caller without a token may call: the one-time setup and login/refresh. */
  public static final List<String> PUBLIC = List.of("/api/v1/setup/**", "/api/v1/auth/**");

  /** The operations only a system administrator may call (FR-TEN-007); anyone else gets a 403. */
  public static final List<String> ADMIN = List.of("/api/v1/admin/**");

  private ApiAccessPaths() {}
}
