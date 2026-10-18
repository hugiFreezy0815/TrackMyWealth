package com.trackmywealth.backend.camt;

import java.util.List;

/**
 * One {@code TxDtls} of an entry, as written; every field {@code null} when absent.
 *
 * @param amount {@code TxDtls/Amt} (from {@code .001.04}) or, before it, {@code AmtDtls/TxAmt/Amt}:
 *     the detail's share of the entry
 * @param creditDebit {@code TxDtls/CdtDbtInd}, absent before {@code .001.04}
 * @param instructedAmount {@code AmtDtls/InstdAmt/Amt}: the amount the payment was instructed in,
 *     such as a card purchase's original currency
 * @param exchangeRate {@code XchgRate} of the instructed or transaction amount's {@code CcyXchg}
 * @param accountServicerReference {@code Refs/AcctSvcrRef}, the bank's reference of the detail
 * @param endToEndId {@code Refs/EndToEndId}, chosen by the payment's originator: no booking id
 * @param creditorName {@code RltdPties/Cdtr/Nm}, or {@code Cdtr/Pty/Nm} from {@code .001.08}
 * @param debtorName as {@code creditorName}, for the debtor
 * @param remittance every {@code RmtInf/Ustrd} and {@code RmtInf/Strd/CdtrRefInf/Ref}, in order
 */
public record CamtTransaction(
    CamtAmount amount,
    String creditDebit,
    CamtAmount instructedAmount,
    String exchangeRate,
    String accountServicerReference,
    String endToEndId,
    String creditorName,
    String debtorName,
    List<String> remittance,
    String additionalInfo,
    CamtBankCode bankCode) {

  public CamtTransaction {
    remittance = List.copyOf(remittance);
  }
}
