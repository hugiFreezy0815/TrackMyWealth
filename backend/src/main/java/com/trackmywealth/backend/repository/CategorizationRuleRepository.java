package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.CategorizationRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Every query filters by workspace explicitly as well as through RLS (V20), so the result never
 * depends on the database role. The order is the evaluation order: lower priority first, then the
 * older rule, then the id so ties are stable.
 */
@Repository
public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, UUID> {

  List<CategorizationRule> findByWorkspaceIdAndActiveTrueOrderByPriorityAscCreatedAtAscIdAsc(
      UUID workspaceId);

  List<CategorizationRule> findByWorkspaceIdOrderByPriorityAscCreatedAtAscIdAsc(UUID workspaceId);

  Optional<CategorizationRule> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
