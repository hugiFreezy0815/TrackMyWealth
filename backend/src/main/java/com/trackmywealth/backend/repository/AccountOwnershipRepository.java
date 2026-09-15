package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.AccountOwnership;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountOwnershipRepository extends JpaRepository<AccountOwnership, UUID> {

  List<AccountOwnership> findByAccountIdAndEffectiveToIsNull(UUID accountId);

  // US-03-03/AccessControlService: an account's currently-effective owner has implicit FULL
  // access to it without needing a sharing_grant row of their own - see the class's Javadoc for
  // why (ownership already means access; a grant is only needed to extend access to a non-owner).
  boolean existsByAccountIdAndWorkspaceMemberIdAndEffectiveToIsNull(
      UUID accountId, UUID workspaceMemberId);
}
