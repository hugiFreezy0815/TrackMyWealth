package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.entity.WorkspaceSecurityTicker;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkspaceSecurityTickerRepository
    extends JpaRepository<WorkspaceSecurityTicker, UUID> {

  /** The workspace's mappings of {@code tickers} (upper case); RLS scopes them as well. */
  List<WorkspaceSecurityTicker> findByWorkspaceIdAndTickerIn(
      UUID workspaceId, Collection<String> tickers);

  /**
   * Maps {@code ticker} to {@code securityId} in the workspace, replacing an earlier mapping of the
   * same ticker: the member's latest word on what it means. {@code ON CONFLICT}, not a read and an
   * insert: two concurrent mappings of one ticker must not abort the transaction.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO workspace_security_ticker (id, workspace_id, ticker, security_id)"
              + " VALUES (gen_random_uuid(), :workspaceId, :ticker, :securityId)"
              + " ON CONFLICT (workspace_id, ticker)"
              + " DO UPDATE SET security_id = EXCLUDED.security_id",
      nativeQuery = true)
  int map(
      @Param("workspaceId") UUID workspaceId,
      @Param("ticker") String ticker,
      @Param("securityId") UUID securityId);
}
