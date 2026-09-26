package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.dto.SnapshotHoldingRequest;
import com.trackmywealth.backend.dto.SnapshotHoldingResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountSnapshot;
import com.trackmywealth.backend.entity.Security;
import com.trackmywealth.backend.entity.SnapshotHolding;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.SecurityRepository;
import com.trackmywealth.backend.repository.SnapshotHoldingRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.web.ExistingResourceConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-25-01/FR-REC-006: manual snapshot entry - the balance, and for a position-holding account the
 * per-security quantities, typed in from a paper or PDF statement. A snapshot is an observation,
 * kept apart from the ledger (RULE-025); the reconciliation engine (US-25-02) compares the two.
 *
 * <p>Rules, all answered with 422 unless noted:
 *
 * <ul>
 *   <li>The date is not in the future ({@link BusinessDateService}) and lies within the account's
 *       {@code opened_at}/{@code closed_at}, where those are known.
 *   <li>Holdings only on an account that {@code holdsPositions}; the check uses the capability
 *       flag, never {@code account_type} ({@code ArchitectureTest}). A balance is required unless
 *       holdings are given - a depot statement may list positions only.
 *   <li>Each holding names an existing security-master record, at most once per snapshot.
 *   <li>A second {@code MANUAL} snapshot for the same account and date is a 409 carrying {@code
 *       existingSnapshotId}, so the client can offer "update today's snapshot" ({@link #replace})
 *       instead of failing silently.
 * </ul>
 *
 * <p>{@link #replace} fully replaces a {@code MANUAL} snapshot's balance and holdings and records
 * who changed it and when. A provider-reported snapshot (any other source) is never edited through
 * the API: it is the institution's statement, not the user's transcription of it (409).
 *
 * <p>Writes need {@code EDIT}, reads {@code READ} on the account (US-03-03): a snapshot lists
 * positions, which is more than a {@code BALANCE_ONLY} grant shows. {@code is_opening_balance} is
 * always {@code false} here; opening balances are US-25-04.
 */
@Service
public class AccountSnapshotService {

  private static final String MANUAL = "MANUAL";
  // The column scales (V11): a write answers with the same 150.0000 a later read returns, not the
  // 150.00 the caller happened to send.
  private static final int MONEY_SCALE = 4;
  private static final int QUANTITY_SCALE = 10;

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final BusinessDateService businessDateService;
  private final AccountSnapshotRepository snapshotRepository;
  private final SnapshotHoldingRepository holdingRepository;
  private final SecurityRepository securityRepository;
  private final Clock clock;

  public AccountSnapshotService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      BusinessDateService businessDateService,
      AccountSnapshotRepository snapshotRepository,
      SnapshotHoldingRepository holdingRepository,
      SecurityRepository securityRepository,
      Clock clock) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.businessDateService = businessDateService;
    this.snapshotRepository = snapshotRepository;
    this.holdingRepository = holdingRepository;
    this.securityRepository = securityRepository;
    this.clock = clock;
  }

  @Transactional
  public AccountSnapshotResponse record(
      UUID accountId, RecordAccountSnapshotRequest request, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    Map<UUID, Security> securities =
        validate(account, request.snapshotDate(), request.balance(), request.holdings());

    snapshotRepository
        .findByAccountIdAndSnapshotDateAndSource(accountId, request.snapshotDate(), MANUAL)
        .ifPresent(
            existing -> {
              throw new ExistingResourceConflictException(
                  "A manual snapshot for this account and date already exists. Update it instead.",
                  "existingSnapshotId",
                  existing.getId());
            });

    AccountSnapshot snapshot = new AccountSnapshot();
    snapshot.setWorkspace(account.getWorkspace());
    snapshot.setAccount(account);
    snapshot.setSnapshotDate(request.snapshotDate());
    snapshot.setBalance(atScale(request.balance(), MONEY_SCALE));
    // Not client-supplied: always the account's own currency (V33 guards it as well).
    snapshot.setCurrency(account.getNativeCurrency());
    snapshot.setSource(MANUAL);
    snapshot.setCreatedBy(actor.userId());
    // flush, not a plain save: a concurrent duplicate's UNIQUE violation surfaces here, as a 409
    // from GlobalExceptionHandler, rather than at commit.
    snapshot = snapshotRepository.saveAndFlush(snapshot);

    List<SnapshotHolding> holdings = saveHoldings(snapshot.getId(), request.holdings());
    return toResponse(snapshot, holdings, securities);
  }

  @Transactional
  public AccountSnapshotResponse replace(
      UUID accountId,
      UUID snapshotId,
      ReplaceAccountSnapshotRequest request,
      AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.EDIT);
    AccountSnapshot snapshot =
        snapshotRepository
            .findForUpdate(snapshotId, accountId)
            .orElseThrow(AccountSnapshotService::snapshotNotFound);
    if (!MANUAL.equals(snapshot.getSource())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Only a manually entered snapshot can be updated; a provider-reported one is kept as"
              + " reported.");
    }
    Map<UUID, Security> securities =
        validate(account, snapshot.getSnapshotDate(), request.balance(), request.holdings());

    snapshot.setBalance(atScale(request.balance(), MONEY_SCALE));
    snapshot.setUpdatedAt(OffsetDateTime.now(clock));
    snapshot.setUpdatedBy(actor.userId());
    snapshot = snapshotRepository.saveAndFlush(snapshot);

    holdingRepository.deleteBySnapshotId(snapshotId);
    List<SnapshotHolding> holdings = saveHoldings(snapshotId, request.holdings());
    return toResponse(snapshot, holdings, securities);
  }

  /** Newest first; two sources on the same date are ordered by source name. */
  @Transactional(readOnly = true)
  public List<AccountSnapshotResponse> list(UUID accountId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    List<AccountSnapshot> snapshots =
        snapshotRepository.findByAccountIdOrderBySnapshotDateDescSourceAsc(accountId);
    if (snapshots.isEmpty()) {
      return List.of();
    }
    // Two queries for the whole list, not two per snapshot.
    Map<UUID, List<SnapshotHolding>> holdingsBySnapshot =
        holdingRepository
            .findBySnapshotIdIn(snapshots.stream().map(AccountSnapshot::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(SnapshotHolding::getSnapshotId));
    Map<UUID, Security> securities =
        securitiesById(
            holdingsBySnapshot.values().stream()
                .flatMap(List::stream)
                .map(SnapshotHolding::getSecurityId)
                .collect(Collectors.toSet()));
    return snapshots.stream()
        .map(
            snapshot ->
                toResponse(
                    snapshot,
                    holdingsBySnapshot.getOrDefault(snapshot.getId(), List.of()),
                    securities))
        .toList();
  }

  @Transactional(readOnly = true)
  public AccountSnapshotResponse get(
      UUID accountId, UUID snapshotId, AuthenticatedUserPrincipal actor) {
    Account account = accountLookupService.findAccountOrThrow(accountId);
    accessControlService.requireAccountAccess(actor, account, AccessLevelValues.READ);
    AccountSnapshot snapshot =
        snapshotRepository
            .findByIdAndAccountId(snapshotId, accountId)
            .orElseThrow(AccountSnapshotService::snapshotNotFound);
    List<SnapshotHolding> holdings = holdingRepository.findBySnapshotIdIn(List.of(snapshotId));
    return toResponse(
        snapshot,
        holdings,
        securitiesById(
            holdings.stream().map(SnapshotHolding::getSecurityId).collect(Collectors.toSet())));
  }

  // Returns the referenced securities by id, for the response.
  private Map<UUID, Security> validate(
      Account account,
      LocalDate snapshotDate,
      BigDecimal balance,
      List<SnapshotHoldingRequest> holdings) {
    if (snapshotDate.isAfter(businessDateService.today())) {
      throw unprocessable("snapshotDate cannot be in the future.");
    }
    if (account.getOpenedAt() != null && snapshotDate.isBefore(account.getOpenedAt())) {
      throw unprocessable("snapshotDate cannot be before the account was opened.");
    }
    if (account.getClosedAt() != null && snapshotDate.isAfter(account.getClosedAt())) {
      throw unprocessable("snapshotDate cannot be after the account was closed.");
    }
    if (!holdings.isEmpty() && !account.isHoldsPositions()) {
      throw unprocessable("This account does not hold securities, so a snapshot has no holdings.");
    }
    if (balance == null && holdings.isEmpty()) {
      throw unprocessable("A snapshot needs a balance, holdings, or both.");
    }

    Set<UUID> securityIds = new HashSet<>();
    for (SnapshotHoldingRequest holding : holdings) {
      if (!securityIds.add(holding.securityId())) {
        throw unprocessable(
            "Security " + holding.securityId() + " is listed more than once; combine the lines.");
      }
    }
    Map<UUID, Security> securities = securitiesById(securityIds);
    if (securities.size() != securityIds.size()) {
      UUID unknown =
          securityIds.stream().filter(id -> !securities.containsKey(id)).findFirst().orElseThrow();
      throw unprocessable(
          "Security " + unknown + " does not exist. Create it first with POST /api/v1/securities.");
    }
    return securities;
  }

  private List<SnapshotHolding> saveHoldings(
      UUID snapshotId, List<SnapshotHoldingRequest> requests) {
    List<SnapshotHolding> holdings =
        requests.stream()
            .map(
                request -> {
                  SnapshotHolding holding = new SnapshotHolding();
                  holding.setSnapshotId(snapshotId);
                  holding.setSecurityId(request.securityId());
                  holding.setQuantity(atScale(request.quantity(), QUANTITY_SCALE));
                  holding.setReportedCostBasis(atScale(request.reportedCostBasis(), MONEY_SCALE));
                  holding.setCostBasisEstimated(
                      Boolean.TRUE.equals(request.costBasisIsEstimated()));
                  return holding;
                })
            .toList();
    return holdingRepository.saveAllAndFlush(holdings);
  }

  private Map<UUID, Security> securitiesById(Set<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return securityRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(Security::getId, Function.identity()));
  }

  private AccountSnapshotResponse toResponse(
      AccountSnapshot snapshot, List<SnapshotHolding> holdings, Map<UUID, Security> securities) {
    List<SnapshotHoldingResponse> holdingResponses =
        holdings.stream()
            .map(
                holding -> {
                  Security security = securities.get(holding.getSecurityId());
                  return new SnapshotHoldingResponse(
                      holding.getId(),
                      holding.getSecurityId(),
                      security.getIsin(),
                      security.getDisplayName(),
                      holding.getQuantity(),
                      holding.getReportedCostBasis(),
                      holding.isCostBasisEstimated());
                })
            .sorted(
                Comparator.comparing(
                        SnapshotHoldingResponse::securityDisplayName,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(SnapshotHoldingResponse::securityId))
            .toList();
    return new AccountSnapshotResponse(
        snapshot.getId(),
        snapshot.getAccount().getId(),
        snapshot.getSnapshotDate(),
        snapshot.getBalance(),
        snapshot.getCurrency(),
        snapshot.getSource(),
        snapshot.isOpeningBalance(),
        snapshot.getCreatedAt(),
        snapshot.getUpdatedAt(),
        holdingResponses);
  }

  // The request's @Digits already caps the fraction at the column scale, so this never rounds.
  private static BigDecimal atScale(BigDecimal value, int scale) {
    return value == null ? null : value.setScale(scale);
  }

  private static ResponseStatusException unprocessable(String detail) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, detail);
  }

  private static ResponseStatusException snapshotNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot not found.");
  }
}
