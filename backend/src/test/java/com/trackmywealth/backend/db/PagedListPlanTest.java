package com.trackmywealth.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.controller.AccountSnapshotController;
import com.trackmywealth.backend.controller.AmortisationScheduleController;
import com.trackmywealth.backend.controller.CustomAssetValuationController;
import com.trackmywealth.backend.controller.ImportBatchController;
import com.trackmywealth.backend.controller.InstitutionCatalogueController;
import com.trackmywealth.backend.controller.LoanRepaymentController;
import com.trackmywealth.backend.controller.ReconciliationController;
import com.trackmywealth.backend.controller.SettlementMatchController;
import com.trackmywealth.backend.controller.TransactionController;
import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.repository.AccountSnapshotRepository;
import com.trackmywealth.backend.repository.AmortisationScheduleEntryRepository;
import com.trackmywealth.backend.repository.CustomAssetValuationRepository;
import com.trackmywealth.backend.repository.ImportBatchRepository;
import com.trackmywealth.backend.repository.InstitutionCatalogueRepository;
import com.trackmywealth.backend.repository.LoanPaymentRepository;
import com.trackmywealth.backend.repository.ReconciliationResultRepository;
import com.trackmywealth.backend.repository.SettlementMatchRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import com.trackmywealth.backend.web.Paged;
import com.trackmywealth.backend.web.SortWhitelist;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-29-06 (#395), US-29-13 (#473): every paged list's page and count query, as Hibernate generates
 * them from the repositories, is read through a V115 or V116 index in page order - no {@code Sort}
 * node. The settlement-match queue is the exception: it is checked to stay inside the caller's
 * workspace, and may sort that workspace's own queue.
 *
 * <p>The reads go through the repositories with the order the controllers' {@code @Paged}
 * whitelists resolve, so a changed derived query, entity restriction or default sort is planned as
 * it ships. PostgreSQL's {@code auto_explain} logs each statement's {@code EXPLAIN (ANALYZE,
 * BUFFERS)} to the server log; the test reads it back from the container's output between two
 * marker statements.
 *
 * <p>The reads run as a {@code NOSUPERUSER NOBYPASSRLS} role in the workspace context, as the
 * runtime role does: a superuser bypasses row-level security, and the policy's workspace condition
 * decides between V114's and V115's indexes (see V115's comment). One test reads as the superuser
 * on purpose, for the single-role installation V115's account-led index serves.
 *
 * <p>The image is pinned to a minor release, unlike the other tests' {@code postgres:16}: the
 * assertions follow the planner's cost model, and a minor release may change it. Raise it
 * deliberately and run the class a few times when doing so.
 */
@Testcontainers
@SpringBootTest
class PagedListPlanTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16.15")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth")
          .withCommand(
              "postgres", "-c", "fsync=off", "-c", "session_preload_libraries=auto_explain");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private static final String RUNTIME_ROLE = "paged_plan_runtime";
  // V20's policy calls current_workspace_id(), which PostgreSQL inlines into the plan's filter.
  private static final String RLS_CONTEXT = "current_setting('app.current_workspace_id'";
  private static final String PLAN_ENTRY = "LOG:  duration: ";
  private static final int PAGE_SIZE = 50;
  private static final Duration LOG_WAIT = Duration.ofSeconds(10);
  private static final Pattern SETTLEMENT_MATCH_INDEX =
      Pattern.compile("\\b(?:idx|uq)_settlement_match_\\w+");

  // Seeded by the first test to run (the schema exists once the context has started).
  private static PagedListFixture.Ids fixture;

  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;
  @Autowired TransactionRepository transactions;
  @Autowired AccountSnapshotRepository snapshots;
  @Autowired CustomAssetValuationRepository valuations;
  @Autowired AmortisationScheduleEntryRepository schedule;
  @Autowired LoanPaymentRepository repayments;
  @Autowired SettlementMatchRepository settlementMatches;
  @Autowired ImportBatchRepository importBatches;
  @Autowired ReconciliationResultRepository reconciliations;
  @Autowired InstitutionCatalogueRepository catalogue;

  @BeforeEach
  void seedOnce() throws SQLException {
    if (fixture != null) {
      return;
    }
    try (Connection admin =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      RowLevelSecurityRole.create(admin, RUNTIME_ROLE);
      fixture = PagedListFixture.seed(admin);
      PagedListFixture.settle(admin);
    }
  }

  @Test
  void plansAreMeasuredUnderRowLevelSecurity() {
    Plans plans = asRuntimeRole(() -> transactions.findByAccountId(fixture.account(), ledger(0)));

    assertThat(plans.page()).contains(RLS_CONTEXT);
    assertThat(plans.count()).contains(RLS_CONTEXT);
  }

  @Test
  void ledgerUsesAnOrderedAccountIndexOnFirstAndDeepPages() {
    // page 500 of 50 = offset 25,000. Either V115 ledger index is in page order; their costs are
    // close enough that the planner's choice between them varies with the table's physical state.
    for (int page : new int[] {0, 500}) {
      Plans plans =
          asRuntimeRole(() -> transactions.findByAccountId(fixture.account(), ledger(page)));

      assertThat(plans.page())
          .as("ledger page %d", page)
          .containsAnyOf(
              "idx_transaction_account_ledger_order", "idx_transaction_account_date_order");
      assertSortFree(plans);
      assertIndexOnlyCount(plans);
    }
  }

  @Test
  void accountCurrencyAmountSortUsesItsOwnIndexWithoutSorting() {
    Pageable byAmount =
        PageRequest.of(
            0, PAGE_SIZE, sort(TransactionController.class, "listTransactions", "amount,desc"));

    Plans plans = asRuntimeRole(() -> transactions.findByAccountId(fixture.account(), byAmount));

    assertThat(plans.page()).contains("idx_transaction_account_amount_order");
    assertSortFree(plans);
  }

  @Test
  void uncategorizedLedgerUsesItsAccountCategoryIndexWithoutSorting() {
    // 500 uncategorized rows: page 9 is the last.
    for (int page : new int[] {0, 9}) {
      Plans plans =
          asRuntimeRole(
              () ->
                  transactions
                      .findByAccountIdAndCategoryIdAndVoidedAtIsNullAndReplacesTransactionIdIsNull(
                          fixture.account(), fixture.uncategorized(), ledger(page)));

      assertThat(plans.page())
          .as("uncategorized page %d", page)
          .contains("idx_transaction_account_category_order");
      assertSortFree(plans);
      assertIndexOnlyCount(plans);
    }
  }

  @Test
  void snapshotHistoryReadsTheAccountOrderIndexUnderRowLevelSecurity() {
    Pageable page =
        PageRequest.of(0, PAGE_SIZE, sort(AccountSnapshotController.class, "listSnapshots"));
    Plans plans =
        asRuntimeRole(() -> snapshots.findByAccountIdAndWithdrawnAtIsNull(fixture.account(), page));

    assertThat(plans.page()).contains("idx_account_snapshot_history_order");
    assertSortFree(plans);
  }

  // US-29-13 (#473): the lists V116 indexes, each read as its service reads it.

  @Test
  void valuationHistoryReadsItsAccountOrderIndex() {
    Pageable page =
        PageRequest.of(0, PAGE_SIZE, sort(CustomAssetValuationController.class, "listValuations"));

    Plans plans = asRuntimeRole(() -> valuations.findByAccountId(fixture.asset(), page));

    assertThat(plans.page()).contains("idx_custom_asset_valuation_history_order");
    assertSortFree(plans);
  }

  @Test
  void amortisationScheduleReadsItsAccountOrderIndex() {
    Pageable page =
        PageRequest.of(0, PAGE_SIZE, sort(AmortisationScheduleController.class, "listEntries"));

    Plans plans = asRuntimeRole(() -> schedule.findByAccountId(fixture.loan(), page));

    assertThat(plans.page()).contains("idx_amortisation_schedule_order");
    assertSortFree(plans);
  }

  @Test
  void repaymentHistoryReadsTheWorkspaceLedLoanIndexUnderRowLevelSecurity() {
    Pageable page =
        PageRequest.of(0, PAGE_SIZE, sort(LoanRepaymentController.class, "listRepayments"));

    Plans plans =
        asRuntimeRole(
            () -> repayments.findVisibleInEffect(fixture.loan(), Set.of(fixture.account()), page));

    assertThat(plans.page()).contains(RLS_CONTEXT).contains("idx_loan_payment_history_order");
    assertSortFree(plans);
  }

  @Test
  void settlementQueueReadsOnlyTheCallersWorkspaceThroughAWorkspaceLedIndex() {
    // The other tenant holds 20,000 PROPOSED matches: a status-led index would walk them.
    Pageable page = PageRequest.of(0, PAGE_SIZE, sort(SettlementMatchController.class, "list"));

    Plans plans =
        asRuntimeRole(
            () ->
                settlementMatches.findActionable(
                    SettlementMatchValues.PROPOSED,
                    null,
                    null,
                    Set.of(fixture.account(), fixture.card()),
                    page));

    assertSettlementMatchesReadWithinTheWorkspace(plans);
  }

  @Test
  void oneCardsSettlementQueueReadsOnlyThatCardsMatches() {
    Pageable page = PageRequest.of(0, PAGE_SIZE, sort(SettlementMatchController.class, "list"));

    Plans plans =
        asRuntimeRole(
            () ->
                settlementMatches.findActionable(
                    SettlementMatchValues.PROPOSED,
                    null,
                    fixture.card(),
                    Set.of(fixture.account(), fixture.card()),
                    page));

    assertSettlementMatchesReadWithinTheWorkspace(plans);
  }

  @Test
  void restoreListReadsOnlyTheAccountsRemovedRowsThroughItsPartialIndex() {
    // 1,000 removed rows of the account, half deleted and half voided, beside the account-wide
    // ledgers of 50,000 rows; a fixed order, so the page is unsorted
    // (PagingValues.requireBoundedFixedOrder). The partial index holds removed rows only, so the
    // page and its count read the account's restore list, never its ledger. Whether PostgreSQL
    // then follows the index order or sorts that list depends on its estimate of how many removed
    // rows the account has (removals cluster in one account after a rollback); either way the work
    // is bounded by the list itself (#473 review).
    OffsetDateTime since = OffsetDateTime.now().minusDays(30);
    for (int number : new int[] {0, PagedListFixture.REMOVED_ROWS / PAGE_SIZE - 1}) {
      Pageable page = PageRequest.of(number, PAGE_SIZE);

      Plans plans =
          asRuntimeRole(
              () -> transactions.findRestorableByAccountIdSince(fixture.rolledBack(), since, page));

      for (String plan : List.of(plans.page(), plans.count())) {
        assertThat(plan)
            .as("restore page %d", number)
            .contains(RLS_CONTEXT)
            .contains("idx_transaction_restorable_order")
            .doesNotContain("Seq Scan on transaction t ");
      }
    }
  }

  @Test
  void importAndCashReconciliationHistoryHaveIndexBackedOrdering() {
    Pageable batchPage = PageRequest.of(0, PAGE_SIZE, sort(ImportBatchController.class, "list"));
    Pageable reconciliationPage =
        PageRequest.of(0, PAGE_SIZE, sort(ReconciliationController.class, "list"));

    Plans batches =
        asRuntimeRole(() -> importBatches.findByAccountId(fixture.account(), batchPage));
    Plans history =
        asRuntimeRole(
            () ->
                reconciliations.findByAccountIdAndAffectedSecurityIdIsNull(
                    fixture.account(), reconciliationPage));

    assertThat(batches.page()).contains("idx_import_batch_account_order");
    assertSortFree(batches);
    assertThat(history.page()).contains("idx_reconciliation_cash_history_order");
    assertSortFree(history);
  }

  @Test
  void activeInstitutionCatalogueHasIndexBackedNameAndIdOrder() {
    Pageable page =
        PageRequest.of(0, PAGE_SIZE, sort(InstitutionCatalogueController.class, "searchCatalogue"));

    Plans plans = asRuntimeRole(() -> catalogue.findByActiveTrue(page));

    assertThat(plans.page()).contains("idx_institution_catalogue_active_name_order");
    assertSortFree(plans);
  }

  @Test
  void aRoleThatBypassesRowLevelSecurityReadsTheLedgerThroughTheAccountLedIndex() {
    // A single-role installation: no policy, so no workspace condition for V115's workspace-led
    // indexes. idx_transaction_account_date_order is what keeps its ledger sort-free.
    for (int page : new int[] {0, 500}) {
      Plans plans =
          capture(null, () -> transactions.findByAccountId(fixture.account(), ledger(page)));

      assertThat(plans.page())
          .as("ledger page %d", page)
          .doesNotContain(RLS_CONTEXT)
          .contains("idx_transaction_account_date_order");
      assertSortFree(plans);
    }
  }

  private static Pageable ledger(int page) {
    return PageRequest.of(page, PAGE_SIZE, sort(TransactionController.class, "listTransactions"));
  }

  /**
   * The order {@code controller}'s {@code method} resolves for {@code requested} (none: default).
   */
  private static org.springframework.data.domain.Sort sort(
      Class<?> controller, String method, String... requested) {
    for (Method candidate : controller.getDeclaredMethods()) {
      if (!candidate.getName().equals(method)) {
        continue;
      }
      for (Parameter parameter : candidate.getParameters()) {
        Paged paged = parameter.getAnnotation(Paged.class);
        if (paged != null) {
          return SortWhitelist.of(paged).resolve(Arrays.asList(requested));
        }
      }
    }
    throw new IllegalStateException(controller.getSimpleName() + "#" + method + " is not @Paged");
  }

  /**
   * Every read of {@code settlement_match} goes through an index that starts with the caller's
   * workspace or with one of its cards (a card belongs to one workspace), never a scan that reaches
   * the other tenant's 20,000 proposals. The queue may still be sorted: with {@code
   * m.cardAccount.id IN :accountIds} PostgreSQL often reads the caller's matches card by card and
   * sorts them, which the caller's own pending matches bound, not the database (#473 review).
   */
  private static void assertSettlementMatchesReadWithinTheWorkspace(Plans plans) {
    String all = String.join("\n", plans.all());
    Matcher indexes = SETTLEMENT_MATCH_INDEX.matcher(all);
    Set<String> used = new TreeSet<>();
    while (indexes.find()) {
      used.add(indexes.group());
    }
    assertThat(all).contains(RLS_CONTEXT).doesNotContain("Seq Scan on settlement_match");
    assertThat(used)
        .as("settlement_match indexes read")
        .isNotEmpty()
        .isSubsetOf(
            "idx_settlement_match_queue_order",
            "idx_settlement_match_card_queue_order",
            "idx_settlement_match_card");
  }

  private static void assertSortFree(Plans plans) {
    for (String plan : plans.all()) {
      assertThat(plan).doesNotContain("Sort  (", "Sort Key:", "Incremental Sort");
    }
  }

  // The count runs on every page that is full: the visibility map is current (as autovacuum keeps
  // it), so the partial index answers it without reading the table.
  private static void assertIndexOnlyCount(Plans plans) {
    assertThat(plans.count())
        .contains("Index Only Scan", "Heap Fetches: 0")
        .doesNotContain("Seq Scan", "Bitmap Heap Scan");
  }

  private Plans asRuntimeRole(Supplier<?> read) {
    return capture(RUNTIME_ROLE, read);
  }

  /**
   * Runs {@code read} in one transaction, as {@code role} in the fixture's workspace ({@code null}:
   * as the connecting superuser, without a workspace), with every statement's plan logged, and
   * returns the plans logged for {@code read}. Rolled back, so the role and settings end with it.
   * auto_explain's settings are superuser-only, so they are made before the role is switched.
   */
  private Plans capture(String role, Supplier<?> read) {
    String marker = "plan-capture-" + UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              if (role != null) {
                entityManager
                    .createNativeQuery("SELECT set_config('app.current_workspace_id', ?1, true)")
                    .setParameter(1, fixture.workspace().toString())
                    .getSingleResult();
              }
              execute("SET LOCAL auto_explain.log_min_duration = 0");
              execute("SET LOCAL auto_explain.log_analyze = on");
              execute("SET LOCAL auto_explain.log_buffers = on");
              mark(marker + "-start");
              if (role != null) {
                execute("SET LOCAL ROLE " + role);
              }
              read.get();
              mark(marker + "-end");
              status.setRollbackOnly();
            });
    return Plans.between(serverLog(marker + "-end"), marker);
  }

  private void execute(String sql) {
    entityManager.createNativeQuery(sql).executeUpdate();
  }

  private void mark(String marker) {
    entityManager.createNativeQuery("SELECT '" + marker + "'").getSingleResult();
  }

  // The container's output arrives a moment after the statement; wait for the end marker.
  private static String serverLog(String until) {
    Instant deadline = Instant.now().plus(LOG_WAIT);
    String log = postgres.getLogs();
    while (!log.contains(until) && Instant.now().isBefore(deadline)) {
      Thread.onSpinWait();
      log = postgres.getLogs();
    }
    assertThat(log).as("auto_explain output").contains(until);
    return log;
  }

  /** The plans auto_explain logged for one read: its page query and, for a full page, its count. */
  private record Plans(List<String> all) {

    static Plans between(String log, String marker) {
      String window = log.substring(log.indexOf(marker + "-start"), log.indexOf(marker + "-end"));
      List<String> plans = new ArrayList<>();
      for (String entry : window.split(PLAN_ENTRY)) {
        // The window ends inside the end marker's own entry, cut before the marker: an entry
        // without a plan node is that remnant, not a read.
        if (entry.contains("Query Text:") && entry.contains("(cost=") && !entry.contains(marker)) {
          plans.add(entry);
        }
      }
      assertThat(plans).as("plans logged for the read").isNotEmpty();
      return new Plans(List.copyOf(plans));
    }

    String page() {
      return all.stream()
          .filter(plan -> !isCount(plan))
          .findFirst()
          .orElseThrow(() -> new AssertionError("No page query among " + all));
    }

    String count() {
      return all.stream()
          .filter(Plans::isCount)
          .findFirst()
          .orElseThrow(() -> new AssertionError("No count query among " + all));
    }

    private static boolean isCount(String plan) {
      String query = plan.substring(plan.indexOf("Query Text:"));
      // Hibernate writes count(, a native count query (the restore list's) COUNT(.
      return query.substring(0, query.indexOf('\n')).toLowerCase(Locale.ROOT).contains("count(");
    }
  }
}
