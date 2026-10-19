package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportSnapshotHolding;
import com.trackmywealth.backend.dto.ImportSnapshotPlan;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import com.trackmywealth.backend.dto.ReconciliationResultValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ReconciliationResult;
import com.trackmywealth.backend.entity.Security;
import com.trackmywealth.backend.entity.SnapshotHolding;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * US-07-31 (#366): an imported ({@code DOCUMENT}) snapshot is completed part by part. It has two
 * parts, its balance and its holdings; a statement of the same account and date meets it part by
 * part:
 *
 * <ul>
 *   <li>a part the snapshot lacks and the statement holds is <b>added</b>;
 *   <li>a part both hold and agree on is kept - a balance of the same amount, holdings of the same
 *       securities in the same quantities (reported prices and values may differ in rounding
 *       between a PDF and a spreadsheet of one date: the snapshot's stay);
 *   <li>a part both hold and disagree on is a <b>conflict</b>, never overwritten.
 * </ul>
 *
 * <p>So a statement is {@code NEW} (no imported snapshot of the date), {@code COMPLETES} (adds a
 * part, conflicts in none), {@code ALREADY_RECORDED} (adds nothing, conflicts in nothing - a
 * duplicate) or {@code CONFLICTING}; {@code OUT_OF_RANGE} when the account's dates do not admit its
 * date, as for a snapshot recorded by hand. A {@code MANUAL} snapshot of the date is never met: the
 * sources stay apart (US-07-12).
 *
 * <p>Each part records the batch that contributed it ({@code balance_import_batch_id}, {@code
 * snapshot_holding.import_batch_id}, V101), so a rollback removes only its own parts ({@link
 * ImportStatementBalanceService#rollBack}). A snapshot on which a member's reconciliation decision
 * stands, or that an adjusting entry points at, is not completed (409 {@code
 * IMPORT_SNAPSHOT_RECONCILIATION_DECIDED}): the decision covered what it held.
 *
 * <p>Runs inside the caller's transaction, after the account's import lock and the batch's lock.
 */
@Service
public class ImportSnapshotCompletionService {

  private static final String SOURCE_DOCUMENT = "DOCUMENT";
  private static final int QUANTITY_SCALE = 10;
  private static final Set<String> DECIDED =
      Set.of(ReconciliationResultValues.ACCEPTED, ReconciliationResultValues.DISMISSED);

  private final AccountSnapshotRepository snapshotRepository;
  private final SnapshotHoldingRepository holdingRepository;
  private final ReconciliationResultRepository resultRepository;
  private final SecurityRepository securityRepository;
  private final ReconciliationService reconciliationService;
  private final BusinessDateService businessDates;

  public ImportSnapshotCompletionService(
      AccountSnapshotRepository snapshotRepository,
      SnapshotHoldingRepository holdingRepository,
      ReconciliationResultRepository resultRepository,
      SecurityRepository securityRepository,
      ReconciliationService reconciliationService,
      BusinessDateService businessDates) {
    this.snapshotRepository = snapshotRepository;
    this.holdingRepository = holdingRepository;
    this.resultRepository = resultRepository;
    this.securityRepository = securityRepository;
    this.reconciliationService = reconciliationService;
    this.businessDates = businessDates;
  }

  /**
   * What committing a statement of {@code date} would do now (see the class comment).
   *
   * @param balance the statement's balance in the account's currency, {@code null} for none
   * @param holdings the statement's resolved positions by security, empty for none
   * @param holdingsKnown whether every position resolved: unless they all did, the holdings are not
   *     compared (a position resolving later may be the one the snapshot holds)
   */
  public ImportSnapshotPlan plan(
      Account account,
      LocalDate date,
      BigDecimal balance,
      Map<UUID, BigDecimal> holdings,
      boolean holdingsKnown) {
    if (!withinAccountDates(account, date)) {
      return new ImportSnapshotPlan(ImportSnapshotValues.OUT_OF_RANGE, null, List.of(), List.of());
    }
    List<String> parts = new ArrayList<>();
    if (balance != null) {
      parts.add(ImportSnapshotValues.PART_BALANCE);
    }
    if (!holdings.isEmpty()) {
      parts.add(ImportSnapshotValues.PART_HOLDINGS);
    }
    Optional<AccountSnapshot> found = existing(account, date);
    if (found.isEmpty()) {
      return new ImportSnapshotPlan(ImportSnapshotValues.NEW, null, parts, List.of());
    }
    AccountSnapshot snapshot = found.get();
    List<SnapshotHolding> held = holdingRepository.findBySnapshotId(snapshot.getId());
    List<String> adds = new ArrayList<>();
    List<ImportSnapshotPlan.Conflict> conflicts = new ArrayList<>();
    if (balance != null) {
      if (snapshot.getBalance() == null) {
        adds.add(ImportSnapshotValues.PART_BALANCE);
      } else if (snapshot.getBalance().compareTo(balance) != 0) {
        conflicts.add(
            new ImportSnapshotPlan.Conflict(
                ImportSnapshotValues.PART_BALANCE,
                null,
                null,
                null,
                snapshot.getBalance(),
                balance));
      }
    }
    if (!holdings.isEmpty()) {
      if (held.isEmpty()) {
        adds.add(ImportSnapshotValues.PART_HOLDINGS);
      } else if (holdingsKnown) {
        conflicts.addAll(holdingConflicts(held, holdings));
      }
    }
    String outcome;
    if (!conflicts.isEmpty()) {
      outcome = ImportSnapshotValues.CONFLICTING;
    } else if (!adds.isEmpty()) {
      outcome = ImportSnapshotValues.COMPLETES;
    } else {
      outcome = ImportSnapshotValues.ALREADY_RECORDED;
    }
    return new ImportSnapshotPlan(
        outcome,
        new ImportSnapshotPlan.Existing(
            snapshot.getId(), snapshot.getImportBatchId(), snapshot.getBalance(), held.size()),
        adds,
        conflicts);
  }

  /**
   * Records what {@code plan} - {@code NEW} or {@code COMPLETES}, planned in this transaction -
   * says: a new snapshot of the batch, or the parts it adds to the existing one. Reconciles the
   * account against its newest snapshot afterwards.
   *
   * @return {@code RECORDED} or {@code COMPLETED}, for the batch's record
   * @throws ApiException 409 {@code IMPORT_SNAPSHOT_RECONCILIATION_DECIDED} when a decision stands
   *     on the snapshot to complete
   */
  public String commit(
      ImportBatch batch,
      Account account,
      LocalDate date,
      String currency,
      ImportSnapshotPlan plan,
      BigDecimal balance,
      List<ImportSnapshotHolding> holdings,
      UUID committedBy) {
    if (!plan.committable()) {
      throw new IllegalStateException(
          "Only a NEW or COMPLETES plan is committed: " + plan.outcome());
    }
    AccountSnapshot snapshot;
    String outcome;
    if (ImportSnapshotValues.NEW.equals(plan.outcome())) {
      snapshot = new AccountSnapshot();
      snapshot.setWorkspace(account.getWorkspace());
      snapshot.setAccount(account);
      snapshot.setSnapshotDate(date);
      snapshot.setCurrency(currency);
      snapshot.setSource(SOURCE_DOCUMENT);
      snapshot.setImportBatchId(batch.getId());
      snapshot.setCreatedBy(committedBy);
      if (balance != null) {
        snapshot.setImportedBalance(balance.setScale(FxRateService.MONEY_SCALE), batch.getId());
      }
      outcome = ImportSnapshotValues.RECORDED;
    } else {
      // The lock a reconciliation and a member's decision take first (US-25-03).
      snapshot =
          snapshotRepository
              .findForUpdate(plan.existing().id(), account.getId())
              .orElseThrow(() -> new IllegalStateException("The planned snapshot is gone."));
      requireNoDecision(snapshot);
      if (plan.adds().contains(ImportSnapshotValues.PART_BALANCE)) {
        snapshot.setImportedBalance(balance.setScale(FxRateService.MONEY_SCALE), batch.getId());
      }
      outcome = ImportSnapshotValues.COMPLETED;
    }
    // Flushed: a concurrent snapshot of the date surfaces here as the unique index's 409.
    snapshot = snapshotRepository.saveAndFlush(snapshot);
    boolean addsHoldings =
        ImportSnapshotValues.NEW.equals(plan.outcome())
            || plan.adds().contains(ImportSnapshotValues.PART_HOLDINGS);
    if (addsHoldings && !holdings.isEmpty()) {
      List<SnapshotHolding> rows = new ArrayList<>(holdings.size());
      for (ImportSnapshotHolding position : holdings) {
        SnapshotHolding holding = new SnapshotHolding();
        holding.setSnapshotId(snapshot.getId());
        holding.setSecurityId(position.securityId());
        holding.setQuantity(position.quantity().setScale(QUANTITY_SCALE));
        holding.setReported(
            position.price(),
            position.value().setScale(FxRateService.MONEY_SCALE),
            position.currency());
        holding.setImportBatchId(batch.getId());
        rows.add(holding);
      }
      holdingRepository.saveAllAndFlush(rows);
    }
    reconciliationService.reconcileLatest(account, committedBy);
    return outcome;
  }

  /**
   * 409 {@code IMPORT_SNAPSHOT_ALREADY_RECORDED} or {@code IMPORT_SNAPSHOT_CONFLICT} for a plan
   * that cannot be committed, carrying the snapshot and the differences, so a client needs no
   * second request to explain it.
   */
  public static ApiException refusal(ImportSnapshotPlan plan, String explanation) {
    boolean duplicate = ImportSnapshotValues.ALREADY_RECORDED.equals(plan.outcome());
    ApiException refused =
        new ApiException(
            HttpStatus.CONFLICT,
            duplicate
                ? ApiErrorCode.IMPORT_SNAPSHOT_ALREADY_RECORDED
                : ApiErrorCode.IMPORT_SNAPSHOT_CONFLICT,
            explanation);
    refused.getBody().setProperty("existingSnapshotId", plan.existing().id());
    if (!duplicate) {
      refused.getBody().setProperty("conflicts", plan.conflicts());
    }
    return refused;
  }

  /**
   * 409 {@code IMPORT_SNAPSHOT_RECONCILIATION_DECIDED} while a member's decision stands on the
   * snapshot (or an adjusting entry points at one of its results).
   */
  void requireNoDecision(AccountSnapshot snapshot) {
    List<ReconciliationResult> results = resultRepository.findBySnapshotId(snapshot.getId());
    Optional<ReconciliationResult> decided =
        results.stream().filter(result -> DECIDED.contains(result.getStatus())).findFirst();
    List<UUID> ids = results.stream().map(ReconciliationResult::getId).toList();
    if (decided.isEmpty() && (ids.isEmpty() || !resultRepository.existsAdjustmentOf(ids))) {
      return;
    }
    ApiException exception =
        new ApiException(
            HttpStatus.CONFLICT,
            ApiErrorCode.IMPORT_SNAPSHOT_RECONCILIATION_DECIDED,
            "A reconciliation decision covers the snapshot of this date as it is; reopen it before"
                + " the snapshot changes.");
    exception
        .getBody()
        .setProperty(
            "reconciliationResultId",
            decided.map(ReconciliationResult::getId).orElse(results.get(0).getId()));
    throw exception;
  }

  private Optional<AccountSnapshot> existing(Account account, LocalDate date) {
    return snapshotRepository.findByAccountIdAndSnapshotDateAndSourceAndWithdrawnAtIsNull(
        account.getId(), date, SOURCE_DOCUMENT);
  }

  // Per security, in the snapshot's order then the statement's: a quantity only one side has, or
  // two that differ. Quantities compare by value (25 is 25.0000000000).
  private List<ImportSnapshotPlan.Conflict> holdingConflicts(
      List<SnapshotHolding> held, Map<UUID, BigDecimal> statement) {
    Map<UUID, BigDecimal> snapshot = new LinkedHashMap<>();
    held.forEach(holding -> snapshot.put(holding.getSecurityId(), holding.getQuantity()));
    Set<UUID> securities = new LinkedHashSet<>(snapshot.keySet());
    securities.addAll(statement.keySet());
    List<UUID> differing =
        securities.stream()
            .filter(
                id -> {
                  BigDecimal before = snapshot.get(id);
                  BigDecimal after = statement.get(id);
                  return before == null || after == null || before.compareTo(after) != 0;
                })
            .toList();
    if (differing.isEmpty()) {
      return List.of();
    }
    Map<UUID, Security> named =
        securityRepository.findAllById(differing).stream()
            .collect(Collectors.toMap(Security::getId, Function.identity()));
    return differing.stream()
        .map(
            id -> {
              Security security = named.get(id);
              return new ImportSnapshotPlan.Conflict(
                  ImportSnapshotValues.CONFLICT_HOLDING,
                  id,
                  security == null ? null : security.getIsin(),
                  security == null ? null : security.getDisplayName(),
                  snapshot.get(id),
                  statement.get(id));
            })
        .toList();
  }

  // As for a snapshot recorded by hand (AccountSnapshotService).
  private boolean withinAccountDates(Account account, LocalDate date) {
    return !date.isAfter(businessDates.today())
        && (account.getOpenedAt() == null || !date.isBefore(account.getOpenedAt()))
        && (account.getClosedAt() == null || !date.isAfter(account.getClosedAt()));
  }
}
