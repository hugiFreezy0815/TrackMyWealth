package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportHoldingsResponse;
import com.trackmywealth.backend.dto.ImportHoldingsStatement;
import com.trackmywealth.backend.dto.ImportHoldingsValues;
import com.trackmywealth.backend.dto.ImportRowErrorResponse;
import com.trackmywealth.backend.dto.ImportSnapshotHolding;
import com.trackmywealth.backend.dto.ImportSnapshotPlan;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.ImportBatch;
import com.trackmywealth.backend.entity.ImportHoldingRow;
import com.trackmywealth.backend.entity.Security;
import com.trackmywealth.backend.entity.WorkspaceSecurityTicker;
import com.trackmywealth.backend.error.ApiErrorCode;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.error.ImportFileRejectedException;
import com.trackmywealth.backend.repository.ImportHoldingRowRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.WorkspaceSecurityTickerRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-07-13 (#273, FR-REC-*, INPUT-004): a holdings statement, read by a {@code SNAPSHOT} template
 * ({@link ImportHoldingsReaderService}), becomes one {@code DOCUMENT} snapshot with a holding per
 * position when its batch is committed - the same batch lifecycle as a cash statement's (upload,
 * preview, commit with {@code If-Match}, rollback), so the file's hash, the batch's provenance and
 * the rollback come with it.
 *
 * <ul>
 *   <li><b>Parse.</b> The statement's lines are staged on the batch ({@code import_holding_row});
 *       nothing is resolved or recorded yet. A statement listing positions needs an account that
 *       holds positions.
 *   <li><b>Resolution.</b> A position is the security of its ISIN in the shared master, or else the
 *       one its symbol means in the workspace ({@code workspace_security_ticker}). It is resolved
 *       afresh on every read and at the commit, so a security the member creates after the upload
 *       counts at once. Nothing is ever created by the import itself.
 *   <li><b>Cash.</b> The cash in the account's own currency (the sum, if the statement lists
 *       several such balances) is the snapshot's balance; cash in another currency is shown only.
 *   <li><b>Commit.</b> Refused while a line is unreadable (422 {@code IMPORT_HOLDINGS_UNREADABLE}),
 *       a position resolves to nothing (422 {@code IMPORT_HOLDINGS_UNRESOLVED}), two resolve to the
 *       same security, the account's dates do not admit the statement's (as for a snapshot recorded
 *       by hand), or an imported snapshot of that date is in effect (409 with {@code
 *       existingSnapshotId}). Then it records the snapshot, pointing at the batch, with each
 *       position's quantity and the price and value the statement reports, and reconciles the
 *       account against it (US-25-02).
 *   <li><b>Rollback.</b> As a statement balance's (US-07-12): {@link
 *       ImportStatementBalanceService#rollBack} deletes or withdraws the batch's snapshot, holdings
 *       included.
 * </ul>
 *
 * <p>Every method runs inside the caller's transaction; a commit holds the account's import lock
 * and the batch row's lock, so two commits of one account never interleave here.
 */
@Service
public class ImportHoldingsService {

  private static final String MESSAGE_PREFIX = "tmw.import.row.";

  private final ImportHoldingRowRepository rowRepository;
  private final SecurityRepository securityRepository;
  private final WorkspaceSecurityTickerRepository tickerRepository;
  private final ImportSnapshotCompletionService completionService;
  private final ImportSnapshotExplanationService explanations;
  private final AccountCurrencyService accountCurrencyService;
  private final MessageSource messageSource;

  public ImportHoldingsService(
      ImportHoldingRowRepository rowRepository,
      SecurityRepository securityRepository,
      WorkspaceSecurityTickerRepository tickerRepository,
      ImportSnapshotCompletionService completionService,
      ImportSnapshotExplanationService explanations,
      AccountCurrencyService accountCurrencyService,
      MessageSource messageSource) {
    this.rowRepository = rowRepository;
    this.securityRepository = securityRepository;
    this.tickerRepository = tickerRepository;
    this.completionService = completionService;
    this.explanations = explanations;
    this.accountCurrencyService = accountCurrencyService;
    this.messageSource = messageSource;
  }

