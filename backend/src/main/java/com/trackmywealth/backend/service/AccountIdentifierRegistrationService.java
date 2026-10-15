package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountIdentifierResponse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-07-10: registers, shows and removes an account's identifier under EDIT access to the account.
 * Only the keyed hash and the masked display value are stored ({@link AccountIdentifierService}).
 */
@Service
public class AccountIdentifierRegistrationService {

  private static final String VERSIONED_RESOURCE = "account";

  private final AccountLookupService lookup;
  private final AccessControlService access;
  private final AccountIdentifierService identifiers;
  private final AccountRepository accounts;
  private final VersionPreconditionService versions;

  public AccountIdentifierRegistrationService(
      AccountLookupService lookup,
      AccessControlService access,
      AccountIdentifierService identifiers,
      AccountRepository accounts,
      VersionPreconditionService versions) {
    this.lookup = lookup;
    this.access = access;
    this.identifiers = identifiers;
    this.accounts = accounts;
    this.versions = versions;
  }

  @Transactional(readOnly = true)
  public AccountIdentifierResponse get(UUID accountId, AuthenticatedUserPrincipal actor) {
    return response(requireEditable(accountId, actor));
  }

  /**
   * Registers {@code request}'s identifier, replacing any earlier one.
   *
   * @throws ApiException 422 {@code ACCOUNT_IDENTIFIER_INVALID}, 409 {@code
   *     ACCOUNT_IDENTIFIER_IN_USE} when another account of the workspace has it, 503 {@code
   *     ACCOUNT_IDENTIFICATION_UNAVAILABLE} without a configured key
   */
  @Transactional
  public AccountIdentifierResponse set(
      UUID accountId,
      AccountIdentifierRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    versions.requireCurrent(expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    String kind = AccountIdentifierService.kindOf(account);
    String normalized =
        AccountIdentifierService.normalize(request.identifier(), kind)
            .orElseThrow(() -> invalid(kind));
    UUID workspaceId = account.getWorkspace().getId();
    String hash = identifiers.hash(workspaceId, normalized, kind);
    // Checked here for a clear answer; the unique index decides a race (GlobalExceptionHandler).
    accounts
        .findByWorkspaceIdAndIdentifierHash(workspaceId, hash)
        .filter(other -> !other.getId().equals(account.getId()))
        .ifPresent(
            other -> {
              throw new ApiException(
                  HttpStatus.CONFLICT,
                  ApiErrorCode.ACCOUNT_IDENTIFIER_IN_USE,
                  "Another account of this workspace is already registered with this identifier.");
            });
    account.setIdentifierHash(hash);
    account.setIdentifierMasked(AccountIdentifierService.masked(normalized, kind));
    return response(accounts.saveAndFlush(account));
  }

  /** Removes the account's identifier: no file is matched to it any more. */
  @Transactional
  public AccountIdentifierResponse clear(
      UUID accountId, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    versions.requireCurrent(expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    account.setIdentifierHash(null);
    account.setIdentifierMasked(null);
    return response(accounts.saveAndFlush(account));
  }

  private Account requireEditable(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = lookup.findAccountOrThrow(accountId, actor);
    access.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    return account;
  }

  private static AccountIdentifierResponse response(Account account) {
    return new AccountIdentifierResponse(
        account.getId(),
        AccountIdentifierService.kindOf(account),
        account.getIdentifierMasked(),
        VersionPreconditionService.persistedVersion(account.getVersion(), VERSIONED_RESOURCE));
  }

  private static ApiException invalid(String kind) {
    String detail =
        ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(kind)
            ? "Enter the card number masked except for at most its first 6 and last 4 digits."
            : "This is not a valid IBAN; check it for typing errors.";
    ApiException exception =
        new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.ACCOUNT_IDENTIFIER_INVALID, detail);
    exception.getBody().setProperty("field", "identifier");
    return exception;
  }
}
