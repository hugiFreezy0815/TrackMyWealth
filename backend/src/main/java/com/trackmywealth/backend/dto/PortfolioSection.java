package com.trackmywealth.backend.dto;

/**
 * The section of the Portfolio View an account is shown in (US-04-06, FR-INS-013), declared in the
 * order the view lists them. {@code AccountService#portfolioPlacementsOf} decides an account's
 * section from its type - the only place allowed to branch on it (US-05-04) - so a Pillar 3a is a
 * {@code PENSION} account with scheme {@code CH_PILLAR_3A}, and property is a {@code CUSTOM_ASSET}
 * of type {@code REAL_ESTATE}.
 */
public enum PortfolioSection {
  /** Current and savings accounts. */
  CASH_AND_SAVINGS(PortfolioType.BANK),
  /** Swiss Pillar 3a accounts, whether they hold positions or not. */
  PILLAR_3A(PortfolioType.PILLAR_3A),
  /** Vested benefits and every other pension scheme. */
  PENSION(PortfolioType.PENSION_FUND),
  /** Securities depots and managed mandates. */
  DEPOT(PortfolioType.PORTFOLIO),
  CRYPTO(PortfolioType.CRYPTO),
  /** Credit cards. */
  CARDS(PortfolioType.BANK),
  /** Real estate and the mortgages financing it. */
  PROPERTY_AND_MORTGAGE(null),
  /** Loans and every custom asset other than real estate. */
  OTHER(null);

  private final PortfolioType typeFamily;

  PortfolioSection(PortfolioType typeFamily) {
    this.typeFamily = typeFamily;
  }

  /**
   * The portfolio type a portfolio holding only accounts of this section gets, see {@link
   * PortfolioType#suggestedFor}; {@code null} when such accounts make a portfolio {@link
   * PortfolioType#MIXED}.
   */
  public PortfolioType family() {
    return typeFamily;
  }
}
