package com.trackmywealth.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/accounts/{accountId}/ownership} (US-03-02). Full-replacement
 * (PUT) semantics, same convention {@code UpdateAccountRequest} already uses: every call supersedes
 * the account's entire currently-effective ownership set, closing every row not represented here
 * (even one whose share is unchanged) and opening a fresh one for each entry - never an in-place
 * edit of an existing row (FR-HOU-006). An empty {@code owners} list is valid - it removes all
 * ownership, and a share sum below 100% is expected mid-onboarding, not an error; only exceeding
 * 100% is rejected ({@code AccountOwnershipService}).
 *
 * <p>Does not itself check that the acting member has {@code EDIT}/{@code FULL} access to this
 * account (the story's own stated requirement) - no per-member access-level enforcement exists
 * anywhere in this codebase yet (that's US-03-03/#74's {@code sharing_grant} mechanism); any member
 * of the account's own workspace may call this for now, the same way every other current write
 * endpoint only scopes by workspace, not by member.
 */
public record AssignAccountOwnershipRequest(@NotNull List<@NotNull @Valid OwnerAllocation> owners) {

  public AssignAccountOwnershipRequest {
    // Defensive copy, not just an immutable wrap: leaves the caller's own list (if any) safe to
    // mutate afterward without this record silently changing underneath them. A null owners is
    // left for @NotNull to produce a clean 400 rather than throwing here first. Unlike
    // List.copyOf, this copy tolerates a null *element* too - List.copyOf forbids null elements
    // and would throw a raw NullPointerException before @NotNull on OwnerAllocation ever got a
    // chance to reject it with a proper 400 instead.
    if (owners != null) {
      owners = Collections.unmodifiableList(new ArrayList<>(owners));
    }
  }

  public record OwnerAllocation(
      @NotNull UUID workspaceMemberId,
      @NotNull
          @DecimalMin(value = "0", inclusive = false)
          @DecimalMax("1")
          @Digits(integer = 1, fraction = 5)
          BigDecimal share) {}
}
