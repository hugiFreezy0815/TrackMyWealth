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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    Account account = findAccountOrThrow(accountId);
    List<OwnerAllocation> owners = request.owners();

    requireNoDuplicateMembers(owners);
    requireShareSumDoesNotExceedWhole(owners);
    List<WorkspaceMember> members = resolveMembersOrThrow(owners);

    // FR-HOU-006: close every currently-effective row before opening any new one, flushing in
    // between - not just closing rows for members who are leaving. A member keeping a (possibly
    // changed) share still gets a fresh row, never an in-place edit, and without an explicit
    // flush here, opening their new row would collide with their own still-open old one under
    // V6's uq_account_ownership_current partial unique index.
    List<AccountOwnership> currentlyEffective =
        accountOwnershipRepository.findByAccountIdAndEffectiveToIsNull(accountId);
    LocalDate today = LocalDate.now();
    currentlyEffective.forEach(ownership -> ownership.setEffectiveTo(today));
    accountOwnershipRepository.saveAllAndFlush(currentlyEffective);

    List<AccountOwnership> newOwnership = new ArrayList<>();
    for (int i = 0; i < owners.size(); i++) {
      AccountOwnership ownership = new AccountOwnership();
      ownership.setAccount(account);
      ownership.setWorkspaceMember(members.get(i));
      ownership.setOwnershipShare(owners.get(i).share());
      ownership.setEffectiveFrom(today);
      newOwnership.add(ownership);
    }
    newOwnership = accountOwnershipRepository.saveAllAndFlush(newOwnership);

    return newOwnership.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<AccountOwnershipResponse> currentOwnership(UUID accountId) {
    findAccountOrThrow(accountId);
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

  private List<WorkspaceMember> resolveMembersOrThrow(List<OwnerAllocation> owners) {
    return owners.stream()
        .map(
            owner ->
                workspaceMemberRepository
                    .findById(owner.workspaceMemberId())
                    .orElseThrow(
                        () ->
                            new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Workspace member not found: " + owner.workspaceMemberId())))
        .toList();
  }

  private Account findAccountOrThrow(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
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
