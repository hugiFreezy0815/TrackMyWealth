package com.trackmywealth.backend.error;

import org.springframework.http.HttpStatusCode;

/**
 * The stable {@code code} every error response carries (EPIC-29 (#149), FR-API-005): one per error
 * class, plus the few cases where a client must act differently. The human {@code detail} stays per
 * rule; a code is added here only when a client needs to branch on it (decision on #149). Codes are
 * part of the API contract - never rename one.
 */
public final class ApiErrorCode {

  /** The name of the error-body property that carries the code. */
  public static final String PROPERTY = "code";

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

  /** 428: a state-changing request omitted the version it must protect with If-Match. */
  public static final String VERSION_REQUIRED = "VERSION_REQUIRED";

  /**
   * 412 (or 409 for a race on a request without {@code If-Match}): the record changed since it was
   * read - reload it, then retry explicitly (FR-CNC-002, ADR 0004).
   */
  public static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** 409: a concurrent request won a race for the same key - simply retry the request. */
  public static final String RETRY = "RETRY";

  /** 409: the account is archived; restore it first. */
  public static final String ACCOUNT_ARCHIVED = "ACCOUNT_ARCHIVED";

  /** 409: a field that can never change after creation (e.g. an account's type or currency). */
  public static final String IMMUTABLE_FIELD = "IMMUTABLE_FIELD";

  /** 422: well-formed, but breaks a business rule; {@code detail} names the rule. */
  public static final String UNPROCESSABLE = "UNPROCESSABLE";

  /**
   * 422: this account takes no opening balance - a loan, a mortgage or a manually valued asset has
   * a value source of its own (US-25-04).
   */
  public static final String OPENING_BALANCE_NOT_APPLICABLE = "OPENING_BALANCE_NOT_APPLICABLE";

  /**
   * 409: live transactions are booked before the requested opening date; {@code transactionCount}
   * and {@code earliestBookingDate} say which. Move the date back, or repeat the request with
   * {@code acknowledgeEarlierTransactions = true} to leave them out of the balance (US-25-04).
   */
  public static final String OPENING_BALANCE_AFTER_FIRST_TRANSACTION =
      "OPENING_BALANCE_AFTER_FIRST_TRANSACTION";

  /**
   * 404: the account exists and the caller may see it, but it has no opening balance yet - record
   * one with {@code POST}. Only ever returned after the account access check, so it reveals nothing
   * an account the caller may not see would; an account the caller cannot see is a plain {@link
   * #NOT_FOUND} (US-25-04, #241 review).
   */
  public static final String OPENING_BALANCE_NOT_RECORDED = "OPENING_BALANCE_NOT_RECORDED";

  /**
   * 409: the reconciliation result no longer describes the account's newest comparison - a newer
   * snapshot superseded it, or it is not in a state the decision applies to. {@code status} and
   * {@code differenceAmount} give the current figure; reload and decide again (US-25-03).
   */
  public static final String RECONCILIATION_STALE = "RECONCILIATION_STALE";

  /**
   * 409: a newer snapshot has finalized this accepted or dismissed reconciliation result. It is
   * history and can no longer be reopened; correct the difference in the latest reconciliation
   * (US-25-03).
   */
  public static final String RECONCILIATION_FINALIZED = "RECONCILIATION_FINALIZED";

  /**
   * 409: the transaction is the adjusting entry of an accepted reconciliation difference. It cannot
   * be corrected, removed, restored or categorized on its own; reopen the reconciliation result
   * instead (US-25-03).
   */
  public static final String RECONCILIATION_ADJUSTMENT_LOCKED = "RECONCILIATION_ADJUSTMENT_LOCKED";

  /**
   * 422: the import template breaks a rule (an unknown encoding, a mapping by a column name the
   * header lacks or repeats, an invalid date pattern, ...); {@code field} names the template field
   * and {@code detail} the rule (US-07-03).
   */
  public static final String IMPORT_TEMPLATE_INVALID = "IMPORT_TEMPLATE_INVALID";

  /**
   * 422: a valid template this release cannot import yet - only cash transactions into an account
   * the member selects at upload are supported (US-07-03, sprint 5).
   */
  public static final String IMPORT_TEMPLATE_UNSUPPORTED = "IMPORT_TEMPLATE_UNSUPPORTED";

  /** 403: a shipped import template is shared by every workspace and never edited by one. */
  public static final String IMPORT_TEMPLATE_READ_ONLY = "IMPORT_TEMPLATE_READ_ONLY";

