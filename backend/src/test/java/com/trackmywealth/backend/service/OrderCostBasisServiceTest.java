package com.trackmywealth.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.trackmywealth.backend.dto.CostFromOrders;
import com.trackmywealth.backend.dto.TradeLeg;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** US-15-15 (#714): the average-cost rules for a cost basis from the orders. */
class OrderCostBasisServiceTest {

  private static final LocalDate DATE = LocalDate.of(2031, 12, 31);
  private final UUID account = UUID.randomUUID();
  private final UUID security = UUID.randomUUID();

  @Test
  void twoBuysGiveTheirTotalCostAtTheAveragePrice() {
    List<TradeLeg> trades = List.of(trade("20", "50"), trade("10", "80"));

    assertThat(cost(trades, "30", "CHF"))
        .contains(new CostFromOrders(new BigDecimal("1800.0000"), "CHF"));
  }

  @Test
  void aSaleKeepsTheAveragePrice() {
    // An average of 60.00 after the buys; 20 units left at 60.00.
    List<TradeLeg> trades = List.of(trade("20", "50"), trade("10", "80"), trade("-10", "90"));

    assertThat(cost(trades, "20", "CHF"))
        .contains(new CostFromOrders(new BigDecimal("1200.0000"), "CHF"));
  }

  @Test
  void aPositionSoldOffStartsAgainAtTheNextBuy() {
    List<TradeLeg> trades = List.of(trade("10", "50"), trade("-10", "70"), trade("5", "100"));

    assertThat(cost(trades, "5", "CHF"))
        .contains(new CostFromOrders(new BigDecimal("500.0000"), "CHF"));
  }

  @Test
  void anAverageThatDoesNotDivideEvenlyIsRoundedOnlyAtTheEnd() {
    // 3 units cost 100.00; selling 1 leaves two thirds of it: 66.6666... -> 66.6667.
    List<TradeLeg> trades = List.of(trade("1", "10"), trade("2", "45"), trade("-1", "1"));

    assertThat(cost(trades, "2", "CHF"))
        .contains(new CostFromOrders(new BigDecimal("66.6667"), "CHF"));
  }

  @Test
  void ordersThatDoNotAddUpToTheQuantityHeldGiveNone() {
    // An order missing, or a transfer-in / opening holding without an order (#233).
    assertThat(cost(List.of(trade("20", "50")), "30", "CHF")).isEmpty();
    assertThat(cost(List.of(trade("40", "50")), "30", "CHF")).isEmpty();
    assertThat(cost(List.of(), "30", "CHF")).isEmpty();
  }

  @Test
  void aSaleOfMoreThanWasBoughtGivesNone() {
    List<TradeLeg> trades = List.of(trade("10", "50"), trade("-15", "60"), trade("15", "40"));

    assertThat(cost(trades, "10", "CHF")).isEmpty();
  }

  @Test
  void ordersInAnotherOrMoreThanOneCurrencyGiveNone() {
    assertThat(cost(List.of(trade("10", "50", "USD")), "10", "CHF")).isEmpty();
    assertThat(cost(List.of(trade("5", "50", "CHF"), trade("5", "50", "EUR")), "10", null))
        .isEmpty();
    // A position quoted in no currency takes the orders' one.
    assertThat(cost(List.of(trade("10", "50", "USD")), "10", null))
        .contains(new CostFromOrders(new BigDecimal("500.0000"), "USD"));
  }

  @Test
  void onlyThisSecuritysOrdersUpToTheDateCount() {
    List<TradeLeg> trades =
        List.of(
            trade("10", "50"),
            new TradeLeg(account, UUID.randomUUID(), BigDecimal.TEN, BigDecimal.ONE, "CHF", DATE),
            new TradeLeg(
                account, security, BigDecimal.TEN, BigDecimal.ONE, "CHF", DATE.plusDays(1)));

    assertThat(cost(trades, "10", "CHF"))
        .contains(new CostFromOrders(new BigDecimal("500.0000"), "CHF"));
  }

  private Optional<CostFromOrders> cost(List<TradeLeg> trades, String held, String currency) {
    return OrderCostBasisService.costBasis(trades, security, DATE, new BigDecimal(held), currency);
  }

  private TradeLeg trade(String quantity, String price) {
    return trade(quantity, price, "CHF");
  }

  private TradeLeg trade(String quantity, String price, String currency) {
    return new TradeLeg(
        account, security, new BigDecimal(quantity), new BigDecimal(price), currency, DATE);
  }
}
