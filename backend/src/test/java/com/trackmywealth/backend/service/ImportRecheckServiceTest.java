package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportDuplicateHold;
import com.trackmywealth.backend.dto.ImportRecheck;
import com.trackmywealth.backend.dto.ImportRowRecheck;
import com.trackmywealth.backend.dto.ParsedImportRow;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportRowRaw;
import com.trackmywealth.backend.repository.ImportRowRawBatchRepository;
import com.trackmywealth.backend.repository.ImportRowRawRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #287/#308: the decision what a commit does now with each preview row, without a database. That
 * the holds and duplicate queries read the right ledger rows, and that the preview and the commit
 * agree, is {@code ImportRollbackControllerTest}'s part.
 */
class ImportRecheckServiceTest {

  private static final UUID ACCOUNT = UUID.randomUUID();

  private final ImportRowRawRepository rowRepository = mock(ImportRowRawRepository.class);
  private final ImportRowRawBatchRepository rowBatchRepository =
      mock(ImportRowRawBatchRepository.class);
  private final ImportDuplicateService duplicateService = mock(ImportDuplicateService.class);
  private final ImportRecheckService service =
      new ImportRecheckService(
          rowRepository, rowBatchRepository, duplicateService, mock(ImportRowJsonService.class));

  @Test
  void onlyAParsedBatchIsCheckedAgain() {
    ImportRowRaw row = row(1, ImportBatchValues.ROW_DUPLICATE, false, UUID.randomUUID());

    for (String status :
        List.of(
            ImportBatchValues.COMMITTED,
            ImportBatchValues.DISCARDED,
            ImportBatchValues.ROLLED_BACK)) {
      assertThat(service.recheck(batch(status), List.of(row))).isSameAs(ImportRecheck.NONE);
    }
    verifyNoInteractions(rowRepository, duplicateService);
  }

  @Test
  void aDuplicateWhoseRowStillHoldsTheBookingStaysAndReservesIt() {
    UUID booking = UUID.randomUUID();
    ImportRowRaw duplicate = row(1, ImportBatchValues.ROW_DUPLICATE, false, booking);
    ImportRowRaw fresh = row(2, ImportBatchValues.ROW_PARSED, true, null);
    holds(new Hold(duplicate.getId(), booking));
    nowDuplicates(Map.of());

    ImportRecheck recheck = service.recheck(parsed(), List.of(duplicate, fresh));

    assertThat(recheck.isEmpty()).isTrue();
    // The held row is not checked again, and nothing else may match its booking.
    ArgumentCaptor<List<ParsedImportRow>> checked = rowsChecked(Set.of(booking));
    assertThat(checked.getValue()).extracting(ParsedImportRow::rowNumber).containsExactly(2);
  }

  @Test
  void aDuplicateWhoseBookingWasReplacedPointsAtTheReplacementIncludedOrNotAsBefore() {
    UUID replacement = UUID.randomUUID();
    ImportRowRaw left = row(1, ImportBatchValues.ROW_DUPLICATE, false, UUID.randomUUID());
    ImportRowRaw forced = row(2, ImportBatchValues.ROW_DUPLICATE, true, UUID.randomUUID());
    UUID other = UUID.randomUUID();
    holds(new Hold(left.getId(), replacement), new Hold(forced.getId(), other));
    nowDuplicates(Map.of());

    ImportRecheck recheck = service.recheck(parsed(), List.of(left, forced));

    assertThat(recheck.of(left.getId())).isEqualTo(ImportRowRecheck.repointed(replacement, false));
    assertThat(recheck.of(forced.getId())).isEqualTo(ImportRowRecheck.repointed(other, true));
  }

  @Test
  void aDuplicateWhoseBookingIsGoneButHeldAsAnotherLedgerRowPointsThere() {
    UUID manual = UUID.randomUUID();
    ImportRowRaw cleared = row(1, ImportBatchValues.ROW_DUPLICATE, false, null);
    holds();
    nowDuplicates(Map.of(1, manual));

    ImportRecheck recheck = service.recheck(parsed(), List.of(cleared));

    assertThat(recheck.of(cleared.getId())).isEqualTo(ImportRowRecheck.repointed(manual, false));
  }

  @Test
  void aDuplicateWhoseBookingIsGoneBecomesNewAndIncludedEvenIfExcluded() {
    ImportRowRaw cleared = row(1, ImportBatchValues.ROW_DUPLICATE, false, null);
    ImportRowRaw voided = row(2, ImportBatchValues.ROW_DUPLICATE, true, UUID.randomUUID());
    holds();
    nowDuplicates(Map.of());

    ImportRecheck recheck = service.recheck(parsed(), List.of(cleared, voided));

    assertThat(recheck.of(cleared.getId())).isEqualTo(ImportRowRecheck.becameNew(false));
    assertThat(recheck.of(voided.getId())).isEqualTo(ImportRowRecheck.becameNew(true));
    assertThat(recheck.of(voided.getId()).status()).isEqualTo(ImportBatchValues.ROW_PARSED);
  }

