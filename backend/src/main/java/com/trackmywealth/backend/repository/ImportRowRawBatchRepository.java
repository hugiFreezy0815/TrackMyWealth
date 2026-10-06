package com.trackmywealth.backend.repository;

import com.trackmywealth.backend.dto.StagedImportRow;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk writes of import rows (US-07-04). JDBC rather than {@code ImportRowRawRepository#saveAll}: a
 * file holds up to 20,000 rows, which JPA would insert one statement and one round trip at a time
 * (same reasoning as {@link FxRateBatchRepository}). Runs in the caller's transaction, so the
 * workspace's row-level security context applies.
 */
@Repository
public class ImportRowRawBatchRepository {

  private static final int BATCH_SIZE = 1000;

  private static final String INSERT =
      "INSERT INTO import_row_raw (id, import_batch_id, workspace_id, row_number, raw_data,"
          + " parse_status, included, duplicate_of_transaction_id, warning_codes, error_code,"
          + " error_args, canonical_data, booking_date, amount, currency)"
          + " VALUES (?, ?, ?, ?, CAST(? AS json), ?, ?, ?, ?, ?, CAST(? AS json),"
          + " CAST(? AS jsonb), ?, ?, ?)";

  private static final String LINK =
      "UPDATE import_row_raw SET resulting_transaction_id = ? WHERE id = ?";

  private static final String MARK_DUPLICATE =
      "UPDATE import_row_raw SET parse_status = 'DUPLICATE', included = FALSE,"
          + " duplicate_of_transaction_id = ? WHERE id = ?";

  private final JdbcTemplate jdbcTemplate;

  public ImportRowRawBatchRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Inserts the rows of one batch. */
  public void insertAll(UUID batchId, UUID workspaceId, List<StagedImportRow> rows) {
    jdbcTemplate.batchUpdate(
        INSERT,
        rows,
        BATCH_SIZE,
        (PreparedStatement statement, StagedImportRow row) ->
            bind(statement, batchId, workspaceId, row));
  }

  /** Removes a batch's rows before it is parsed again; nothing references them before a commit. */
  public int deleteByBatch(UUID batchId) {
    return jdbcTemplate.update("DELETE FROM import_row_raw WHERE import_batch_id = ?", batchId);
  }

  /** Records the transaction each committed row became: {@code rowIds[i]} -> {@code ids[i]}. */
  public void linkTransactions(List<UUID> rowIds, List<UUID> transactionIds) {
    updatePairs(LINK, rowIds, transactionIds);
  }

  /**
   * Records rows the commit's duplicate check found in the ledger after all: each becomes an
   * excluded {@code DUPLICATE} of {@code transactionIds[i]}.
   */
  public void markDuplicates(List<UUID> rowIds, List<UUID> transactionIds) {
    updatePairs(MARK_DUPLICATE, rowIds, transactionIds);
  }

  // Runs sql once per row: the transaction id as its first parameter, the row id as its second.
  private void updatePairs(String sql, List<UUID> rowIds, List<UUID> transactionIds) {
    if (rowIds.size() != transactionIds.size()) {
      throw new IllegalArgumentException("Every row needs exactly one transaction id.");
    }
    List<UUID[]> pairs =
        IntStream.range(0, rowIds.size())
            .mapToObj(i -> new UUID[] {transactionIds.get(i), rowIds.get(i)})
            .toList();
    jdbcTemplate.batchUpdate(
        sql,
        pairs,
        BATCH_SIZE,
        (PreparedStatement statement, UUID[] pair) -> {
          statement.setObject(1, pair[0]);
          statement.setObject(2, pair[1]);
        });
  }

  private static void bind(
      PreparedStatement statement, UUID batchId, UUID workspaceId, StagedImportRow row)
      throws SQLException {
    statement.setObject(1, row.id());
    statement.setObject(2, batchId);
    statement.setObject(3, workspaceId);
    statement.setInt(4, row.rowNumber());
    statement.setString(5, row.rawDataJson());
    statement.setString(6, row.status());
    statement.setBoolean(7, row.included());
    statement.setObject(8, row.duplicateOfTransactionId(), Types.OTHER);
    statement.setArray(
        9, statement.getConnection().createArrayOf("text", row.warningCodes().toArray()));
    statement.setString(10, row.errorCode());
    statement.setString(11, row.errorArgsJson());
    statement.setString(12, row.canonicalJson());
    statement.setObject(13, row.bookingDate(), Types.DATE);
    statement.setBigDecimal(14, row.amount());
    statement.setString(15, row.currency());
  }
}
