package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.WorkspaceMember;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

  // US-03-05: deterministic order, inactive members included, and an SQL-level row limit.
  // Workspace scoping is explicit here and independently enforced by PostgreSQL RLS.
  @Query(
      "SELECT m FROM WorkspaceMember m WHERE m.workspace.id = :workspaceId"
          + " ORDER BY m.displayName ASC, m.id ASC")
  List<WorkspaceMember> findMembersForListing(@Param("workspaceId") UUID workspaceId, Limit limit);

  // AccessControlService's interim administrator rule (FR-TEN-007): a SYSTEM_ADMINISTRATOR linked
  // to a member keeps that member's access only while it is the workspace's sole active member.
  long countByWorkspaceIdAndStatus(UUID workspaceId, String status);

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
