package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountIdentifierFromImportRequest;
import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountIdentifierResponse;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountIdentifierRegistrationService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
import com.trackmywealth.backend.web.ProblemCodes;
import com.trackmywealth.backend.web.ResponseHeader;
import com.trackmywealth.backend.web.SendsHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-07-10: the account identifier an import file is matched to, for a member with access to it.
 * The identifier is sent once; responses show only its masked display value. Every change requires
 * the account's version in {@code If-Match} and answers with its new {@code ETag}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/identifier")
@Tag(name = "Accounts")
public class AccountIdentifierController {

  private final AccountIdentifierRegistrationService registration;

  public AccountIdentifierController(AccountIdentifierRegistrationService registration) {
    this.registration = registration;
  }

  @Operation(operationId = "getAccountIdentifier", summary = "Read an account's masked identifiers")
  @GetMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<AccountIdentifierResponse> get(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(registration.get(accountId, actor));
  }

  @Operation(
      operationId = "setAccountIdentifier",
      summary = "Register an account's IBAN, card number or contract number")
  @ProblemCodes({
    ProblemCode.ACCOUNT_IDENTIFIER_INVALID,
    ProblemCode.ACCOUNT_IDENTIFIER_IN_USE,
    ProblemCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE
  })
  @PutMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<AccountIdentifierResponse> set(
      @PathVariable UUID accountId,
      @Valid @RequestBody AccountIdentifierRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(
        registration.set(accountId, request, IfMatchVersionParser.parse(ifMatch), actor));
  }

  /**
   * US-07-11: registers the identifier an open import batch's file names, without sending it. A 422
   * {@code ACCOUNT_IDENTIFIER_NOT_IN_FILE} when the file names none this account could take.
   */
  @Operation(
      operationId = "setAccountIdentifierFromImport",
      summary = "Take an account's identifier from an imported file")
  @ProblemCodes({
    ProblemCode.IMPORT_TEMPLATE_INVALID,
    ProblemCode.IMPORT_TEMPLATE_UNSUPPORTED,
    ProblemCode.IMPORT_TEMPLATE_MISMATCH,
    ProblemCode.IMPORT_ENCODING_SUSPECT,
    ProblemCode.IMPORT_FILE_EMPTY,
    ProblemCode.IMPORT_FILE_NO_DATA_ROWS,
    ProblemCode.IMPORT_FILE_MALFORMED,
    ProblemCode.IMPORT_FILE_TOO_MANY_ROWS,
    ProblemCode.IMPORT_FILE_TOO_MANY_PAGES,
    ProblemCode.IMPORT_PDF_LIMIT_EXCEEDED,
    ProblemCode.IMPORT_PDF_NO_TEXT,
    ProblemCode.IMPORT_PDF_BUSY,
    ProblemCode.IMPORT_OCR_FAILED,
    ProblemCode.IMPORT_OCR_UNAVAILABLE,
    ProblemCode.IMPORT_BATCH_STATE,
    ProblemCode.ACCOUNT_IDENTIFIER_IN_USE,
    ProblemCode.ACCOUNT_IDENTIFIER_NOT_IN_FILE,
    ProblemCode.ACCOUNT_IDENTIFICATION_UNAVAILABLE
  })
  @PutMapping("/from-import")
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<AccountIdentifierResponse> setFromImport(
      @PathVariable UUID accountId,
      @Valid @RequestBody AccountIdentifierFromImportRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(
        registration.setFromImport(accountId, request, IfMatchVersionParser.parse(ifMatch), actor));
  }

  /**
   * Removes the account's identifiers; with {@code kind} ({@code IBAN}, {@code MASKED_CARD} or
   * {@code CONTRACT_NUMBER}) only that one (#323).
   */
  @Operation(operationId = "clearAccountIdentifier", summary = "Remove an account's identifiers")
  @ProblemCodes({ProblemCode.ACCOUNT_IDENTIFIER_INVALID})
  @DeleteMapping
  @SendsHeaders(ResponseHeader.ETAG)
  public ResponseEntity<AccountIdentifierResponse> clear(
      @PathVariable UUID accountId,
      @RequestParam(required = false) String kind,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(
        registration.clear(accountId, kind, IfMatchVersionParser.parse(ifMatch), actor));
  }

  private static ResponseEntity<AccountIdentifierResponse> withEtag(
      AccountIdentifierResponse body) {
    return ResponseEntity.ok().eTag(IfMatchVersionParser.toEtag(body.version())).body(body);
  }
}