  /**
   * Stages {@code statement} on the batch, replacing what an earlier parse staged; with {@code
   * null}, clears it (the batch is parsed as a cash statement now).
   *
   * @throws ResponseStatusException 422 for positions on an account that holds none
   * @throws ImportFileRejectedException 422 {@code IMPORT_FILE_NO_DATA_ROWS} when the statement
   *     holds neither a position, nor cash in the account's currency, nor an unreadable line: the
   *     commit would have nothing to record
   */
  public void stage(ImportBatch batch, Account account, ImportHoldingsStatement statement) {
    rowRepository.deleteByImportBatchId(batch.getId());
    if (statement == null) {
      batch.recordHoldingsStatement(null, null);
      return;
    }
    boolean positions = !statement.linesOf(ImportHoldingsValues.KIND_POSITION).isEmpty();
    if (positions && !account.isHoldsPositions()) {
      throw holdsNoPositions();
    }
    String currency = accountCurrencyService.ownCurrency(account);
    boolean ownCash =
        statement.linesOf(ImportHoldingsValues.KIND_CASH).stream()
            .anyMatch(cash -> currency.equals(cash.currency()));
    if (!positions && !ownCash && statement.linesOf(ImportHoldingsValues.KIND_ERROR).isEmpty()) {
      throw new ImportFileRejectedException(
          ApiErrorCode.IMPORT_FILE_NO_DATA_ROWS,
          "The statement holds no position and no cash in the account's currency ("
              + currency
              + ").");
    }
    // A holdings batch states no balance of its own: its cash is part of the statement (V99).
    batch.recordStatementBalance(null, null, null, null, null, null);
    batch.recordHoldingsStatement(statement.date(), statement.dateSource());
    List<ImportHoldingRow> rows = new ArrayList<>();
    for (ImportHoldingsStatement.Line line : statement.lines()) {
      rows.add(row(batch, line));
    }
    rowRepository.saveAllAndFlush(rows);
  }

  /**
   * The batch's holdings statement for its summary, {@code null} unless it holds one. Before the
   * commit it says what a commit would do now (see {@link ImportHoldingsResponse}).
   */
  public ImportHoldingsResponse response(ImportBatch batch, Account account, Locale locale) {
    if (!batch.isHoldingsStatement()) {
      return null;
    }
    List<ImportHoldingRow> rows = rowRepository.findByImportBatchIdOrderByRowNumber(batch.getId());
    List<ImportHoldingRow> positions = ofKind(rows, ImportHoldingsValues.KIND_POSITION);
    Map<Integer, UUID> resolved = resolve(account.getWorkspace().getId(), positions);
    String currency = accountCurrencyService.ownCurrency(account);
    BigDecimal balance = ownCash(rows, currency);
    int unresolvedCount = positions.size() - resolved.size();
    List<String> duplicates = duplicateSecurities(positions, resolved);
    List<ImportHoldingRow> errors = ofKind(rows, ImportHoldingsValues.KIND_ERROR);

    // Before the commit, what it would do now; afterwards, what it (and a rollback) did.
    String snapshot = batch.getHoldingsSnapshot();
    ImportSnapshotPlan plan = null;
    if (snapshot == null && ImportBatchValues.PARSED.equals(batch.getStatus())) {
      plan =
          completionService.plan(
              account,
              batch.getHoldingsSnapshotDate(),
              balance,
              quantities(positions, resolved),
              unresolvedCount == 0 && duplicates.isEmpty());
      snapshot = plan.outcome();
    }

    List<ImportHoldingsResponse.Position> positionResponses = new ArrayList<>();
    for (ImportHoldingRow row : positions) {
      UUID securityId = resolved.get(row.getRowNumber());
      positionResponses.add(
          new ImportHoldingsResponse.Position(
              row.getRowNumber(),
              row.getIsin(),
              row.getSymbol(),
              row.getSecurityName(),
              row.getQuantity(),
              row.getReportedPrice(),
              row.getReportedValue(),
              row.getCurrency(),
              securityId == null ? ImportHoldingsValues.UNRESOLVED : ImportHoldingsValues.RESOLVED,
              securityId,
              row.getWarningCodes().stream().map(code -> message(code, locale)).toList()));
    }
    List<ImportHoldingsResponse.Cash> cash =
        ofKind(rows, ImportHoldingsValues.KIND_CASH).stream()
            .map(
                row ->
                    new ImportHoldingsResponse.Cash(
                        row.getRowNumber(),
                        row.getCurrency(),
                        row.getAmount(),
                        currency.equals(row.getCurrency())))
            .toList();
    List<ImportHoldingsResponse.Unreadable> unreadable =
        errors.stream()
            .map(
                row ->
                    new ImportHoldingsResponse.Unreadable(
                        row.getRowNumber(), row.getRawText(), message(row.getErrorCode(), locale)))
            .toList();
    boolean ready =
        plan != null
            && plan.committable()
            && unresolvedCount == 0
            && errors.isEmpty()
            && duplicates.isEmpty();
    String explanation =
        explanations.explain(
            snapshot,
            plan,
            batch.getHoldingsSnapshotDate(),
            errors.size(),
            unresolvedCount,
            !duplicates.isEmpty(),
            locale);
    return new ImportHoldingsResponse(
        batch.getHoldingsSnapshotDate(),
        batch.getHoldingsDateSource(),
        snapshot,
        plan == null ? null : plan.existing(),
        plan == null ? List.of() : plan.adds(),
        plan == null ? List.of() : plan.conflicts(),
        explanation,
        balance,
        balance == null ? null : currency,
        positionResponses,
        cash,
        unreadable,
        unresolvedCount,
        ready);
  }

