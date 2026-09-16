package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.WorkspaceMember;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
