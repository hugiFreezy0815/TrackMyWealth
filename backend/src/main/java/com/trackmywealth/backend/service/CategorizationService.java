package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.FuzzyCategoryCandidate;
import com.trackmywealth.backend.dto.TransactionSourceCode;
import com.trackmywealth.backend.entity.CategorizationRule;
import com.trackmywealth.backend.entity.Transaction;
import com.trackmywealth.backend.entity.TransactionCategorizationLog;
import com.trackmywealth.backend.repository.CategorizationRuleRepository;
import com.trackmywealth.backend.repository.CategoryRepository;
import com.trackmywealth.backend.repository.TransactionCategorizationLogRepository;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 *   <li><b>FALLBACK_MATCH</b> - learning from the workspace's earlier rows that a rule or a user
 *       categorized: the most trigram-similar merchant description at or above the configured
 *       threshold, which must also share the merchant's brand (see {@link #brandOf}) - a shared
 *       city name alone ({@code COOP BASEL}, {@code MIGROS BASEL}) is as similar as a shared brand.
 *   <li>otherwise the protected {@code UNCATEGORIZED} default (FR-CAT-013), with no log row: the
 *       log says how a category was assigned, and nothing assigned this one.
 * </ol>
 *
 * <p>Only the cash and card types in {@link #CATEGORIZED_TYPES} are categorized; a settlement, a
 * trade or a dividend is not spending or income and would only crowd the Uncategorized list.
 * Categorizing writes {@code category_id} and a log row, never a financial field (FR-CAT-014). It
 * runs once, when the row is recorded; re-running it over history is US-08-03's, and a user's own
 * choice is US-08-02's, which later runs must respect.
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
  static final String ASSIGNED_BY_FALLBACK = "FALLBACK_MATCH";
  private static final String UNCATEGORIZED = "UNCATEGORIZED";
  // Enough headroom for candidates that fail the brand or assignability check below.
  private static final int FUZZY_CANDIDATES = 20;
  private static final int MIN_BRAND_LENGTH = 3;

  private final CategoryService categoryService;
  private final CategoryRepository categoryRepository;
  private final CategorizationRuleRepository ruleRepository;
  private final TransactionCategorizationLogRepository logRepository;
  private final TransactionRepository transactionRepository;
  private final ObjectMapper objectMapper;
  private final double fuzzyThreshold;

  public CategorizationService(
      CategoryService categoryService,
      CategoryRepository categoryRepository,
      CategorizationRuleRepository ruleRepository,
      TransactionCategorizationLogRepository logRepository,
      TransactionRepository transactionRepository,
      ObjectMapper objectMapper,
      @Value("${app.categorization.fuzzy-similarity-threshold}") double fuzzyThreshold) {
    this.categoryService = categoryService;
    this.categoryRepository = categoryRepository;
    this.ruleRepository = ruleRepository;
    this.logRepository = logRepository;
    this.transactionRepository = transactionRepository;
    this.objectMapper = objectMapper;
    this.fuzzyThreshold = fuzzyThreshold;
  }

  /**
   * Categorizes a just-saved transaction in the caller's transaction and returns how the category
   * was assigned - empty when the row landed in UNCATEGORIZED or its type is not categorized.
   */
  @Transactional
  public Optional<String> categorize(Transaction transaction) {
    if (!CATEGORIZED_TYPES.contains(transaction.getTransactionType())) {
      return Optional.empty();
    }
    UUID workspaceId = transaction.getWorkspace().getId();
    Set<UUID> assignable = categoryService.assignableCategoryIds(workspaceId);
    String merchant = normalizeMerchant(transaction.getMerchantDescription());
    List<TransactionSourceCode> codes = sourceCodes(transaction.getRawSourceData());

    for (CategorizationRule rule :
        ruleRepository.findByWorkspaceIdAndActiveTrueOrderByPriorityAscCreatedAtAscIdAsc(
            workspaceId)) {
      if (assignable.contains(rule.getCategoryId()) && matches(rule, merchant, codes)) {
        return assign(transaction, rule.getCategoryId(), ASSIGNED_BY_RULE, rule.getId(), null);
      }
    }
    for (TransactionSourceCode code : codes) {
      Optional<UUID> mapped = categoryRepository.findMappedCategoryId(code.standard(), code.code());
      if (mapped.isPresent() && assignable.contains(mapped.get())) {
        return assign(transaction, mapped.get(), ASSIGNED_BY_SOURCE_CODE, null, null);
      }
    }
    Optional<FuzzyCategoryCandidate> similar = fuzzyMatch(transaction, workspaceId, assignable);
    if (similar.isPresent()) {
      return assign(
          transaction,
          similar.get().getCategoryId(),
          ASSIGNED_BY_FALLBACK,
          null,
          similar.get().getSimilarity());
    }
    categoryRepository
        .findByWorkspaceIdIsNullAndCode(UNCATEGORIZED)
        .ifPresent(
            uncategorized -> {
              transaction.setCategoryId(uncategorized.getId());
              transactionRepository.saveAndFlush(transaction);
            });
    return Optional.empty();
  }

  /** The id of the shipped UNCATEGORIZED default, for the actionable list (FR-CAT-013). */
  @Transactional(readOnly = true)
  public UUID uncategorizedCategoryId() {
    return categoryRepository
        .findByWorkspaceIdIsNullAndCode(UNCATEGORIZED)
        .orElseThrow(() -> new IllegalStateException("V19 seeds the UNCATEGORIZED category."))
        .getId();
  }

  private Optional<String> assign(
      Transaction transaction,
      UUID categoryId,
      String assignedBy,
      UUID ruleId,
      BigDecimal confidence) {
    transaction.setCategoryId(categoryId);
    transactionRepository.saveAndFlush(transaction);
    logRepository.save(
        new TransactionCategorizationLog(
            transaction.getId(), categoryId, assignedBy, ruleId, confidence));
    return Optional.of(assignedBy);
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