  /**
   * 409: an import batch used a version of this template, so it cannot be deleted (FR-LIF-001);
   * deactivate it instead.
   */
  public static final String IMPORT_TEMPLATE_IN_USE = "IMPORT_TEMPLATE_IN_USE";

  /**
   * 422: the file's header lacks columns the template maps; {@code missingColumns} lists them. No
   * row is parsed, so a column is never silently shifted (FR-IMP-023).
   */
  public static final String IMPORT_TEMPLATE_MISMATCH = "IMPORT_TEMPLATE_MISMATCH";

  /**
   * 422: the header holds characters the template's encoding cannot decode, so the file is probably
   * in another encoding.
   */
  public static final String IMPORT_ENCODING_SUSPECT = "IMPORT_ENCODING_SUSPECT";

  /** 422: the file is empty, or holds nothing after the skipped preamble. */
  public static final String IMPORT_FILE_EMPTY = "IMPORT_FILE_EMPTY";

  /** 422: the file has a header but no data row. */
  public static final String IMPORT_FILE_NO_DATA_ROWS = "IMPORT_FILE_NO_DATA_ROWS";

  /**
   * 422: the file is not valid CSV for the template's delimiter (e.g. an unclosed quote), or not a
   * readable PDF (damaged, encrypted).
   */
  public static final String IMPORT_FILE_MALFORMED = "IMPORT_FILE_MALFORMED";

  /** 413: the uploaded file exceeds the 5 MB import limit. */
  public static final String IMPORT_FILE_TOO_LARGE = "IMPORT_FILE_TOO_LARGE";

  /** 422: the file has more data rows than one import may hold; {@code maxRows} says how many. */
  public static final String IMPORT_FILE_TOO_MANY_ROWS = "IMPORT_FILE_TOO_MANY_ROWS";

  /**
   * 422: a PDF import file has more pages than one import may hold; {@code maxPages} says how many.
   */
  public static final String IMPORT_FILE_TOO_MANY_PAGES = "IMPORT_FILE_TOO_MANY_PAGES";

  /**
   * 422: a PDF import file is readable, but holds more than one statement may: more text, objects,
   * decoded data, image pixels or drawing work, or a page too large for OCR. Unlike {@code
   * IMPORT_FILE_MALFORMED}, exporting a shorter statement helps.
   */
  public static final String IMPORT_PDF_LIMIT_EXCEEDED = "IMPORT_PDF_LIMIT_EXCEEDED";

  /**
   * 422: a PDF has no text layer (a scanned document) but the template reads text ({@code
   * PDF_TEXT}); an OCR template ({@code PDF_OCR}) reads it.
   */
  public static final String IMPORT_PDF_NO_TEXT = "IMPORT_PDF_NO_TEXT";

  /**
   * 503: the server is already reading as many PDF statements as it may at once (each takes tens of
   * megabytes); retry later.
   */
  public static final String IMPORT_PDF_BUSY = "IMPORT_PDF_BUSY";

  /** 422: local OCR could not read a scanned page (it failed or produced too much text). */
  public static final String IMPORT_OCR_FAILED = "IMPORT_OCR_FAILED";

  /**
   * 503: local OCR is not installed, all of its slots are busy, or it did not finish within the
   * server's time limit; retry later.
   */
  public static final String IMPORT_OCR_UNAVAILABLE = "IMPORT_OCR_UNAVAILABLE";

  /**
   * 409: the import batch's status does not allow this step (US-07-04) - e.g. parsing or committing
   * a batch that is already committed or discarded; {@code status} says which it is. A retried
   * commit is answered with this, never with a second import (FR-API-006).
   */
  public static final String IMPORT_BATCH_STATE = "IMPORT_BATCH_STATE";

  /** 422: an import row with status {@code ERROR} cannot be included in the commit (US-07-04). */
  public static final String IMPORT_ROW_NOT_INCLUDABLE = "IMPORT_ROW_NOT_INCLUDABLE";

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
      case 404 -> NOT_FOUND;
      case 409 -> CONFLICT;
      case 412 -> VERSION_CONFLICT;
      case 422 -> UNPROCESSABLE;
      case 423 -> LOCKED;
      case 428 -> VERSION_REQUIRED;
      case 429 -> RATE_LIMITED;
      default -> status.is4xxClientError() ? VALIDATION_FAILED : INTERNAL;
    };
  }
}
