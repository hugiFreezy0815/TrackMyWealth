package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccessLevelValues;
import com.trackmywealth.backend.dto.SetSettlementSourceRequest;
import com.trackmywealth.backend.dto.SettlementMatchResponse;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.dto.SettlementSourceResponse;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountCreditCard;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.repository.AccountCreditCardRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-09-02: the member-facing side of settlement matching - choosing which account pays a card,
 * seeing what needs a decision, and confirming or rejecting a match (FR-CC-007, FR-CF-004). The
 * matching itself is {@link SettlementDetectionService}.
 *
 * <p>Deciding a match affects two accounts, so it needs {@code EDIT} on <em>both</em> the card and
 * the paying account; a caller who lacks either sees the match as nonexistent (404), the same
 * deny-as-not-found convention as {@link AccessControlService}. A match is only ever listed to a
 * caller who could act on it, so the list cannot reveal an account the caller has no access to.
 *
 * <p>State machine: {@code PROPOSED -> CONFIRMED | REJECTED}, {@code CONFIRMED -> REJECTED}. A
 * rejected match is final - it is never proposed again and cannot be re-confirmed; matching that
 * yields the same pair is simply not repeated.
 */
@Service
public class SettlementMatchService {

  // The list is a work queue, not a history browser: bounded, newest first.
  static final int MAX_LISTED = 200;

  private final AccountLookupService accountLookupService;
  private final AccessControlService accessControlService;
  private final AccountRepository accountRepository;
  private final AccountCreditCardRepository accountCreditCardRepository;
  private final SettlementMatchRepository settlementMatchRepository;
  private final SettlementDetectionService settlementDetectionService;
  private final Clock clock;

  public SettlementMatchService(
      AccountLookupService accountLookupService,
      AccessControlService accessControlService,
      AccountRepository accountRepository,
      AccountCreditCardRepository accountCreditCardRepository,
      SettlementMatchRepository settlementMatchRepository,
      SettlementDetectionService settlementDetectionService,
      Clock clock) {
    this.accountLookupService = accountLookupService;
    this.accessControlService = accessControlService;
    this.accountRepository = accountRepository;
    this.accountCreditCardRepository = accountCreditCardRepository;
    this.settlementMatchRepository = settlementMatchRepository;
    this.settlementDetectionService = settlementDetectionService;
    this.clock = clock;
  }

  /**
   * Sets (or, for a {@code null} id, clears) the account that pays this card's statement, then
   * matches what is already in the ledger. The source must be an active, ordinary account in the
   * card's own currency - a card cannot be paid by a card, and cross-currency settlement (the
   * *payment* itself in a different currency than the card) is not built - and the caller needs
   * {@code EDIT} on both accounts. Not to be confused with US-09-04's foreign-currency card
   * *purchases*, a different leg entirely.
   */
  @Transactional
  public SettlementSourceResponse setSettlementSource(
      UUID cardAccountId, SetSettlementSourceRequest request, AuthenticatedUserPrincipal actor) {
    Account card = requireCard(cardAccountId, actor, AccessLevelValues.EDIT);
    settlementDetectionService.lockCard(cardAccountId);
    AccountCreditCard extension = extensionOf(card);

    UUID sourceId = request.settlementSourceAccountId();
    if (sourceId != null) {
      Account source = accountLookupService.findAccountOrThrow(sourceId);
      accessControlService.requireAccountAccess(actor, source, AccessLevelValues.EDIT);
      validateSource(card, source);
    }
    extension.setSettlementSourceAccountId(sourceId);
    accountCreditCardRepository.saveAndFlush(extension);

    settlementDetectionService.detectForCard(cardAccountId);
    return new SettlementSourceResponse(cardAccountId, sourceId);
  }

  @Transactional(readOnly = true)
  public SettlementSourceResponse getSettlementSource(
      UUID cardAccountId, AuthenticatedUserPrincipal actor) {
    Account card = requireCard(cardAccountId, actor, AccessLevelValues.BALANCE_ONLY);
    UUID sourceId = extensionOf(card).getSettlementSourceAccountId();
    if (sourceId == null) {
      return new SettlementSourceResponse(cardAccountId, null);
    }
    // An account id the caller cannot see is not revealed through this card.
    UUID memberId = accessControlService.requireActingMember(actor);
    Account source = accountLookupService.findAccountOrThrow(sourceId);
    boolean visible =
        !accessControlService
            .accountsWithAccess(memberId, List.of(source), AccessLevelValues.BALANCE_ONLY)
            .isEmpty();
    return new SettlementSourceResponse(cardAccountId, visible ? sourceId : null);
  }

