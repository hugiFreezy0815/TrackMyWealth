package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.trackmywealth.backend.dto.AccountListItemResponse;
import com.trackmywealth.backend.dto.AccountPositionsResponse;
import com.trackmywealth.backend.dto.AccountSnapshotResponse;
import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AccountValuation;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateSecurityRequest;
import com.trackmywealth.backend.dto.ImportBatchResponse;
import com.trackmywealth.backend.dto.ImportBatchValues;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportHoldingsResponse;
import com.trackmywealth.backend.dto.ImportHoldingsValues;
import com.trackmywealth.backend.dto.ImportRollbackRequest;
import com.trackmywealth.backend.dto.ImportRollbackResponse;
import com.trackmywealth.backend.dto.ImportSnapshotValues;
import com.trackmywealth.backend.dto.ImportStatementBalanceResponse;
import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.NetWorthResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PortfolioViewResponse;
import com.trackmywealth.backend.dto.RecordAccountSnapshotRequest;
import com.trackmywealth.backend.dto.ReplaceAccountSnapshotRequest;
import com.trackmywealth.backend.dto.SecurityResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.SnapshotCashBalance;
import com.trackmywealth.backend.dto.SnapshotHoldingRequest;
import com.trackmywealth.backend.dto.SnapshotHoldingResponse;
import com.trackmywealth.backend.dto.ValueHistoryResponse;
import com.trackmywealth.backend.dto.ValueStatus;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.error.ProblemCode;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.service.ShippedTemplateFixtures;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.LedgerCleanup;
import com.trackmywealth.backend.testsupport.MutableClock;
import com.trackmywealth.backend.testsupport.TestClockConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-13 (#273) against a real PostgreSQL, through the templates V100 ships. Acceptance
 * criterion: given a synthetic statement in the Swissquote layout, when it is imported, then a
 * {@code DOCUMENT} snapshot with one holding per position (quantity, reported price and value) and
 * the cash balance exist. Every file is an invented {@link ShippedTemplateFixtures} statement.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
class HoldingsImportControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final UUID SWISSQUOTE = UUID.fromString("003878c0-995e-4801-96d3-3e431c5095a2");
  private static final UUID POSITIONS_XLSX =
      UUID.fromString("41aff7f6-3fc8-4206-ae61-dc4bf3fdad53");
  private static final UUID CASH_XLS = UUID.fromString("1dcaf9a1-3aab-4b56-8e44-1a252821ca23");
  private static final LocalDate STATEMENT_DATE = LocalDate.of(2031, 12, 31);
  private static final LocalDate EXPORT_DATE = LocalDate.of(2031, 11, 30);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @LocalServerPort int port;

  @Autowired DataSource dataSource;

  // The invented statements are dated 2031: "today" comes after them.
  @Autowired MutableClock clock;

  @Autowired FxRateRepository fxRateRepository;

  private String token;
  private UUID depot;

  @BeforeEach
  void cleanDatabaseAndSignIn() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM import_row_raw",
              "DELETE FROM fx_rate",
              LedgerCleanup.DELETE_ALL_TRANSACTIONS,
              "DELETE FROM reconciliation_result",
              "DELETE FROM snapshot_holding",
              "DELETE FROM account_snapshot",
              "DELETE FROM import_holding_row",
              "DELETE FROM import_file",
              "DELETE FROM import_batch",
              "DELETE FROM import_template WHERE workspace_id IS NOT NULL",
              "DELETE FROM workspace_security_ticker",
              "DELETE FROM security_asset_class_weight",
              "DELETE FROM security_field_provenance",
              "DELETE FROM security",
              "DELETE FROM account_securities",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM portfolio",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
    clock.set(
        LocalDate.of(2032, 1, 15).atTime(12, 0).atZone(ZoneId.of("Europe/Zurich")).toInstant());
    token = bootstrapAdministrator();
    depot = createAccount("Depot", true);
  }

  // --- acceptance criterion ------------------------------------------------------------------

  @Test
  void aSwissquoteStatementBecomesADocumentSnapshotWithItsPositionsAndCash() throws Exception {
    ImportBatchResponse preview = upload(depot, ShippedTemplateFixtures.swissquoteHoldings(), null);

    // Detected by the shipped template alone, and staged as a holdings statement, not as rows.
    assertThat(preview.templateId()).isEqualTo(SWISSQUOTE);
    assertThat(preview.status()).isEqualTo(ImportBatchValues.PARSED);
    assertThat(preview.counts().total()).isZero();
    ImportHoldingsResponse holdings = preview.holdings();
    assertThat(holdings.snapshotDate()).isEqualTo(STATEMENT_DATE);
    assertThat(holdings.dateSource()).isEqualTo(ImportHoldingsValues.DATE_STATEMENT);
    assertThat(holdings.snapshot()).isEqualTo(ImportSnapshotValues.NEW);
    assertThat(holdings.balance()).isEqualByComparingTo("1204.35");
    assertThat(holdings.cashBalances())
        .extracting(ImportHoldingsResponse.Cash::currency, ImportHoldingsResponse.Cash::recorded)
        .containsExactly(tuple("CHF", true), tuple("USD", true));
    assertThat(holdings.positions())
        .extracting(ImportHoldingsResponse.Position::resolution)
        .containsOnly(ImportHoldingsValues.UNRESOLVED);
    assertThat(holdings.unresolvedCount()).isEqualTo(4);
    assertThat(holdings.readyToCommit()).isFalse();

    // Nothing is created by the import: the commit waits for the member's securities.
    expectProblem(commitRequest(preview), 422, ProblemCode.IMPORT_HOLDINGS_UNRESOLVED);
    assertThat(count("SELECT count(*) FROM account_snapshot")).isZero();
    assertThat(count("SELECT count(*) FROM security")).isZero();

    UUID chf = createSecurity(ShippedTemplateFixtures.HOLDING_ISIN_CHF, null, "EQUITY", "CHF");
    UUID usd = createSecurity(ShippedTemplateFixtures.HOLDING_ISIN_USD, null, "EQUITY", "USD");
    UUID fund = createSecurity(ShippedTemplateFixtures.HOLDING_ISIN_FUND, null, "ETF", "CHF");
    UUID coin = createSecurity(null, ShippedTemplateFixtures.HOLDING_CRYPTO, "CRYPTO", "CHF");

    ImportBatchResponse resolved = get(depot, preview.id());
    assertThat(resolved.holdings().positions())
        .extracting(ImportHoldingsResponse.Position::securityId)
        .containsExactly(coin, chf, usd, fund);
    assertThat(resolved.holdings().readyToCommit()).isTrue();

    ImportBatchResponse committed = commit(resolved);

    assertThat(committed.status()).isEqualTo(ImportBatchValues.COMMITTED);
    assertThat(committed.holdings().snapshot()).isEqualTo(ImportSnapshotValues.RECORDED);
    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.source()).isEqualTo("DOCUMENT");
    assertThat(snapshot.snapshotDate()).isEqualTo(STATEMENT_DATE);
    assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
    assertThat(snapshot.holdings())
        .extracting(
            SnapshotHoldingResponse::securityId,
            SnapshotHoldingResponse::quantity,
            SnapshotHoldingResponse::reportedPrice,
            SnapshotHoldingResponse::reportedValue,
            SnapshotHoldingResponse::reportedCurrency)
        .containsExactlyInAnyOrder(
            tuple(
                coin,
                new BigDecimal("12.5000000000"),
                new BigDecimal("3.1415926000"),
                new BigDecimal("39.2700"),
                "CHF"),
            tuple(
                chf,
                new BigDecimal("25.0000000000"),
                new BigDecimal("113.4000000000"),
                new BigDecimal("2835.0000"),
                "CHF"),
            tuple(
                usd,
                new BigDecimal("1200.0000000000"),
                new BigDecimal("4.3700000000"),
                new BigDecimal("5244.0000"),
                "USD"),
            tuple(
                fund,
                new BigDecimal("12.3456000000"),
                new BigDecimal("95.1200000000"),
                new BigDecimal("1174.3100"),
                "CHF"));
    assertThat(rowsOf("SELECT import_batch_id FROM account_snapshot WHERE account_id = ?", depot))
        .singleElement()
        .satisfies(row -> assertThat(row.get("import_batch_id")).isEqualTo(preview.id()));
  }

  @Test
  void foreignCashAndHoldingsBothUseTheValuationDatesRate() throws Exception {
    createAllSecurities();
    commit(upload(depot, ShippedTemplateFixtures.swissquoteHoldings(), null));
    assertThat(snapshots(depot).get(0).foreignCashBalances())
        .containsExactly(new SnapshotCashBalance("USD", new BigDecimal("310.2000")));
    assertThat(listedDepot().valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
    assertThat(netWorth().complete()).isFalse();

    fx("USD", "CHF", STATEMENT_DATE, "0.80");
    assertThat(balance(STATEMENT_DATE, "CHF")).isEqualByComparingTo("9696.29");
    assertThat(listedDepot().valueStatus()).isEqualTo(ValueStatus.STALE);

    LocalDate today = LocalDate.of(2032, 1, 15);
    fx("USD", "CHF", today, "0.90");
    assertThat(listedDepot().balance()).isEqualByComparingTo("10251.71");
    assertThat(listedDepot().valueStatus()).isEqualTo(ValueStatus.CURRENT);
    NetWorthResponse netWorth = netWorth();
    assertThat(netWorth.complete()).isTrue();
    assertThat(netWorth.netWorth()).isEqualByComparingTo("10251.71");
    UUID portfolio = listedDepot().portfolioId();
    PortfolioViewResponse view =
        client()
            .get()
            .uri("/api/v1/portfolios/{id}/view", portfolio)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(PortfolioViewResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(view.complete()).isTrue();
    assertThat(view.totalInWorkspaceCurrency()).isEqualByComparingTo("10251.71");
    ValueHistoryResponse history =
        client()
            .get()
            .uri("/api/v1/net-worth/history?period=1M")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ValueHistoryResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(history.points())
        .anySatisfy(
            point -> {
              assertThat(point.date()).isEqualTo(STATEMENT_DATE);
              assertThat(point.value()).isEqualByComparingTo("9696.29");
            });
    assertThat(history.points().get(history.points().size() - 1).value())
        .isEqualByComparingTo("10251.71");
    assertThat(positions().positions())
        .anySatisfy(
            position -> {
              assertThat(position.value()).isEqualByComparingTo("5244");
              assertThat(position.valueInAccountCurrency()).isEqualByComparingTo("4719.60");
            });

    fx("USD", "EUR", today, "0.70");
    fx("CHF", "EUR", today, "1.10");
    // 5252.93 CHF and 5554.20 USD; the USD amount takes the direct USD/EUR quote.
    assertThat(balance(today, "EUR")).isEqualByComparingTo("9666.1630");
  }

  @Test
  void laterCashImportAddsOnlyNewCurrenciesAndRollingItBackLeavesTheOriginalBalance()
      throws Exception {
    ImportBatchResponse original =
        commit(
            upload(
                depot, ShippedTemplateFixtures.postfinanceCashWorkbook(true, false), EXPORT_DATE));
    ImportBatchResponse preview =
        upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE);
    assertThat(preview.holdings().snapshot()).isEqualTo(ImportSnapshotValues.COMPLETES);
    ImportBatchResponse added = commit(preview);
    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
    assertThat(snapshot.foreignCashBalances())
        .containsExactly(new SnapshotCashBalance("USD", new BigDecimal("310.2000")));
    assertThat(
            rowsOf(
                "SELECT balance_import_batch_id FROM account_snapshot WHERE id = ?", snapshot.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.get("balance_import_batch_id")).isEqualTo(original.id()));

    rollback(get(depot, added.id()));
    assertThat(snapshots(depot))
        .singleElement()
        .satisfies(
            kept -> {
              assertThat(kept.balance()).isEqualByComparingTo("1204.35");
              assertThat(kept.foreignCashBalances()).isEmpty();
            });
  }

  @Test
  void aForeignOnlyImportPersistsTheCashWithoutAssumingAnOwnCurrencyBalance() throws Exception {
    createWorkbookSecurities();
    commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));
    commit(
        upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(false, true), EXPORT_DATE));
    fx("USD", "CHF", EXPORT_DATE, "0.80");

    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.balance()).isNull();
    assertThat(snapshot.foreignCashBalances())
        .containsExactly(new SnapshotCashBalance("USD", new BigDecimal("310.2000")));
    assertThat(listedDepot().valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void theBackfillRestoresCashOnceAndTheSourceBatchCanRollItBack() throws Exception {
    createWorkbookSecurities();
    commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));
    ImportBatchResponse cash =
        commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));
    execute("DELETE FROM snapshot_foreign_cash");
    assertThat(listedDepot().valueStatus()).isEqualTo(ValueStatus.UNKNOWN);

    ResourceDatabasePopulator backfill =
        new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V123__restore_snapshot_foreign_cash.sql"));
    backfill.execute(dataSource);
    backfill.execute(dataSource);
    assertThat(rowsOf("SELECT import_batch_id FROM snapshot_foreign_cash"))
        .singleElement()
        .satisfies(row -> assertThat(row.get("import_batch_id")).isEqualTo(cash.id()));
    fx("USD", "CHF", EXPORT_DATE, "0.80");
    assertThat(listedDepot().balance()).isNotNull();

    rollback(get(depot, cash.id()));
    assertThat(count("SELECT count(*) FROM snapshot_foreign_cash")).isZero();
    assertThat(snapshots(depot).get(0).holdings()).hasSize(3);
  }

  @Test
  void theBackfillLeavesConflictingLegacyCashSourcesUnknown() throws Exception {
    createWorkbookSecurities();
    ImportBatchResponse positions =
        commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));
    commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));
    execute("DELETE FROM snapshot_foreign_cash");
    // Simulate a second contributing legacy batch that also declared its own USD cash amount.
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO import_holding_row
                (workspace_id, import_batch_id, row_number, kind, currency, amount)
            SELECT workspace_id, id, 999, 'CASH', 'USD', 1 FROM import_batch WHERE id = ?
            """)) {
      statement.setObject(1, positions.id());
      statement.executeUpdate();
    }
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V123__restore_snapshot_foreign_cash.sql"))
        .execute(dataSource);
    fx("USD", "CHF", EXPORT_DATE, "0.80");

    assertThat(count("SELECT count(*) FROM snapshot_foreign_cash")).isZero();
    assertThat(listedDepot().valueStatus()).isEqualTo(ValueStatus.UNKNOWN);
  }

  @Test
  void manualSnapshotAcceptsForeignCashAndRejectsAnInvalidCurrencyOrScale() {
    UUID security = createSecurity("CH0038863350", null, "EQUITY", "CHF");
    SnapshotHoldingRequest holding =
        new SnapshotHoldingRequest(
            security, BigDecimal.ONE, null, false, null, new BigDecimal("100"), "CHF");
    fx("USD", "CHF", EXPORT_DATE, "0.80");

    for (SnapshotCashBalance invalid :
        List.of(
            new SnapshotCashBalance("ZZZ", BigDecimal.ONE),
            new SnapshotCashBalance(null, BigDecimal.ONE),
            new SnapshotCashBalance("USD", null),
            new SnapshotCashBalance("USD", new BigDecimal("1.00001")),
            new SnapshotCashBalance("CHF", BigDecimal.ONE))) {
      client()
          .post()
          .uri("/api/v1/accounts/{id}/snapshots", depot)
          .body(
              new RecordAccountSnapshotRequest(
                  EXPORT_DATE, BigDecimal.ZERO, List.of(holding), List.of(invalid)))
          .exchange()
          .expectStatus()
          .isEqualTo(422);
    }

    AccountSnapshotResponse snapshot =
        client()
            .post()
            .uri("/api/v1/accounts/{id}/snapshots", depot)
            .body(
                new RecordAccountSnapshotRequest(
                    EXPORT_DATE,
                    BigDecimal.ZERO,
                    List.of(holding),
                    List.of(new SnapshotCashBalance("USD", new BigDecimal("25")))))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(AccountSnapshotResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(balance(EXPORT_DATE, "CHF")).isEqualByComparingTo("120");

    AccountSnapshotResponse updated =
        replaceSnapshot(
            snapshot, new ReplaceAccountSnapshotRequest(BigDecimal.ONE, List.of(holding)));
    assertThat(balance(EXPORT_DATE, "CHF")).isEqualByComparingTo("121");
    replaceSnapshot(
        updated, new ReplaceAccountSnapshotRequest(BigDecimal.ONE, List.of(holding), List.of()));
    assertThat(balance(EXPORT_DATE, "CHF")).isEqualByComparingTo("101");
  }

  private AccountSnapshotResponse replaceSnapshot(
      AccountSnapshotResponse snapshot, ReplaceAccountSnapshotRequest request) {
    return client()
        .put()
        .uri("/api/v1/accounts/{id}/snapshots/{snapshotId}", depot, snapshot.id())
        .header(HttpHeaders.IF_MATCH, etag(snapshot.version()))
        .body(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountSnapshotResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private void fx(String base, String quote, LocalDate date, String value) {
    FxRate rate = new FxRate();
    rate.setBaseCurrency(base);
    rate.setQuoteCurrency(quote);
    rate.setRateDate(date);
    rate.setRate(new BigDecimal(value));
    rate.setSource("ECB");
    fxRateRepository.saveAndFlush(rate);
  }

  private NetWorthResponse netWorth() {
    return client()
        .get()
        .uri("/api/v1/net-worth")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(NetWorthResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private BigDecimal balance(LocalDate date, String currency) {
    return client()
        .get()
        .uri("/api/v1/accounts/{id}/balance?asOf={date}&currency={currency}", depot, date, currency)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountValuation.class)
        .returnResult()
        .getResponseBody()
        .value();
  }

  private AccountPositionsResponse positions() {
    return client()
        .get()
        .uri("/api/v1/accounts/{id}/positions", depot)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(AccountPositionsResponse.class)
        .returnResult()
        .getResponseBody();
  }

  @Test
  void theSameStatementAgainIsRefusedUntilTheFirstImportIsRolledBack() throws Exception {
    createAllSecurities();
    // One file, uploaded twice: PDFBox gives every new document an id of its own.
    byte[] statement = ShippedTemplateFixtures.swissquoteHoldings();
    ImportBatchResponse first = commit(upload(depot, statement, null));

    ImportBatchResponse again = upload(depot, statement, null);
    assertThat(again.sameFileImportedIn().batchId()).isEqualTo(first.id());
    assertThat(again.holdings().snapshot()).isEqualTo(ImportSnapshotValues.ALREADY_RECORDED);
    assertThat(again.holdings().readyToCommit()).isFalse();
    expectProblem(commitRequest(again), 409, ProblemCode.IMPORT_SNAPSHOT_ALREADY_RECORDED);

    ImportRollbackResponse rolledBack = rollback(get(depot, first.id()));
    assertThat(rolledBack.batch().holdings().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_DELETED);
    assertThat(count("SELECT count(*) FROM account_snapshot")).isZero();
    assertThat(count("SELECT count(*) FROM snapshot_holding")).isZero();

    assertThat(get(depot, again.id()).holdings().readyToCommit()).isTrue();
    assertThat(commit(get(depot, again.id())).holdings().snapshot())
        .isEqualTo(ImportSnapshotValues.RECORDED);
  }

  // --- spreadsheets --------------------------------------------------------------------------

  @Test
  void aPositionsWorkbookIsDatedByTheMemberAndResolvedByTheWorkspacesTickers() throws Exception {
    byte[] workbook = ShippedTemplateFixtures.postfinancePositionsWorkbook();
    expectProblem(uploadRequest(depot, workbook, null), 400, null);

    ImportBatchResponse preview = upload(depot, workbook, EXPORT_DATE);
    assertThat(preview.templateId()).isEqualTo(POSITIONS_XLSX);
    assertThat(preview.holdings().snapshotDate()).isEqualTo(EXPORT_DATE);
    assertThat(preview.holdings().dateSource()).isEqualTo(ImportHoldingsValues.DATE_MEMBER);
    assertThat(preview.holdings().balance()).isNull();

    UUID chf = createSecurity(null, ShippedTemplateFixtures.WORKBOOK_TICKER_CHF, "EQUITY", "CHF");
    createSecurity(null, ShippedTemplateFixtures.WORKBOOK_TICKER_USD, "EQUITY", "USD");
    createSecurity(null, ShippedTemplateFixtures.WORKBOOK_TICKER_ETF, "ETF", "CHF");
    ImportBatchResponse committed = commit(get(depot, preview.id()));

    assertThat(committed.holdings().snapshot()).isEqualTo(ImportSnapshotValues.RECORDED);
    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.balance()).isNull();
    assertThat(snapshot.holdings()).hasSize(3);
    assertThat(snapshot.holdings())
        .filteredOn(holding -> holding.securityId().equals(chf))
        .singleElement()
        .satisfies(holding -> assertThat(holding.reportedValue()).isEqualByComparingTo("2835"));
  }

  @Test
  void aCashWorkbookRecordsTheAccountCurrencysBalanceOnly() throws Exception {
    ImportBatchResponse preview =
        upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE);
    assertThat(preview.templateId()).isEqualTo(CASH_XLS);
    assertThat(preview.holdings().positions()).isEmpty();
    assertThat(preview.holdings().readyToCommit()).isTrue();

    commit(preview);

    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
    assertThat(snapshot.currency()).isEqualTo("CHF");
    assertThat(snapshot.holdings()).isEmpty();
  }

  // --- US-07-31: completing a snapshot part by part -------------------------------------------

  @Test
  void theCashExportCompletesTheSnapshotThePositionsExportRecorded() throws Exception {
    createWorkbookSecurities();
    ImportBatchResponse positions =
        commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));

    ImportBatchResponse cash =
        upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE);

    ImportHoldingsResponse preview = cash.holdings();
    assertThat(preview.snapshot()).isEqualTo(ImportSnapshotValues.COMPLETES);
    assertThat(preview.adds()).containsExactly(ImportSnapshotValues.PART_BALANCE);
    assertThat(preview.conflicts()).isEmpty();
    assertThat(preview.existingSnapshot().importBatchId()).isEqualTo(positions.id());
    assertThat(preview.existingSnapshot().balance()).isNull();
    assertThat(preview.existingSnapshot().holdingsCount()).isEqualTo(3);
    assertThat(preview.explanation())
        .startsWith("This statement adds the cash balance to the snapshot of");
    assertThat(preview.readyToCommit()).isTrue();

    ImportBatchResponse completed = commit(cash);

    assertThat(completed.holdings().snapshot()).isEqualTo(ImportSnapshotValues.COMPLETED);
    assertThat(snapshots(depot))
        .singleElement()
        .satisfies(
            snapshot -> {
              assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
              assertThat(snapshot.holdings()).hasSize(3);
            });
    assertThat(
            rowsOf(
                "SELECT import_batch_id, balance_import_batch_id FROM account_snapshot"
                    + " WHERE account_id = ?",
                depot))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("import_batch_id")).isEqualTo(positions.id());
              assertThat(row.get("balance_import_batch_id")).isEqualTo(cash.id());
            });
  }

  @Test
  void thePositionsExportCompletesTheSnapshotTheCashExportRecorded() throws Exception {
    createWorkbookSecurities();
    commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));

    ImportBatchResponse positions =
        upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE);
    assertThat(positions.holdings().snapshot()).isEqualTo(ImportSnapshotValues.COMPLETES);
    assertThat(positions.holdings().adds()).containsExactly(ImportSnapshotValues.PART_HOLDINGS);

    commit(positions);

    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
    assertThat(snapshot.holdings()).hasSize(3);
  }

  @Test
  void theSameCashInAnotherFormatIsADuplicateAndOtherPositionsAConflict() throws Exception {
    createAllSecurities();
    commit(upload(depot, ShippedTemplateFixtures.swissquoteHoldings(), null));

    // PF-4 states the cash the PDF recorded already, and nothing else.
    ImportBatchResponse cash =
        upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), STATEMENT_DATE);
    assertThat(cash.holdings().snapshot()).isEqualTo(ImportSnapshotValues.ALREADY_RECORDED);
    assertThat(cash.holdings().explanation()).endsWith("Discard this import.");
    expectProblem(commitRequest(cash), 409, ProblemCode.IMPORT_SNAPSHOT_ALREADY_RECORDED);

    // PF-5 lists other securities (its tickers are mapped to none of the PDF's): a conflict, each
    // position named; nothing is overwritten.
    createWorkbookSecurities();
    ImportBatchResponse positions =
        upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), STATEMENT_DATE);
    ImportHoldingsResponse preview = positions.holdings();
    assertThat(preview.snapshot()).isEqualTo(ImportSnapshotValues.CONFLICTING);
    assertThat(preview.conflicts())
        .extracting(c -> c.part(), c -> c.existingValue() == null, c -> c.statementValue() == null)
        .containsOnly(
            tuple(ImportSnapshotValues.CONFLICT_HOLDING, false, true),
            tuple(ImportSnapshotValues.CONFLICT_HOLDING, true, false));
    assertThat(preview.conflicts()).hasSize(7);
    assertThat(preview.readyToCommit()).isFalse();
    expectProblem(commitRequest(positions), 409, ProblemCode.IMPORT_SNAPSHOT_CONFLICT);
    assertThat(snapshots(depot).get(0).holdings()).hasSize(4);
  }

  @Test
  void aRollbackRemovesOnlyItsOwnPartAndTheSnapshotPassesToTheRemainingImport() throws Exception {
    createWorkbookSecurities();
    ImportBatchResponse positions =
        commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));
    ImportBatchResponse cash =
        commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));

    // The cash import goes: the positions stay, without a balance.
    ImportRollbackResponse cashGone = rollback(get(depot, cash.id()));
    assertThat(cashGone.batch().holdings().snapshot()).isEqualTo(ImportSnapshotValues.REMOVED);
    AccountSnapshotResponse positionsOnly = snapshots(depot).get(0);
    assertThat(positionsOnly.balance()).isNull();
    assertThat(positionsOnly.holdings()).hasSize(3);

    // The cash again, then the positions import goes: the balance stays, and so does the
    // snapshot, now the cash import's.
    ImportBatchResponse cashAgain =
        commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));
    rollback(get(depot, positions.id()));
    AccountSnapshotResponse cashOnly = snapshots(depot).get(0);
    assertThat(cashOnly.balance()).isEqualByComparingTo("1204.35");
    assertThat(cashOnly.holdings()).isEmpty();
    assertThat(rowsOf("SELECT import_batch_id FROM account_snapshot WHERE account_id = ?", depot))
        .singleElement()
        .satisfies(row -> assertThat(row.get("import_batch_id")).isEqualTo(cashAgain.id()));

    // The last part goes: no snapshot remains.
    ImportRollbackResponse last = rollback(get(depot, cashAgain.id()));
    assertThat(last.batch().holdings().snapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_DELETED);
    assertThat(snapshots(depot)).isEmpty();
  }

  @Test
  void aCashStatementsBalanceCompletesAHoldingsOnlySnapshot() throws Exception {
    createWorkbookSecurities();
    commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));
    UUID template = createCashTemplate();

    ImportBatchResponse statement = uploadWithTemplate(depot, CASH_STATEMENT, template);
    ImportStatementBalanceResponse preview = statement.statementBalance();
    assertThat(preview.snapshot()).isEqualTo(ImportSnapshotValues.COMPLETES);
    assertThat(preview.adds()).containsExactly(ImportSnapshotValues.PART_BALANCE);
    assertThat(preview.existingSnapshot().holdingsCount()).isEqualTo(3);
    assertThat(preview.explanation())
        .startsWith("This statement adds the cash balance to the snapshot of");

    ImportBatchResponse committed = commit(statement);

    assertThat(committed.statementBalance().snapshot()).isEqualTo(ImportSnapshotValues.COMPLETED);
    AccountSnapshotResponse snapshot = snapshots(depot).get(0);
    assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
    assertThat(snapshot.holdings()).hasSize(3);
  }

  @Test
  void aHoldingsStatementCompletesACashStatementsBalanceOnlySnapshot() throws Exception {
    createWorkbookSecurities();
    UUID template = createCashTemplate();
    ImportBatchResponse cash = commit(uploadWithTemplate(depot, CASH_STATEMENT, template));
    assertThat(cash.statementBalance().snapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_RECORDED);

    ImportBatchResponse positions =
        upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE);
    ImportHoldingsResponse preview = positions.holdings();
    assertThat(preview.snapshot()).isEqualTo(ImportSnapshotValues.COMPLETES);
    assertThat(preview.adds()).containsExactly(ImportSnapshotValues.PART_HOLDINGS);
    // The snapshot is named by the file of the import that recorded it.
    assertThat(preview.existingSnapshot().importFileName()).isEqualTo("statement.csv");
    assertThat(preview.explanation())
        .isEqualTo(
            "This statement adds the positions to the snapshot of Nov 30, 2031 you imported"
                + " before from “statement.csv”.");

    commit(positions);

    assertThat(
            rowsOf(
                "SELECT import_batch_id, balance_import_batch_id, balance FROM account_snapshot"
                    + " WHERE account_id = ?",
                depot))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("import_batch_id")).isEqualTo(cash.id());
              assertThat(row.get("balance_import_batch_id")).isEqualTo(cash.id());
              assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("1204.35");
            });
    assertThat(
            count(
                "SELECT count(*) FROM snapshot_holding WHERE import_batch_id = ?", positions.id()))
        .isEqualTo(3);
  }

  // --- reconciliation decisions --------------------------------------------------------------
  // An account that holds positions is never reconciled (cash scope only, US-25-02): a decision
  // stands on a snapshot of one only from before it held them, so these tests write it directly.

  @Test
  void aStandingDecisionKeepsTheSnapshotFromBeingCompletedUntilANewerBalanceFinalizesIt()
      throws Exception {
    createWorkbookSecurities();
    commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));
    UUID decision = dismissedResult(documentSnapshotId());

    ImportBatchResponse positions =
        upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE);
    ImportHoldingsResponse preview = positions.holdings();
    // The preview says beforehand what the commit then refuses.
    assertThat(preview.snapshot()).isEqualTo(ImportSnapshotValues.COMPLETES);
    assertThat(preview.existingSnapshot().reconciliationResultId()).isEqualTo(decision);
    assertThat(preview.readyToCommit()).isFalse();
    assertThat(preview.explanation())
        .startsWith("A reconciliation decision covers the snapshot of Nov 30, 2031 as it is.");
    expectProblem(
        commitRequest(positions), 409, ProblemCode.IMPORT_SNAPSHOT_RECONCILIATION_DECIDED);
    assertThat(count("SELECT count(*) FROM snapshot_holding")).isZero();

    // A newer balance finalizes the decision: it can never be reopened, so it no longer blocks.
    recordSnapshot(EXPORT_DATE.plusDays(10), "1300.00");
    ImportBatchResponse again = get(depot, positions.id());
    assertThat(again.holdings().existingSnapshot().reconciliationResultId()).isNull();
    assertThat(again.holdings().readyToCommit()).isTrue();
    assertThat(commit(again).holdings().snapshot()).isEqualTo(ImportSnapshotValues.COMPLETED);
    assertThat(count("SELECT count(*) FROM snapshot_holding")).isEqualTo(3);
  }

  @Test
  void aBalanceKeptAsHistoryLeavesTheOtherImportsPositionsInEffect() throws Exception {
    createWorkbookSecurities();
    ImportBatchResponse cash =
        commit(upload(depot, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE));
    UUID recorded = documentSnapshotId();
    dismissedResult(recorded);
    recordSnapshot(EXPORT_DATE.plusDays(10), "1300.00");
    ImportBatchResponse positions =
        commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));

    // The positions go: the final decision covers the balance only, which stays with it.
    ImportRollbackResponse positionsGone = rollback(get(depot, positions.id()));
    assertThat(positionsGone.batch().holdings().snapshot()).isEqualTo(ImportSnapshotValues.REMOVED);
    assertThat(documentSnapshotId()).isEqualTo(recorded);
    assertThat(count("SELECT count(*) FROM snapshot_holding")).isZero();
    assertThat(count("SELECT count(*) FROM reconciliation_result WHERE status = 'DISMISSED'"))
        .isEqualTo(1);

    // The positions again, then the cash goes: its balance is kept as history with the decision,
    // and the positions stay in effect on a snapshot of the date that is now theirs.
    ImportBatchResponse positionsAgain =
        commit(upload(depot, ShippedTemplateFixtures.postfinancePositionsWorkbook(), EXPORT_DATE));
    ImportRollbackResponse cashGone = rollback(get(depot, cash.id()));

    assertThat(cashGone.batch().holdings().snapshot())
        .isEqualTo(ImportBatchValues.SNAPSHOT_WITHDRAWN);
    assertThat(
            rowsOf(
                "SELECT import_batch_id, balance, withdrawn_at FROM account_snapshot WHERE id = ?",
                recorded))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("import_batch_id")).isEqualTo(cash.id());
              assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("1204.35");
              assertThat(row.get("withdrawn_at")).isNotNull();
            });
    UUID successor = documentSnapshotId();
    assertThat(successor).isNotEqualTo(recorded);
    assertThat(
            rowsOf("SELECT import_batch_id, balance FROM account_snapshot WHERE id = ?", successor))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("import_batch_id")).isEqualTo(positionsAgain.id());
              assertThat(row.get("balance")).isNull();
            });
    assertThat(
            count(
                "SELECT count(*) FROM snapshot_holding WHERE snapshot_id = ? AND import_batch_id = ?",
                successor,
                positionsAgain.id()))
        .isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM reconciliation_result WHERE snapshot_id = ?"
                    + " AND status = 'DISMISSED'",
                recorded))
        .isEqualTo(1);

    // The last import goes: no imported snapshot remains in effect.
    ImportRollbackResponse last = rollback(get(depot, positionsAgain.id()));
    assertThat(last.batch().holdings().snapshot()).isEqualTo(ImportBatchValues.SNAPSHOT_DELETED);
    assertThat(
            count(
                "SELECT count(*) FROM account_snapshot WHERE source = 'DOCUMENT'"
                    + " AND withdrawn_at IS NULL"))
        .isZero();
  }

  // --- refusals ------------------------------------------------------------------------------

  @Test
  void positionsNeedAnAccountThatHoldsThemAndADateIsOnlyForAStatementWithout() throws Exception {
    UUID current = createAccount("Girokonto", false);
    expectProblem(
        uploadRequest(current, ShippedTemplateFixtures.swissquoteHoldings(), null), 422, null);
    assertThat(count("SELECT count(*) FROM import_batch")).isZero();

    expectProblem(
        uploadRequest(depot, ShippedTemplateFixtures.swissquoteHoldings(), STATEMENT_DATE),
        400,
        null);
  }

  // #614: like the manual snapshot API, only an account that holds positions keeps foreign cash.
  @Test
  void anAccountWithoutPositionsRecordsOnlyItsOwnCurrencyCash() throws Exception {
    UUID current = createAccount("Girokonto", false);
    ImportBatchResponse preview =
        upload(current, ShippedTemplateFixtures.postfinanceCashWorkbook(), EXPORT_DATE);
    assertThat(preview.holdings().cashBalances())
        .extracting(ImportHoldingsResponse.Cash::currency, ImportHoldingsResponse.Cash::recorded)
        .containsExactly(tuple("CHF", true), tuple("USD", false));

    commit(preview);
    AccountSnapshotResponse snapshot = snapshots(current).get(0);
    assertThat(snapshot.balance()).isEqualByComparingTo("1204.35");
    assertThat(snapshot.foreignCashBalances()).isEmpty();
    assertThat(count("SELECT count(*) FROM snapshot_foreign_cash")).isZero();
  }

  @Test
  void aHoldingsTemplateHasNoDryRunAndATickerMeansASecurityInOneWorkspaceOnly() throws Exception {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", file(ShippedTemplateFixtures.swissquoteHoldings(), "statement.pdf"));
    expectProblem(
        client()
            .post()
            .uri("/api/v1/import-templates/" + SWISSQUOTE + "/test")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .exchange(),
        422,
        ProblemCode.IMPORT_TEMPLATE_UNSUPPORTED);

    UUID coin = createSecurity(null, ShippedTemplateFixtures.HOLDING_CRYPTO, "CRYPTO", "CHF");
    assertThat(
            rowsOf(
                "SELECT ticker, security_id FROM workspace_security_ticker WHERE security_id = ?",
                coin))
        .singleElement()
        .satisfies(row -> assertThat(row.get("ticker")).isEqualTo("XQZ"));
    // The shared security row carries nothing of the workspace's mapping.
    assertThat(count("SELECT count(*) FROM security_identifier")).isZero();
  }

  // --- helpers -------------------------------------------------------------------------------

  // The depot's imported snapshot of the export date in effect.
  private UUID documentSnapshotId() {
    return (UUID)
        rowsOf(
                "SELECT id FROM account_snapshot WHERE account_id = ? AND snapshot_date = ?"
                    + " AND source = 'DOCUMENT' AND withdrawn_at IS NULL",
                depot,
                EXPORT_DATE)
            .get(0)
            .get("id");
  }

  // A member's dismissal of the snapshot's reconciliation, written as the decision would be.
  private UUID dismissedResult(UUID snapshotId) {
    return (UUID)
        rowsOf(
                "INSERT INTO reconciliation_result (workspace_id, account_id, snapshot_id,"
                    + " difference_amount, status, resolution_note, resolved_at)"
                    + " SELECT workspace_id, account_id, id, 12.40, 'DISMISSED', 'Checked', now()"
                    + " FROM account_snapshot WHERE id = ? RETURNING id",
                snapshotId)
            .get(0)
            .get("id");
  }

  // A snapshot recorded by hand, newer than the imported one.
  private void recordSnapshot(LocalDate date, String balance) {
    client()
        .post()
        .uri("/api/v1/accounts/" + depot + "/snapshots")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RecordAccountSnapshotRequest(date, new BigDecimal(balance), List.of()))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private void createAllSecurities() {
    createSecurity(ShippedTemplateFixtures.HOLDING_ISIN_CHF, null, "EQUITY", "CHF");
    createSecurity(ShippedTemplateFixtures.HOLDING_ISIN_USD, null, "EQUITY", "USD");
    createSecurity(ShippedTemplateFixtures.HOLDING_ISIN_FUND, null, "ETF", "CHF");
    createSecurity(null, ShippedTemplateFixtures.HOLDING_CRYPTO, "CRYPTO", "CHF");
  }

  // PF-5's tickers, each a security of its own.
  private void createWorkbookSecurities() {
    createSecurity(null, ShippedTemplateFixtures.WORKBOOK_TICKER_CHF, "EQUITY", "CHF");
    createSecurity(null, ShippedTemplateFixtures.WORKBOOK_TICKER_USD, "EQUITY", "USD");
    createSecurity(null, ShippedTemplateFixtures.WORKBOOK_TICKER_ETF, "ETF", "CHF");
  }

  // A DKB-like cash statement of the depot, stating its balance of the export date (US-07-12).
  private static final String CASH_STATEMENT =
      """
      Kontostand vom 30.11.2031: 1.204,35 €
      Datum;Betrag;Waehrung;Text;Referenz
      15.11.31;-12,40;CHF;Invented fee;R1
      """;

  private UUID createCashTemplate() {
    return client()
        .post()
        .uri("/api/v1/import-templates")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new ImportTemplateRequest(
                "Invented cash statement",
                null,
                null,
                ";",
                "UTF-8",
                ",",
                ".",
                "dd.MM.yy",
                0,
                1,
                0,
                "SINGLE_SIGNED_COLUMN",
                "PER_ROW",
                null,
                new ImportColumnMapping(
                    "Datum",
                    null,
                    "Betrag",
                    null,
                    null,
                    "Waehrung",
                    "Text",
                    null,
                    "Referenz",
                    null,
                    null,
                    null,
                    null),
                Map.of(),
                null,
                List.of("Datum", "Betrag", "Waehrung", "Text", "Referenz"),
                null,
                null,
                Map.of(),
                null,
                null,
                new ImportStatementBalanceRule(
                    "^Kontostand vom (?P<date>[0-9.]+): (?P<amount>-?[0-9.]+,[0-9]{2}) €$",
                    "dd.MM.yyyy")))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private ImportBatchResponse uploadWithTemplate(UUID account, String content, UUID template) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", file(content.getBytes(StandardCharsets.UTF_8), "statement.csv"));
    body.add("templateId", template.toString());
    return client()
        .post()
        .uri(imports(account))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private UUID createSecurity(String isin, String ticker, String type, String currency) {
    return client()
        .post()
        .uri("/api/v1/securities")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreateSecurityRequest(
                isin,
                "Invented " + (isin == null ? ticker : isin),
                currency,
                type,
                "CRYPTO".equals(type) ? "CRYPTOCURRENCY" : "EQUITY",
                null,
                null,
                null,
                null,
                ticker))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(SecurityResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private ImportBatchResponse upload(UUID account, byte[] content, LocalDate snapshotDate) {
    return uploadRequest(account, content, snapshotDate)
        .expectStatus()
        .isCreated()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private RestTestClient.ResponseSpec uploadRequest(
      UUID account, byte[] content, LocalDate snapshotDate) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", file(content, "statement"));
    if (snapshotDate != null) {
      body.add("snapshotDate", snapshotDate.toString());
    }
    return client()
        .post()
        .uri(imports(account))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange();
  }

  private static HttpEntity<ByteArrayResource> file(byte[] content, String name) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    headers.setContentDispositionFormData("file", name);
    return new HttpEntity<>(new ByteArrayResource(content), headers);
  }

  private RestTestClient.ResponseSpec commitRequest(ImportBatchResponse batch) {
    return client()
        .post()
        .uri(imports(batch.accountId()) + "/" + batch.id() + "/commit")
        .header(HttpHeaders.IF_MATCH, etag(batch.version()))
        .exchange();
  }

  private ImportBatchResponse commit(ImportBatchResponse batch) {
    return commitRequest(batch)
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportRollbackResponse rollback(ImportBatchResponse batch) {
    return client()
        .post()
        .uri(imports(batch.accountId()) + "/" + batch.id() + "/rollback")
        .header(HttpHeaders.IF_MATCH, etag(batch.version()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ImportRollbackRequest("Imported the wrong statement"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportRollbackResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportBatchResponse get(UUID account, UUID batchId) {
    return client()
        .get()
        .uri(imports(account) + "/" + batchId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportBatchResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<AccountSnapshotResponse> snapshots(UUID account) {
    return client()
        .get()
        .uri("/api/v1/accounts/" + account + "/snapshots")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(new ParameterizedTypeReference<PageResponse<AccountSnapshotResponse>>() {})
        .returnResult()
        .getResponseBody()
        .content();
  }

  private static void expectProblem(
      RestTestClient.ResponseSpec response, int status, ProblemCode code) {
    @SuppressWarnings("unchecked")
    Map<String, Object> problem =
        response
            .expectStatus()
            .isEqualTo(status)
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    if (code != null) {
      assertThat(problem).containsEntry("code", code.name());
    }
  }

  private UUID createAccount(String name, boolean holdsPositions) {
    return client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            AccountRequests.account(name, holdsPositions ? "SECURITIES" : "CASH", "CHF")
                .holdsPositions(holdsPositions)
                .build())
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody()
        .id();
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SetupAdministratorRequest("admin@example.com", PASSWORD, "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private static String imports(UUID account) {
    return "/api/v1/accounts/" + account + "/imports";
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }

  private static String etag(Object version) {
    return "\"" + version + "\"";
  }

  private AccountListItemResponse listedDepot() {
    AccountListItemResponse[] accounts =
        client()
            .get()
            .uri("/api/v1/accounts")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AccountListItemResponse[].class)
            .returnResult()
            .getResponseBody();
    assertThat(accounts).singleElement().extracting(AccountListItemResponse::id).isEqualTo(depot);
    return accounts[0];
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private long count(String sql, Object... parameters) {
    return ((Number) rowsOf(sql, parameters).get(0).values().iterator().next()).longValue();
  }

  private List<Map<String, Object>> rowsOf(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int columns = resultSet.getMetaData().getColumnCount();
        while (resultSet.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= columns; i++) {
            row.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
