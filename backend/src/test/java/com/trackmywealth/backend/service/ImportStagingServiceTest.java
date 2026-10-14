package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawBatchRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Import staging must never disclose payments from an account the importing member cannot see. */
class ImportStagingServiceTest {

  private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
  private final ImportRowRawBatchRepository rawRows = mock(ImportRowRawBatchRepository.class);
  private final ImportRowCheckService rowChecks = mock(ImportRowCheckService.class);
  private final ImportDuplicateService duplicates = mock(ImportDuplicateService.class);
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final AccessControlService access = mock(AccessControlService.class);
  private final ImportStagingService staging =
      new ImportStagingService(
          batches, rawRows, rowChecks, duplicates, JsonMapper.builder().build(),
          Clock.systemUTC(), accounts, access);

  @Test
  void revokedPaymentAccountAccessPreventsSettlementCandidateLookup() {
    UUID cardId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Account card = mock(Account.class);
    Account source = mock(Account.class);
    ImportBatch batch = mock(ImportBatch.class);
    AuthenticatedUserPrincipal actor = mock(AuthenticatedUserPrincipal.class);
    when(card.getId()).thenReturn(cardId);
    when(card.getNativeCurrency()).thenReturn("CHF");
    when(batch.getSourceKind()).thenReturn("CSV");
    when(rowChecks.settlementSourceOf(card)).thenReturn(Optional.of(sourceId));
    when(accounts.findById(sourceId)).thenReturn(Optional.of(source));
    when(access.requireActingMember(actor)).thenReturn(member);
    when(access.accountsWithAccess(member, List.of(source), AccessLevelValues.BALANCE_ONLY))
        .thenReturn(List.of());

    staging.stage(batch, card, List.of(), Map.of(), actor);

    verify(access).accountsWithAccess(member, List.of(source), AccessLevelValues.BALANCE_ONLY);
    verify(rowChecks).matchedSettlementRows(isNull(), eq("CHF"), eq(List.of()));
  }

  @Test
  void visiblePaymentAccountCanBeUsedToFindSettlementCandidates() {
    UUID sourceId = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Account card = mock(Account.class);
    Account source = mock(Account.class);
    ImportBatch batch = mock(ImportBatch.class);
    AuthenticatedUserPrincipal actor = mock(AuthenticatedUserPrincipal.class);
    when(card.getId()).thenReturn(UUID.randomUUID());
    when(card.getNativeCurrency()).thenReturn("CHF");
    when(batch.getSourceKind()).thenReturn("CSV");
    when(rowChecks.settlementSourceOf(card)).thenReturn(Optional.of(sourceId));
    when(accounts.findById(sourceId)).thenReturn(Optional.of(source));
    when(access.requireActingMember(actor)).thenReturn(member);
    when(access.accountsWithAccess(member, List.of(source), AccessLevelValues.BALANCE_ONLY))
        .thenReturn(List.of(source));

    staging.stage(batch, card, List.of(), Map.of(), actor);

    verify(rowChecks).matchedSettlementRows(eq(sourceId), eq("CHF"), eq(List.of()));
  }
}
