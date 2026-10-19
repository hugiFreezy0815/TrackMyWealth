package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.ImportSnapshotPlan;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.SnapshotHolding;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * US-07-31 (#366): how a statement meets the imported snapshot of its date, part by part, and the
 * sentence that tells the member - without a database. The Testcontainers counterpart is {@code
 * HoldingsImportControllerTest}.
 */
class ImportSnapshotCompletionServiceTest {

  private static final LocalDate DATE = LocalDate.of(2031, 12, 31);
  private static final UUID CHF_SHARE = UUID.randomUUID();
  private static final UUID USD_SHARE = UUID.randomUUID();

  private final AccountSnapshotRepository snapshots = mock(AccountSnapshotRepository.class);
  private final SnapshotHoldingRepository holdings = mock(SnapshotHoldingRepository.class);
  private final ReconciliationResultRepository results = mock(ReconciliationResultRepository.class);
  private final ReconciliationService reconciliation = mock(ReconciliationService.class);
  private final BusinessDateService businessDates = mock(BusinessDateService.class);
  private final ImportSnapshotCompletionService service =
      new ImportSnapshotCompletionService(
          snapshots,
          holdings,
          results,
          mock(SecurityRepository.class),
          reconciliation,
          businessDates);
  private final Account account = mock(Account.class);

  @BeforeEach
  void anAccountAndToday() {
    when(account.getId()).thenReturn(UUID.randomUUID());
    when(businessDates.today()).thenReturn(LocalDate.of(2032, 1, 15));
  }

  @Test
  void withoutAnImportedSnapshotOfTheDateTheStatementRecordsANewOne() {
    ImportSnapshotPlan plan = service.plan(account, DATE, new BigDecimal("1204.35"), held(), true);

    assertThat(plan.outcome()).isEqualTo(ImportSnapshotValues.NEW);
    assertThat(plan.adds())
        .containsExactly(ImportSnapshotValues.PART_BALANCE, ImportSnapshotValues.PART_HOLDINGS);
    assertThat(plan.existing()).isNull();
  }

  /** PF-7 after PF-5: the same positions, and the cash PF-5 did not have. */
  @Test
  void theSamePositionsWithTheMissingCashCompleteTheSnapshot() {
    existing(null, holding(CHF_SHARE, "25.0000000000"), holding(USD_SHARE, "1200.0000000000"));

    ImportSnapshotPlan plan = service.plan(account, DATE, new BigDecimal("1204.35"), held(), true);

    // 25 is 25.0000000000: quantities compare by value, not by scale.
    assertThat(plan.outcome()).isEqualTo(ImportSnapshotValues.COMPLETES);
    assertThat(plan.adds()).containsExactly(ImportSnapshotValues.PART_BALANCE);
    assertThat(plan.conflicts()).isEmpty();
    assertThat(plan.existing().holdingsCount()).isEqualTo(2);
  }

  @Test
  void anotherQuantityOrAnotherBalanceIsAConflictAndNothingTheSameIsADuplicate() {
    existing(new BigDecimal("1204.35"), holding(CHF_SHARE, "25"), holding(USD_SHARE, "1199"));
    ImportSnapshotPlan quantity =
        service.plan(account, DATE, new BigDecimal("1204.35"), held(), true);
    assertThat(quantity.outcome()).isEqualTo(ImportSnapshotValues.CONFLICTING);
    assertThat(quantity.conflicts())
        .singleElement()
        .satisfies(
            conflict -> {
              assertThat(conflict.part()).isEqualTo(ImportSnapshotValues.CONFLICT_HOLDING);
              assertThat(conflict.securityId()).isEqualTo(USD_SHARE);
              assertThat(conflict.existingValue()).isEqualByComparingTo("1199");
              assertThat(conflict.statementValue()).isEqualByComparingTo("1200");
            });

    ImportSnapshotPlan balance =
        service.plan(account, DATE, new BigDecimal("99.00"), Map.of(), true);
    assertThat(balance.outcome()).isEqualTo(ImportSnapshotValues.CONFLICTING);
    assertThat(balance.conflicts().get(0).part()).isEqualTo(ImportSnapshotValues.PART_BALANCE);

    ImportSnapshotPlan same =
        service.plan(account, DATE, new BigDecimal("1204.3500"), Map.of(), true);
    assertThat(same.outcome()).isEqualTo(ImportSnapshotValues.ALREADY_RECORDED);
    assertThat(same.committable()).isFalse();
  }

  /** Until every position resolves, the one resolving later may be the snapshot's: no verdict. */
  @Test
  void holdingsAreNotComparedWhilePositionsAreUnresolved() {
    existing(null, holding(CHF_SHARE, "25"), holding(USD_SHARE, "1199"));

    ImportSnapshotPlan plan = service.plan(account, DATE, new BigDecimal("1.00"), held(), false);

    assertThat(plan.conflicts()).isEmpty();
    assertThat(plan.outcome()).isEqualTo(ImportSnapshotValues.COMPLETES);
  }

  @Test
  void aDateTheAccountDoesNotAdmitIsOutOfRange() {
    when(account.getClosedAt()).thenReturn(DATE.minusDays(1));

    assertThat(service.plan(account, DATE, BigDecimal.ONE, Map.of(), true).outcome())
        .isEqualTo(ImportSnapshotValues.OUT_OF_RANGE);
  }

  @Test
  void aStandingDecisionOrAnAdjustingEntryKeepsTheSnapshotFromBeingCompleted() {
    AccountSnapshot snapshot = existing(null, holding(CHF_SHARE, "25"), holding(USD_SHARE, "1200"));
    when(snapshots.findForUpdate(snapshot.getId(), account.getId()))
        .thenReturn(Optional.of(snapshot));
    ReconciliationResult accepted = new ReconciliationResult();
    ReflectionTestUtils.setField(accepted, "id", UUID.randomUUID());
    accepted.setStatus(ReconciliationResultValues.ACCEPTED);
    when(results.findBySnapshotId(snapshot.getId())).thenReturn(List.of(accepted));
    ImportSnapshotPlan plan = service.plan(account, DATE, new BigDecimal("1204.35"), held(), true);
    ImportBatch batch = new ImportBatch();
    ReflectionTestUtils.setField(batch, "id", UUID.randomUUID());

    assertThatThrownBy(
            () ->
                service.commit(
                    batch,
                    account,
                    DATE,
                    "CHF",
                    plan,
                    new BigDecimal("1204.35"),
                    List.of(),
                    UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getCode())
                  .isEqualTo(ApiErrorCode.IMPORT_SNAPSHOT_RECONCILIATION_DECIDED);
              assertThat(e.getBody().getProperties())
                  .containsEntry("reconciliationResultId", accepted.getId());
            });
    verify(snapshots, never()).saveAndFlush(any());
  }

  @Test
  void aRefusedPlanCarriesTheSnapshotAndItsDifferences() {
    existing(new BigDecimal("1204.35"));
    ImportSnapshotPlan conflict =
        service.plan(account, DATE, new BigDecimal("1.00"), Map.of(), true);

    ApiException refused = ImportSnapshotCompletionService.refusal(conflict, "Differs.");

    assertThat(refused.getCode()).isEqualTo(ApiErrorCode.IMPORT_SNAPSHOT_CONFLICT);
    assertThat(refused.getBody().getProperties())
        .containsEntry("existingSnapshotId", conflict.existing().id())
        .containsKey("conflicts");
  }

  // --- the member's sentence -----------------------------------------------------------------

  @Test
  void theExplanationSaysWhatTheCommitDoesInTheMembersLanguage() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    ImportSnapshotExplanationService explanations = new ImportSnapshotExplanationService(messages);
    ImportSnapshotPlan completes =
        new ImportSnapshotPlan(
            ImportSnapshotValues.COMPLETES,
            new ImportSnapshotPlan.Existing(UUID.randomUUID(), UUID.randomUUID(), null, 3),
            List.of(ImportSnapshotValues.PART_BALANCE),
            List.of());
    ImportSnapshotPlan created =
        new ImportSnapshotPlan(
            ImportSnapshotValues.NEW,
            null,
            List.of(ImportSnapshotValues.PART_BALANCE, ImportSnapshotValues.PART_HOLDINGS),
            List.of());

    assertThat(explanations.explain("COMPLETES", completes, DATE, 0, 0, false, Locale.ENGLISH))
        .isEqualTo(
            "This statement adds the cash balance to the snapshot of Dec 31, 2031 you"
                + " imported before.");
    // German needs the accusative after "hinzufügen" and the dative after "mit".
    assertThat(explanations.explain("COMPLETES", completes, DATE, 0, 0, false, Locale.GERMAN))
        .isEqualTo(
            "Dieser Auszug fügt dem zuvor importierten Stand vom 31.12.2031 den Kontostand"
                + " hinzu.");
    assertThat(explanations.explain("NEW", created, DATE, 0, 0, false, Locale.GERMAN))
        .isEqualTo(
            "Dieser Auszug erfasst einen neuen Stand vom 31.12.2031 mit den Positionen und"
                + " dem Kontostand.");
    // What keeps the commit back comes first.
    assertThat(explanations.explain("COMPLETES", completes, DATE, 0, 2, false, Locale.ENGLISH))
        .startsWith("2 positions are securities your workspace does not know yet.");
  }

  // --- helpers -------------------------------------------------------------------------------

  // The statement's positions: 25 of the CHF share, 1200 of the USD one.
  private static Map<UUID, BigDecimal> held() {
    Map<UUID, BigDecimal> held = new LinkedHashMap<>();
    held.put(CHF_SHARE, new BigDecimal("25"));
    held.put(USD_SHARE, new BigDecimal("1200"));
    return held;
  }

  private AccountSnapshot existing(BigDecimal balance, SnapshotHolding... lines) {
    AccountSnapshot snapshot = new AccountSnapshot();
    ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
    snapshot.setImportBatchId(UUID.randomUUID());
    snapshot.setBalance(balance);
    when(snapshots.findByAccountIdAndSnapshotDateAndSourceAndWithdrawnAtIsNull(
            account.getId(), DATE, "DOCUMENT"))
        .thenReturn(Optional.of(snapshot));
    when(holdings.findBySnapshotId(snapshot.getId())).thenReturn(List.of(lines));
    return snapshot;
  }

  private static SnapshotHolding holding(UUID security, String quantity) {
    SnapshotHolding holding = new SnapshotHolding();
    holding.setSecurityId(security);
    holding.setQuantity(new BigDecimal(quantity));
    return holding;
  }
}
