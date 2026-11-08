package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.AccountSummaryResponse;
import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.CreateAccountRequest;
import com.trackmywealth.backend.dto.CreateCustomAssetValuationRequest;
import com.trackmywealth.backend.dto.CreatePortfolioRequest;
import com.trackmywealth.backend.dto.CreateUserRequest;
import com.trackmywealth.backend.dto.CustomAssetValuationResponse;
import com.trackmywealth.backend.dto.InstitutionCatalogueEntrySummaryResponse;
import com.trackmywealth.backend.dto.LoginRequest;
import com.trackmywealth.backend.dto.LoginResponse;
import com.trackmywealth.backend.dto.PageResponse;
import com.trackmywealth.backend.dto.PortfolioResponse;
import com.trackmywealth.backend.dto.PortfolioSummaryResponse;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.dto.UserSummaryResponse;
import com.trackmywealth.backend.entity.FxRate;
import com.trackmywealth.backend.repository.FxRateRepository;
import com.trackmywealth.backend.repository.PortfolioRepository;
import com.trackmywealth.backend.testsupport.AccountRequests;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-04-01: search the shared institution catalogue and create a workspace's own financial
 * institution, both from a catalogue entry and as a custom one. {@code institution_catalogue} is
 * shared, non-tenant-scoped seed data (V19) - never truncated between tests, unlike every
 * workspace-scoped table below.
 *
 * <p>US-04-03's DoD scenario ({@link #summaryAggregatesAssetsAndLiabilitiesIntoANegativeNetValue})
 * substitutes a {@code CUSTOM_ASSET} for the story's own "current account" example and a {@code
 * MORTGAGE} for its liability, since neither cash-account balances (EPIC 07) nor amortized loan
 * balances (EPIC 10) exist in this codebase yet - see {@code PortfolioService.getSummary}'s own
 * Javadoc. The numbers (EUR 2,000 asset, EUR 300,000 liability, EUR -298,000 net) are the story's
 * own, so this is still the literal scenario, just backed by the two account types that actually
 * have a real value source today.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortfolioControllerTest {

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

  private static final String SECOND_MEMBER_PASSWORD = "correct-horse-battery-staple";

  @LocalServerPort int port;

  @Autowired PortfolioRepository portfolioRepository;
  @Autowired FxRateRepository fxRateRepository;
  @Autowired DataSource dataSource;

  @BeforeEach
  void cleanDatabase() throws Exception {
    // Deliberately plain DELETEs, not TRUNCATE ... CASCADE (the pattern AdminUserControllerTest
    // uses): CASCADE here would also reach institution_catalogue - app_user is referenced by
    // reference_package.imported_by (V18), and institution_catalogue is itself a child of
    // reference_package (V18's fk_institution_catalogue_reference_package) - silently wiping the
    // shared, non-tenant-scoped seed catalogue (V19) this test depends on staying seeded across
    // every test method in this class. DELETE respects the actual (always-NULL here) row data
    // instead of TRUNCATE's blanket structural table-level check, so it never needs to touch
    // reference_package/institution_catalogue at all. Order matches FK dependency order (children
    // before parents) - no CASCADE needed.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String table :
          List.of(
              "fx_rate",
              "custom_asset_valuation",
              "account_custom_asset",
              "account_mortgage",
              "account",
              "admin_audit_log",
              "user_session",
              "refresh_token",
              "authorization_denial_log",
              "app_user",
              "workspace_member",
              "portfolio",
              "workspace")) {
        statement.execute("DELETE FROM " + table);
      }
    }
  }

  @Test
  void searchingCatalogueByNameSuggestsTheMatchingSeedInstitution() {
    String token = bootstrapAdministrator();

    List<InstitutionCatalogueEntrySummaryResponse> results =
        searchCatalogue(token, "Post").content();

    assertThat(results)
        .anySatisfy(
            entry -> {
              assertThat(entry.name()).isEqualTo("PostFinance");
              assertThat(entry.country()).isEqualTo("CH");
            });
  }

  @Test
  void searchingCatalogueWithNoQueryReturnsEveryActiveEntry() {
    String token = bootstrapAdministrator();

    // The V19 seed carries PostFinance, Yuh, VIAC, DKB, Sparkasse; V79 adds LBS Süd: six active
    // entries.
    assertThat(searchCatalogue(token, null).page().totalElements()).isEqualTo(6);
  }

  @Test
  void catalogueCountryFilterUsesServerSideCountAndSupportsCommaSeparatedValues() {
    String token = bootstrapAdministrator();
    PageResponse<InstitutionCatalogueEntrySummaryResponse> swiss =
        catalogue(token, "?country=CH&page=0&size=2");
    assertThat(swiss.page().totalElements()).isEqualTo(3);
    assertThat(swiss.content()).hasSize(2).allMatch(entry -> "CH".equals(entry.country()));

    PageResponse<InstitutionCatalogueEntrySummaryResponse> germany =
        catalogue(token, "?country=DE&query=DKB");
    assertThat(germany.page().totalElements()).isEqualTo(1);
    assertThat(germany.content().get(0).name()).isEqualTo("DKB");

    assertThat(catalogue(token, "?country=CH,DE").page().totalElements()).isEqualTo(6);
    assertThat(catalogue(token, "?country=CH&country=DE").page().totalElements()).isEqualTo(6);
    assertThat(catalogue(token, "?country=%20").page().totalElements()).isEqualTo(6);
    assertThat(catalogue(token, "?country=XX").page().totalElements()).isZero();
  }

  @Test
  void catalogueRejectsMalformedCountryWithValidationProblemCode() {
    String token = bootstrapAdministrator();
    client(token)
        .get()
        .uri("/api/v1/institutions/catalogue?country=chx")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void catalogueShowsSortedDistinctFormatsFromShippedTemplatesAndCamtFlag() {
    String token = bootstrapAdministrator();
    var postFinance = catalogue(token, "?query=PostFinance").content().get(0);
    assertThat(postFinance.importFormats()).containsExactly("CAMT", "CSV", "PDF");
    var dkb = catalogue(token, "?query=DKB").content().get(0);
    // V19 advertises CAMT053 for DKB as well as V75's shipped CSV template.
    assertThat(dkb.importFormats()).containsExactly("CAMT", "CSV");
  }

  @Test
  void inactiveTemplateDoesNotContributeItsFormat() throws Exception {
    String token = bootstrapAdministrator();
    UUID id = UUID.fromString("35cd132b-78f1-4480-93a7-e59e0fd18f9f");
    try {
      try (Connection connection = dataSource.getConnection();
          var statement =
              connection.prepareStatement(
                  "UPDATE import_template SET is_active = false WHERE id = ?")) {
        statement.setObject(1, id);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      assertThat(catalogue(token, "?query=PostFinance").content().get(0).importFormats())
          .containsExactly("CAMT", "CSV");
    } finally {
      try (Connection connection = dataSource.getConnection();
          var statement =
              connection.prepareStatement(
                  "UPDATE import_template SET is_active = true WHERE id = ?")) {
        statement.setObject(1, id);
        statement.executeUpdate();
      }
    }
  }

  private PageResponse<InstitutionCatalogueEntrySummaryResponse> catalogue(
      String token, String params) {
    return client(token)
        .get()
        .uri("/api/v1/institutions/catalogue" + params)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(
            new ParameterizedTypeReference<
                PageResponse<InstitutionCatalogueEntrySummaryResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  @Test
  void creatingFromACatalogueEntryDefaultsCurrencyFromCountryAndLinksTheCatalogueId() {
    String token = bootstrapAdministrator();
    var postFinance = searchCatalogue(token, "Post").content().get(0);

    PortfolioResponse created =
        createPortfolio(
            token,
            new CreatePortfolioRequest(postFinance.id(), null, null, null, null, null, null));

    assertThat(created.catalogueInstitutionId()).isEqualTo(postFinance.id());
    assertThat(created.name()).isEqualTo("PostFinance");
    assertThat(created.country()).isEqualTo("CH");
    // FR-INS-004: CH's default per the country->currency map (US-04-01), not user-supplied.
    assertThat(created.portfolioCurrency()).isEqualTo("CHF");
    assertThat(created.personalAssetsDefault()).isFalse();
    assertThat(created.status()).isEqualTo("ACTIVE");
  }

  @Test
  void aPortfolioLinkedToACatalogueEntryKeepsTheNameTheMemberGaveIt() {
    // US-04-06/FR-INS-001: portfolios are named by the member, e.g. "VIAC 3a"; the institution
    // behind it still comes from the catalogue.
    String token = bootstrapAdministrator();
    var viac = searchCatalogue(token, "VIAC").content().get(0);

    PortfolioResponse created =
        createPortfolio(
            token, new CreatePortfolioRequest(viac.id(), "VIAC 3a", null, null, null, null, null));

    assertThat(created.name()).isEqualTo("VIAC 3a");
    assertThat(created.catalogueInstitutionId()).isEqualTo(viac.id());
    assertThat(created.logoUrl()).isEqualTo(viac.logoUrl());
  }

  @Test
  void creatingFromACatalogueEntryHonorsAnExplicitCurrencyOverride() {
    String token = bootstrapAdministrator();
    var postFinance = searchCatalogue(token, "Post").content().get(0);

    PortfolioResponse created =
        createPortfolio(
            token,
            new CreatePortfolioRequest(postFinance.id(), null, null, null, null, null, "USD"));

    assertThat(created.portfolioCurrency()).isEqualTo("USD");
  }

  @Test
  void creatingACustomInstitutionWithNoCatalogueLinkSucceeds() {
    String token = bootstrapAdministrator();

    PortfolioResponse created =
        createPortfolio(
            token,
            new CreatePortfolioRequest(
                null, "My Local Credit Union", "US", "BANK", null, null, "USD"));

    assertThat(created.catalogueInstitutionId()).isNull();
    assertThat(created.name()).isEqualTo("My Local Credit Union");
    assertThat(created.institutionType()).isEqualTo("BANK");
    assertThat(created.portfolioCurrency()).isEqualTo("USD");
  }

  @Test
  void customInstitutionDefaultsInstitutionTypeToOtherWhenNotChosen() {
    String token = bootstrapAdministrator();

    PortfolioResponse created =
        createPortfolio(
            token,
            new CreatePortfolioRequest(null, "Unlabelled Provider", null, null, null, null, "CHF"));

    assertThat(created.institutionType()).isEqualTo("OTHER");
  }

  @Test
  void customInstitutionWithoutANameIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, null, null, null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void customInstitutionWithoutACurrencyIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, "No Currency Bank", null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void invalidCurrencyCodeIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, "Bad Currency Bank", null, null, null, null, "ZZZ"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(portfolioRepository.findAll())
        .noneMatch(institution -> "Bad Currency Bank".equals(institution.getName()));
  }

  @Test
  void personalAssetsIsNotASelectableInstitutionTypeForANewInstitution() {
    // Reserved for the one system-created default container per workspace (V19's trigger) - a
    // user must never be able to create a second one via this endpoint.
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreatePortfolioRequest(
                null, "Sneaky Personal Assets", null, "PERSONAL_ASSETS", null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void creatingAnInstitutionFromAnUnknownCatalogueIdIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreatePortfolioRequest(
                java.util.UUID.randomUUID(), null, null, null, null, null, null))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void summaryOfAnInstitutionWithNoAccountsIsValidAndAllZero() {
    // C7: a container with zero accounts must show a valid, empty summary, not an error.
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token, new CreatePortfolioRequest(null, "Empty Bank", null, null, null, null, "CHF"));

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    assertThat(summary.totalLiabilities()).isEqualByComparingTo("0");
    assertThat(summary.netValue()).isEqualByComparingTo("0");
    assertThat(summary.complete()).isTrue();
    assertThat(summary.accounts()).isEmpty();
  }

  @Test
  void summaryAggregatesAssetsAndLiabilitiesIntoANegativeNetValue() {
    // The story's own DoD scenario, exact numbers - see this class's own Javadoc for why a
    // CUSTOM_ASSET and a MORTGAGE stand in for its "current account" and "mortgage" examples.
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token, new CreatePortfolioRequest(null, "Sparkasse", "DE", "BANK", null, null, "EUR"));
    AccountSummaryResponse asset =
        createAccount(
            token,
            AccountRequests.account("Family Home", "CUSTOM_ASSET", "EUR")
                .portfolioId(institution.id())
                .customAssetType("REAL_ESTATE")
                .build());
    recordValuation(
        token, asset.id(), new CreateCustomAssetValuationRequest(today(), new BigDecimal("2000")));
    createAccount(
        token,
        AccountRequests.account("Home Mortgage", "MORTGAGE", "EUR")
            .portfolioId(institution.id())
            .originalPrincipal(new BigDecimal("300000"))
            .interestRatePercent(new BigDecimal("1.5"))
            .build());

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.totalAssets()).isEqualByComparingTo("2000");
    assertThat(summary.totalLiabilities()).isEqualByComparingTo("300000");
    assertThat(summary.netValue()).isEqualByComparingTo("-298000");
    assertThat(summary.complete()).isTrue();
    assertThat(summary.accounts()).hasSize(2);
  }

  @Test
  void foreignCurrencyAccountsConvertUsingTheDirectPairAndExposeTheRateUsed() {
    // AC #2: FR-INS-SUM-004/FR-CUR-011 - the rate/date used must be inspectable.
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token, new CreatePortfolioRequest(null, "US Broker", null, null, null, null, "CHF"));
    AccountSummaryResponse account =
        createAccount(
            token,
            AccountRequests.account("US Property", "CUSTOM_ASSET", "USD")
                .portfolioId(institution.id())
                .customAssetType("REAL_ESTATE")
                .build());
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(today(), new BigDecimal("1000")));
    seedFxRate("USD", "CHF", today(), "0.9000000000");

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.totalAssets()).isEqualByComparingTo("900.0000");
    PortfolioSummaryResponse.AccountContribution contribution = summary.accounts().get(0);
    assertThat(contribution.value()).isEqualByComparingTo("900.0000");
    assertThat(contribution.conversionRate()).isEqualByComparingTo("0.9000000000");
    assertThat(contribution.conversionRateDate()).isEqualTo(today());
    assertThat(contribution.conversionRateCarriedForward()).isFalse();
  }

  @Test
  void aCarriedForwardFxRateIsVisiblyMarkedNotPresentedAsFresh() {
    // FR-CUR-012/PR-011, and the specific bug a code review of this PR found: the only stored
    // USD/CHF rate is a week old (no row for today) - convert() must still succeed via
    // carry-forward, but the summary must say so, not present the resulting figure identically to
    // one backed by an exact same-day rate.
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token, new CreatePortfolioRequest(null, "US Broker", null, null, null, null, "CHF"));
    AccountSummaryResponse account =
        createAccount(
            token,
            AccountRequests.account("US Property", "CUSTOM_ASSET", "USD")
                .portfolioId(institution.id())
                .customAssetType("REAL_ESTATE")
                .build());
    recordValuation(
        token,
        account.id(),
        new CreateCustomAssetValuationRequest(today(), new BigDecimal("1000")));
    seedFxRate("USD", "CHF", today().minusWeeks(1), "0.9000000000");

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    PortfolioSummaryResponse.AccountContribution contribution = summary.accounts().get(0);
    assertThat(contribution.value()).isEqualByComparingTo("900.0000");
    assertThat(contribution.conversionRateCarriedForward()).isTrue();
  }

  @Test
  void accountsWithNoValueSourceAreExcludedFromTotalsAndMarkTheSummaryIncomplete() {
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token, new CreatePortfolioRequest(null, "Mixed Bank", null, null, null, null, "CHF"));
    createAccount(
        token,
        AccountRequests.account("Everyday Checking", "CASH", "CHF")
            .portfolioId(institution.id())
            .build());
    AccountSummaryResponse asset =
        createAccount(
            token,
            AccountRequests.account("Watch Collection", "CUSTOM_ASSET", "CHF")
                .portfolioId(institution.id())
                .customAssetType("COLLECTIBLE")
                .build());
    recordValuation(
        token, asset.id(), new CreateCustomAssetValuationRequest(today(), new BigDecimal("5000")));

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.complete()).isFalse();
    assertThat(summary.totalAssets()).isEqualByComparingTo("5000");
    assertThat(summary.accounts())
        .hasSize(2)
        .anySatisfy(
            contribution -> {
              assertThat(contribution.name()).isEqualTo("Everyday Checking");
              assertThat(contribution.valueKnown()).isFalse();
              assertThat(contribution.value()).isNull();
            });
  }

  // #380 review: the summary flags an out-of-date valuation as the account list and the Portfolio
  // View do, rather than presenting it as fresh.
  @Test
  void aContributionRestingOnAnOldValuationSaysHowOldAndThatItIsStale() {
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token, new CreatePortfolioRequest(null, "Collectibles", null, null, null, null, "CHF"));
    AccountSummaryResponse old =
        createAccount(
            token,
            AccountRequests.account("Old Watch", "CUSTOM_ASSET", "CHF")
                .portfolioId(institution.id())
                .customAssetType("COLLECTIBLE")
                .build());
    AccountSummaryResponse fresh =
        createAccount(
            token,
            AccountRequests.account("New Watch", "CUSTOM_ASSET", "CHF")
                .portfolioId(institution.id())
                .customAssetType("COLLECTIBLE")
                .build());
    LocalDate longAgo = today().minusDays(100);
    recordValuation(
        token, old.id(), new CreateCustomAssetValuationRequest(longAgo, new BigDecimal("1000")));
    recordValuation(
        token, fresh.id(), new CreateCustomAssetValuationRequest(today(), new BigDecimal("2000")));

    Map<String, PortfolioSummaryResponse.AccountContribution> byName =
        getSummary(token, institution.id()).accounts().stream()
            .collect(
                Collectors.toMap(
                    PortfolioSummaryResponse.AccountContribution::name, Function.identity()));

    assertThat(byName.get("Old Watch").valueSourceDate()).isEqualTo(longAgo);
    assertThat(byName.get("Old Watch").valueSourceStale()).isTrue();
    assertThat(byName.get("New Watch").valueSourceDate()).isEqualTo(today());
    assertThat(byName.get("New Watch").valueSourceStale()).isFalse();
  }

  @Test
  void archivedAccountsAreExcludedFromTheSummaryEntirely() {
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token,
            new CreatePortfolioRequest(null, "Archiving Bank", null, null, null, null, "CHF"));
    AccountSummaryResponse asset =
        createAccount(
            token,
            AccountRequests.account("Old Watch", "CUSTOM_ASSET", "CHF")
                .portfolioId(institution.id())
                .customAssetType("COLLECTIBLE")
                .build());
    recordValuation(
        token, asset.id(), new CreateCustomAssetValuationRequest(today(), new BigDecimal("1000")));
    client(token)
        .post()
        .uri("/api/v1/accounts/" + asset.id() + "/archive")
        .header("If-Match", "\"" + asset.version() + "\"")
        .exchange();

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.accounts()).isEmpty();
    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    assertThat(summary.complete()).isTrue();
  }

  @Test
  void aResolvedAccountWithNoFxRateAtAllDegradesToUnknownNotAFailedRequest() {
    // Regression for #78's follow-up fix: FxRateService.getConversionRate() 404s when no direct
    // or chained rate exists at all (not merely stale), and getSummary() is itself @Transactional
    // - catching that 404 does not stop Spring marking the shared transaction rollback-only, so
    // this request must never reach a try/catch around getConversionRate() in the first place
    // (PortfolioService now calls tryGetConversionRate() instead). Before the fix this 500'd
    // with UnexpectedRollbackException instead of returning the degraded contribution below.
    String token = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            token,
            new CreatePortfolioRequest(
                null, "Foreign Currency Bank", "DE", "BANK", null, null, "CHF"));
    AccountSummaryResponse asset =
        createAccount(
            token,
            AccountRequests.account("Foreign Collectible", "CUSTOM_ASSET", "USD")
                .portfolioId(institution.id())
                .customAssetType("COLLECTIBLE")
                .build());
    recordValuation(
        token, asset.id(), new CreateCustomAssetValuationRequest(today(), new BigDecimal("1000")));
    // Deliberately no seedFxRate call - USD/CHF has no rate at all, not even a stale one.

    PortfolioSummaryResponse summary = getSummary(token, institution.id());

    assertThat(summary.complete()).isFalse();
    assertThat(summary.totalAssets()).isEqualByComparingTo("0");
    PortfolioSummaryResponse.AccountContribution contribution = summary.accounts().get(0);
    assertThat(contribution.valueKnown()).isFalse();
    assertThat(contribution.value()).isNull();
  }

  @Test
  void summaryOfAnUnknownInstitutionIsNotFound() {
    String token = bootstrapAdministrator();

    client(token)
        .get()
        .uri("/api/v1/portfolios/" + UUID.randomUUID() + "/summary")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void anotherMemberSeesTheInstitutionSummaryWithoutAnyGrant() {
    // Proves AccessControlService is wired in on membership alone (US-03-06): a second member of
    // the workspace sees the first one's institution at once.
    String adminToken = bootstrapAdministrator();
    PortfolioResponse institution =
        createPortfolio(
            adminToken,
            new CreatePortfolioRequest(null, "Admin's Bank", null, null, null, null, "CHF"));
    createSecondMember(adminToken, "partner@example.com");
    String partnerToken = login("partner@example.com", SECOND_MEMBER_PASSWORD);

    client(partnerToken)
        .get()
        .uri("/api/v1/portfolios/" + institution.id() + "/summary")
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void anonymousRequestIsUnauthorized() {
    RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .get()
        .uri("/api/v1/institutions/catalogue")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aDeactivatedWorkspaceMemberCannotAuthenticateAtAll() throws Exception {
    // No code path deactivates a workspace_member yet (US-03-04 is later in this sprint) - this
    // simulates that future state directly at the DB level to prove JwtAuthenticationFilter's
    // workspace_member.status check (added alongside this story - see its class Javadoc) actually
    // holds, ahead of US-03-04 landing for real. This is enforced globally (401, the token simply
    // no longer authenticates - the same treatment a disabled AppUser already gets), not as a
    // per-endpoint 403, so every workspace-scoped endpoint is covered automatically, not just
    // this one.
    String token = bootstrapAdministrator();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("UPDATE workspace_member SET status = 'INACTIVE'");
    }

    client(token)
        .get()
        .uri("/api/v1/institutions/catalogue")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new CreatePortfolioRequest(
                null, "Should Not Be Created", null, null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void blankOptionalFieldsAreTreatedAsOmittedWhenLinkingACatalogueEntry() {
    // A client that reuses one form model for both create flows and defaults unset fields to ""
    // rather than null must not have that fail validation for fields the catalogue path ignores.
    String token = bootstrapAdministrator();
    var postFinance = searchCatalogue(token, "Post").content().get(0);

    PortfolioResponse created =
        createPortfolio(
            token, new CreatePortfolioRequest(postFinance.id(), "", "", "", "", "", ""));

    assertThat(created.name()).isEqualTo("PostFinance");
    // portfolioCurrency="" normalizes to null too, so the country-derived default still applies.
    assertThat(created.portfolioCurrency()).isEqualTo("CHF");
  }

  @Test
  void malformedCountryCodeIsRejected() {
    String token = bootstrapAdministrator();

    client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePortfolioRequest(null, "Bad Country Bank", "USA", null, null, null, "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(portfolioRepository.findAll())
        .noneMatch(institution -> "Bad Country Bank".equals(institution.getName()));
  }

  private PageResponse<InstitutionCatalogueEntrySummaryResponse> searchCatalogue(
      String token, String query) {
    String uri =
        query != null
            ? "/api/v1/institutions/catalogue?query=" + query
            : "/api/v1/institutions/catalogue";
    return client(token)
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(
            new ParameterizedTypeReference<
                PageResponse<InstitutionCatalogueEntrySummaryResponse>>() {})
        .returnResult()
        .getResponseBody();
  }

  private PortfolioSummaryResponse getSummary(String token, UUID institutionId) {
    return client(token)
        .get()
        .uri("/api/v1/portfolios/" + institutionId + "/summary")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(PortfolioSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private AccountSummaryResponse createAccount(String token, CreateAccountRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AccountSummaryResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private CustomAssetValuationResponse recordValuation(
      String token, UUID accountId, CreateCustomAssetValuationRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/accounts/" + accountId + "/valuations")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(CustomAssetValuationResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // app.business-zone's default: the service's "today" is Zurich's date, not this JVM's (CI is
  // UTC).
  private static LocalDate today() {
    return LocalDate.now(ZoneId.of("Europe/Zurich"));
  }

  private void seedFxRate(String base, String quote, LocalDate date, String rate) {
    FxRate fxRate = new FxRate();
    fxRate.setBaseCurrency(base);
    fxRate.setQuoteCurrency(quote);
    fxRate.setRateDate(date);
    fxRate.setRate(new BigDecimal(rate));
    fxRate.setSource("ECB"); // app.fx.default-source
    fxRateRepository.save(fxRate);
  }

  private void createSecondMember(String adminToken, String email) {
    client(adminToken)
        .post()
        .uri("/api/v1/admin/users")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreateUserRequest(email, SECOND_MEMBER_PASSWORD, "STANDARD_USER", "EN"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(UserSummaryResponse.class);
  }

  private String login(String email, String password) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new LoginRequest(email, password))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(LoginResponse.class)
        .returnResult()
        .getResponseBody()
        .tokens()
        .accessToken();
  }

  private PortfolioResponse createPortfolio(String token, CreatePortfolioRequest request) {
    return client(token)
        .post()
        .uri("/api/v1/portfolios")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(PortfolioResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private String bootstrapAdministrator() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .build()
        .post()
        .uri("/api/v1/setup/administrator")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new SetupAdministratorRequest(
                "admin@example.com", "correct-horse-battery-staple", "Test Workspace", "CHF"))
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CREATED)
        .expectBody(AuthTokensResponse.class)
        .returnResult()
        .getResponseBody()
        .accessToken();
  }

  private RestTestClient client(String accessToken) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + accessToken)
        .build();
  }
}