  /**
   * On commit: records the batch's snapshot with its holdings, or completes the imported snapshot
   * of its date ({@link ImportSnapshotCompletionService}), and reconciles the account.
   *
   * @throws ApiException 422 {@code IMPORT_HOLDINGS_UNREADABLE} or {@code
   *     IMPORT_HOLDINGS_UNRESOLVED}, 409 {@code IMPORT_SNAPSHOT_ALREADY_RECORDED} or {@code
   *     IMPORT_SNAPSHOT_CONFLICT}; nothing is written then
   */
  public void commit(ImportBatch batch, Account account, UUID committedBy) {
    List<ImportHoldingRow> rows = rowRepository.findByImportBatchIdOrderByRowNumber(batch.getId());
    List<ImportHoldingRow> errors = ofKind(rows, ImportHoldingsValues.KIND_ERROR);
    if (!errors.isEmpty()) {
      ApiException unreadable =
          new ApiException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ApiErrorCode.IMPORT_HOLDINGS_UNREADABLE,
              "The statement has lines that could not be read; the snapshot would miss them.");
      unreadable
          .getBody()
          .setProperty("rowNumbers", errors.stream().map(ImportHoldingRow::getRowNumber).toList());
      throw unreadable;
    }
    List<ImportHoldingRow> positions = ofKind(rows, ImportHoldingsValues.KIND_POSITION);
    if (!positions.isEmpty() && !account.isHoldsPositions()) {
      throw holdsNoPositions();
    }
    LocalDate date = batch.getHoldingsSnapshotDate();
    Map<Integer, UUID> resolved = resolve(account.getWorkspace().getId(), positions);
    requireResolved(positions, resolved);
    List<String> duplicates = duplicateSecurities(positions, resolved);
    if (!duplicates.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "Rows "
              + String.join(", ", duplicates)
              + " are the same security; a snapshot holds it once.");
    }
    String currency = accountCurrencyService.ownCurrency(account);
    BigDecimal balance = ownCash(rows, currency);
    ImportSnapshotPlan plan =
        completionService.plan(account, date, balance, quantities(positions, resolved), true);
    if (ImportSnapshotValues.OUT_OF_RANGE.equals(plan.outcome())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "The statement's date "
              + date
              + " is in the future, before the account was opened or after it was closed.");
    }
    if (!plan.committable()) {
      throw ImportSnapshotCompletionService.refusal(
          plan,
          explanations.explain(
              plan.outcome(), plan, date, 0, 0, false, LocaleContextHolder.getLocale()));
    }
    List<ImportSnapshotHolding> holdings =
        positions.stream()
            .map(
                position ->
                    new ImportSnapshotHolding(
                        resolved.get(position.getRowNumber()),
                        position.getQuantity(),
                        position.getReportedPrice(),
                        position.getReportedValue(),
                        position.getCurrency()))
            .toList();
    batch.setHoldingsSnapshot(
        completionService.commit(
            batch, account, date, currency, plan, balance, holdings, committedBy));
  }

  // Each resolved position's quantity by security (the statement lists a security once).
  private static Map<UUID, BigDecimal> quantities(
      List<ImportHoldingRow> positions, Map<Integer, UUID> resolved) {
    Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
    for (ImportHoldingRow row : positions) {
      UUID id = resolved.get(row.getRowNumber());
      if (id != null) {
        quantities.putIfAbsent(id, row.getQuantity());
      }
    }
    return quantities;
  }

  /**
   * Row number to security for every position that resolves: by ISIN in the shared master, else by
   * the workspace's own mapping of its symbol. Two queries however many positions there are.
   */
  Map<Integer, UUID> resolve(UUID workspaceId, List<ImportHoldingRow> positions) {
    List<String> isins =
        positions.stream().map(ImportHoldingRow::getIsin).filter(Objects::nonNull).toList();
    Map<String, UUID> byIsin =
        isins.isEmpty()
            ? Map.of()
            : securityRepository.findByIsinIn(isins).stream()
                .collect(Collectors.toMap(Security::getIsin, Security::getId));
    List<String> symbols =
        positions.stream()
            .filter(row -> row.getIsin() == null || !byIsin.containsKey(row.getIsin()))
            .map(ImportHoldingRow::getSymbol)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<String, UUID> byTicker =
        symbols.isEmpty()
            ? Map.of()
            : tickerRepository.findByWorkspaceIdAndTickerIn(workspaceId, symbols).stream()
                .collect(
                    Collectors.toMap(
                        WorkspaceSecurityTicker::getTicker,
                        WorkspaceSecurityTicker::getSecurityId));
    Map<Integer, UUID> resolved = new HashMap<>();
    for (ImportHoldingRow row : positions) {
      UUID id = row.getIsin() == null ? null : byIsin.get(row.getIsin());
      if (id == null && row.getSymbol() != null) {
        id = byTicker.get(row.getSymbol());
      }
      if (id != null) {
        resolved.put(row.getRowNumber(), id);
      }
    }
    return resolved;
  }

  private static void requireResolved(List<ImportHoldingRow> positions, Map<Integer, UUID> ids) {
    List<Map<String, Object>> unresolved = new ArrayList<>();
    for (ImportHoldingRow row : positions) {
      if (!ids.containsKey(row.getRowNumber())) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("rowNumber", row.getRowNumber());
        position.put("isin", row.getIsin());
        position.put("symbol", row.getSymbol());
        unresolved.add(position);
      }
    }
    if (!unresolved.isEmpty()) {
      ApiException exception =
          new ApiException(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ApiErrorCode.IMPORT_HOLDINGS_UNRESOLVED,
              unresolved.size()
                  + " positions resolve to no security. Create them (with the statement's ticker"
                  + " for a position without an ISIN), then commit again.");
      exception.getBody().setProperty("unresolved", unresolved);
      throw exception;
    }
  }

  // The row numbers, joined per security, of positions that resolve to a security another one
  // resolves to as well (an ISIN line and a ticker line of the same security).
  private static List<String> duplicateSecurities(
      List<ImportHoldingRow> positions, Map<Integer, UUID> resolved) {
    Map<UUID, List<Integer>> rowsBySecurity = new LinkedHashMap<>();
    for (ImportHoldingRow row : positions) {
      UUID id = resolved.get(row.getRowNumber());
      if (id != null) {
        rowsBySecurity.computeIfAbsent(id, key -> new ArrayList<>()).add(row.getRowNumber());
      }
    }
    return rowsBySecurity.values().stream()
        .filter(numbers -> numbers.size() > 1)
        .map(numbers -> numbers.stream().map(String::valueOf).collect(Collectors.joining(" and ")))
        .toList();
  }

  // The sum of the statement's cash in the account's currency, or null without any.
  private static BigDecimal ownCash(List<ImportHoldingRow> rows, String currency) {
    return ofKind(rows, ImportHoldingsValues.KIND_CASH).stream()
        .filter(row -> currency.equals(row.getCurrency()))
        .map(ImportHoldingRow::getAmount)
        .reduce(BigDecimal::add)
        .map(sum -> sum.setScale(FxRateService.MONEY_SCALE))
        .orElse(null);
  }

  private ImportRowErrorResponse message(String code, Locale locale) {
    return new ImportRowErrorResponse(
        code, Map.of(), messageSource.getMessage(MESSAGE_PREFIX + code, null, code, locale));
  }

  private static List<ImportHoldingRow> ofKind(List<ImportHoldingRow> rows, String kind) {
    return rows.stream().filter(row -> kind.equals(row.getKind())).toList();
  }

  private static ImportHoldingRow row(ImportBatch batch, ImportHoldingsStatement.Line line) {
    ImportHoldingRow row = new ImportHoldingRow();
    row.setWorkspaceId(batch.getWorkspaceId());
    row.setImportBatchId(batch.getId());
    row.setRowNumber(line.rowNumber());
    row.setKind(line.kind());
    row.setIsin(line.isin());
    row.setSymbol(line.symbol());
    row.setSecurityName(line.name());
    row.setQuantity(line.quantity());
    row.setReportedPrice(line.price());
    row.setReportedValue(line.value());
    row.setCurrency(line.currency());
    row.setAmount(line.amount());
    row.setRawText(line.rawText());
    row.setErrorCode(line.errorCode());
    row.setWarningCodes(line.warnings());
    return row;
  }

  private static ResponseStatusException holdsNoPositions() {
    return new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "This account does not hold securities, so a holdings statement cannot be imported into"
            + " it.");
  }
}
