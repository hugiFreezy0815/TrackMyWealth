package com.trackmywealth.backend.dto;

import static com.trackmywealth.backend.dto.PortfolioSection.CARDS;
import static com.trackmywealth.backend.dto.PortfolioSection.CASH_AND_SAVINGS;
import static com.trackmywealth.backend.dto.PortfolioSection.CRYPTO;
import static com.trackmywealth.backend.dto.PortfolioSection.DEPOT;
import static com.trackmywealth.backend.dto.PortfolioSection.OTHER;
import static com.trackmywealth.backend.dto.PortfolioSection.PENSION;
import static com.trackmywealth.backend.dto.PortfolioSection.PILLAR_3A;
import static com.trackmywealth.backend.dto.PortfolioSection.PROPERTY_AND_MORTGAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** US-04-06: the suggested portfolio type (PO decision on #390, 2026-11-02). */
class PortfolioTypeTest {

  @Test
  void anEmptyPortfolioIsABank() {
    assertThat(PortfolioType.suggestedFor(false, List.of())).isEqualTo(PortfolioType.BANK);
  }

  @Test
  void thePersonalAssetsDefaultIsAlwaysPersonalAssets() {
    assertThat(PortfolioType.suggestedFor(true, List.of()))
        .isEqualTo(PortfolioType.PERSONAL_ASSETS);
    assertThat(PortfolioType.suggestedFor(true, List.of(DEPOT, CASH_AND_SAVINGS)))
        .isEqualTo(PortfolioType.PERSONAL_ASSETS);
  }

  @Test
  void accountsOfOneFamilyGiveThatFamily() {
    assertThat(PortfolioType.suggestedFor(false, List.of(CASH_AND_SAVINGS, CARDS)))
        .isEqualTo(PortfolioType.BANK);
    assertThat(PortfolioType.suggestedFor(false, List.of(PILLAR_3A, PILLAR_3A)))
        .isEqualTo(PortfolioType.PILLAR_3A);
    assertThat(PortfolioType.suggestedFor(false, List.of(PENSION)))
        .isEqualTo(PortfolioType.PENSION_FUND);
    assertThat(PortfolioType.suggestedFor(false, List.of(DEPOT)))
        .isEqualTo(PortfolioType.PORTFOLIO);
    assertThat(PortfolioType.suggestedFor(false, List.of(CRYPTO))).isEqualTo(PortfolioType.CRYPTO);
  }

  @Test
  void accountsOfSeveralFamiliesAreMixed() {
    // The PostFinance example of the story: private, savings, 3a and depot.
    assertThat(
            PortfolioType.suggestedFor(
                false, List.of(CASH_AND_SAVINGS, CASH_AND_SAVINGS, PILLAR_3A, DEPOT)))
        .isEqualTo(PortfolioType.MIXED);
  }

  @Test
  void anAccountWithoutAFamilyMakesThePortfolioMixed() {
    assertThat(PortfolioType.suggestedFor(false, List.of(PROPERTY_AND_MORTGAGE)))
        .isEqualTo(PortfolioType.MIXED);
    assertThat(PortfolioType.suggestedFor(false, List.of(CASH_AND_SAVINGS, OTHER)))
        .isEqualTo(PortfolioType.MIXED);
  }

  @Test
  void thePatternListsEveryType() {
    assertThat(PortfolioType.PATTERN)
        .isEqualTo(
            Arrays.stream(PortfolioType.values()).map(Enum::name).collect(Collectors.joining("|")));
  }

  @Test
  void sectionsAreDeclaredInTheOrderTheViewShowsThem() {
    assertThat(PortfolioSection.values())
        .containsExactly(
            CASH_AND_SAVINGS,
            PILLAR_3A,
            PENSION,
            DEPOT,
            CRYPTO,
            CARDS,
            PROPERTY_AND_MORTGAGE,
            OTHER);
  }
}
