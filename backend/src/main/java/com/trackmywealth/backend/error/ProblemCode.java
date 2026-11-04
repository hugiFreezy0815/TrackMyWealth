package com.trackmywealth.backend.error;

import static com.trackmywealth.backend.error.ClientReaction.FIX_INPUT;
import static com.trackmywealth.backend.error.ClientReaction.REAUTHENTICATE;
import static com.trackmywealth.backend.error.ClientReaction.RELOAD;
import static com.trackmywealth.backend.error.ClientReaction.RELOAD_AND_RETRY;
import static com.trackmywealth.backend.error.ClientReaction.REPORT;
import static com.trackmywealth.backend.error.ClientReaction.RETRY_LATER;
import static com.trackmywealth.backend.error.ClientReaction.SHOW;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The registry of every stable {@code code} an error response carries (EPIC-29 (#149), FR-API-005;
 * catalogue US-29-04, #374): one per error class, plus the cases where a client must act
 * differently. The human {@code detail} stays per rule; a code is added only when a client needs to
 * branch on it (decision on #149).
 *
 * <p>Each code carries the HTTP status it is answered with, a one-line meaning, the message key of
 * its EN/DE text ({@code tmw.error.<CODE>} in {@code messages*.properties}) and the client reaction
 * the catalogue recommends. {@code docs/api/error-codes.md}, {@code api/problem-codes.json} and the
 * OpenAPI {@code ApiProblem.code} list ({@code x-extensible-enum}) are generated from this list.
 * Codes are part of the API contract and an open set: adding one is additive, never rename one, and
 * remove one only after deprecating it, as a declared breaking change that adds its name to {@link
 * #RETIRED} ({@code scripts/api_breaking.py} enforces this against {@code api/problem-codes.json}).
 */
public enum ProblemCode {
  VALIDATION_FAILED(
      FIX_INPUT,
      "The request body or parameters are malformed or fail validation; errors lists each"
          + " rejected field. Also any other 4xx without a code of its own (e.g. 405, 415).",
      HttpStatus.BAD_REQUEST),
  INVALID_SORT(
      FIX_INPUT,
      "A paged list was asked to sort by a field it does not offer, or in a malformed way; detail"
          + " names the field and the fields the list sorts by.",
      HttpStatus.BAD_REQUEST),
  UNAUTHENTICATED(
      REAUTHENTICATE, "No valid access token, or a sign-in was refused.", HttpStatus.UNAUTHORIZED),
  FORBIDDEN(
      SHOW,
      "Signed in, but not allowed to do this (e.g. an administration endpoint).",
      HttpStatus.FORBIDDEN),
  NOT_FOUND(
      RELOAD,
      "Not found, or not visible to the caller; the two are never told apart.",
      HttpStatus.NOT_FOUND),
  CONFLICT(
      SHOW, "The request conflicts with the current state; detail says how.", HttpStatus.CONFLICT),
  VERSION_REQUIRED(
      RELOAD_AND_RETRY,
      "A state-changing request omitted the If-Match version it must protect.",
      HttpStatus.PRECONDITION_REQUIRED),
  VERSION_CONFLICT(
      RELOAD_AND_RETRY,
      "The record changed since it was read: 412 with If-Match, 409 for a lost race without it.",
      HttpStatus.PRECONDITION_FAILED,
      HttpStatus.CONFLICT),
  RETRY(
      ClientReaction.RETRY,
      "A concurrent request won a race for the same key; the retry answers with the winner.",
      HttpStatus.CONFLICT),
  ACCOUNT_ARCHIVED(SHOW, "The account is archived; restore it first.", HttpStatus.CONFLICT),
  IMMUTABLE_FIELD(
      FIX_INPUT,
      "A field that never changes after creation, such as an account's type or currency.",
      HttpStatus.CONFLICT),
  UNPROCESSABLE(
      FIX_INPUT,
      "Well-formed, but breaks a business rule; detail names the rule.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  OPENING_BALANCE_NOT_APPLICABLE(
      SHOW,
      "This account takes no opening balance: a loan, a mortgage or a manually valued asset has a"
          + " value source of its own.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  OPENING_BALANCE_AFTER_FIRST_TRANSACTION(
      FIX_INPUT,
      "Live transactions are booked before the opening date (transactionCount,"
          + " earliestBookingDate); move the date back or repeat with"
          + " acknowledgeEarlierTransactions = true.",
      HttpStatus.CONFLICT),
  OPENING_BALANCE_NOT_RECORDED(
      SHOW, "The account has no opening balance yet; record one with POST.", HttpStatus.NOT_FOUND),
  RECONCILIATION_STALE(
      RELOAD,
      "The reconciliation result no longer describes the newest comparison (status,"
          + " differenceAmount); reload and decide again.",
      HttpStatus.CONFLICT),
  RECONCILIATION_FINALIZED(
      SHOW,
      "A newer snapshot has finalized this decision; correct the difference in the latest"
          + " reconciliation.",
      HttpStatus.CONFLICT),
  RECONCILIATION_ADJUSTMENT_LOCKED(
      SHOW,
      "The transaction is the adjusting entry of an accepted reconciliation; reopen the"
          + " reconciliation result instead.",
      HttpStatus.CONFLICT),
  IMPORT_TEMPLATE_INVALID(
      FIX_INPUT,
      "The import template breaks a rule; field names the template field, detail the rule.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_TEMPLATE_UNSUPPORTED(
      SHOW, "A valid template this release cannot import yet.", HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_TEMPLATE_READ_ONLY(
      SHOW,
      "A shipped import template is shared by every workspace and never edited by one.",
      HttpStatus.FORBIDDEN),
  IMPORT_TEMPLATE_IN_USE(
      SHOW,
      "An import batch used a version of this template, so it cannot be deleted; deactivate it.",
      HttpStatus.CONFLICT),
  IMPORT_TEMPLATE_MISMATCH(
      SHOW,
      "The file's header lacks columns the template maps (missingColumns); no row is parsed.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_ENCODING_SUSPECT(
      SHOW,
      "The header holds characters the template's encoding cannot decode; the file is probably"
          + " in another encoding.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_FILE_EMPTY(
      SHOW,
      "The file is empty, or holds nothing after the skipped preamble.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_FILE_NO_DATA_ROWS(
      SHOW, "The file has a header but no data row.", HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_FILE_MALFORMED(
      SHOW,
      "The file is not valid CSV for the template's delimiter, or not a readable PDF, XML or"
          + " spreadsheet (damaged, encrypted).",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_FILE_TOO_LARGE(
      SHOW, "The uploaded file exceeds the 5 MB import limit.", HttpStatus.CONTENT_TOO_LARGE),
  IMPORT_FILE_TOO_MANY_ROWS(
      SHOW,
      "The file has more data rows than one import may hold (maxRows).",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_CAMT_UNSUPPORTED(
      SHOW,
      "An XML file is no supported ISO 20022 message: only camt.053 and camt.052, versions"
          + " 001.02, 001.04 and 001.08.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_CAMT_LIMIT_EXCEEDED(
      SHOW,
      "A CAMT file holds more than one import may; exporting a shorter period helps.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_CAMT_MULTIPLE_ACCOUNTS(
      SHOW,
      "A CAMT file holds statements of more than one account; import each account's file on its"
          + " own.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_FILE_TOO_MANY_PAGES(
      SHOW,
      "A PDF has more pages than one import may hold (maxPages).",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_PDF_LIMIT_EXCEEDED(
      SHOW,
      "A PDF holds more than one statement may (text, objects, images); exporting a shorter"
          + " statement helps.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_PDF_NO_TEXT(
      SHOW,
      "A scanned PDF without a text layer, but the template reads text; use an OCR template.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_PDF_BUSY(
      RETRY_LATER,
      "The server is reading as many PDF statements as it may at once.",
      HttpStatus.SERVICE_UNAVAILABLE),
  IMPORT_SPREADSHEET_LIMIT_EXCEEDED(
      SHOW,
      "A spreadsheet holds more sheets, rows, cells or text than one import may; exporting less"
          + " helps.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_SPREADSHEET_BUSY(
      RETRY_LATER,
      "The server is reading as many spreadsheets as it may at once.",
      HttpStatus.SERVICE_UNAVAILABLE),
  IMPORT_OCR_FAILED(
      SHOW,
      "Local OCR could not read a scanned page (it failed or produced too much text).",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_OCR_UNAVAILABLE(
      RETRY_LATER,
      "Local OCR is not installed, busy, or did not finish within the time limit.",
      HttpStatus.SERVICE_UNAVAILABLE),
  IMPORT_BATCH_STATE(
      RELOAD,
      "The import batch's status (status) does not allow this step; a retried commit gets this,"
          + " never a second import.",
      HttpStatus.CONFLICT),
  IMPORT_TOO_MANY_OPEN_BATCHES(
      SHOW,
      "The account holds as many open import batches as it may (maxOpenBatches); commit or"
          + " discard one first.",
      HttpStatus.CONFLICT),
  IMPORT_ROW_NOT_INCLUDABLE(
      FIX_INPUT,
      "An import row with status ERROR cannot be included in the commit.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_ACCOUNT_UNCONFIRMED(
      SHOW,
      "The file's account identification needs the member's confirmation first"
          + " (accountIdentification).",
      HttpStatus.CONFLICT),
  IMPORT_SNAPSHOT_ALREADY_RECORDED(
      SHOW,
      "The statement adds nothing to the imported snapshot of its account and date"
          + " (existingSnapshotId); discard the import.",
      HttpStatus.CONFLICT),
  IMPORT_SNAPSHOT_CONFLICT(
      SHOW,
      "The statement differs from the imported snapshot of its account and date"
          + " (existingSnapshotId, conflicts); nothing is overwritten.",
      HttpStatus.CONFLICT),
  IMPORT_SNAPSHOT_RECONCILIATION_DECIDED(
      SHOW,
      "The imported snapshot cannot change while a reconciliation decision on it stands"
          + " (reconciliationResultId).",
      HttpStatus.CONFLICT),
  IMPORT_HOLDINGS_UNRESOLVED(
      SHOW,
      "A holdings position resolves to no security (unresolved); create the security, then"
          + " commit again.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_HOLDINGS_UNREADABLE(
      SHOW,
      "A position or cash line of the holdings statement could not be read (rowNumbers); the"
          + " template needs correcting.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  IMPORT_ROLLBACK_RECONCILIATION_DECIDED(
      SHOW,
      "The batch cannot be rolled back while a reconciliation decision on its statement balance"
          + " stands (reconciliationResultId); reopen it first.",
      HttpStatus.CONFLICT),
  ACCOUNT_IDENTIFIER_INVALID(
      FIX_INPUT,
      "Not a valid account identifier for this account (IBAN check digits, masked card number).",
      HttpStatus.UNPROCESSABLE_CONTENT),
  ACCOUNT_IDENTIFIER_IN_USE(
      FIX_INPUT,
      "Another account of the workspace is already registered with this identifier.",
      HttpStatus.CONFLICT),
  ACCOUNT_IDENTIFIER_NOT_IN_FILE(
      SHOW,
      "The import file names no account identifier the template can read, or one of another"
          + " kind.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  ACCOUNT_IDENTIFICATION_UNAVAILABLE(
      SHOW,
      "Account identification is switched off on this server (no key configured).",
      HttpStatus.SERVICE_UNAVAILABLE),
  SAVINGS_POCKET_NOT_APPLICABLE(
      SHOW,
      "The account can have no savings pocket: a card, a loan, an account without transactions,"
          + " or one that is itself another account's pocket.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  SAVINGS_POCKET_UNSUITABLE(
      FIX_INPUT,
      "The chosen account cannot be the savings pocket: the account itself, not active, without"
          + " transactions, a card or loan, in another currency, or with a pocket of its own.",
      HttpStatus.UNPROCESSABLE_CONTENT),
  SAVINGS_POCKET_IN_USE(
      FIX_INPUT,
      "The chosen account is already the savings pocket of another account.",
      HttpStatus.CONFLICT),
  LOCKED(
      RETRY_LATER,
      "The user account is temporarily locked after failed sign-ins.",
      HttpStatus.LOCKED),
  RATE_LIMITED(RETRY_LATER, "Too many requests; honour Retry-After.", HttpStatus.TOO_MANY_REQUESTS),
  INTERNAL(
      REPORT,
      "Unexpected; quote the correlationId when reporting it. Also any other 5xx without a code"
          + " of its own.",
      HttpStatus.INTERNAL_SERVER_ERROR);

  /** The name of the error-body property that carries the code. */
  public static final String PROPERTY = "code";

  /** The prefix of each code's message key in {@code messages*.properties}. */
  public static final String MESSAGE_KEY_PREFIX = "tmw.error.";

  /**
   * The names of codes that were removed, in the order they were retired. A retired name is never
   * used again, not even for a new code with another meaning: a client that still knows the old
   * code would misread it. Removing a code is a declared breaking change, and this list only grows
   * (the API contract's check, {@code scripts/api_breaking.py}, enforces both against {@code
   * api/problem-codes.json}). Before removing a code, deprecate it ({@link Deprecated}) for at
   * least one release.
   */
  public static final List<String> RETIRED = List.of();

  private static final Set<String> NAMES =
      Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  private final ClientReaction clientReaction;
  private final String oneLineMeaning;
  private final HttpStatus defaultStatus;
  private final Set<HttpStatus> allowedStatuses;

  ProblemCode(ClientReaction reaction, String meaning, HttpStatus status, HttpStatus... others) {
    this.clientReaction = reaction;
    this.oneLineMeaning = meaning;
    this.defaultStatus = status;
    Set<HttpStatus> all = EnumSet.of(status, others);
    this.allowedStatuses = Collections.unmodifiableSet(all);
  }

  /** The status this code is answered with by default. */
  public HttpStatus status() {
    return defaultStatus;
  }

  /** Every status this code may be answered with; {@link #status()} first in the catalogue. */
  public Set<HttpStatus> statuses() {
    return allowedStatuses;
  }

  /**
   * Whether this code may be answered with {@code candidate}: one of its {@link #statuses()}, or a
   * status it is the class-level code of ({@link #forStatus}, e.g. {@code INTERNAL} for a 503 the
   * container answers).
   */
  public boolean allows(HttpStatusCode candidate) {
    return allowedStatuses.stream().anyMatch(allowed -> allowed.value() == candidate.value())
        || candidate.isError() && forStatus(candidate) == this;
  }

  /**
   * Whether the code is on its way out: still sent and still in the contract, but marked {@link
   * Deprecated} so clients stop relying on it before it is retired ({@link #RETIRED}).
   */
  public boolean isDeprecated() {
    return Codes.isDeprecated(this);
  }

  public ClientReaction reaction() {
    return clientReaction;
  }

  /** One line for the catalogue and the OpenAPI description. */
  public String meaning() {
    return oneLineMeaning;
  }

  /** The key of the code's EN/DE text, e.g. {@code tmw.error.ACCOUNT_ARCHIVED}. */
  public String messageKey() {
    return MESSAGE_KEY_PREFIX + name();
  }

  /** Whether {@code name} is a registered code (case-sensitive, as it goes over the wire). */
  public static boolean isRegistered(String name) {
    return name != null && NAMES.contains(name);
  }

  /** The class-level code for a status, used when nothing more specific applies. */
  public static ProblemCode forStatus(HttpStatusCode status) {
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
