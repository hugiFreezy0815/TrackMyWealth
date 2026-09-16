package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.WorkspaceMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

  // US-03-03/AccessControlService's bootstrap rule: the sole active, non-dependent member of a
  // workspace has implicit FULL access to everything in it, since with only one such member no
  // sharing_grant could possibly exist yet to grant them access to their own workspace's data
  // (AppUser.role deliberately confers no financial-data access - see its own Javadoc - so this
  // structural check, not the SYSTEM_ADMINISTRATOR role, is what breaks the chicken-and-egg
  // problem). Once a second non-dependent member joins, this stops applying and access reverts to
  // ownership/explicit grants only. Dependent members (is_dependent=true - "a person represented
  // financially; may have no login", per WorkspaceMember's own Javadoc) never count here: they can
  // never authenticate, so they can never be "the caller" this rule is granting access to.
  long countByWorkspaceIdAndStatusAndDependentFalse(UUID workspaceId, String status);

  // US-03-04/FR-HHL-015: locks the target row plus every other currently-ACTIVE member of the
  // caller's own workspace, in one statement - mirrors
  // AppUserRepository.lockTargetAndActiveAdministrators (FR-USR-005's analogous "last active
  // administrator" guard), including both the ORDER BY id (two concurrent deactivation calls
  // touching overlapping members of the same workspace must acquire these locks in the same
  // deterministic order or they can deadlock instead of one simply waiting for the other) and the
  // EXISTS-gated second branch: the broader active-member set is only locked when the target
  // itself is currently ACTIVE, so a call against an already-INACTIVE target locks just that one
  // row instead of every active member of the workspace for no reason. workspaceId is passed in
  // directly (the caller's own AuthenticatedUserPrincipal.workspaceId(), since this is
  // self-service-only - see WorkspaceMemberService) rather than re-derived from the target row via
  // a subquery.
  @Query(
      value =
          "SELECT * FROM workspace_member WHERE id = :targetId OR ("
              + "status = 'ACTIVE' AND workspace_id = :workspaceId AND EXISTS ("
              + "SELECT 1 FROM workspace_member t WHERE t.id = :targetId AND t.status = 'ACTIVE')"
              + ") ORDER BY id FOR UPDATE",
      nativeQuery = true)
  List<WorkspaceMember> lockTargetAndActiveMembersInWorkspace(
      @Param("targetId") UUID targetId, @Param("workspaceId") UUID workspaceId);
}
