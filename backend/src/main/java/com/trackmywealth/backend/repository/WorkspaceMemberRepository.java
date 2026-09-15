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

  // US-03-04/FR-HHL-015: locks the target row plus every other currently-ACTIVE member of the
  // same workspace, in one statement - mirrors AppUserRepository.lockTargetAndActiveAdministrators
  // (FR-USR-005's analogous "last active administrator" guard), including the ORDER BY id: two
  // concurrent deactivation calls touching overlapping members of the same (small, household-sized)
  // workspace must acquire these locks in the same deterministic order or they can deadlock instead
  // of one simply waiting for the other. Unlike that query, there is no EXISTS-gated second branch
  // here - a workspace's membership is small by design (this is a household, not a multi-tenant
  // table), so locking every active member whenever any one of them is touched is not the
  // contention concern it would be for the global app_user table.
  @Query(
      value =
          "SELECT * FROM workspace_member WHERE id = :targetId OR ("
              + "status = 'ACTIVE' AND workspace_id = "
              + "(SELECT workspace_id FROM workspace_member WHERE id = :targetId)"
              + ") ORDER BY id FOR UPDATE",
      nativeQuery = true)
  List<WorkspaceMember> lockTargetAndActiveMembersInWorkspace(@Param("targetId") UUID targetId);
}
