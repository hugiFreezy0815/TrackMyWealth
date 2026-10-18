package com.trackmywealth.backend.camt;

import java.util.List;

/**
 * One {@code Ntry} of a statement or report, as written; every text field {@code null} when absent.
 *
 * @param status {@code Sts} up to {@code .001.04}, {@code Sts/Cd} (or {@code Sts/Prtry}) from
 *     {@code .001.06}: {@code BOOK}, {@code PDNG}, {@code INFO}, ...
 * @param reversal {@code RvslInd}: {@code true} when the entry reverses an earlier one
 * @param accountServicerReference {@code AcctSvcrRef}, the bank's reference of the entry
 * @param declaredTransactions {@code NtryDtls/Btch/NbOfTxs}: how many transactions a collective
 *     entry says it holds
 * @param transactions its {@code TxDtls} across all {@code NtryDtls}, in file order, at most {@link
 *     CamtXmlReader#MAX_TRANSACTIONS_PER_ENTRY}
 * @param transactionsOmitted the entry has more {@code TxDtls} than were read: they cannot be
 *     reconciled with its amount
 */
public record CamtEntry(
    CamtAmount amount,
    String creditDebit,
    String reversal,
    String status,
    CamtDate bookingDate,
    CamtDate valueDate,
    String accountServicerReference,
    String additionalInfo,
    CamtBankCode bankCode,
    String declaredTransactions,
    List<CamtTransaction> transactions,
    boolean transactionsOmitted) {

  public CamtEntry {
    transactions = List.copyOf(transactions);
  }
}
