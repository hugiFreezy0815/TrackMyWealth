package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportIdentificationResponse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-07-10: matches the account identifier an import file names to the workspace's accounts, and
 * keeps a batch from being committed into an account the file does not name until the member has
 * confirmed it.
 *
 * <p>Only the outcome and, for another account, that account's id are stored on the batch; the
 * identifier itself is matched by its keyed hash and dropped. A file naming the batch's account by
 * its IBAN needs no confirmation; a masked card number (several cards can share the visible
 * digits), a file naming another account or none, and a server without a key always do. Another
 * account is proposed only when the member may import into it; otherwise the file is reported as
 * matching no account, so the outcome reveals nothing the member may not see.
 */
@Service
public class ImportAccountIdentificationService {

  private static final String ACTIVE = "ACTIVE";

  private final AccountIdentifierService identifiers;
  private final AccessControlService accessControlService;
  private final AccountRepository accountRepository;

  public ImportAccountIdentificationService(
      AccountIdentifierService identifiers,
      AccessControlService accessControlService,
      AccountRepository accountRepository) {
    this.identifiers = identifiers;
    this.accessControlService = accessControlService;
    this.accountRepository = accountRepository;
  }

  /**
   * Records on {@code batch} what {@code fileIdentifier} says about {@code account}, the batch's
   * account, replacing any earlier outcome and confirmation: a re-parse decides again. A {@code
   * null} identifier (the template does not identify accounts) clears it.
   */
  public void identify(
      ImportBatch batch,
      Account account,
      ImportFileAccountIdentifier fileIdentifier,
      AuthenticatedUserPrincipal actor) {
    batch.setAccountConfirmed(false);
    batch.setProposedAccountId(null);
    if (fileIdentifier == null) {
      batch.setAccountIdentification(null);
      batch.setAccountConfirmationRequired(false);
      return;
    }
    String outcome;
    if (!identifiers.enabled()) {
      outcome = ImportBatchValues.IDENTIFIED_UNAVAILABLE;
    } else if (!fileIdentifier.found()) {
      outcome = ImportBatchValues.IDENTIFIED_NOT_FOUND;
    } else {
      Optional<Account> named =
          identifiers.match(
              account.getWorkspace().getId(), fileIdentifier.value(), fileIdentifier.kind());
      if (named.isEmpty()) {
        outcome = ImportBatchValues.IDENTIFIED_NO_MATCH;
      } else if (named.get().getId().equals(account.getId())) {
        outcome =
            ImportTemplateValues.IDENTIFIER_MASKED_CARD.equals(fileIdentifier.kind())
                ? ImportBatchValues.IDENTIFIED_MATCHED_MASKED
                : ImportBatchValues.IDENTIFIED_MATCHED;
      } else if (mayImportInto(actor, named.get())) {
        outcome = ImportBatchValues.IDENTIFIED_OTHER_ACCOUNT;
        batch.setProposedAccountId(named.get().getId());
      } else {
        outcome = ImportBatchValues.IDENTIFIED_NO_MATCH;
      }
    }
    batch.setAccountIdentification(outcome);
    batch.setAccountConfirmationRequired(!ImportBatchValues.IDENTIFIED_MATCHED.equals(outcome));
  }

  /** The batch's identification as {@code actor} may see it; {@code null} when it has none. */
  public ImportIdentificationResponse response(
      ImportBatch batch, AuthenticatedUserPrincipal actor) {
    if (batch.getAccountIdentification() == null) {
      return null;
    }
    UUID proposed = batch.getProposedAccountId();
    // Proposed for the member who uploaded it; another viewer sees it only with access too.
    if (proposed != null && !mayImportInto(actor, proposed)) {
      return new ImportIdentificationResponse(
          ImportBatchValues.IDENTIFIED_NO_MATCH,
          null,
          batch.isAccountConfirmationRequired(),
          batch.isAccountConfirmed());
    }
    return new ImportIdentificationResponse(
        batch.getAccountIdentification(),
        proposed,
        batch.isAccountConfirmationRequired(),
        batch.isAccountConfirmed());
  }

  /**
   * @throws ApiException 409 {@code IMPORT_ACCOUNT_UNCONFIRMED} while the batch needs a
   *     confirmation it does not have
   */
  public void requireConfirmedIfNeeded(ImportBatch batch) {
    if (batch.isAccountConfirmationRequired() && !batch.isAccountConfirmed()) {
      ApiException exception =
          new ApiException(
              HttpStatus.CONFLICT,
              ApiErrorCode.IMPORT_ACCOUNT_UNCONFIRMED,
              "The file does not name this account for certain; confirm the account before"
                  + " committing.");
      exception.getBody().setProperty("accountIdentification", batch.getAccountIdentification());
      throw exception;
    }
  }

  private boolean mayImportInto(AuthenticatedUserPrincipal actor, UUID accountId) {
    return accountRepository.findById(accountId).filter(a -> mayImportInto(actor, a)).isPresent();
  }

  private boolean mayImportInto(AuthenticatedUserPrincipal actor, Account account) {
    UUID memberId = accessControlService.requireActingMember(actor);
    return ACTIVE.equals(account.getStatus())
        && account.isHasTransactions()
        && !accessControlService
            .accountsWithAccess(memberId, List.of(account), AccessLevelValues.EDIT)
            .isEmpty();
  }
}
