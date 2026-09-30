package com.trackmywealth.backend.web;

import org.springframework.http.HttpStatusCode;

/**
 * The stable {@code code} every error response carries (EPIC-29 (#149), FR-API-005): one per error
 * class, plus the few cases where a client must act differently. The human {@code detail} stays per
 * rule; a code is added here only when a client needs to branch on it (decision on #149). Codes are
 * part of the API contract - never rename one.
 */
public final class ApiErrorCode {

  /** 400: the request body or parameters are malformed or fail validation. */
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  /** 401: no valid access token - sign in (again). */
  public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

  /** 403: authenticated, but not allowed (e.g. an administration endpoint). */
  public static final String FORBIDDEN = "FORBIDDEN";

  /** 404: not found - or not visible to the caller; the two are never told apart (US-28-03). */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** 409: conflicts with the current state; {@code detail} says how. */
  public static final String CONFLICT = "CONFLICT";

  /** 409: the record changed since it was read - reload it, then retry (FR-CNC-002, #172). */
  public static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** 409: a concurrent request won a race for the same key - simply retry the request. */
  public static final String RETRY = "RETRY";

  /** 409: the account is archived; restore it first. */
  public static final String ACCOUNT_ARCHIVED = "ACCOUNT_ARCHIVED";

  /** 409: a field that can never change after creation (e.g. an account's type or currency). */
  public static final String IMMUTABLE_FIELD = "IMMUTABLE_FIELD";

  /** 422: well-formed, but breaks a business rule; {@code detail} names the rule. */
  public static final String UNPROCESSABLE = "UNPROCESSABLE";

  /** 423: the user account is temporarily locked (FR-AUT lockout). */
  public static final String LOCKED = "LOCKED";

  /** 429: rate limited; honour {@code Retry-After}. */
  public static final String RATE_LIMITED = "RATE_LIMITED";

  /** 5xx: unexpected; quote the {@code correlationId} when reporting it. */
  public static final String INTERNAL = "INTERNAL";

  private ApiErrorCode() {}

  /** The class-level code for a status, used when nothing more specific applies. */
  public static String forStatus(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> VALIDATION_FAILED;
      case 401 -> UNAUTHENTICATED;
      case 403 -> FORBIDDEN;
      case 404, 405 -> NOT_FOUND;
      case 409 -> CONFLICT;
      case 422 -> UNPROCESSABLE;
      case 423 -> LOCKED;
      case 429 -> RATE_LIMITED;
      default -> status.is4xxClientError() ? VALIDATION_FAILED : INTERNAL;
    };
  }
}
