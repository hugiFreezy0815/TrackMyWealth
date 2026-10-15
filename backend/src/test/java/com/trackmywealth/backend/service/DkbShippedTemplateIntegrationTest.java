package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.ImportColumnMapping;
import com.trackmywealth.backend.dto.ImportParseResult;
import com.trackmywealth.backend.dto.ImportRowFilter;
import com.trackmywealth.backend.dto.ImportStatementBalanceRule;
import com.trackmywealth.backend.dto.ImportTemplateDefinition;
import com.trackmywealth.backend.dto.ImportTemplateValues;
import com.trackmywealth.backend.entity.ImportTemplate;
import com.trackmywealth.backend.repository.ImportTemplateRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** US-07-15: the shipped DKB CSV template must work without a user-created mapping. */
@Testcontainers
@SpringBootTest
class DkbShippedTemplateIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16")
          .withDatabaseName("trackmywealth")
          .withUsername("trackmywealth")
          .withPassword("trackmywealth");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("app.rate-limit.enabled", () -> "false");
  }

  @Autowired ImportTemplateRepository templates;
  @Autowired ImportFileParserService parser;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  void shippedDkbCsvIsDetectedAndParsedWithPendingBookingsExcluded() throws IOException {
    ImportTemplate stored =
        templates.findAll().stream()
            .filter(t -> "DKB Girokonto CSV".equals(t.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(stored.getWorkspaceId()).isNull();
    assertThat(stored.isSystemProvided()).isTrue();
    assertThat(stored.isCurrent()).isTrue();
    assertThat(stored.isActive()).isTrue();

    ImportColumnMapping columns =
        mapper.readValue(stored.getColumnMapping(), ImportColumnMapping.class);
    ImportRowFilter filter = mapper.readValue(stored.getRowFilter(), ImportRowFilter.class);
    ImportStatementBalanceRule balance =
        mapper.readValue(stored.getStatementBalanceRule(), ImportStatementBalanceRule.class);
    ImportTemplateDefinition definition =
        new ImportTemplateDefinition(
            stored.getTemplateClass(),
            stored.getDelimiter(),
            stored.getEncoding(),
            stored.getDecimalSeparator(),
            stored.getThousandsSeparator(),
            stored.getDateFormat(),
            stored.getHeaderRowIndex(),
            stored.getPreambleRowCount(),
            stored.getTrailingSummaryRowCount(),
            stored.getAmountRepresentation(),
            stored.getCurrencyMode(),
            stored.getFixedCurrency(),
            columns,
            Map.of("Eingang", "INCOME", "Ausgang", "EXPENSE"),
            stored.getAccountIdentificationStrategy(),
            ImportTemplateValues.FORMAT_CSV,
            null,
            Map.of(),
            filter,
            mapper.readValue(
                stored.getAccountIdentifierRule(),
                com.trackmywealth.backend.dto.ImportAccountIdentifierRule.class),
            balance);
    parser.validateTemplate(
        definition,
        mapper.readValue(
            stored.getHeaderColumns(),
            mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
    byte[] sample;
    try (var stream = getClass().getResourceAsStream("/import/dkb-girokonto-synthetic.csv")) {
      assertThat(stream).isNotNull();
      sample = stream.readAllBytes();
    }

    ImportParseResult parsed = parser.parse(sample, definition, "EUR");
    assertThat(parsed.headerFingerprint()).isEqualTo(stored.getHeaderFingerprint());
    assertThat(parsed.rows()).hasSize(3);
    assertThat(parsed.rows().get(0).canonical().transactionType()).isEqualTo("EXPENSE");
    assertThat(parsed.rows().get(0).canonical().amount()).isEqualByComparingTo("-17.90");
    assertThat(parsed.rows().get(0).canonical().bookingDate())
        .isEqualTo(LocalDate.of(2031, 1, 28));
    assertThat(parsed.rows().get(1).canonical().transactionType()).isEqualTo("INCOME");
    assertThat(parsed.rows().get(1).canonical().amount()).isEqualByComparingTo("2987.00");
    assertThat(parsed.rows().get(2).isSkipped()).isTrue();
    assertThat(parsed.rows().get(2).canonical()).isNull();
    assertThat(parsed.accountIdentifier().found()).isTrue();
    assertThat(parsed.accountIdentifier().value()).isEqualTo("DE97999999990000012345");
    assertThat(parsed.statementBalances().balances()).hasSize(1);
    assertThat(parsed.statementBalances().balances().get(0).date())
        .isEqualTo(LocalDate.of(2031, 1, 31));
    assertThat(parsed.statementBalances().balances().get(0).amount())
        .isEqualByComparingTo("1111.11");
  }

  @Test
  void migrationRestoresForcedRowLevelSecurity() {
    assertThat(
            jdbc.queryForObject(
                "SELECT relforcerowsecurity FROM pg_class WHERE oid = 'import_template'::regclass",
                Boolean.class))
        .isTrue();
  }
}
