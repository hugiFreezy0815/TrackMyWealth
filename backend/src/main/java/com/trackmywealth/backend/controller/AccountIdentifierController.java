package com.trackmywealth.backend.controller;

import com.trackmywealth.backend.dto.AccountIdentifierFromImportRequest;
import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountIdentifierResponse;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.AccountIdentifierRegistrationService;
import com.trackmywealth.backend.web.IfMatchVersionParser;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * US-07-10: the account identifier an import file is matched to, under EDIT access to the account.
 * The identifier is sent once; responses show only its masked display value. Every change requires
 * the account's version in {@code If-Match} and answers with its new {@code ETag}.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/identifier")
public class AccountIdentifierController {

  private final AccountIdentifierRegistrationService registration;

  public AccountIdentifierController(AccountIdentifierRegistrationService registration) {
    this.registration = registration;
  }

  @GetMapping
  public ResponseEntity<AccountIdentifierResponse> get(
      @PathVariable UUID accountId, @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(registration.get(accountId, actor));
  }

  @PutMapping
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
  @PutMapping("/from-import")
  public ResponseEntity<AccountIdentifierResponse> setFromImport(
      @PathVariable UUID accountId,
      @Valid @RequestBody AccountIdentifierFromImportRequest request,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(
        registration.setFromImport(accountId, request, IfMatchVersionParser.parse(ifMatch), actor));
  }

  @DeleteMapping
  public ResponseEntity<AccountIdentifierResponse> clear(
      @PathVariable UUID accountId,
      @RequestHeader(value = IfMatchVersionParser.HEADER, required = false) String ifMatch,
      @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
    return withEtag(registration.clear(accountId, IfMatchVersionParser.parse(ifMatch), actor));
  }

  private static ResponseEntity<AccountIdentifierResponse> withEtag(
      AccountIdentifierResponse body) {
    return ResponseEntity.ok().eTag(IfMatchVersionParser.toEtag(body.version())).body(body);
  }
}