  @Test
  void anIncludedNewRowTheLedgerHasGainedBecomesAnExcludedDuplicateAnExcludedOneStays() {
    UUID gained = UUID.randomUUID();
    ImportRowRaw included = row(1, ImportBatchValues.ROW_PARSED, true, null);
    ImportRowRaw excluded = row(2, ImportBatchValues.ROW_PARSED, false, null);
    ImportRowRaw untouched = row(3, ImportBatchValues.ROW_PARSED, true, null);
    holds();
    nowDuplicates(Map.of(1, gained, 2, UUID.randomUUID()));

    ImportRecheck recheck = service.recheck(parsed(), List.of(included, excluded, untouched));

    assertThat(recheck.rows()).containsOnlyKeys(included.getId());
    ImportRowRecheck duplicate = recheck.of(included.getId());
    assertThat(duplicate).isEqualTo(ImportRowRecheck.becameDuplicate(gained));
    assertThat(duplicate.status()).isEqualTo(ImportBatchValues.ROW_DUPLICATE);
    assertThat(duplicate.included()).isFalse();
  }

  @Test
  void applyingStoresEveryOutcomeAndTheBatchsDuplicateCount() {
    UUID becameNew = UUID.randomUUID();
    UUID repointed = UUID.randomUUID();
    UUID becameDuplicate = UUID.randomUUID();
    UUID secondNew = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    UUID match = UUID.randomUUID();
    Map<UUID, ImportRowRecheck> rows = new LinkedHashMap<>();
    rows.put(becameNew, ImportRowRecheck.becameNew(false));
    rows.put(repointed, ImportRowRecheck.repointed(holder, true));
    rows.put(becameDuplicate, ImportRowRecheck.becameDuplicate(match));
    rows.put(secondNew, ImportRowRecheck.becameNew(true));
    ImportBatch batch = parsed();
    batch.setDuplicateRowCount(5);

    service.apply(batch, new ImportRecheck(rows));

    verify(rowBatchRepository).markNew(List.of(becameNew, secondNew));
    verify(rowBatchRepository).repointDuplicates(List.of(repointed), List.of(holder));
    verify(rowBatchRepository).markDuplicates(List.of(becameDuplicate), List.of(match));
    assertThat(batch.getDuplicateRowCount()).isEqualTo(4);
  }

  @Test
  void applyingNothingWritesNothing() {
    ImportBatch batch = parsed();
    batch.setDuplicateRowCount(2);

    service.apply(batch, ImportRecheck.NONE);

    verifyNoInteractions(rowBatchRepository);
    assertThat(batch.getDuplicateRowCount()).isEqualTo(2);
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private record Hold(UUID getRowId, UUID getTransactionId) implements ImportDuplicateHold {}

  private void holds(ImportDuplicateHold... holds) {
    when(rowRepository.findDuplicateHolds(any())).thenReturn(List.of(holds));
  }

  private void nowDuplicates(Map<Integer, UUID> duplicates) {
    when(duplicateService.findDuplicates(eq(ACCOUNT), eq("CSV"), anyList(), anySet()))
        .thenReturn(duplicates);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<List<ParsedImportRow>> rowsChecked(Set<UUID> reserved) {
    ArgumentCaptor<List<ParsedImportRow>> checked = ArgumentCaptor.forClass(List.class);
    verify(duplicateService)
        .findDuplicates(eq(ACCOUNT), eq("CSV"), checked.capture(), eq(reserved));
    return checked;
  }

  private static ImportBatch parsed() {
    return batch(ImportBatchValues.PARSED);
  }

  private static ImportBatch batch(String status) {
    ImportBatch batch = new ImportBatch();
    batch.setStatus(status);
    batch.setAccountId(ACCOUNT);
    batch.setSourceKind(ImportBatchValues.SOURCE_CSV);
    return batch;
  }

  // The entity has no setters for what the parse stores; its getters are all the re-check reads.
  private static ImportRowRaw row(
      int rowNumber, String status, boolean included, UUID duplicateOf) {
    ImportRowRaw row = mock(ImportRowRaw.class);
    UUID id = UUID.randomUUID();
    when(row.getId()).thenReturn(id);
    when(row.getRowNumber()).thenReturn(rowNumber);
    when(row.getParseStatus()).thenReturn(status);
    when(row.isIncluded()).thenReturn(included);
    when(row.getDuplicateOfTransactionId()).thenReturn(duplicateOf);
    return row;
  }
}
