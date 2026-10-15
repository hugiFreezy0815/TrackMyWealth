package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportFileAccountIdentifier;
import com.trackmywealth.backend.dto.ImportIdentificationResponse;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US-07-10: the outcome table of a file's account identification, and the commit's guard. The
 * integration tests in {@code ImportBatchControllerTest} cover it end to end with a key; a server
 * without one ({@code UNAVAILABLE}) is only reachable here.
 */
class ImportAccountIdentificationServiceTest {

  private static final String IBAN = "DE89370400440532013000";
  private static final UUID MEMBER = UUID.randomUUID();

  private final AccountIdentifierService identifiers = mock(AccountIdentifierService.class);
  private final AccessControlService access = mock(AccessControlService.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final ImportAccountIdentificationService service =
      new ImportAccountIdentificationService(identifiers, access, accounts);

  private final AuthenticatedUserPrincipal actor =
      new AuthenticatedUserPrincipal(UUID.randomUUID(), "USER", UUID.randomUUID(), null, "EN");
  private final Workspace workspace = new Workspace();
  private Account own;
  private Account other;
  private ImportBatch batch;

  @BeforeEach
  void setUp() {
    workspace.setId(UUID.randomUUID());
    own = account();
    other = account();
    batch = new ImportBatch();
    when(identifiers.enabled()).thenReturn(true);
    when(access.requireActingMember(actor)).thenReturn(MEMBER);
    when(accounts.findById(other.getId())).thenReturn(Optional.of(other));
  }

  @Test
  void withoutAKeyTheOutcomeIsUnavailableAndTheCommitWaitsForAConfirmation() {
    when(identifiers.enabled()).thenReturn(false);

    service.identify(batch, own, iban(IBAN), actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_UNAVAILABLE, true);
    assertUnconfirmed();
  }

  @Test
  void aFileNamingNoReadableIdentifierIsNotFound() {
    service.identify(batch, own, iban(null), actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_NOT_FOUND, true);
  }

  @Test
  void anIdentifierNoAccountHoldsIsNoMatch() {
    when(identifiers.match(workspace.getId(), IBAN, ImportTemplateValues.IDENTIFIER_IBAN))
        .thenReturn(Optional.empty());

    service.identify(batch, own, iban(IBAN), actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_NO_MATCH, true);
  }

  @Test
  void theBatchsOwnAccountByItsIbanIsMatchedAndNeedsNoConfirmation() {
    when(identifiers.match(workspace.getId(), IBAN, ImportTemplateValues.IDENTIFIER_IBAN))
        .thenReturn(Optional.of(own));

    service.identify(batch, own, iban(IBAN), actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_MATCHED, false);
    assertThatCode(() -> service.requireConfirmedIfNeeded(batch)).doesNotThrowAnyException();
  }

  @Test
  void theBatchsOwnAccountByMaskedDigitsStillNeedsAConfirmation() {
    String card = "123456******1234";
    when(identifiers.match(workspace.getId(), card, ImportTemplateValues.IDENTIFIER_MASKED_CARD))
        .thenReturn(Optional.of(own));

    service.identify(
        batch,
        own,
        new ImportFileAccountIdentifier(ImportTemplateValues.IDENTIFIER_MASKED_CARD, card),
        actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_MATCHED_MASKED, true);
  }

  @Test
  void anotherAccountTheMemberMayImportIntoIsProposed() {
    namesOther(true);

    service.identify(batch, own, iban(IBAN), actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_OTHER_ACCOUNT, true);
    assertThat(batch.getProposedAccountId()).isEqualTo(other.getId());
    assertThat(service.response(batch, actor).proposedAccountId()).isEqualTo(other.getId());
  }

  @Test
  void anotherAccountTheMemberMayNotImportIntoIsNoMatchAndNotNamed() {
    namesOther(false);

    service.identify(batch, own, iban(IBAN), actor);

    assertOutcome(ImportBatchValues.IDENTIFIED_NO_MATCH, true);
    assertThat(batch.getProposedAccountId()).isNull();
  }

  @Test
  void aViewerWithoutAccessToTheProposedAccountSeesNoMatch() {
    namesOther(true);
    service.identify(batch, own, iban(IBAN), actor);
    namesOther(false);

    ImportIdentificationResponse shown = service.response(batch, actor);

    assertThat(shown.outcome()).isEqualTo(ImportBatchValues.IDENTIFIED_NO_MATCH);
    assertThat(shown.proposedAccountId()).isNull();
  }

  @Test
  void aReParseDecidesAgainAndATemplateWithoutARuleClearsIt() {
    namesOther(true);
    service.identify(batch, own, iban(IBAN), actor);
    batch.setAccountConfirmed(true);

    service.identify(batch, own, iban(IBAN), actor);
    assertThat(batch.isAccountConfirmed()).as("a re-parse asks again").isFalse();

    service.identify(batch, own, null, actor);
    assertThat(batch.getAccountIdentification()).isNull();
    assertThat(batch.getProposedAccountId()).isNull();
    assertThat(batch.isAccountConfirmationRequired()).isFalse();
    assertThat(service.response(batch, actor)).isNull();
  }

  @Test
  void aConfirmedBatchMayBeCommitted() {
    service.identify(batch, own, iban(null), actor);
    batch.setAccountConfirmed(true);

    assertThatCode(() -> service.requireConfirmedIfNeeded(batch)).doesNotThrowAnyException();
  }

  private void namesOther(boolean mayImport) {
    when(identifiers.match(workspace.getId(), IBAN, ImportTemplateValues.IDENTIFIER_IBAN))
        .thenReturn(Optional.of(other));
    when(access.accountsWithAccess(eq(MEMBER), anyCollection(), eq(AccessLevelValues.EDIT)))
        .thenAnswer(
            invocation ->
                mayImport
                    ? List.copyOf((Collection<Account>) invocation.getArgument(1))
                    : List.of());
  }

  private void assertOutcome(String outcome, boolean confirmationRequired) {
    assertThat(batch.getAccountIdentification()).isEqualTo(outcome);
    assertThat(batch.isAccountConfirmationRequired()).isEqualTo(confirmationRequired);
    assertThat(batch.isAccountConfirmed()).isFalse();
  }

  private void assertUnconfirmed() {
    assertThatThrownBy(() -> service.requireConfirmedIfNeeded(batch))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(e.getBody().getProperties())
                  .containsEntry(ApiErrorCode.PROPERTY, ApiErrorCode.IMPORT_ACCOUNT_UNCONFIRMED)
                  .containsEntry("accountIdentification", batch.getAccountIdentification());
            });
  }

  private static ImportFileAccountIdentifier iban(String value) {
    return new ImportFileAccountIdentifier(ImportTemplateValues.IDENTIFIER_IBAN, value);
  }

  private Account account() {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
    account.setWorkspace(workspace);
    account.setStatus("ACTIVE");
    account.setHasTransactions(true);
    return account;
  }
}
