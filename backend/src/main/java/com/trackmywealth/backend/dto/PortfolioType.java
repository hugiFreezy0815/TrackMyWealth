package com.trackmywealth.backend.dto;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a portfolio holds, for display only (US-04-06, FR-INS-006/008). It never restricts which
 * account types a portfolio may contain (RULE-020): a depot under a {@link #PILLAR_3A} portfolio is
 * as valid as any other combination. Stored in {@code portfolio.portfolio_type} (V112).
 *
 * <p>Unless the member has set it ({@code portfolio_type_overridden}), it is the type {@link
 * #suggestedFor} returns for the portfolio's active accounts, recomputed whenever one is added,
 * moved, archived or restored.
 */
public enum PortfolioType {
  BANK,
  PORTFOLIO,
  PILLAR_3A,
  PENSION_FUND,
  CRYPTO,
  PERSONAL_ASSETS,
  MIXED;

  /**
   * Every type a member may set, for a request field's {@code @Pattern}: all but {@link
   * #PERSONAL_ASSETS}, which belongs to the workspace's default portfolio alone (V19) and which
   * that portfolio always has. {@code PortfolioTypeTest} keeps the list complete.
   */
  public static final String SELECTABLE_PATTERN =
      "BANK|PORTFOLIO|PILLAR_3A|PENSION_FUND|CRYPTO|MIXED";

  /**
   * The suggested type of a portfolio whose active accounts fall into {@code sections}, one entry
   * per account (PO decision on #390, 2026-11-02):
   *
   * <ul>
   *   <li>the workspace's Personal Assets default is always {@link #PERSONAL_ASSETS};
   *   <li>a portfolio without active accounts is a {@link #BANK};
   *   <li>when every account's section belongs to the same {@link PortfolioSection#family()}, the
   *       portfolio is of that family;
   *   <li>otherwise, and whenever one account's section has no family (property, mortgage, other),
   *       it is {@link #MIXED}.
   * </ul>
   */
  public static PortfolioType suggestedFor(
      boolean personalAssetsDefault, Collection<PortfolioSection> sections) {
    if (personalAssetsDefault) {
      return PERSONAL_ASSETS;
    }
    if (sections.isEmpty()) {
      return BANK;
    }
    Set<PortfolioType> families = EnumSet.noneOf(PortfolioType.class);
    for (PortfolioSection section : sections) {
      PortfolioType family = section.family();
      if (family == null) {
        return MIXED;
      }
      families.add(family);
    }
    return switch (families.size()) {
      case 1 -> families.iterator().next();
      default -> MIXED;
    };
  }
}
