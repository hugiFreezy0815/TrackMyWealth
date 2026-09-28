package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.LatestCategoryAssignment;
import com.trackmywealth.backend.entity.TransactionCategorizationLog;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionCategorizationLogRepository
    extends JpaRepository<TransactionCategorizationLog, UUID> {

  /**
   * The latest assignment of each given transaction, in one query for a whole page. A transaction
   * with no log row (not categorized, or UNCATEGORIZED) is simply absent. Callers pass only ids of
   * transactions already loaded under the transaction table's RLS policy.
   */
  @Query(
      value =
          "SELECT DISTINCT ON (transaction_id) transaction_id AS transactionId,"
              + " assigned_by AS assignedBy"
              + " FROM transaction_categorization_log WHERE transaction_id IN (:ids)"
              + " ORDER BY transaction_id, assigned_at DESC, id DESC",
      nativeQuery = true)
  List<LatestCategoryAssignment> findLatestAssignments(@Param("ids") Collection<UUID> ids);
}
