package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.PendingTransferDetection;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code transfer_detection_fx_pending} (V54, #223): where transfer detection could not judge a
 * cross-currency pair for lack of an FX rate, so that it re-runs once the import has stored one.
 * JDBC because the table has no entity: nothing but this queue reads it, and the job that does has
 * no workspace context (the table is outside RLS, see V54).
 */
@Repository
public class TransferDetectionFxPendingRepository {

  private final JdbcTemplate jdbcTemplate;

  public TransferDetectionFxPendingRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Records a detection to re-run; a date already recorded for the workspace stays one entry. */
  public void record(UUID workspaceId, LocalDate bookingDate) {
    jdbcTemplate.update(
        "INSERT INTO transfer_detection_fx_pending (workspace_id, booking_date) VALUES (?, ?)"
            + " ON CONFLICT (workspace_id, booking_date) DO NOTHING",
        workspaceId,
        Date.valueOf(bookingDate));
  }

  /** Every recorded detection for a date on or after {@code from}, oldest first. */
  public List<PendingTransferDetection> findFrom(LocalDate from) {
    return jdbcTemplate.query(
        "SELECT id, workspace_id, booking_date FROM transfer_detection_fx_pending"
            + " WHERE booking_date >= ? ORDER BY booking_date, workspace_id",
        (rs, rowNum) ->
            new PendingTransferDetection(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("booking_date", LocalDate.class)),
        Date.valueOf(from));
  }

  public void delete(UUID id) {
    jdbcTemplate.update("DELETE FROM transfer_detection_fx_pending WHERE id = ?", id);
  }
}
