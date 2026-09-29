package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trackmywealth.backend.dto.FuzzyCategoryCandidate;
import com.trackmywealth.backend.dto.LatestCategoryAssignment;
import com.trackmywealth.backend.entity.CategorizationRule;
import com.trackmywealth.backend.entity.Category;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.TransactionCategorizationLog;
import com.trackmywealth.backend.entity.Workspace;
import com.trackmywealth.backend.repository.CategorizationRuleRepository;
import com.trackmywealth.backend.repository.CategoryRepository;
import com.trackmywealth.backend.repository.TransactionCategorizationLogRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-08-01: the layers {@link CategorizationService} tries, with the repositories mocked - the
 * source-code parsing and precedence (ISO 20022 purpose, bank transaction code, MCC), the
 * type-implied category, the guard rails of the fuzzy match, and that a batch loads each
 * workspace's taxonomy once. {@code CategorizationControllerTest} covers the same against
 * PostgreSQL, including the SQL of the fuzzy query.
 */
class CategorizationServiceTest {

  private static final UUID WORKSPACE = UUID.randomUUID();
  private static final UUID GROCERIES = UUID.randomUUID();
  private static final UUID INCOME = UUID.randomUUID();
  private static final UUID SHOPPING = UUID.randomUUID();
  private static final UUID FEES = UUID.randomUUID();
  private static final UUID TAXES = UUID.randomUUID();
  private static final UUID UNCATEGORIZED = UUID.randomUUID();

  private final CategoryService categoryService = mock(CategoryService.class);
  private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
  private final CategorizationRuleRepository ruleRepository =
      mock(CategorizationRuleRepository.class);
  private final TransactionCategorizationLogRepository logRepository =
      mock(TransactionCategorizationLogRepository.class);
  private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final CategorizationService service =
      new CategorizationService(
          categoryService,
          categoryRepository,
          ruleRepository,
          logRepository,
          transactionRepository,
          JsonMapper.builder().build(),
          entityManager,
          0.3);

  private final Set<UUID> assignable = new HashSet<>();
  private final List<CategorizationRule> rules = new ArrayList<>();
  private long createdSequence;

