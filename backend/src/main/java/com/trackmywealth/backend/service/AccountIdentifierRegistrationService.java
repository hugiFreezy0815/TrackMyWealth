package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountIdentifierFromImportRequest;
import com.trackmywealth.backend.dto.AccountIdentifierRequest;
import com.trackmywealth.backend.dto.AccountIdentifierResponse;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
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
 * Besides its IBAN, an account other than a credit card can have a contract number (#323).
 */
@Service
public class AccountIdentifierRegistrationService {

  private static final String VERSIONED_RESOURCE = "account";

  private final AccountLookupService lookup;
  private final AccessControlService access;
  private final AccountIdentifierService identifiers;
  private final AccountRepository accounts;
  private final VersionPreconditionService versions;
  private final ImportAccountProposalService proposals;

  public AccountIdentifierRegistrationService(
      AccountLookupService lookup,
      AccessControlService access,
      AccountIdentifierService identifiers,
      AccountRepository accounts,
      VersionPreconditionService versions,
      ImportAccountProposalService proposals) {
    this.lookup = lookup;
    this.access = access;
    this.identifiers = identifiers;
    this.accounts = accounts;
    this.versions = versions;
    this.proposals = proposals;
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
    String kind =
        request.kind() == null ? AccountIdentifierService.kindOf(account) : request.kind();
    requireRegistrable(account, kind);
    String normalized =
        AccountIdentifierService.normalize(request.identifier(), kind)
            .orElseThrow(() -> invalid(kind));
    return register(account, normalized, kind);
  }

  /**
   * US-07-11: registers the identifier the file of an open import batch names (typically one that
   * matched no account), so the member does not type it again after creating the account from the
   * file. The plain identifier stays on the server. Needs EDIT access to both accounts.
   *
   * @throws ApiException 422 {@code ACCOUNT_IDENTIFIER_NOT_IN_FILE} when the file names none the
   *     template's rule reads, or one of another kind than this account takes; otherwise as {@link
   *     #set}
   */
  @Transactional
  public AccountIdentifierResponse setFromImport(
      UUID accountId,
      AccountIdentifierFromImportRequest request,
      Integer expectedVersion,
      AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    versions.requireCurrent(expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    ImportFileAccountIdentifier named =
        proposals.identifierOf(request.importAccountId(), request.batchId(), actor);
    String kind = named == null ? AccountIdentifierService.kindOf(account) : named.kind();
    if (named == null || !named.found() || !registrable(account, kind)) {
      ApiException exception =
          new ApiException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ApiErrorCode.ACCOUNT_IDENTIFIER_NOT_IN_FILE,
              "The import file names no " + noun(kind) + " this account could be registered with.");
      exception.getBody().setProperty("field", "batchId");
      throw exception;
    }
    return register(account, named.value(), kind);
  }

  // Stores the keyed hash and the display value of a normalized identifier of a registrable kind:
  // a contract number in its own columns, beside the account's IBAN (#323).
  private AccountIdentifierResponse register(Account account, String normalized, String kind) {
    UUID workspaceId = account.getWorkspace().getId();
    String hash = identifiers.hash(workspaceId, normalized, kind);
    // Checked here for a clear answer; the unique index decides a race (GlobalExceptionHandler).
    (isContract(kind)
            ? accounts.findByWorkspaceIdAndContractIdentifierHash(workspaceId, hash)
            : accounts.findByWorkspaceIdAndIdentifierHash(workspaceId, hash))
        .filter(other -> !other.getId().equals(account.getId()))
        .ifPresent(
            other -> {
              throw new ApiException(
                  HttpStatus.CONFLICT,
                  ApiErrorCode.ACCOUNT_IDENTIFIER_IN_USE,
                  "Another account of this workspace is already registered with this identifier.");
            });
    if (isContract(kind)) {
      account.setContractIdentifierHash(hash);
      account.setContractIdentifierMasked(AccountIdentifierService.masked(normalized, kind));
    } else {
      account.setIdentifierHash(hash);
      account.setIdentifierMasked(AccountIdentifierService.masked(normalized, kind));
    }
    return response(accounts.saveAndFlush(account));
  }

  /**
   * Removes the account's identifiers, or with {@code kind} only that one (#323: its contract
   * number, or its IBAN or card number): no file is matched to it by them any more.
   *
   * @throws ApiException 422 {@code ACCOUNT_IDENTIFIER_INVALID} for a kind the account cannot have
   */
  @Transactional
  public AccountIdentifierResponse clear(
      UUID accountId, String kind, Integer expectedVersion, AuthenticatedUserPrincipal actor) {
    Account account = requireEditable(accountId, actor);
    versions.requireCurrent(expectedVersion, account.getVersion(), VERSIONED_RESOURCE);
    if (kind != null) {
      requireRegistrable(account, kind);
    }
    if (kind == null || !isContract(kind)) {
      account.setIdentifierHash(null);
      account.setIdentifierMasked(null);
    }
    if (kind == null || isContract(kind)) {
      account.setContractIdentifierHash(null);
      account.setContractIdentifierMasked(null);
    }
    return response(accounts.saveAndFlush(account));
  }

  // An account takes an identifier of its own kind and, unless it is a credit card, a contract
  // number besides it (#323).
  private static boolean registrable(Account account, String kind) {
    return kind.equals(AccountIdentifierService.kindOf(account))
        || isContract(kind) && !account.isHasStatementCycle();
  }

  private static void requireRegistrable(Account account, String kind) {
    if (!registrable(account, kind)) {
      ApiException exception =
          new ApiException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ApiErrorCode.ACCOUNT_IDENTIFIER_INVALID,
              "This account cannot be registered with a " + noun(kind) + ".");
      exception.getBody().setProperty("field", "kind");
      throw exception;
    }
  }

  private static boolean isContract(String kind) {
    return ImportTemplateValues.IDENTIFIER_CONTRACT_NUMBER.equals(kind);
  }

  private static String noun(String kind) {
    if (ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(kind)) {
      return "card number";
    }
    return isContract(kind) ? "contract number" : "IBAN";
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
        VersionPreconditionService.persistedVersion(account.getVersion(), VERSIONED_RESOURCE),
        account.getContractIdentifierMasked());
  }

  private static ApiException invalid(String kind) {
    String detail =
        ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(kind)
            ? "Enter the card number masked except for at most its first 6 and last 4 digits."
            : isContract(kind)
                ? "Enter a ten-digit contract number, with optional spaces or hyphens."
                : "This is not a valid IBAN; check it for typing errors.";
    ApiException exception =
        new ApiException(
            HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCode.ACCOUNT_IDENTIFIER_INVALID, detail);
    exception.getBody().setProperty("field", "identifier");
    return exception;
  }
}
