package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CategoryDecision;
import com.trackmywealth.backend.dto.FuzzyCategoryCandidate;
import com.trackmywealth.backend.dto.LatestCategoryAssignment;
import com.trackmywealth.backend.dto.TransactionSourceCode;
import com.trackmywealth.backend.entity.CategorizationRule;
import com.trackmywealth.backend.entity.Category;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.TransactionCategorizationLog;
import com.trackmywealth.backend.repository.CategorizationRuleRepository;
import com.trackmywealth.backend.repository.CategoryRepository;
import com.trackmywealth.backend.repository.TransactionCategorizationLogRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * US-08-01/FR-CAT-005/007/009..013: assigns a reporting category to a newly recorded transaction,
 * trying four layers in order and stopping at the first that yields a category the workspace may
 * assign (active, every ancestor included - {@link CategoryService#assignableCategoryIds}):
 *
 * <ol>
 *   <li><b>RULE</b> - the workspace's own active rules, lowest priority first. A rule is explicit
 *       user intent, so it beats a shipped mapping (FR-CAT-011: an MCC often names the acquirer,
 *       not the merchant).
 *   <li><b>SOURCE_CODE</b> - the shipped mapping ({@code category_source_mapping}, V37) of the
 *       row's source codes, most specific first: ISO 20022 purpose, bank transaction code, MCC. A
 *       code with no mapping falls through rather than straight to UNCATEGORIZED, since mapping
 *       gaps are common.
 *   <li><b>TRANSACTION_TYPE</b> - the category the row's own type implies: a {@code FEE} is in
 *       {@code FEES} (e.g. a card's foreign-transaction fee row, which has no merchant or code), a
 *       {@code TAX} in {@code TAXES}. Certain where it applies, so it comes before any guess.
 *   <li><b>FALLBACK_MATCH</b> - learning from the workspace's earlier rows that a rule or a user
 *       categorized: the most trigram-similar merchant description at or above the configured
 *       threshold, which must also share the merchant's brand (see {@link #brandOf}) - a shared
 *       city name alone ({@code COOP BASEL}, {@code MIGROS BASEL}) is as similar as a shared brand.
 *   <li>otherwise the protected {@code UNCATEGORIZED} default (FR-CAT-013), with no log row: the
 *       log says how a category was assigned, and nothing assigned this one.
 * </ol>
 *
 * <p>{@link #categorizeAll} categorizes many rows (an import) against one load of each workspace's
 * taxonomy, rules and shipped defaults, and resolves each distinct source code once; {@link
 * #categorize} is the same for one row. The fuzzy query is served by V10's trigram index: it
 * filters with pg_trgm's {@code %} operator under a threshold set for the current transaction only
 * (never the session - connections are pooled), and rechecks the exact threshold.
 *
 * <p>Only the cash and card types in {@link #CATEGORIZED_TYPES} are categorized; a settlement, a
 * trade or a dividend is not spending or income and would only crowd the Uncategorized list.
 * Categorizing writes {@code category_id} and a log row, never a financial field (FR-CAT-014). It
 * runs when the row is recorded; {@link #recategorizeWorkspace} re-runs it over existing rows (the
 * user-facing re-run with a preview is US-08-03's). A member's own choice ({@link #override},
 * US-08-02) is never replaced by any automatic path (RULE-031, FR-CAT-014), until the member resets
 * it ({@link #resetToAutomatic}).
 *
 * <p>Source codes are read from {@code raw_source_data}: {@code mcc} (four digits, string or
 * number), {@code purposeCode} (e.g. {@code SALA}) and {@code bankTransactionCode} as {@code
 * DOMAIN-FAMILY-SUBFAMILY} (e.g. {@code PMNT-RCDT-ESCT}) - the keys a camt import (EPIC 07) is to
 * write. The same strings, prefixed by their standard ({@code MCC:5812}), are what a {@code
 * SOURCE_CODE} rule matches.
 */
@Service
public class CategorizationService {

  static final Set<String> CATEGORIZED_TYPES =
      Set.of(
          "INCOME",
          "EXPENSE",
          "DEPOSIT",
          "WITHDRAWAL",
          "INTEREST",
          "FEE",
          "TAX",
          "REFUND",
          "CREDIT_CARD_PURCHASE");
  static final String MERCHANT = "MERCHANT";
  static final String SOURCE_CODE = "SOURCE_CODE";
  static final String MCC = "MCC";
  static final String ISO20022_PURPOSE = "ISO20022_PURPOSE";
  static final String ISO20022_BTC = "ISO20022_BTC";
  static final String ASSIGNED_BY_RULE = "RULE";
  static final String ASSIGNED_BY_SOURCE_CODE = "SOURCE_CODE";
  static final String ASSIGNED_BY_TRANSACTION_TYPE = "TRANSACTION_TYPE";
  static final String ASSIGNED_BY_FALLBACK = "FALLBACK_MATCH";
  private static final String UNCATEGORIZED = "UNCATEGORIZED";
  // A re-run's page: small enough for one IN list and one persistence context, large enough to keep
  // the per-page taxonomy load negligible.
  public static final int RECATEGORIZATION_PAGE_SIZE = 500;
  // Keyset start: every row was created after the epoch, and the nil UUID sorts first.
  private static final OffsetDateTime BEFORE_ANY_ROW =
      OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private static final UUID NIL_ID = new UUID(0L, 0L);
  // The shipped default a type implies when nothing more specific applies (V37).
  static final Map<String, String> TYPE_CATEGORY_CODES = Map.of("FEE", "FEES", "TAX", "TAXES");
  private static final Set<String> SHIPPED_CODES = Set.of(UNCATEGORIZED, "FEES", "TAXES");
  // Enough headroom for candidates that fail the brand or assignability check below.
  private static final int FUZZY_CANDIDATES = 20;
  private static final int MIN_BRAND_LENGTH = 3;

  private final CategoryService categoryService;
  private final CategoryRepository categoryRepository;
  private final CategorizationRuleRepository ruleRepository;
  private final TransactionCategorizationLogRepository logRepository;
  private final TransactionRepository transactionRepository;
  private final ObjectMapper objectMapper;
  private final EntityManager entityManager;
  private final double fuzzyThreshold;

  public CategorizationService(
      CategoryService categoryService,
      CategoryRepository categoryRepository,
      CategorizationRuleRepository ruleRepository,
      TransactionCategorizationLogRepository logRepository,
      TransactionRepository transactionRepository,
      ObjectMapper objectMapper,
      EntityManager entityManager,
      @Value("${app.categorization.fuzzy-similarity-threshold}") double fuzzyThreshold) {
    this.categoryService = categoryService;
    this.categoryRepository = categoryRepository;
    this.ruleRepository = ruleRepository;
    this.logRepository = logRepository;
    this.transactionRepository = transactionRepository;
    this.objectMapper = objectMapper;
    this.entityManager = entityManager;
    this.fuzzyThreshold = fuzzyThreshold;
  }

  /**
   * Categorizes a just-saved transaction in the caller's transaction and returns how the category
   * was assigned - empty when the row landed in UNCATEGORIZED or its type is not categorized.
   */
  @Transactional
  public Optional<String> categorize(Transaction transaction) {
    return Optional.ofNullable(categorizeAll(List.of(transaction)).get(transaction.getId()));
  }

  /**
   * Categorizes just-saved transactions (e.g. one import) in the caller's transaction, loading each
   * workspace's taxonomy, rules and shipped defaults once, and returns how each categorized row was
   * assigned, by transaction id; a row that landed in UNCATEGORIZED or is not categorized is
   * absent.
   */
  @Transactional
  public Map<UUID, String> categorizeAll(List<Transaction> transactions) {
    Map<UUID, String> assignedBy = new HashMap<>();
    forEachDecision(
        transactions,
        overridden(currentAssignments(transactions)),
        (transaction, decision) -> {
          apply(transaction, decision);
          if (decision.assignedBy() != null) {
            assignedBy.put(transaction.getId(), decision.assignedBy());
          }
        });
    return assignedBy;
  }

  /**
   * US-08-02: re-runs the automatic layers over a workspace's existing, non-voided rows - after a
   * new rule, for instance - and writes a row only where its assignment changes: another category,
   * or the same category now reached another way (e.g. a rule instead of a fuzzy guess), so the log
   * keeps saying how the category was found. An unchanged decision writes nothing, so repeated
   * re-runs do not grow the log. A row whose current category is a member's override is skipped
   * (RULE-031, FR-CAT-014). Returns how many rows were written. Internal for now: the user-facing
   * re-run with a preview is US-08-03's.
   *
   * <p>Rows are processed in pages of {@value #RECATEGORIZATION_PAGE_SIZE}, in creation order, each
   * locked FOR UPDATE before its overrides are checked. An override locks its row too, so either it
   * committed first and is seen here, or it waits for this run and then wins. Every page's rows
   * stay locked until this transaction ends, and the persistence context is cleared after each
   * page, so memory stays flat however large the workspace is.
   */
  @Transactional
  public int recategorizeWorkspace(UUID workspaceId) {
    int written = 0;
    OffsetDateTime afterCreatedAt = BEFORE_ANY_ROW;
    UUID afterId = NIL_ID;
    List<Transaction> page;
    do {
      page =
          transactionRepository.lockRecategorizationPage(
              workspaceId, CATEGORIZED_TYPES, afterCreatedAt, afterId, RECATEGORIZATION_PAGE_SIZE);
      if (page.isEmpty()) {
        break;
      }
      Map<UUID, LatestCategoryAssignment> current = currentAssignments(page);
      int[] writtenInPage = {0};
      forEachDecision(
          page,
          overridden(current),
          (transaction, decision) -> {
            if (!unchanged(transaction, current.get(transaction.getId()), decision)) {
              apply(transaction, decision);
              writtenInPage[0]++;
            }
          });
      written += writtenInPage[0];
      Transaction last = page.get(page.size() - 1);
      afterCreatedAt = last.getCreatedAt();
      afterId = last.getId();
      entityManager.flush();
      entityManager.clear();
    } while (page.size() == RECATEGORIZATION_PAGE_SIZE);
    return written;
  }

  /**
   * US-08-02: a member's own choice. Written as a {@code USER} log row with {@code
   * is_user_override} naming the member who made it (V38), which every automatic path then
   * respects. The caller locks the row first ({@code TransactionRepository#findByIdForUpdate}). The
   * category must be assignable for the workspace (404 unknown, 422 inactive) and cannot be
   * UNCATEGORIZED: resetting to automatic is the way back to it.
   */
  @Transactional
  public void override(Transaction transaction, UUID categoryId, UUID userId) {
    categoryService.requireAssignable(categoryId, transaction.getWorkspace().getId());
    if (categoryId.equals(uncategorizedCategoryId())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "A transaction cannot be set to UNCATEGORIZED; reset it to automatic instead.");
    }
    transaction.setCategoryId(categoryId);
    transactionRepository.saveAndFlush(transaction);
    logRepository.save(
        TransactionCategorizationLog.ofUserOverride(transaction.getId(), categoryId, userId));
  }

  /**
   * US-08-02 "reset to automatic": runs the automatic layers again as for a new row and returns how
   * the category was assigned (empty for UNCATEGORIZED). A type the engine never categorizes goes
   * back to having no category. The override log row stays as history; it stops counting because it
   * no longer describes the current category, or a newer automatic row follows it.
   */
  @Transactional
  public Optional<String> resetToAutomatic(Transaction transaction) {
    if (!CATEGORIZED_TYPES.contains(transaction.getTransactionType())) {
      transaction.setCategoryId(null);
      transactionRepository.saveAndFlush(transaction);
      return Optional.empty();
    }
    String[] assignedBy = {null};
    forEachDecision(
        List.of(transaction),
        Set.of(),
        (row, decision) -> {
          apply(row, decision);
          assignedBy[0] = decision.assignedBy();
        });
    return Optional.ofNullable(assignedBy[0]);
  }

  /** Whether the transaction's current category is a member's override (US-08-02). */
  @Transactional(readOnly = true)
  public boolean isOverridden(Transaction transaction) {
    return !overridden(currentAssignments(List.of(transaction))).isEmpty();
  }

  // Loads each workspace's taxonomy, rules and shipped defaults once, skips the given rows (those a
  // member has overridden, unless the member is resetting that override), and hands each remaining
  // categorized-type row its decision.
  private void forEachDecision(
      List<Transaction> transactions,
      Set<UUID> skipped,
      BiConsumer<Transaction, CategoryDecision> action) {
    Map<UUID, List<Transaction>> byWorkspace = new LinkedHashMap<>();
    for (Transaction transaction : transactions) {
      if (CATEGORIZED_TYPES.contains(transaction.getTransactionType())
          && !skipped.contains(transaction.getId())) {
        byWorkspace
            .computeIfAbsent(transaction.getWorkspace().getId(), id -> new ArrayList<>())
            .add(transaction);
      }
    }
    boolean thresholdSet = false;
    for (Map.Entry<UUID, List<Transaction>> group : byWorkspace.entrySet()) {
      UUID workspaceId = group.getKey();
      Set<UUID> assignable = categoryService.assignableCategoryIds(workspaceId);
      List<CategorizationRule> rules =
          ruleRepository.findByWorkspaceIdAndActiveTrueOrderByPriorityAscCreatedAtAscIdAsc(
              workspaceId);
      Map<String, UUID> shipped = new HashMap<>();
      for (Category category : categoryRepository.findByWorkspaceIdIsNullAndCodeIn(SHIPPED_CODES)) {
        shipped.put(category.getCode(), category.getId());
      }
      Map<TransactionSourceCode, Optional<UUID>> mappings = new HashMap<>();
      for (Transaction transaction : group.getValue()) {
        if (!thresholdSet && brandOf(transaction.getMerchantDescription()) != null) {
          // Transaction-local (is_local = true): it ends with this transaction, so it can never
          // leak to another request on the same pooled connection.
          transactionRepository.setSimilarityThresholdForTransaction(
              Double.toString(fuzzyThreshold));
          thresholdSet = true;
        }
        action.accept(transaction, decide(transaction, assignable, rules, shipped, mappings));
      }
    }
  }

  // How each given row got its current category: its latest log row, where that row still
  // describes the category the transaction holds (LatestCategoryAssignment#describes). A row with
  // no category, or whose latest log row is stale (e.g. an override reset to UNCATEGORIZED), is
  // absent. One query for the given rows, which callers keep to a page.
  private Map<UUID, LatestCategoryAssignment> currentAssignments(List<Transaction> transactions) {
    Map<UUID, UUID> currentCategory = new HashMap<>();
    for (Transaction transaction : transactions) {
      if (transaction.getId() != null && transaction.getCategoryId() != null) {
        currentCategory.put(transaction.getId(), transaction.getCategoryId());
      }
    }
    if (currentCategory.isEmpty()) {
      return Map.of();
    }
    Map<UUID, LatestCategoryAssignment> current = new HashMap<>();
    for (LatestCategoryAssignment latest :
        logRepository.findLatestAssignments(currentCategory.keySet())) {
      if (latest.describes(currentCategory.get(latest.getTransactionId()))) {
        current.put(latest.getTransactionId(), latest);
      }
    }
    return current;
  }

  // The rows whose current category is a member's override.
  private static Set<UUID> overridden(Map<UUID, LatestCategoryAssignment> current) {
    Set<UUID> overridden = new HashSet<>();
    current.forEach(
        (transactionId, latest) -> {
          if (latest.isUserOverride()) {
            overridden.add(transactionId);
          }
        });
    return overridden;
  }

  // Whether the decision would record what the row already holds: the same category, found the
  // same way (the same rule, for a rule). A fuzzy match's confidence may drift between runs
  // without it counting as a change.
  private static boolean unchanged(
      Transaction transaction, LatestCategoryAssignment current, CategoryDecision decision) {
    if (!Objects.equals(transaction.getCategoryId(), decision.categoryId())) {
      return false;
    }
    String currentAssignedBy = current == null ? null : current.getAssignedBy();
    UUID currentRuleId = current == null ? null : current.getRuleId();
    return Objects.equals(currentAssignedBy, decision.assignedBy())
        && Objects.equals(currentRuleId, decision.ruleId());
  }

  private CategoryDecision decide(
      Transaction transaction,
      Set<UUID> assignable,
      List<CategorizationRule> rules,
      Map<String, UUID> shipped,
      Map<TransactionSourceCode, Optional<UUID>> mappings) {
    String merchant = normalizeMerchant(transaction.getMerchantDescription());
    List<TransactionSourceCode> codes = sourceCodes(transaction.getRawSourceData());

    for (CategorizationRule rule : rules) {
      if (assignable.contains(rule.getCategoryId()) && matches(rule, merchant, codes)) {
        return new CategoryDecision(rule.getCategoryId(), ASSIGNED_BY_RULE, rule.getId(), null);
      }
    }
    for (TransactionSourceCode code : codes) {
      Optional<UUID> mapped =
          mappings.computeIfAbsent(
              code, key -> categoryRepository.findMappedCategoryId(key.standard(), key.code()));
      if (mapped.isPresent() && assignable.contains(mapped.get())) {
        return new CategoryDecision(mapped.get(), ASSIGNED_BY_SOURCE_CODE, null, null);
      }
    }
    UUID typed = shipped.get(TYPE_CATEGORY_CODES.get(transaction.getTransactionType()));
    if (typed != null && assignable.contains(typed)) {
      return new CategoryDecision(typed, ASSIGNED_BY_TRANSACTION_TYPE, null, null);
    }
    Optional<FuzzyCategoryCandidate> similar =
        fuzzyMatch(transaction, transaction.getWorkspace().getId(), assignable);
    if (similar.isPresent()) {
      return new CategoryDecision(
          similar.get().getCategoryId(), ASSIGNED_BY_FALLBACK, null, similar.get().getSimilarity());
    }
    return new CategoryDecision(shipped.get(UNCATEGORIZED), null, null, null);
  }

  /** The id of the shipped UNCATEGORIZED default, for the actionable list (FR-CAT-013). */
  @Transactional(readOnly = true)
  public UUID uncategorizedCategoryId() {
    return categoryRepository
        .findByWorkspaceIdIsNullAndCode(UNCATEGORIZED)
        .orElseThrow(() -> new IllegalStateException("V19 seeds the UNCATEGORIZED category."))
        .getId();
  }

  // UNCATEGORIZED (no assignedBy) is recorded without a log row: nothing assigned it.
  private void apply(Transaction transaction, CategoryDecision decision) {
    transaction.setCategoryId(decision.categoryId());
    transactionRepository.saveAndFlush(transaction);
    if (decision.assignedBy() != null) {
      logRepository.save(
          new TransactionCategorizationLog(
              transaction.getId(),
              decision.categoryId(),
              decision.assignedBy(),
              decision.ruleId(),
              decision.confidence()));
    }
  }

  private static boolean matches(
      CategorizationRule rule, String merchant, List<TransactionSourceCode> codes) {
    if (MERCHANT.equals(rule.getMatchType())) {
      return merchant != null && merchant.contains(normalizeMerchant(rule.getMatchValue()));
    }
    if (SOURCE_CODE.equals(rule.getMatchType())) {
      return codes.stream().anyMatch(code -> code.asRuleValue().equals(rule.getMatchValue()));
    }
    return false; // COUNTERPARTY_IBAN/AMOUNT_PATTERN: not creatable until imports supply the data
  }

  private Optional<FuzzyCategoryCandidate> fuzzyMatch(
      Transaction transaction, UUID workspaceId, Set<UUID> assignable) {
    String brand = brandOf(transaction.getMerchantDescription());
    if (brand == null) {
      return Optional.empty();
    }
    return transactionRepository
        .findFuzzyCandidates(
            workspaceId,
            transaction.getId(),
            transaction.getMerchantDescription(),
            fuzzyThreshold,
            FUZZY_CANDIDATES)
        .stream()
        .filter(candidate -> assignable.contains(candidate.getCategoryId()))
        .filter(candidate -> brand.equals(brandOf(candidate.getMerchantDescription())))
        .findFirst();
  }

  /**
   * Lower case, whitespace collapsed: {@code "MIGROS Zürich"} and {@code "migros zürich"} are the
   * same merchant for a {@code MERCHANT} rule. Package-visible for the rule service, which stores
   * rule values the same way.
   */
  static String normalizeMerchant(String merchant) {
    if (merchant == null) {
      return null;
    }
    String normalized = merchant.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    return normalized.isEmpty() ? null : normalized;
  }

  /**
   * The merchant's leading word of at least three letters, after a payment processor's {@code *}
   * separator if there is one ({@code "SUMUP *CAFE ROSSO"} is the café, not SumUp). {@code null}
   * when there is none, which rules out a fuzzy match.
   */
  static String brandOf(String merchant) {
    if (merchant == null) {
      return null;
    }
    String name = merchant.substring(merchant.lastIndexOf('*') + 1);
    for (String word : name.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
      if (word.length() >= MIN_BRAND_LENGTH) {
        return word;
      }
    }
    return null;
  }

  // Most specific first: a purpose code says what a payment is for, a bank transaction code only
  // how it moved, and an MCC names the acquirer's business.
  private List<TransactionSourceCode> sourceCodes(String rawSourceData) {
    List<TransactionSourceCode> codes = new ArrayList<>();
    if (rawSourceData == null) {
      return codes;
    }
    JsonNode root = objectMapper.readTree(rawSourceData);
    text(root.path("purposeCode"))
        .ifPresent(code -> codes.add(new TransactionSourceCode(ISO20022_PURPOSE, code)));
    text(root.path("bankTransactionCode"))
        .ifPresent(code -> codes.add(new TransactionSourceCode(ISO20022_BTC, code)));
    JsonNode mcc = root.path("mcc");
    if (mcc.isIntegralNumber()) {
      codes.add(new TransactionSourceCode(MCC, String.format("%04d", mcc.longValue())));
    } else {
      text(mcc).ifPresent(code -> codes.add(new TransactionSourceCode(MCC, code)));
    }
    return codes;
  }

  private static Optional<String> text(JsonNode node) {
    if (!node.isString() || node.stringValue().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(node.stringValue().strip().toUpperCase(Locale.ROOT));
  }
}
