package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.SettlementMatchValues;
import com.trackmywealth.backend.entity.SettlementMatch;
import com.trackmywealth.backend.entity.Transaction;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** US-10-06: the rate a confirmed transfer implies, without a database. */
class SettlementMatchServiceTest {

  @Test
  void aCrossCurrencyTransferImpliesCreditOverDebit() {
    assertThat(
            SettlementMatchService.impliedTransferRate(
                transfer("-1000.00", "CHF", "1038.25", "EUR")))
        .isEqualByComparingTo("1.03825")
        .hasScaleOf(10);
  }

  // Rounded half-even at fx_rate's ten places, never truncated.
  @Test
  void theRateIsRoundedAtTenPlaces() {
    assertThat(SettlementMatchService.impliedTransferRate(transfer("-3.00", "CHF", "1.00", "EUR")))
        .isEqualByComparingTo("0.3333333333");
    assertThat(SettlementMatchService.impliedTransferRate(transfer("-3.00", "CHF", "2.00", "EUR")))
        .isEqualByComparingTo("0.6666666667");
  }

  @Test
  void aSameCurrencyTransferOrACardSettlementHasNone() {
    assertThat(
            SettlementMatchService.impliedTransferRate(transfer("-100.00", "CHF", "100.00", "CHF")))
        .isNull();
    SettlementMatch settlement = transfer("-100.00", "CHF", "95.00", "EUR");
    settlement.setMatchKind(SettlementMatchValues.CARD_SETTLEMENT);
    assertThat(SettlementMatchService.impliedTransferRate(settlement)).isNull();
  }

  private static SettlementMatch transfer(
      String debitAmount, String debitCurrency, String creditAmount, String creditCurrency) {
    SettlementMatch match = new SettlementMatch();
    match.setMatchKind(SettlementMatchValues.TRANSFER);
    match.setPaymentTransaction(leg(debitAmount, debitCurrency));
    match.setCardTransaction(leg(creditAmount, creditCurrency));
    return match;
  }

  private static Transaction leg(String amount, String currency) {
    Transaction leg = new Transaction();
    leg.setAmount(new BigDecimal(amount));
    leg.setCurrency(currency);
    return leg;
  }
}
