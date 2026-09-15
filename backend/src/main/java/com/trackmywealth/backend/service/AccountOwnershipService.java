package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.AccountOwnershipResponse;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest;
import com.trackmywealth.backend.dto.AssignAccountOwnershipRequest.OwnerAllocation;
import com.trackmywealth.backend.entity.Account;
import com.trackmywealth.backend.entity.AccountOwnership;
import com.trackmywealth.backend.entity.WorkspaceMember;
import com.trackmywealth.backend.repository.AccountOwnershipRepository;
import com.trackmywealth.backend.repository.AccountRepository;
import com.trackmywealth.backend.repository.WorkspaceMemberRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * US-03-02: assign fractional or joint ownership of an account (FR-HOU-002/003/005/006). {@link
 * #assignOwnership} is a full replacement of the account's currently-effective ownership set, not a
 * per-member add/remove - see {@link AssignAccountOwnershipRequest}'s own Javadoc for why,
 * including why this deliberately doesn't check for EDIT/FULL access (US-03-03/#74 not built yet).
 *
 * <p>Relies on RLS for workspace isolation the same way {@code AccountService}/{@code
 * InstitutionService} do: both {@code accountId} and every {@code workspaceMemberId} in the request
 * are looked up through repositories RLS already confines to the caller's own workspace, so a
 * cross-workspace id simply isn't found, degrading safely to 404 rather than needing a separate
 * equality check.
 *
 * <p>{@link #assignOwnership} locks the account row for the duration of its read-close-write
 * sequence ({@code AccountRepository#findByIdForUpdate}, the same pattern {@code
 * AppUserRepository#findByIdForUpdate} already uses for a read-modify-write race of this shape).
 * Without it, two concurrent full-replacement writes for *disjoint* members (e.g. one assigning
 * memberA 100% while another assigns memberB 100%) would each read the same pre-change state, each
 * pass its own single-request "shares don't exceed 100%" check, and each commit a new open row -
 * neither collides with V6's {@code uq_account_ownership_current} partial unique index (different
 * {@code workspace_member_id}), so both end up simultaneously effective with no error raised
 * anywhere, silently leaving the account over-allocated. The lock forces the second writer to wait
 * for the first to commit and then read its result, turning that silent corruption into a clean,
 * serialized last-write-wins replacement - the correct semantics for a full-replacement PUT.
 */
@Service
public class AccountOwnershipService {

  private final AccountRepository accountRepository;
  private final WorkspaceMemberRepository workspaceMemberRepository;
  private final AccountOwnershipRepository accountOwnershipRepository;

  public AccountOwnershipService(
      AccountRepository accountRepository,
      WorkspaceMemberRepository workspaceMemberRepository,
      AccountOwnershipRepository accountOwnershipRepository) {
    this.accountRepository = accountRepository;
    this.workspaceMemberRepository = workspaceMemberRepository;
    this.accountOwnershipRepository = accountOwnershipRepository;
  }

  @Transactional
  public List<AccountOwnershipResponse> assignOwnership(
      UUID accountId, AssignAccountOwnershipRequest request) {
    Account account =
        accountRepository
            .findByIdForUpdate(accountId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
    List<OwnerAllocation> owners = request.owners();

    requireNoDuplicateMembers(owners);
    Map<UUID, WorkspaceMember> membersById = resolveMembersOrThrow(owners);
    requireShareSumDoesNotExceedWhole(owners);

    // FR-HOU-006: close every currently-effective row before opening any new one, flushing in
    // between - not just closing rows for members who are leaving. A member keeping a (possibly
    // changed) share still gets a fresh row, never an in-place edit, and without an explicit
    // flush here, opening their new row would collide with their own still-open old one under
    // V6's uq_account_ownership_current partial unique index. Safe from the concurrent-write
    // corruption described in this class's own Javadoc only because findByIdForUpdate above
    // already holds this account's row lock for the rest of the transaction.
    LocalDate today = LocalDate.now();
    List<AccountOwnership> currentlyEffective =
        accountOwnershipRepository.findByAccountIdAndEffectiveToIsNull(accountId);
    // Same day both closes and reopens: a future point-in-time query must treat the newly-opened
    // row, not the just-closed one, as authoritative for "today" - effective_to IS inclusive of
    // its date in general (V6 permits effective_to = effective_from for a same-day close+reopen),
    // so such a query needs its own tie-break (e.g. prefer the row with the later created_at)
    // rather than assuming effective_from <= D <= effective_to never has more than one match.
    currentlyEffective.forEach(ownership -> ownership.setEffectiveTo(today));
    accountOwnershipRepository.saveAllAndFlush(currentlyEffective);

    List<AccountOwnership> newOwnership =
        owners.stream()
            .map(
                owner -> {
                  AccountOwnership ownership = new AccountOwnership();
                  ownership.setAccount(account);
                  ownership.setWorkspaceMember(membersById.get(owner.workspaceMemberId()));
                  ownership.setOwnershipShare(owner.share());
                  ownership.setEffectiveFrom(today);
                  return ownership;
                })
            .toList();
    newOwnership = accountOwnershipRepository.saveAllAndFlush(newOwnership);

    return newOwnership.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<AccountOwnershipResponse> currentOwnership(UUID accountId) {
    if (!accountRepository.existsById(accountId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found.");
    }
    return accountOwnershipRepository.findByAccountIdAndEffectiveToIsNull(accountId).stream()
        .map(this::toResponse)
        .toList();
  }

  private void requireNoDuplicateMembers(List<OwnerAllocation> owners) {
    Set<UUID> seen = new HashSet<>();
    for (OwnerAllocation owner : owners) {
      if (!seen.add(owner.workspaceMemberId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "workspaceMemberId " + owner.workspaceMemberId() + " appears more than once.");
      }
    }
  }

  private void requireShareSumDoesNotExceedWhole(List<OwnerAllocation> owners) {
    BigDecimal total =
        owners.stream().map(OwnerAllocation::share).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(BigDecimal.ONE) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Ownership shares sum to "
              + total
              + ", which exceeds 100% (FR-HOU-002/003). A share below 100% is fine mid-onboarding"
              + " - only exceeding it is rejected.");
    }
  }

  // Batched, not one findById per owner: a single findAllById issues one SELECT ... IN (...)
  // instead of N round-trips, and returning a Map keyed by id (rather than a same-order List)
  // means the caller pairs each owner with its member by id, not by position - safe regardless of
  // what order the query happens to return rows in.
  private Map<UUID, WorkspaceMember> resolveMembersOrThrow(List<OwnerAllocation> owners) {
    List<UUID> requestedIds = owners.stream().map(OwnerAllocation::workspaceMemberId).toList();
    Map<UUID, WorkspaceMember> found =
        workspaceMemberRepository.findAllById(requestedIds).stream()
            .collect(Collectors.toMap(WorkspaceMember::getId, Function.identity()));
    for (UUID requestedId : requestedIds) {
      if (!found.containsKey(requestedId)) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Workspace member not found: " + requestedId);
      }
    }
    return found;
  }

  private AccountOwnershipResponse toResponse(AccountOwnership ownership) {
    return new AccountOwnershipResponse(
        ownership.getId(),
        ownership.getAccount().getId(),
        ownership.getWorkspaceMember().getId(),
        ownership.getOwnershipShare(),
        ownership.getEffectiveFrom(),
        ownership.getEffectiveTo());
  }
}