  @BeforeEach
  void setUp() {
    assignable.addAll(Set.of(GROCERIES, INCOME, SHOPPING, FEES, TAXES, UNCATEGORIZED));
    when(categoryService.assignableCategoryIds(any())).thenAnswer(inv -> Set.copyOf(assignable));
    when(ruleRepository.findByWorkspaceIdAndActiveTrueOrderByPriorityAscCreatedAtAscIdAsc(any()))
        .thenAnswer(inv -> List.copyOf(rules));
    when(categoryRepository.findByWorkspaceIdIsNullAndCodeIn(any()))
        .thenReturn(
            List.of(
                shipped("UNCATEGORIZED", UNCATEGORIZED),
                shipped("FEES", FEES),
                shipped("TAXES", TAXES)));
    when(categoryRepository.findMappedCategoryId(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(transactionRepository.saveAndFlush(any(Transaction.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  // --- source codes --------------------------------------------------------------------------

  @Nested
  class SourceCodes {

    @Test
    void aBankTransactionCodeRuleMatchesTheNormalizedCode() {
      rule("SOURCE_CODE", "ISO20022_BTC:PMNT-RCDT-ESCT", INCOME);
      Transaction row = row("INCOME", null, "{\"bankTransactionCode\": \" pmnt-rcdt-esct \"}");

      assertThat(service.categorize(row)).contains("RULE");
      assertThat(row.getCategoryId()).isEqualTo(INCOME);
    }

    @Test
    void aMappedBankTransactionCodeIsUsedBeforeTheMcc() {
      mapping("ISO20022_BTC", "PMNT-RCDT-ESCT", INCOME);
      mapping("MCC", "5411", GROCERIES);
      Transaction row =
          row("INCOME", null, "{\"bankTransactionCode\": \"PMNT-RCDT-ESCT\", \"mcc\": \"5411\"}");

      assertThat(service.categorize(row)).contains("SOURCE_CODE");
      assertThat(row.getCategoryId()).isEqualTo(INCOME);
      verify(categoryRepository, never()).findMappedCategoryId("MCC", "5411");
    }

    @Test
    void aPurposeCodeIsTriedBeforeABankTransactionCode() {
      mapping("ISO20022_PURPOSE", "SALA", INCOME);
      mapping("ISO20022_BTC", "PMNT-RCDT-ESCT", SHOPPING);
      Transaction row =
          row(
              "INCOME",
              null,
              "{\"purposeCode\": \"sala\", \"bankTransactionCode\": \"PMNT-RCDT-ESCT\"}");

      service.categorize(row);

      assertThat(row.getCategoryId()).isEqualTo(INCOME);
    }

    @Test
    void anUnmappedPurposeCodeFallsThroughToTheBankTransactionCode() {
      mapping("ISO20022_BTC", "PMNT-RCDT-ESCT", INCOME);
      Transaction row =
          row(
              "INCOME",
              null,
              "{\"purposeCode\": \"ZZZZ\", \"bankTransactionCode\": \"PMNT-RCDT-ESCT\"}");

      service.categorize(row);

      assertThat(row.getCategoryId()).isEqualTo(INCOME);
    }

    @Test
    void aNumericMccIsZeroPaddedToFourDigits() {
      mapping("MCC", "0742", GROCERIES);
      Transaction row = row("EXPENSE", null, "{\"mcc\": 742}");

      assertThat(service.categorize(row)).contains("SOURCE_CODE");
      assertThat(row.getCategoryId()).isEqualTo(GROCERIES);
    }

    @Test
    void blankOrNonTextCodesAreIgnored() {
      Transaction row =
          row(
              "EXPENSE",
              null,
              "{\"purposeCode\": \"  \", \"bankTransactionCode\": 12, \"mcc\": null}");

      assertThat(service.categorize(row)).isEmpty();
      verify(categoryRepository, never()).findMappedCategoryId(anyString(), anyString());
      assertThat(row.getCategoryId()).isEqualTo(UNCATEGORIZED);
    }

    @Test
    void aMappedCategoryTheWorkspaceCannotAssignIsSkipped() {
      mapping("MCC", "5411", GROCERIES);
      assignable.remove(GROCERIES);
      Transaction row = row("EXPENSE", null, "{\"mcc\": \"5411\"}");

      assertThat(service.categorize(row)).isEmpty();
      assertThat(row.getCategoryId()).isEqualTo(UNCATEGORIZED);
    }
  }

  // --- rules ---------------------------------------------------------------------------------

  @Nested
  class Rules {

    @Test
    void aMerchantRuleNeverMatchesARowWithoutAMerchant() {
      rule("MERCHANT", "migros", GROCERIES);
      Transaction row = row("EXPENSE", null, null);

      assertThat(service.categorize(row)).isEmpty();
      assertThat(row.getCategoryId()).isEqualTo(UNCATEGORIZED);
    }

    @Test
    void aMerchantRuleMatchesCaseAndWhitespaceInsensitively() {
      rule("MERCHANT", "migros zürich", GROCERIES);
      Transaction row = row("EXPENSE", "  MIGROS   Zürich HB ", null);

      assertThat(service.categorize(row)).contains("RULE");
      ArgumentCaptor<TransactionCategorizationLog> log =
          ArgumentCaptor.forClass(TransactionCategorizationLog.class);
      verify(logRepository).save(log.capture());
      assertThat(log.getValue().getRuleId()).isEqualTo(rules.get(0).getId());
    }

    @Test
    void aRuleToACategoryTheWorkspaceCannotAssignIsSkipped() {
      rule("MERCHANT", "migros", SHOPPING);
      assignable.remove(SHOPPING);
      mapping("MCC", "5411", GROCERIES);
      Transaction row = row("EXPENSE", "MIGROS", "{\"mcc\": \"5411\"}");

      assertThat(service.categorize(row)).contains("SOURCE_CODE");
      assertThat(row.getCategoryId()).isEqualTo(GROCERIES);
    }
  }

  // --- the type-implied category -------------------------------------------------------------

  @Nested
  class TransactionType {

    @ParameterizedTest
    @CsvSource({"FEE", "TAX"})
    void aFeeOrTaxWithNothingMoreSpecificLandsInItsTypesCategory(String type) {
      Transaction row = row(type, "Kontofuehrung", null);

      assertThat(service.categorize(row)).contains("TRANSACTION_TYPE");
      assertThat(row.getCategoryId()).isEqualTo("FEE".equals(type) ? FEES : TAXES);
      ArgumentCaptor<TransactionCategorizationLog> log =
          ArgumentCaptor.forClass(TransactionCategorizationLog.class);
      verify(logRepository).save(log.capture());
      assertThat(log.getValue().getRuleId()).isNull();
      assertThat(log.getValue().getConfidence()).isNull();
      verify(transactionRepository, never())
          .findFuzzyCandidates(any(), any(), any(), anyDouble(), anyInt());
    }

    @Test
    void aRuleOrACodeStillBeatsTheType() {
      rule("MERCHANT", "steueramt", SHOPPING);
      mapping("MCC", "9311", GROCERIES);

      Transaction ruled = row("TAX", "Steueramt Zuerich", null);
      Transaction coded = row("TAX", "Kanton", "{\"mcc\": \"9311\"}");
      service.categorize(ruled);
      service.categorize(coded);

      assertThat(ruled.getCategoryId()).isEqualTo(SHOPPING);
      assertThat(coded.getCategoryId()).isEqualTo(GROCERIES);
    }

    @Test
    void aTypeCategoryTheWorkspaceCannotAssignFallsThrough() {
      assignable.remove(FEES);
      Transaction row = row("FEE", null, null);

      assertThat(service.categorize(row)).isEmpty();
      assertThat(row.getCategoryId()).isEqualTo(UNCATEGORIZED);
    }

    @Test
    void anExpenseHasNoTypeCategory() {
      Transaction row = row("EXPENSE", null, null);

      assertThat(service.categorize(row)).isEmpty();
      assertThat(row.getCategoryId()).isEqualTo(UNCATEGORIZED);
    }
  }

  // --- the fuzzy match -----------------------------------------------------------------------

  @Nested
  class FuzzyMatch {

    @Test
    void theThresholdIsSetForTheTransactionBeforeTheFuzzyQuery() {
      when(transactionRepository.findFuzzyCandidates(any(), any(), any(), anyDouble(), anyInt()))
          .thenReturn(List.of(candidate(GROCERIES, "MIGROS ZUERICH", "0.45")));
      Transaction row = row("EXPENSE", "MIGROS BASEL", null);

      assertThat(service.categorize(row)).contains("FALLBACK_MATCH");

      InOrder order = inOrder(transactionRepository);
      order.verify(transactionRepository).setSimilarityThresholdForTransaction("0.3");
      order
          .verify(transactionRepository)
          .findFuzzyCandidates(
              eq(WORKSPACE), eq(row.getId()), eq("MIGROS BASEL"), eq(0.3), anyInt());
      ArgumentCaptor<TransactionCategorizationLog> log =
          ArgumentCaptor.forClass(TransactionCategorizationLog.class);
      verify(logRepository).save(log.capture());
      assertThat(log.getValue().getConfidence()).isEqualByComparingTo("0.45");
    }

    @Test
    void aCandidateOfAnotherBrandOrAnUnassignableCategoryIsSkipped() {
      assignable.remove(SHOPPING);
      when(transactionRepository.findFuzzyCandidates(any(), any(), any(), anyDouble(), anyInt()))
          .thenReturn(
              List.of(
                  candidate(GROCERIES, "COOP BASEL", "0.6"),
                  candidate(SHOPPING, "MIGROS BASEL CITY", "0.5"),
                  candidate(GROCERIES, "Migros Zuerich", "0.4")));
      Transaction row = row("EXPENSE", "MIGROS BASEL", null);

      service.categorize(row);

      assertThat(row.getCategoryId()).isEqualTo(GROCERIES);
      ArgumentCaptor<TransactionCategorizationLog> log =
          ArgumentCaptor.forClass(TransactionCategorizationLog.class);
      verify(logRepository).save(log.capture());
      assertThat(log.getValue().getConfidence()).isEqualByComparingTo("0.4");
    }

    @ParameterizedTest
    @CsvSource(
        value = {"NULL", "'12 34'", "'*'", "'AB *CD'"},
        nullValues = "NULL")
    void aMerchantWithoutABrandWordIsNeverFuzzyMatched(String merchant) {
      Transaction row = row("EXPENSE", merchant, null);

      assertThat(service.categorize(row)).isEmpty();
      verify(transactionRepository, never()).setSimilarityThresholdForTransaction(any());
      verify(transactionRepository, never())
          .findFuzzyCandidates(any(), any(), any(), anyDouble(), anyInt());
      assertThat(row.getCategoryId()).isEqualTo(UNCATEGORIZED);
    }
  }

  // --- batches -------------------------------------------------------------------------------

  @Nested
  class Batches {

    @Test
    void aBatchLoadsTheWorkspaceOnceAndResolvesEachCodeOnce() {
      mapping("MCC", "5411", GROCERIES);
      List<Transaction> rows =
          List.of(
              row("CREDIT_CARD_PURCHASE", "COOP ONE", "{\"mcc\": \"5411\"}"),
              row("CREDIT_CARD_PURCHASE", "COOP TWO", "{\"mcc\": \"5411\"}"),
              row("FEE", null, null),
              row("SETTLEMENT", null, null));

      Map<UUID, String> assigned = service.categorizeAll(rows);

      assertThat(assigned)
          .containsOnly(
              Map.entry(rows.get(0).getId(), "SOURCE_CODE"),
              Map.entry(rows.get(1).getId(), "SOURCE_CODE"),
              Map.entry(rows.get(2).getId(), "TRANSACTION_TYPE"));
      assertThat(rows.get(3).getCategoryId()).as("a settlement is not categorized").isNull();
      verify(categoryService, times(1)).assignableCategoryIds(WORKSPACE);
      verify(ruleRepository, times(1))
          .findByWorkspaceIdAndActiveTrueOrderByPriorityAscCreatedAtAscIdAsc(WORKSPACE);
      verify(categoryRepository, times(1)).findByWorkspaceIdIsNullAndCodeIn(any());
      verify(categoryRepository, times(1)).findMappedCategoryId("MCC", "5411");
      verify(transactionRepository, times(1)).setSimilarityThresholdForTransaction("0.3");
    }

    @Test
    void eachWorkspaceInABatchUsesItsOwnTaxonomy() {
      UUID other = UUID.randomUUID();
      when(categoryService.assignableCategoryIds(other)).thenReturn(Set.of(UNCATEGORIZED));
      Transaction mine = row("FEE", null, null);
      Transaction theirs = row("FEE", null, null);
      ReflectionTestUtils.setField(theirs.getWorkspace(), "id", other);

      service.categorizeAll(List.of(mine, theirs));

      assertThat(mine.getCategoryId()).isEqualTo(FEES);
      assertThat(theirs.getCategoryId()).as("FEES not assignable there").isEqualTo(UNCATEGORIZED);
      verify(categoryService).assignableCategoryIds(WORKSPACE);
      verify(categoryService).assignableCategoryIds(other);
    }

    @Test
    void typesThatAreNotCategorizedTouchNothing() {
      service.categorizeAll(
          List.of(
              row("SETTLEMENT", "x", null), row("BUY", null, null), row("DIVIDEND", null, null)));

      verifyNoInteractions(categoryService, ruleRepository, categoryRepository, logRepository);
      verifyNoInteractions(transactionRepository);
    }
  }

  // --- re-running a workspace (US-08-02) ----------------------------------------------------

  @Nested
  class Recategorization {

    @Test
    void anUnchangedDecisionWritesNothingSoRepeatedRunsDoNotGrowTheLog() {
      mapping("MCC", "5411", GROCERIES);
      Transaction row = categorizedRow("{\"mcc\": \"5411\"}", GROCERIES);
      page(List.of(row));
      latest(row, GROCERIES, "SOURCE_CODE", null, false);

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isZero();
      verify(transactionRepository, never()).saveAndFlush(any());
      verify(logRepository, never()).save(any());
    }

    @Test
    void theSameCategoryNowFoundByARuleIsWrittenWithTheNewProvenance() {
      Transaction row = categorizedRow(null, GROCERIES);
      row.setMerchantDescription("MIGROS BASEL");
      page(List.of(row));
      latest(row, GROCERIES, "FALLBACK_MATCH", null, false);
      rule("MERCHANT", "migros basel", GROCERIES);

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isEqualTo(1);
      ArgumentCaptor<TransactionCategorizationLog> log =
          ArgumentCaptor.forClass(TransactionCategorizationLog.class);
      verify(logRepository).save(log.capture());
      assertThat(log.getValue().getAssignedBy()).isEqualTo("RULE");
      assertThat(log.getValue().getRuleId()).isEqualTo(rules.get(0).getId());
    }

    @Test
    void theSameCategoryFromAnotherRuleIsWritten() {
      Transaction row = categorizedRow(null, GROCERIES);
      row.setMerchantDescription("MIGROS BASEL");
      page(List.of(row));
      rule("MERCHANT", "migros", GROCERIES);
      latest(row, GROCERIES, "RULE", UUID.randomUUID(), false);

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isEqualTo(1);
    }

    @Test
    void anOverriddenRowIsSkippedEvenWhenARuleNowMatches() {
      Transaction row = categorizedRow(null, SHOPPING);
      row.setMerchantDescription("MIGROS BASEL");
      page(List.of(row));
      latest(row, SHOPPING, "USER", null, true);
      rule("MERCHANT", "migros", GROCERIES);

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isZero();
      assertThat(row.getCategoryId()).isEqualTo(SHOPPING);
      verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void anOverrideRowThatNoLongerDescribesTheCategoryNoLongerProtectsIt() {
      // Reset to automatic landed in UNCATEGORIZED, which writes no log row of its own.
      Transaction row = categorizedRow(null, UNCATEGORIZED);
      row.setMerchantDescription("MIGROS BASEL");
      page(List.of(row));
      latest(row, SHOPPING, "USER", null, true);
      rule("MERCHANT", "migros", GROCERIES);

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isEqualTo(1);
      assertThat(row.getCategoryId()).isEqualTo(GROCERIES);
    }

    @Test
    void pagesFollowTheLastRowAndTheContextIsClearedAfterEachPage() {
      List<Transaction> full = new ArrayList<>();
      for (int i = 0; i < CategorizationService.RECATEGORIZATION_PAGE_SIZE; i++) {
        full.add(categorizedRow(null, UNCATEGORIZED));
      }
      Transaction lastOfFirstPage = full.get(full.size() - 1);
      when(transactionRepository.lockRecategorizationPage(
              eq(WORKSPACE),
              any(),
              any(),
              any(),
              eq(CategorizationService.RECATEGORIZATION_PAGE_SIZE)))
          .thenReturn(full)
          .thenReturn(List.of(categorizedRow(null, UNCATEGORIZED)));

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isZero();

      InOrder order = inOrder(transactionRepository, entityManager);
      order
          .verify(transactionRepository)
          .lockRecategorizationPage(
              eq(WORKSPACE), eq(CategorizationService.CATEGORIZED_TYPES), any(), any(), anyInt());
      order.verify(entityManager).clear();
      order
          .verify(transactionRepository)
          .lockRecategorizationPage(
              eq(WORKSPACE),
              eq(CategorizationService.CATEGORIZED_TYPES),
              eq(lastOfFirstPage.getCreatedAt()),
              eq(lastOfFirstPage.getId()),
              anyInt());
      order.verify(entityManager).clear();
      // The second page was short, so there is no third query.
      verify(transactionRepository, times(2))
          .lockRecategorizationPage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void anEmptyWorkspaceIsOneQuery() {
      when(transactionRepository.lockRecategorizationPage(any(), any(), any(), any(), anyInt()))
          .thenReturn(List.of());

      assertThat(service.recategorizeWorkspace(WORKSPACE)).isZero();
      verifyNoInteractions(entityManager, logRepository);
    }
  }

  // --- helpers the rule service shares -------------------------------------------------------

  @ParameterizedTest
  @CsvSource(
      value = {
        "'SUMUP *CAFE ROSSO', cafe",
        "'PAYPAL *NETFLIX.COM', netflix",
        "'Zürich Café', zürich",
        "'ab cd efg', efg",
        "'12 34', NULL",
        "'*', NULL",
        "NULL, NULL"
      },
      nullValues = "NULL")
  void theBrandIsTheFirstWordOfThreeLettersAfterAnyProcessorPrefix(
      String merchant, String expected) {
    assertThat(CategorizationService.brandOf(merchant)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      value = {"'  MIGROS   Zürich ', migros zürich", "'   ', NULL", "NULL, NULL"},
      nullValues = "NULL")
  void merchantsAreComparedLowerCaseWithWhitespaceCollapsed(String merchant, String expected) {
    assertThat(CategorizationService.normalizeMerchant(merchant)).isEqualTo(expected);
  }

  // --- fixtures ------------------------------------------------------------------------------

  private Transaction categorizedRow(String rawSourceData, UUID categoryId) {
    Transaction transaction = row("EXPENSE", null, rawSourceData);
    transaction.setCategoryId(categoryId);
    ReflectionTestUtils.setField(
        transaction, "createdAt", OffsetDateTime.now().plusNanos(createdSequence++ * 1000L));
    return transaction;
  }

  private void page(List<Transaction> rows) {
    when(transactionRepository.lockRecategorizationPage(any(), any(), any(), any(), anyInt()))
        .thenReturn(rows)
        .thenReturn(List.of());
  }

  private final List<LatestCategoryAssignment> latestRows = new ArrayList<>();

  private void latest(
      Transaction transaction,
      UUID categoryId,
      String assignedBy,
      UUID ruleId,
      boolean userOverride) {
    latestRows.add(
        new LatestCategoryAssignment() {
          @Override
          public UUID getTransactionId() {
            return transaction.getId();
          }

          @Override
          public String getAssignedBy() {
            return assignedBy;
          }

          @Override
          public UUID getCategoryId() {
            return categoryId;
          }

          @Override
          public UUID getRuleId() {
            return ruleId;
          }

          @Override
          public boolean isUserOverride() {
            return userOverride;
          }
        });
    when(logRepository.findLatestAssignments(any())).thenReturn(List.copyOf(latestRows));
  }

  private Transaction row(String type, String merchant, String rawSourceData) {
    Workspace workspace = new Workspace();
    ReflectionTestUtils.setField(workspace, "id", WORKSPACE);
    Transaction transaction = new Transaction();
    ReflectionTestUtils.setField(transaction, "id", UUID.randomUUID());
    transaction.setWorkspace(workspace);
    transaction.setTransactionType(type);
    transaction.setMerchantDescription(merchant);
    transaction.setRawSourceData(rawSourceData);
    return transaction;
  }

  private void rule(String matchType, String matchValue, UUID categoryId) {
    CategorizationRule rule = new CategorizationRule();
    ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
    rule.setWorkspaceId(WORKSPACE);
    rule.setMatchType(matchType);
    rule.setMatchValue(matchValue);
    rule.setCategoryId(categoryId);
    rule.setPriority(100 + rules.size());
    rules.add(rule);
  }

  private void mapping(String standard, String code, UUID categoryId) {
    when(categoryRepository.findMappedCategoryId(standard, code))
        .thenReturn(Optional.of(categoryId));
  }

  private static Category shipped(String code, UUID id) {
    Category category = new Category();
    ReflectionTestUtils.setField(category, "id", id);
    category.setCode(code);
    return category;
  }

  private static FuzzyCategoryCandidate candidate(
      UUID categoryId, String merchant, String similarity) {
    return new FuzzyCategoryCandidate() {
      @Override
      public UUID getCategoryId() {
        return categoryId;
      }

      @Override
      public String getMerchantDescription() {
        return merchant;
      }

      @Override
      public BigDecimal getSimilarity() {
        return new BigDecimal(similarity);
      }
    };
  }
}
