package com.trackmywealth.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trackmywealth.backend.dto.AuthTokensResponse;
import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportPdfLayout;
import com.trackmywealth.backend.dto.ImportTemplateCandidateResponse;
import com.trackmywealth.backend.dto.ImportTemplateRequest;
import com.trackmywealth.backend.dto.ImportTemplateResponse;
import com.trackmywealth.backend.dto.ImportTemplateTestResponse;
import com.trackmywealth.backend.dto.ResolvedImportTemplate;
import com.trackmywealth.backend.dto.SetupAdministratorRequest;
import com.trackmywealth.backend.error.ApiException;
import com.trackmywealth.backend.security.AuthenticatedUserPrincipal;
import com.trackmywealth.backend.service.ImportTemplateService;
import com.trackmywealth.backend.testsupport.AccountRequests;
import com.trackmywealth.backend.testsupport.RowLevelSecurityRole;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.StatusAssertions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * US-07-03 against a real PostgreSQL: template CRUD, versioning, If-Match, the shipped read-only
 * rule, cross-workspace 404, detection and the dry run with both synthetic fixtures. The parser's
 * own rules are pinned without Spring in {@code ImportFileParserServiceTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportTemplateControllerTest {

  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String BASE = "/api/v1/import-templates";
  private static final String SWISS = "swiss-cash-iso-8859-1.csv";
  private static final String GERMAN = "german-cash-utf8-bom.csv";
  // Bound by RLS, unlike the application's own role in these tests (see RowLevelSecurityRole).
  private static final String RLS_ROLE = "import_template_rls_role";
  private static final ParameterizedTypeReference<List<ImportTemplateResponse>> TEMPLATE_LIST =
      new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<ImportTemplateCandidateResponse>>
      CANDIDATES = new ParameterizedTypeReference<>() {};

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

  @Autowired ImportTemplateService importTemplateService;

  @Autowired PlatformTransactionManager transactionManager;

  @Autowired EntityManager entityManager;

  private String token;

  @BeforeEach
  void cleanDatabaseAndSignIn() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql :
          List.of(
              "DELETE FROM import_batch",
              "DELETE FROM import_template",
              "DELETE FROM account_ownership",
              "DELETE FROM account",
              "DELETE FROM admin_audit_log",
              "DELETE FROM user_session",
              "DELETE FROM refresh_token",
              "DELETE FROM authorization_denial_log",
              "DELETE FROM app_user",
              "DELETE FROM workspace_member",
              "DELETE FROM financial_institution",
              "DELETE FROM workspace")) {
        statement.execute(sql);
      }
    }
    token = bootstrapAdministrator();
  }

  // --- acceptance criteria 1 and 2: save from a sample, detect the next file -------------------

  @Test
  void aTemplateSavedFromASampleIsTheTopCandidateForTheNextFileOfThatFormat() throws Exception {
    // The mapping screen's flow: dry-run the sample unsaved, then save with its header columns.
    ImportTemplateTestResponse preview = testUnsaved(swissRequest(null), fixture(SWISS));
    assertThat(preview.parsedRowCount()).isEqualTo(5);

    EntityExchangeResult<ImportTemplateResponse> result =
        client()
            .post()
            .uri(BASE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(swissRequest(preview.headerColumns()))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(ImportTemplateResponse.class)
            .returnResult();
    ImportTemplateResponse created =
        CurrentVersion.storedEtag(
            result, dataSource, "import_template", result.getResponseBody().id());
    assertThat(result.getResponseHeaders().getLocation()).hasPath(BASE + "/" + created.id());
    assertThat(created.templateVersion()).isEqualTo("1");
    assertThat(created.current()).isTrue();
    assertThat(created.canEdit()).isTrue();
    assertThat(created.encoding()).as("stored in canonical spelling").isEqualTo("ISO-8859-1");

    Map<String, Object> row = row(created.id());
    assertThat(row.get("workspace_id")).isEqualTo(workspaceId());
    assertThat(row.get("is_system_provided")).isEqualTo(false);
    assertThat(row.get("template_family_id")).isNotNull();
    assertThat(row.get("column_mapping").toString())
        .contains("\"bookingDate\": \"Buchungsdatum\"", "\"amount\": \"Betrag\"");
    assertThat(row.get("header_fingerprint")).isEqualTo(preview.headerFingerprint()).isNotNull();

    // A second file of the same format: its own rows, the same header.
    byte[] nextMonth =
        ("Buchungsdatum;Valuta;Avisierungstext;Betrag;W\u00e4hrung;Saldo\r\n"
                + "03.02.2026;03.02.2026;Einkauf;-9.90;CHF;100.00\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    create(germanRequest(headerOf(GERMAN)));

    List<ImportTemplateCandidateResponse> candidates = detect(nextMonth);

    assertThat(candidates).isNotEmpty();
    assertThat(candidates.get(0).template().id()).isEqualTo(created.id());
    assertThat(candidates.get(0).match()).isEqualTo(ImportTemplateCandidateResponse.EXACT_HEADER);
    assertThat(candidates).extracting(c -> c.template().name()).doesNotContain("German bank");
  }

  @Test
  void aHeaderWithEveryMappedColumnIsACandidateBehindAnExactMatch() throws Exception {
    ImportTemplateResponse swiss = create(swissRequest(headerOf(SWISS)));
    byte[] extraColumn =
        ("Buchungsdatum;Valuta;Avisierungstext;Betrag;W\u00e4hrung;Saldo;Kategorie\r\n"
                + "03.02.2026;03.02.2026;Einkauf;-9.90;CHF;100.00;Haushalt\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);

    List<ImportTemplateCandidateResponse> candidates = detect(extraColumn);

    assertThat(candidates)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.template().id()).isEqualTo(swiss.id());
              assertThat(c.match())
                  .isEqualTo(ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT);
            });
  }

  // --- acceptance criterion 3 and If-Match: versioning --------------------------------------

  @Test
  void changingTheDateFormatWritesANewVersionAndKeepsTheOldRowUnchanged() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    ImportTemplateRequest changed = withDateFormat(swissRequest(null), "dd.MM.yy");

    EntityExchangeResult<ImportTemplateResponse> result =
        client()
            .put()
            .uri(BASE + "/" + v1.id())
            .header("If-Match", "\"" + v1.version() + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(changed)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ImportTemplateResponse.class)
            .returnResult();
    ImportTemplateResponse v2 =
        CurrentVersion.storedEtag(
            result, dataSource, "import_template", result.getResponseBody().id());

    assertThat(v2.id()).isNotEqualTo(v1.id());
    assertThat(v2.familyId()).isEqualTo(v1.familyId());
    assertThat(v2.templateVersion()).isEqualTo("2");
    assertThat(v2.dateFormat()).isEqualTo("dd.MM.yy");
    assertThat(v2.headerColumns()).as("kept when the PUT sends none").isEqualTo(v1.headerColumns());
    Map<String, Object> old = row(v1.id());
    assertThat(old.get("is_current")).isEqualTo(false);
    assertThat(old.get("date_format")).isEqualTo("dd.MM.yyyy");
    assertThat(old.get("template_version")).isEqualTo("1");
    assertThat(list(false)).extracting(ImportTemplateResponse::id).containsExactly(v2.id());
    assertThat(get(v1.id()).current()).as("history stays readable").isFalse();
  }

  @Test
  void aNameOnlyChangeUpdatesInPlace() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    ImportTemplateRequest renamed = withName(swissRequest(null), "PostFinance-like");

    ImportTemplateResponse updated = update(v1.id(), v1.version(), renamed);

    assertThat(updated.id()).isEqualTo(v1.id());
    assertThat(updated.templateVersion()).isEqualTo("1");
    assertThat(updated.name()).isEqualTo("PostFinance-like");
    assertThat(updated.version()).isGreaterThan(v1.version());
  }

  @Test
  void aStaleIfMatchIs412AndAMissingOneIs428() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    update(v1.id(), v1.version(), withName(swissRequest(null), "Renamed"));

    putStatus(v1.id(), "\"" + v1.version() + "\"", swissRequest(null))
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_CONFLICT");
    putStatus(v1.id(), null, swissRequest(null))
        .isEqualTo(HttpStatus.PRECONDITION_REQUIRED)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("VERSION_REQUIRED");
  }

  @Test
  void aRetiredVersionCannotBeChangedAndPointsToTheCurrentOne() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    ImportTemplateResponse v2 =
        update(v1.id(), v1.version(), withDateFormat(swissRequest(null), "dd.MM.yy"));
    ImportTemplateResponse retired = get(v1.id());

    putStatus(v1.id(), "\"" + retired.version() + "\"", swissRequest(null))
        .isEqualTo(HttpStatus.PRECONDITION_FAILED)
        .expectBody()
        .jsonPath("$.currentId")
        .isEqualTo(v2.id().toString());
  }

  // --- acceptance criteria 5 and 6: dry runs ---------------------------------------------------

  @Test
  void aFileLackingAMappedColumnIsAFileLevelMismatch() throws Exception {
    ImportTemplateResponse swiss = create(swissRequest(headerOf(SWISS)));
    byte[] withoutAmount =
        "Buchungsdatum;Valuta;Avisierungstext\r\n02.01.2026;02.01.2026;x\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);

    multipart(BASE + "/" + swiss.id() + "/test", withoutAmount, null)
        .expectStatus()
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_MISMATCH")
        .jsonPath("$.missingColumns")
        .isEqualTo(List.of("Betrag", "W\u00e4hrung"))
        .jsonPath("$.rows")
        .doesNotExist();
  }

  @Test
  void bothFixturesParseThroughTheirSavedTemplates() throws Exception {
    ImportTemplateResponse swiss = create(swissRequest(headerOf(SWISS)));
    ImportTemplateResponse german = create(germanRequest(headerOf(GERMAN)));

    multipart(BASE + "/" + swiss.id() + "/test", fixture(SWISS), null)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.rowCount")
        .isEqualTo(5)
        .jsonPath("$.errorRowCount")
        .isEqualTo(0)
        .jsonPath("$.rows[0].canonical.amount")
        .isEqualTo("5250.00")
        .jsonPath("$.rows[2].canonical.amount")
        .isEqualTo("-1850.00")
        .jsonPath("$.rows[2].canonical.description")
        .isEqualTo("Miete Januar; Wohnung 3.OG")
        .jsonPath("$.rows[4].canonical.currency")
        .isEqualTo("CHF");
    multipart(BASE + "/" + german.id() + "/test", fixture(GERMAN), null)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.rowCount")
        .isEqualTo(5)
        .jsonPath("$.parsedRowCount")
        .isEqualTo(5)
        .jsonPath("$.rows[*].canonical.amount")
        .isEqualTo(List.of("-85.00", "3150.00", "-1204.56", "-4.90", "0.12"))
        .jsonPath("$.rows[*].canonical.transactionType")
        .isEqualTo(List.of("EXPENSE", "INCOME", "EXPENSE", "FEE", "INTEREST"))
        .jsonPath("$.rows[0].canonical.bookingDate")
        .isEqualTo("2026-01-02");
    assertThat(count("SELECT count(*) FROM import_batch")).as("a dry run writes nothing").isZero();
  }

  @Test
  void rowErrorsAreCountedAndExplainedInTheCallersLanguage() throws Exception {
    byte[] csv =
        ("Buchungsdatum;Valuta;Avisierungstext;Betrag;W\u00e4hrung;Saldo\r\n"
                + "31.02.2026;;a;-1.00;CHF;0\r\n"
                + "01.03.2026;;b;zwei;CHF;0\r\n"
                + "02.03.2026;;c;-2.00;CHF;0\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);

    multipart(BASE + "/test", csv, swissRequest(null))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.errorRowCount")
        .isEqualTo(2)
        .jsonPath("$.errorCounts.IMPORT_ROW_DATE_UNPARSEABLE")
        .isEqualTo(1)
        .jsonPath("$.errorCounts.IMPORT_ROW_AMOUNT_UNPARSEABLE")
        .isEqualTo(1)
        .jsonPath("$.rows[0].error.args.pattern")
        .isEqualTo("dd.MM.yyyy")
        .jsonPath("$.rows[0].error.message")
        .isEqualTo(
            "Column \"Buchungsdatum\": \"31.02.2026\" is not a date in the format dd.MM.yyyy.")
        .jsonPath("$.rows[2].status")
        .isEqualTo("PARSED");

    setLanguage("DE");
    multipart(BASE + "/test", csv, swissRequest(null))
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.rows[1].error.message")
        .isEqualTo(
            "Spalte \u201eBetrag\u201c: \u201ezwei\u201c ist kein Betrag mit dem Dezimal- und"
                + " Tausendertrennzeichen dieser Vorlage.");
  }

  @Test
  void aFileOverFiveMegabytesIs413() {
    byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

    multipart(BASE + "/detect", tooLarge, null)
        .expectStatus()
        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_FILE_TOO_LARGE");
  }

  /** The unsaved dry run skips Bean Validation (it needs no name); the parser checks the counts. */
  @Test
  void anUnsavedDryRunRejectsRowCountsOutOfRange() throws Exception {
    ImportTemplateRequest swiss = swissRequest(null);
    Map<String, ImportTemplateRequest> requests =
        Map.of(
            "headerRowIndex", withRowCounts(swiss, -3, 0, 0),
            "preambleRowCount", withRowCounts(swiss, 0, -1, 0),
            "trailingSummaryRowCount", withRowCounts(swiss, 0, 0, -5));
    for (Map.Entry<String, ImportTemplateRequest> entry : requests.entrySet()) {
      multipart(BASE + "/test", fixture(SWISS), entry.getValue())
          .expectStatus()
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("IMPORT_TEMPLATE_INVALID")
          .jsonPath("$.field")
          .isEqualTo(entry.getKey());
    }
  }

  // --- template rules at save time ---------------------------------------------------------

  @Test
  void unsupportedTemplateClassesAndAccountStrategiesAre422() throws Exception {
    List<String> header = headerOf(SWISS);
    for (ImportTemplateRequest request :
        List.of(
            withClassAndStrategy(swissRequest(header), "SNAPSHOT", null),
            withClassAndStrategy(swissRequest(header), "SECURITIES_TRANSACTIONS", null),
            withClassAndStrategy(swissRequest(header), null, "COLUMN"))) {
      postStatus(request)
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("IMPORT_TEMPLATE_UNSUPPORTED");
    }
    assertThat(count("SELECT count(*) FROM import_template")).isZero();
  }

  @Test
  void aRepeatedHeaderNameCannotBeMappedByNameButByIndex() throws Exception {
    List<String> header = List.of("Datum", "Betrag", "Betrag", "Text");
    ImportColumnMapping byName =
        new ImportColumnMapping(
            "Datum", null, "Betrag", null, null, null, "Text", null, null, null, null, null, null);
    ImportColumnMapping byIndex =
        new ImportColumnMapping(
            "Datum", null, "2", null, null, null, "Text", null, null, null, null, null, null);

    postStatus(simpleRequest(byName, header))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_INVALID")
        .jsonPath("$.field")
        .isEqualTo("columnMapping.amount");
    postStatus(simpleRequest(byIndex, header)).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void aTemplateWithAHeaderRowNeedsItsHeaderColumns() {
    postStatus(swissRequest(null))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.field")
        .isEqualTo("headerColumns");
  }

  // --- acceptance criterion 7: shipped read-only, other workspace not found -------------------

  @Test
  void aShippedTemplateIsVisibleButReadOnly() throws Exception {
    UUID shipped = insertTemplate(null, "Shipped bank");
    String ifMatch = "\"" + get(shipped).version() + "\"";

    assertThat(get(shipped).systemProvided()).isTrue();
    assertThat(get(shipped).canEdit()).isFalse();
    assertThat(list(false)).extracting(ImportTemplateResponse::id).contains(shipped);
    putStatus(shipped, ifMatch, swissRequest(headerOf(SWISS)))
        .isEqualTo(HttpStatus.FORBIDDEN)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_READ_ONLY");
    client()
        .delete()
        .uri(BASE + "/" + shipped)
        .header("If-Match", ifMatch)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_READ_ONLY");
    assertThat(count("SELECT count(*) FROM import_template WHERE id = ?", shipped)).isOne();
  }

  // #279: the test above runs as a role that bypasses RLS, so it cannot see V65. Under an RLS-bound
  // role a workspace cannot lock a shipped row; the answer must still be read-only (403), not the
  // 404 of a lock that found nothing. Own templates stay changeable and foreign ones hidden, so the
  // 403s are not an artefact of a role that can do nothing.
  @Test
  void aShippedTemplateIsReadOnlyUnderRowLevelSecurity() throws Exception {
    UUID shipped = insertTemplate(null, "Shipped bank");
    Integer shippedVersion = get(shipped).version();
    ImportTemplateRequest request = swissRequest(headerOf(SWISS));
    ImportTemplateResponse own = create(request);
    UUID otherWorkspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", otherWorkspace);
    UUID foreign = insertTemplate(otherWorkspace, "Foreign");
    AuthenticatedUserPrincipal admin = adminPrincipal();
    createRowLevelSecurityRole();

    List<Supplier<?>> shippedChanges =
        List.of(
            () -> importTemplateService.update(shipped, request, shippedVersion, admin),
            () -> importTemplateService.setActive(shipped, false, shippedVersion, admin),
            () -> {
              importTemplateService.delete(shipped, shippedVersion, admin);
              return null;
            });
    for (Supplier<?> change : shippedChanges) {
      assertThatThrownBy(() -> underRowLevelSecurity(admin, change))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.getStatusCode().value()).isEqualTo(403);
                assertThat(e.getCode()).isEqualTo("IMPORT_TEMPLATE_READ_ONLY");
              });
    }
    assertThatThrownBy(
            () ->
                underRowLevelSecurity(
                    admin, () -> importTemplateService.setActive(foreign, false, 0, admin)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    ImportTemplateResponse deactivated =
        underRowLevelSecurity(
            admin, () -> importTemplateService.setActive(own.id(), false, own.version(), admin));
    assertThat(deactivated.active()).isFalse();
    assertThat(count("SELECT count(*) FROM import_template WHERE id = ?", shipped)).isOne();
  }

  @Test
  void anotherWorkspacesTemplateIsAnAuditedNotFound() throws Exception {
    UUID otherWorkspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", otherWorkspace);
    UUID foreign = insertTemplate(otherWorkspace, "Foreign");

    client()
        .get()
        .uri(BASE + "/" + foreign)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo("Not found.");
    client()
        .put()
        .uri(BASE + "/" + foreign)
        .header("If-Match", "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(swissRequest(headerOf(SWISS)))
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(list(true)).extracting(ImportTemplateResponse::id).doesNotContain(foreign);
    awaitDenials(foreign, 2);
  }

  // --- delete and deactivate ---------------------------------------------------------------

  @Test
  void anUnusedTemplateIsDeletedWithEveryVersion() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    ImportTemplateResponse v2 =
        update(v1.id(), v1.version(), withDateFormat(swissRequest(null), "dd.MM.yy"));

    client()
        .delete()
        .uri(BASE + "/" + v2.id())
        .header("If-Match", "\"" + v2.version() + "\"")
        .exchange()
        .expectStatus()
        .isNoContent();

    assertThat(count("SELECT count(*) FROM import_template")).isZero();
  }

  @Test
  void aTemplateAnImportUsedIsDeactivatedNotDeleted() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    ImportTemplateResponse v2 =
        update(v1.id(), v1.version(), withDateFormat(swissRequest(null), "dd.MM.yy"));
    insertBatchUsing(v1.id());

    client()
        .delete()
        .uri(BASE + "/" + v2.id())
        .header("If-Match", "\"" + v2.version() + "\"")
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_IN_USE");

    ImportTemplateResponse inactive = setActive(v2, false);
    assertThat(inactive.active()).isFalse();
    assertThat(list(false)).isEmpty();
    assertThat(list(true)).extracting(ImportTemplateResponse::id).containsExactly(v2.id());
    assertThat(detect(fixture(SWISS))).as("hidden from detection").isEmpty();

    assertThat(setActive(inactive, true).active()).isTrue();
    assertThat(detect(fixture(SWISS))).hasSize(1);
  }

  @Test
  void theListFiltersByInstitution() throws Exception {
    UUID institution = catalogueInstitutionId();
    create(withInstitution(swissRequest(headerOf(SWISS)), institution));
    create(germanRequest(headerOf(GERMAN)));

    List<ImportTemplateResponse> filtered =
        client()
            .get()
            .uri(BASE + "?institutionCatalogueId=" + institution)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(TEMPLATE_LIST)
            .returnResult()
            .getResponseBody();

    assertThat(filtered)
        .singleElement()
        .satisfies(t -> assertThat(t.name()).isEqualTo("Swiss bank"));
    postStatus(withInstitution(swissRequest(headerOf(SWISS)), UUID.randomUUID()))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.field")
        .isEqualTo("institutionCatalogueId");
  }

  // --- the lookup US-07-04 uses ------------------------------------------------------------

  @Test
  void anUploadResolvesOnlyTheCurrentVersionOfAnActiveTemplate() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));
    ImportTemplateResponse v2 =
        update(v1.id(), v1.version(), withDateFormat(swissRequest(null), "dd.MM.yy"));
    UUID otherWorkspace = UUID.randomUUID();
    execute("INSERT INTO workspace (id, name) VALUES (?, 'Workspace B')", otherWorkspace);
    UUID foreign = insertTemplate(otherWorkspace, "Foreign");
    AuthenticatedUserPrincipal admin = adminPrincipal();

    ResolvedImportTemplate resolved =
        as(admin, () -> importTemplateService.resolveForImport(v2.id(), admin));
    assertThat(resolved.id()).isEqualTo(v2.id());
    assertThat(resolved.templateVersion()).isEqualTo("2");
    assertThat(resolved.definition().dateFormat()).isEqualTo("dd.MM.yy");
    assertThat(resolved.definition().columnMapping().amount()).isEqualTo("Betrag");

    assertThatThrownBy(
            () -> as(admin, () -> importTemplateService.resolveForImport(v1.id(), admin)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    setActive(v2, false);
    assertThatThrownBy(
            () -> as(admin, () -> importTemplateService.resolveForImport(v2.id(), admin)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    assertThatThrownBy(
            () -> as(admin, () -> importTemplateService.resolveForImport(foreign, admin)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
  }

  // --- PDF templates (#268) -------------------------------------------------------------------

  @Test
  void aPdfTemplateIsSavedWithItsLayoutTestedAndDetected() throws Exception {
    byte[] statement =
        pdf(
            PDF_MARKER,
            "Datum Betrag Text",
            "04.01.2031 -1.234,50 Invented expense",
            "05.01.2031 987,65 Invented income");

    ImportTemplateResponse created = create(pdfRequest("PDF_TEXT"));

    assertThat(created.fileFormat()).isEqualTo("PDF_TEXT");
    assertThat(created.pdfLayout().columns()).containsExactly("Datum", "Betrag", "Text");
    assertThat(created.headerColumns()).containsExactly("Datum", "Betrag", "Text");
    // Its columns are named by its own layout, so a fingerprint of them would identify nothing.
    assertThat(created.headerFingerprint()).isNull();
    assertThat(
            count(
                "SELECT count(*) FROM import_template WHERE id = ? AND file_format = 'PDF_TEXT'"
                    + " AND pdf_layout IS NOT NULL",
                created.id()))
        .isOne();

    ImportTemplateTestResponse tested =
        multipart(BASE + "/" + created.id() + "/test", statement, null)
            .expectStatus()
            .isOk()
            .expectBody(ImportTemplateTestResponse.class)
            .returnResult()
            .getResponseBody();
    assertThat(tested.rowCount()).isEqualTo(2);
    assertThat(tested.parsedRowCount()).isEqualTo(2);

    // A PDF's columns are named by its layout, not read from the file: never an exact header.
    assertThat(detect(statement))
        .first()
        .satisfies(
            c -> {
              assertThat(c.template().id()).isEqualTo(created.id());
              assertThat(c.match())
                  .isEqualTo(ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT);
            });
  }

  @Test
  void aPdfLayoutChangeIsANewVersionAndTheOldLayoutStays() {
    ImportTemplateResponse v1 = create(pdfRequest("PDF_TEXT"));
    ImportPdfLayout widened =
        new ImportPdfLayout(
            List.of("Datum", "Betrag", "Text"),
            "(\\S+)\\s+(\\S+)\\s+(.*)",
            PDF_MARKER,
            "^\\d{2}\\.");

    ImportTemplateResponse v2 =
        update(v1.id(), v1.version(), withPdf(pdfRequest("PDF_TEXT"), "PDF_TEXT", widened));

    assertThat(v2.id()).isNotEqualTo(v1.id());
    assertThat(v2.templateVersion()).isEqualTo("2");
    assertThat(v2.pdfLayout()).isEqualTo(widened);
    assertThat(get(v1.id()).pdfLayout()).isEqualTo(pdfRequest("PDF_TEXT").pdfLayout());
    assertThat(get(v1.id()).current()).isFalse();
  }

  @Test
  void switchingACsvTemplateToPdfIsANewVersion() throws Exception {
    ImportTemplateResponse v1 = create(swissRequest(headerOf(SWISS)));

    ImportTemplateResponse v2 = update(v1.id(), v1.version(), pdfRequest("PDF_TEXT"));

    assertThat(v2.templateVersion()).isEqualTo("2");
    assertThat(v2.fileFormat()).isEqualTo("PDF_TEXT");
    assertThat(v2.headerColumns()).containsExactly("Datum", "Betrag", "Text");
    Map<String, Object> old = row(v1.id());
    assertThat(old.get("file_format")).isEqualTo("CSV");
    assertThat(old.get("pdf_layout")).isNull();
  }

  /**
   * Second PR #267 review: a PDF version's header columns are its layout's names. A switch back to
   * CSV without the file's own header columns is a 422, not a CSV template fingerprinted on them.
   */
  @Test
  void switchingAPdfTemplateToCsvNeedsTheFilesOwnHeaderColumns() throws Exception {
    ImportTemplateResponse v1 = create(pdfRequest("PDF_TEXT"));

    putStatus(v1.id(), "\"" + v1.version() + "\"", swissRequest(null))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_INVALID")
        .jsonPath("$.field")
        .isEqualTo("headerColumns");

    ImportTemplateResponse v2 = update(v1.id(), v1.version(), swissRequest(headerOf(SWISS)));
    assertThat(v2.templateVersion()).isEqualTo("2");
    assertThat(v2.fileFormat()).isEqualTo("CSV");
    assertThat(v2.headerColumns()).isEqualTo(headerOf(SWISS));
    assertThat(v2.headerFingerprint()).isNotNull();
    assertThat(row(v2.id()).get("pdf_layout")).isNull();
  }

  /**
   * A PDF is tried only on PDF templates and a CSV file only on CSV ones; two PDF templates with
   * one marker are both candidates, neither an exact match.
   */
  @Test
  void detectionKeepsPdfAndCsvTemplatesApart() throws Exception {
    ImportTemplateResponse csv = create(swissRequest(headerOf(SWISS)));
    ImportTemplateResponse first = create(pdfRequest("PDF_TEXT"));
    ImportTemplateResponse second = create(withName(pdfRequest("PDF_TEXT"), "Second PDF bank"));

    List<ImportTemplateCandidateResponse> forPdf =
        detect(pdf(PDF_MARKER, "04.01.2031 -1,00 Invented"));
    assertThat(forPdf).extracting(c -> c.template().id()).containsExactly(first.id(), second.id());
    assertThat(forPdf)
        .extracting(ImportTemplateCandidateResponse::match)
        .containsOnly(ImportTemplateCandidateResponse.MAPPED_COLUMNS_PRESENT);

    assertThat(detect(fixture(SWISS))).extracting(c -> c.template().id()).containsExactly(csv.id());
    assertThat(detect(pdf("Another bank entirely", "04.01.2031 -1,00 Invented"))).isEmpty();
  }

  /**
   * OCR per candidate would make detection far too slow: an OCR template is picked explicitly. The
   * template was saved while OCR was on (none can be saved while it is off).
   */
  @Test
  void anOcrTemplateIsNeverADetectionCandidate() throws Exception {
    ImportTemplateResponse template = create(pdfRequest("PDF_TEXT"));
    execute("UPDATE import_template SET file_format = 'PDF_OCR' WHERE id = ?", template.id());

    assertThat(detect(pdf(PDF_MARKER, "04.01.2031 -1,00 Invented"))).isEmpty();
  }

  /**
   * Third PR #267 review: while OCR is switched off (until #276), a PDF_OCR template could never
   * read a file, so none is saved - neither a new one nor a switch to OCR.
   */
  @Test
  void anOcrTemplateIsNotSavedWhileOcrIsSwitchedOff() {
    postStatus(pdfRequest("PDF_OCR"))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_INVALID")
        .jsonPath("$.field")
        .isEqualTo("fileFormat");

    ImportTemplateResponse text = create(pdfRequest("PDF_TEXT"));
    putStatus(text.id(), "\"" + text.version() + "\"", pdfRequest("PDF_OCR"))
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.field")
        .isEqualTo("fileFormat");
  }

  /**
   * F1 of the PR #267 review: OCR is off by default until #276 adds a confidence threshold, so a
   * scanned statement is a retryable 503, never rows built from unchecked recognized digits.
   */
  @Test
  void anOcrDryRunIsUnavailableWhileOcrIsSwitchedOff() throws Exception {
    multipart(BASE + "/test", pdf(), pdfRequest("PDF_OCR"))
        .expectStatus()
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        // Switched off is no reason to retry: only a busy or too slow recognition says when to.
        .expectHeader()
        .doesNotExist(HttpHeaders.RETRY_AFTER)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_OCR_UNAVAILABLE");
  }

  @Test
  void aPdfTemplateWithoutLayoutIs422() {
    ImportTemplateRequest withoutLayout = withPdf(pdfRequest("PDF_TEXT"), "PDF_TEXT", null);

    postStatus(withoutLayout)
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("IMPORT_TEMPLATE_INVALID")
        .jsonPath("$.field")
        .isEqualTo("pdfLayout");
  }

  private static final String PDF_MARKER = "Invented Bank statement";

  private static ImportTemplateRequest pdfRequest(String format) {
    return new ImportTemplateRequest(
        "Invented PDF bank",
        null,
        null,
        null,
        null,
        ",",
        ".",
        "dd.MM.yyyy",
        null,
        null,
        null,
        null,
        "FIXED",
        "EUR",
        new ImportColumnMapping(
            "Datum", null, "Betrag", null, null, null, "Text", null, null, null, null, null, null),
        Map.of(),
        null,
        null,
        format,
        new ImportPdfLayout(
            List.of("Datum", "Betrag", "Text"),
            "(\\S+)\\s+(\\S+)\\s+(.+)",
            PDF_MARKER,
            "^\\d{2}\\."));
  }

  private static ImportTemplateRequest withPdf(
      ImportTemplateRequest r, String format, ImportPdfLayout layout) {
    return new ImportTemplateRequest(
        r.name(),
        r.institutionCatalogueId(),
        r.templateClass(),
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        r.dateFormat(),
        r.headerRowIndex(),
        r.preambleRowCount(),
        r.trailingSummaryRowCount(),
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        r.accountIdentificationStrategy(),
        r.headerColumns(),
        format,
        layout);
  }

  // A one-page PDF with these text lines (invented values only).
  private static byte[] pdf(String... lines) throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        stream.setLeading(16);
        stream.newLineAtOffset(50, 720);
        for (String line : lines) {
          stream.showText(line);
          stream.newLine();
        }
        stream.endText();
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  // --- requests --------------------------------------------------------------------------------

  private static ImportTemplateRequest swissRequest(List<String> headerColumns) {
    return new ImportTemplateRequest(
        "Swiss bank",
        null,
        null,
        ";",
        "iso-8859-1",
        ".",
        "'",
        "dd.MM.yyyy",
        0,
        0,
        0,
        "SINGLE_SIGNED_COLUMN",
        "PER_ROW",
        null,
        new ImportColumnMapping(
            "Buchungsdatum",
            "Valuta",
            "Betrag",
            null,
            null,
            "W\u00e4hrung",
            "Avisierungstext",
            null,
            null,
            null,
            null,
            null,
            null),
        Map.of(),
        null,
        headerColumns,
        null,
        null);
  }

  private static ImportTemplateRequest germanRequest(List<String> headerColumns) {
    return new ImportTemplateRequest(
        "German bank",
        null,
        "CASH_TRANSACTIONS",
        ";",
        "UTF-8",
        ",",
        ".",
        "dd.MM.yyyy",
        0,
        4,
        1,
        "SEPARATE_DEBIT_CREDIT",
        "PER_ROW",
        null,
        new ImportColumnMapping(
            "Buchungstag",
            "Wertstellung",
            null,
            "Soll",
            "Haben",
            "W\u00e4hrung",
            "Verwendungszweck",
            "Auftraggeber / Beg\u00fcnstigter",
            null,
            "Buchungstext",
            null,
            null,
            null),
        Map.of(
            "Lastschrift",
            "EXPENSE",
            "Gutschrift",
            "INCOME",
            "Entgelt",
            "FEE",
            "Zinsen",
            "INTEREST"),
        "USER_SELECTED",
        headerColumns,
        null,
        null);
  }

  private static ImportTemplateRequest simpleRequest(
      ImportColumnMapping mapping, List<String> headerColumns) {
    return new ImportTemplateRequest(
        "Simple",
        null,
        null,
        ",",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "EUR",
        mapping,
        null,
        null,
        headerColumns,
        null,
        null);
  }

  private static ImportTemplateRequest withDateFormat(ImportTemplateRequest r, String dateFormat) {
    return new ImportTemplateRequest(
        r.name(),
        r.institutionCatalogueId(),
        r.templateClass(),
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        dateFormat,
        r.headerRowIndex(),
        r.preambleRowCount(),
        r.trailingSummaryRowCount(),
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        r.accountIdentificationStrategy(),
        r.headerColumns(),
        r.fileFormat(),
        r.pdfLayout());
  }

  private static ImportTemplateRequest withName(ImportTemplateRequest r, String name) {
    return new ImportTemplateRequest(
        name,
        r.institutionCatalogueId(),
        r.templateClass(),
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        r.dateFormat(),
        r.headerRowIndex(),
        r.preambleRowCount(),
        r.trailingSummaryRowCount(),
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        r.accountIdentificationStrategy(),
        r.headerColumns(),
        r.fileFormat(),
        r.pdfLayout());
  }

  private static ImportTemplateRequest withInstitution(ImportTemplateRequest r, UUID institution) {
    return new ImportTemplateRequest(
        r.name(),
        institution,
        r.templateClass(),
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        r.dateFormat(),
        r.headerRowIndex(),
        r.preambleRowCount(),
        r.trailingSummaryRowCount(),
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        r.accountIdentificationStrategy(),
        r.headerColumns(),
        r.fileFormat(),
        r.pdfLayout());
  }

  private static ImportTemplateRequest withClassAndStrategy(
      ImportTemplateRequest r, String templateClass, String strategy) {
    return new ImportTemplateRequest(
        r.name(),
        r.institutionCatalogueId(),
        templateClass,
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        r.dateFormat(),
        r.headerRowIndex(),
        r.preambleRowCount(),
        r.trailingSummaryRowCount(),
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        strategy,
        r.headerColumns(),
        r.fileFormat(),
        r.pdfLayout());
  }

  private static ImportTemplateRequest withRowCounts(
      ImportTemplateRequest r, int headerRowIndex, int preamble, int trailing) {
    return new ImportTemplateRequest(
        r.name(),
        r.institutionCatalogueId(),
        r.templateClass(),
        r.delimiter(),
        r.encoding(),
        r.decimalSeparator(),
        r.thousandsSeparator(),
        r.dateFormat(),
        headerRowIndex,
        preamble,
        trailing,
        r.amountRepresentation(),
        r.currencyMode(),
        r.fixedCurrency(),
        r.columnMapping(),
        r.typeMapping(),
        r.accountIdentificationStrategy(),
        r.headerColumns(),
        r.fileFormat(),
        r.pdfLayout());
  }

  // --- HTTP helpers ------------------------------------------------------------------------------

  private ImportTemplateResponse create(ImportTemplateRequest request) {
    return client()
        .post()
        .uri(BASE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportTemplateResponse update(UUID id, int version, ImportTemplateRequest request) {
    return client()
        .put()
        .uri(BASE + "/" + id)
        .header("If-Match", "\"" + version + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private ImportTemplateResponse setActive(ImportTemplateResponse template, boolean active) {
    return client()
        .post()
        .uri(BASE + "/" + template.id() + (active ? "/activate" : "/deactivate"))
        .header("If-Match", "\"" + template.version() + "\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private StatusAssertions postStatus(ImportTemplateRequest request) {
    return client()
        .post()
        .uri(BASE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(request)
        .exchange()
        .expectStatus();
  }

  private StatusAssertions putStatus(UUID id, String ifMatch, ImportTemplateRequest request) {
    RestTestClient.RequestBodySpec spec = client().put().uri(BASE + "/" + id);
    if (ifMatch != null) {
      spec = spec.header("If-Match", ifMatch);
    }
    return spec.contentType(MediaType.APPLICATION_JSON).body(request).exchange().expectStatus();
  }

  private ImportTemplateResponse get(UUID id) {
    return client()
        .get()
        .uri(BASE + "/" + id)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private List<ImportTemplateResponse> list(boolean includeInactive) {
    return client()
        .get()
        .uri(BASE + "?includeInactive=" + includeInactive)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(TEMPLATE_LIST)
        .returnResult()
        .getResponseBody();
  }

  private List<ImportTemplateCandidateResponse> detect(byte[] content) {
    return multipart(BASE + "/detect", content, null)
        .expectStatus()
        .isOk()
        .expectBody(CANDIDATES)
        .returnResult()
        .getResponseBody();
  }

  private ImportTemplateTestResponse testUnsaved(ImportTemplateRequest template, byte[] content) {
    return multipart(BASE + "/test", content, template)
        .expectStatus()
        .isOk()
        .expectBody(ImportTemplateTestResponse.class)
        .returnResult()
        .getResponseBody();
  }

  // The header columns a client would take from the dry run of the sample.
  private List<String> headerOf(String fixtureName) throws IOException {
    ImportTemplateRequest template =
        SWISS.equals(fixtureName) ? swissRequest(null) : germanRequest(null);
    return testUnsaved(template, fixture(fixtureName)).headerColumns();
  }

  private RestTestClient.ResponseSpec multipart(
      String uri, byte[] content, ImportTemplateRequest template) {
    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.parseMediaType("text/csv"));
    fileHeaders.setContentDispositionFormData("file", "statement.csv");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new HttpEntity<>(new ByteArrayResource(content), fileHeaders));
    if (template != null) {
      HttpHeaders templateHeaders = new HttpHeaders();
      templateHeaders.setContentType(MediaType.APPLICATION_JSON);
      body.add("template", new HttpEntity<>(template, templateHeaders));
    }
    return client()
        .post()
        .uri(uri)
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body)
        .exchange();
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

  private RestTestClient client() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:%d".formatted(port))
        .defaultHeader("Authorization", "Bearer " + token)
        .build();
  }

  // --- database helpers --------------------------------------------------------------------------

  private static byte[] fixture(String name) throws IOException {
    try (InputStream stream =
        ImportTemplateControllerTest.class.getResourceAsStream("/import/" + name)) {
      return stream.readAllBytes();
    }
  }

  private UUID workspaceId() throws SQLException {
    return (UUID)
        single(
            "SELECT m.workspace_id FROM app_user u JOIN workspace_member m"
                + " ON m.id = u.workspace_member_id WHERE u.email = 'admin@example.com'");
  }

  private AuthenticatedUserPrincipal adminPrincipal() throws SQLException {
    UUID userId = (UUID) single("SELECT id FROM app_user WHERE email = 'admin@example.com'");
    String role = (String) single("SELECT role FROM app_user WHERE id = ?", userId);
    return new AuthenticatedUserPrincipal(userId, role, workspaceId(), UUID.randomUUID(), "EN");
  }

  private static <T> T as(AuthenticatedUserPrincipal principal, Supplier<T> call) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    try {
      return call.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void createRowLevelSecurityRole() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      RowLevelSecurityRole.create(connection, RLS_ROLE);
    }
  }

  private <T> T underRowLevelSecurity(AuthenticatedUserPrincipal principal, Supplier<T> call) {
    return as(
        principal,
        () -> RowLevelSecurityRole.call(transactionManager, entityManager, RLS_ROLE, call));
  }

  // A row written past the API: a shipped template (null workspace) or another workspace's own.
  private UUID insertTemplate(UUID workspaceId, String name) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO import_template (id, template_family_id, workspace_id, name,"
            + " template_version, delimiter, date_format, currency_mode, fixed_currency,"
            + " column_mapping, header_columns, is_system_provided) VALUES (?, ?, ?, ?, '2026.1',"
            + " ';', 'dd.MM.yyyy', 'FIXED', 'CHF',"
            + " '{\"bookingDate\": \"Datum\", \"amount\": \"Betrag\"}'::jsonb,"
            + " '[\"Datum\", \"Betrag\"]'::jsonb, ?)",
        id,
        id,
        workspaceId,
        name,
        workspaceId == null);
    return id;
  }

  private void insertBatchUsing(UUID templateId) throws Exception {
    client()
        .post()
        .uri("/api/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(AccountRequests.account("Everyday Checking", "CASH", "CHF").build())
        .exchange()
        .expectStatus()
        .isCreated();
    UUID accountId = (UUID) single("SELECT id FROM account WHERE name = 'Everyday Checking'");
    execute(
        "INSERT INTO import_batch (workspace_id, account_id, template_id, template_version_used)"
            + " VALUES (?, ?, ?, '1')",
        workspaceId(),
        accountId,
        templateId);
  }

  private UUID catalogueInstitutionId() throws SQLException {
    return (UUID) single("SELECT id FROM institution_catalogue ORDER BY id LIMIT 1");
  }

  private void setLanguage(String language) throws SQLException {
    execute("UPDATE app_user SET language = ?", language);
  }

  // The denial audit is written off the request thread; give it a moment.
  private void awaitDenials(UUID entityId, int expected) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    long found;
    do {
      found =
          count(
              "SELECT count(*) FROM authorization_denial_log WHERE requested_entity_type ="
                  + " 'ImportTemplate' AND requested_entity_id = ?",
              entityId);
      if (found >= expected) {
        break;
      }
      Thread.sleep(100);
    } while (Instant.now().isBefore(deadline));
    assertThat(found).isEqualTo(expected);
  }

  private Map<String, Object> row(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT * FROM import_template WHERE id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        Map<String, Object> row = new HashMap<>();
        for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
          row.put(resultSet.getMetaData().getColumnName(i), resultSet.getObject(i));
        }
        return row;
      }
    }
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private long count(String sql, Object... parameters) throws SQLException {
    return ((Number) single(sql, parameters)).longValue();
  }

  private Object single(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getObject(1);
      }
    }
  }
}
