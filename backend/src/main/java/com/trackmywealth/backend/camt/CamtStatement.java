package com.trackmywealth.backend.camt;

import java.util.List;

/**
 * One {@code Stmt} of a {@code camt.053} or {@code Rpt} of a {@code camt.052}: the account it is
 * for, its balances and its entries in file order.
 *
 * @param iban {@code Acct/Id/IBAN} as written, {@code null} for an account without one
 * @param otherAccountId {@code Acct/Id/Othr/Id}, the account's identifier when it has no IBAN
 */
public record CamtStatement(
    String iban, String otherAccountId, List<CamtBalance> balances, List<CamtEntry> entries) {

  public CamtStatement {
    balances = List.copyOf(balances);
    entries = List.copyOf(entries);
  }
}
