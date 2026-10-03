package com.trackmywealth.backend.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * #207 test support: the {@code If-Match} a client that just read a row would send, for tests whose
 * subject is not optimistic concurrency itself (those go through ETags over HTTP instead).
 *
 * <p>The header is added only if the row exists. A test that deliberately targets an unknown id
 * therefore sends none - as a real client could not - and still expects its 404, which also pins
 * that lookup and authorization run before the version precondition (ADR 0004).
 */
final class CurrentVersion {

  private static final Set<String> TABLES =
      Set.of(
          "account",
          "account_snapshot",
          "app_user",
          "categorization_rule",
          "fx_import_setting",
          "settlement_match",
          "sharing_grant",
          "transaction",
          "workspace_member");

  private CurrentVersion() {}

  /** {@code If-Match} for the row of {@code table} with this id, if there is one. */
  static Consumer<HttpHeaders> ifMatch(DataSource dataSource, String table, UUID id) {
    if (!TABLES.contains(table)) {
      throw new IllegalArgumentException("not a versioned table: " + table);
    }
    return headers(dataSource, "SELECT version FROM " + table + " WHERE id = ?", id);
  }

  /**
   * Asserts that the {@code ETag} a successful write answered with is the version the row was
   * actually stored with. A client sends exactly that tag on its next write, so one that lags the
   * stored row - e.g. a follow-up change that is flushed only at commit - would turn the client's
   * own next edit into a 412 nobody caused. Returns the response body for chaining.
   */
  static <T> T storedEtag(
      EntityExchangeResult<T> result, DataSource dataSource, String table, UUID id) {
    if (!TABLES.contains(table)) {
      throw new IllegalArgumentException("not a versioned table: " + table);
    }
    return requireStored(
        result, read(dataSource, "SELECT version FROM " + table + " WHERE id = ?", id), table, id);
  }

  /**
   * {@link #storedEtag} for a credit card's extension row (statement config, settlement source).
   */
  static <T> T storedCardEtag(EntityExchangeResult<T> result, DataSource dataSource, UUID card) {
    return requireStored(
        result,
        read(dataSource, "SELECT version FROM account_credit_card WHERE account_id = ?", card),
        "account_credit_card",
        card);
  }

  private static <T> T requireStored(
      EntityExchangeResult<T> result, Integer stored, String table, UUID id) {
    String etag = result.getResponseHeaders().getETag();
    if (stored == null || !("\"" + stored + "\"").equals(etag)) {
      throw new AssertionError(
          "ETag " + etag + " is not the stored version " + stored + " of " + table + " " + id);
    }
    return result.getResponseBody();
  }

  /** {@code If-Match} for a credit card's extension row (statement config, settlement source). */
  static Consumer<HttpHeaders> ifMatchForCard(DataSource dataSource, UUID cardAccountId) {
    return headers(
        dataSource, "SELECT version FROM account_credit_card WHERE account_id = ?", cardAccountId);
  }

  private static Consumer<HttpHeaders> headers(DataSource dataSource, String sql, UUID id) {
    Integer version = read(dataSource, sql, id);
    return headers -> {
      if (version != null) {
        headers.setIfMatch("\"" + version + "\"");
      }
    };
  }

  private static Integer read(DataSource dataSource, String sql, UUID id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : null;
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("could not read the current version", ex);
    }
  }
}
