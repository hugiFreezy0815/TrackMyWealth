package com.trackmywealth.backend.camt;

/**
 * A {@code BkTxCd}: the ISO domain, family and sub-family codes ({@code Domn/Cd}, {@code
 * Domn/Fmly/Cd}, {@code Domn/Fmly/SubFmlyCd}) and the proprietary code with its issuer ({@code
 * Prtry/Cd}, {@code Prtry/Issr}), each {@code null} when absent.
 */
public record CamtBankCode(
    String domain, String family, String subFamily, String proprietary, String issuer) {

  public boolean isEmpty() {
    return domain == null && proprietary == null;
  }
}