  /**
   * Runs matching for one card on demand and returns the card's matches (any status) that the
   * caller may act on, newest first. Idempotent: running it again changes nothing. A match whose
   * payment account the caller cannot edit is left out - a card's settlement source can change, and
   * an earlier source's matches must not be revealed through the new one.
   */
  @Transactional
  public List<SettlementMatchResponse> run(UUID cardAccountId, AuthenticatedUserPrincipal actor) {
    Account card = requireCard(cardAccountId, actor, AccessLevelValues.EDIT);
    UUID sourceId = extensionOf(card).getSettlementSourceAccountId();
    if (sourceId == null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "This card has no settlement source account set.");
    }
    accessControlService.requireAccountAccess(
        actor, accountLookupService.findAccountOrThrow(sourceId), AccessLevelValues.EDIT);

    settlementDetectionService.detectForCard(cardAccountId);
    Set<UUID> editable = editableAccountIds(actor);
    return settlementMatchRepository.findByCardAccountId(cardAccountId).stream()
        .filter(m -> editable.contains(m.getPaymentTransaction().getAccount().getId()))
        .map(this::toResponse)
        .toList();
  }

  /**
   * The matches with the given {@code status} (default {@code PROPOSED}, i.e. what needs a
   * decision) that the caller may act on: newest first, at most {@value #MAX_LISTED}. The access
   * filter is part of the query, so the cap applies to the caller's own matches and another
   * member's cannot crowd them out.
   */
  @Transactional(readOnly = true)
  public List<SettlementMatchResponse> list(String status, AuthenticatedUserPrincipal actor) {
    String wanted = status == null ? SettlementMatchValues.PROPOSED : status;
    if (!SettlementMatchValues.STATUSES.contains(wanted)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "status must be one of PROPOSED, CONFIRMED, REJECTED.");
    }
    Set<UUID> editable = editableAccountIds(actor);
    if (editable.isEmpty()) {
      return List.of();
    }
    return settlementMatchRepository
        .findActionable(wanted, editable, PageRequest.of(0, MAX_LISTED))
        .stream()
        .map(this::toResponse)
        .toList();
  }

  /**
   * Applies a proposed match: flags its leg(s) as an internal transfer (so they stop counting as
   * spending) and rejects any other proposal competing for the same payment or card credit.
   */
  @Transactional
  public SettlementMatchResponse confirm(UUID matchId, AuthenticatedUserPrincipal actor) {
    SettlementMatch match = lockActionableMatch(matchId, actor);
    if (!SettlementMatchValues.PROPOSED.equals(match.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only a proposed match can be confirmed.");
    }
    if (isVoided(match)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A voided transaction cannot be settled.");
    }
    OffsetDateTime now = OffsetDateTime.now(clock);

    // Competitors first: they share a payment or a credit with this match, and only one match may
    // own it (uq_settlement_match_confirmed_*). Decided by the system, on this member's choice.
    List<SettlementMatch> competitors =
        settlementMatchRepository.findCompetingProposals(
            match.getId(),
            match.getPaymentTransaction().getId(),
            match.getCardTransaction() == null ? null : match.getCardTransaction().getId());
    competitors.forEach(
        competitor -> {
          competitor.setStatus(SettlementMatchValues.REJECTED);
          competitor.setDecidedAt(now);
        });
    settlementMatchRepository.saveAllAndFlush(competitors);

    match.setStatus(SettlementMatchValues.CONFIRMED);
    match.setDecidedBy(actor.userId());
    match.setDecidedAt(now);
    settlementMatchRepository.saveAndFlush(match);
    settlementDetectionService.applyFlags(match);
    return toResponse(match);
  }

  /**
   * Declines a proposal, or undoes a confirmed match (FR-CF-004): a confirmed match's legs revert
   * to ordinary transactions. Final - the same pair is never proposed again. Matching then re-runs
   * for the card, since a freed credit may now pair with a different payment.
   */
  @Transactional
  public SettlementMatchResponse reject(UUID matchId, AuthenticatedUserPrincipal actor) {
    SettlementMatch match = lockActionableMatch(matchId, actor);
    boolean wasConfirmed = SettlementMatchValues.CONFIRMED.equals(match.getStatus());
    if (!wasConfirmed && !SettlementMatchValues.PROPOSED.equals(match.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only a proposed or confirmed match can be rejected.");
    }
    match.setStatus(SettlementMatchValues.REJECTED);
    match.setDecidedBy(actor.userId());
    match.setDecidedAt(OffsetDateTime.now(clock));
    settlementMatchRepository.saveAndFlush(match);
    if (wasConfirmed) {
      settlementDetectionService.clearFlags(match);
    }
    settlementDetectionService.detectForCard(match.getCardAccount().getId());
    return toResponse(match);
  }

  private SettlementMatch lockActionableMatch(UUID matchId, AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    // Serialise on the card first, learned from a scalar so the match itself is not loaded (and its
    // status cached) before the lock is held: a second decision on the same card then sees what the
    // first committed. Taken before the access check, so a member with no access briefly queues
    // behind a card's decision at worst - it changes nothing.
    UUID cardAccountId =
        settlementMatchRepository.findCardAccountIdById(matchId).orElseThrow(this::matchNotFound);
    settlementDetectionService.lockCard(cardAccountId);
    // Again under the lock: a removal (which takes the same card lock) may have soft-deleted a leg
    // in between, and a match with a hidden leg is not actionable (US-07-02).
    settlementMatchRepository.findCardAccountIdById(matchId).orElseThrow(this::matchNotFound);
    SettlementMatch match =
        settlementMatchRepository.findByIdForUpdate(matchId).orElseThrow(this::matchNotFound);
    Set<Account> both = Set.of(match.getCardAccount(), match.getPaymentTransaction().getAccount());
    if (accessControlService.accountsWithAccess(memberId, both, AccessLevelValues.EDIT).size()
        != both.size()) {
      throw matchNotFound();
    }
    return match;
  }

  // The ids of the workspace's accounts the caller may EDIT - the accounts whose matches the caller
  // may see and decide. Household scale: a workspace holds tens of accounts.
  private Set<UUID> editableAccountIds(AuthenticatedUserPrincipal actor) {
    UUID memberId = accessControlService.requireActingMember(actor);
    return accessControlService
        .accountsWithAccess(
            memberId,
            accountRepository.findByWorkspaceId(actor.workspaceId()),
            AccessLevelValues.EDIT)
        .stream()
        .map(Account::getId)
        .collect(Collectors.toSet());
  }

  private static boolean isVoided(SettlementMatch match) {
    return match.getPaymentTransaction().getVoidedAt() != null
        || (match.getCardTransaction() != null && match.getCardTransaction().getVoidedAt() != null);
  }

  private Account requireCard(UUID cardAccountId, AuthenticatedUserPrincipal actor, String level) {
    Account card = accountLookupService.findAccountOrThrow(cardAccountId);
    accessControlService.requireAccountAccess(actor, card, level);
    if (!card.isHasStatementCycle()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "Only a credit-card account has a settlement source.");
    }
    return card;
  }

  private AccountCreditCard extensionOf(Account card) {
    return accountCreditCardRepository
        .findById(card.getId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "Credit-card details are missing."));
  }

  private static void validateSource(Account card, Account source) {
    if (source.getId().equals(card.getId())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT, "A card cannot be its own settlement source.");
    }
    if (source.isHasStatementCycle() || !source.isHasTransactions()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "The settlement source must be an ordinary account that holds transactions.");
    }
    if (!"ACTIVE".equals(source.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The settlement source account is archived.");
    }
    if (!source.getNativeCurrency().equals(card.getNativeCurrency())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "The settlement source must be in the card's currency ("
              + card.getNativeCurrency()
              + "); cross-currency settlement (the payment itself in a different currency than the"
              + " card) is not supported yet.");
    }
  }

  private ResponseStatusException matchNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement match not found.");
  }

  private SettlementMatchResponse toResponse(SettlementMatch match) {
    return new SettlementMatchResponse(
        match.getId(),
        match.getCardAccount().getId(),
        match.getPaymentTransaction().getAccount().getId(),
        match.getPaymentTransaction().getId(),
        match.getCardTransaction() == null ? null : match.getCardTransaction().getId(),
        match.getPaymentTransaction().getAmount().negate(),
        match.getPaymentTransaction().getCurrency(),
        match.getPaymentTransaction().getBookingDate(),
        match.getStatus(),
        match.getMatchBasis(),
        match.getDecidedAt(),
        match.getCreatedAt());
  }
}
