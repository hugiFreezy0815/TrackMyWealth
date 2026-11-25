package com.trackmywealth.backend.service;

import com.trackmywealth.backend.dto.CostFromOrders;
import com.trackmywealth.backend.dto.TradeLeg;
import com.trackmywealth.backend.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * US-15-15 (#714): a position without a cost basis gets one calculated from the account's buy and
 * sell orders, at their average price, once the orders explain the position - product owner,
 * 2026-11-23.
 *
 * <ul>
 *   <li>Average cost: a buy adds quantity x unit price, a sell takes out its share of the cost at
 *       the average price, so the average price stays. Fees are not part of it.
 *   <li>Only the orders up to the snapshot's date count (by trade date, else booking date), and
 *       only those in effect: a voided, reversed or deleted order does not.
 *   <li>The orders count day by day. The ledger holds no time of day, and rows of one import share
 *       their recording time, so nothing orders a same-day buy and sell. Buys among themselves, and
 *       sells among themselves, give the same cost in any order. A day with both counts its buys
 *       before its sells only when the order cannot change the cost: every lot in play - the units
 *       held before the day and the day's buys - has one unit price. Otherwise (say 10 held at 100,
 *       then a sale of 10 and a buy of 10 at 50 on one day: 500 or 750) the position has no cost
 *       basis (#719 review).
 *   <li>The orders must add up to exactly the quantity held: an order missing, a transfer-in or an
 *       opening holding without orders (#233), a split, or a sale of more than was bought leaves
 *       the position without a cost basis. Nothing is estimated (FR-PERF-018).
 *   <li>All orders of the security must be in one currency, the position's: the cost basis is in
 *       it. No FX is applied, so a foreign position has no account-currency cost (as #527/#589).
 * </ul>
 *
 * <p>The cost basis is derived when positions are read, never stored (FR-DAT-008): it follows the
 * orders as they are recorded, corrected, voided or rolled back, and a cost basis the snapshot
 * holds - printed by a statement, entered by the member - always comes first.
 */
@Service
public class OrderCostBasisService {

  private final TransactionRepository transactionRepository;

  public OrderCostBasisService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  /**
   * The buys and sells of {@code securityIds} in effect on {@code accountIds} up to {@code asOf},
   * per account and security. Only the securities asked for are read, not the account's whole order
   * history.
   */
  public Map<UUID, Map<UUID, List<TradeLeg>>> tradesFor(
      Collection<UUID> accountIds, Collection<UUID> securityIds, LocalDate asOf) {
    if (accountIds.isEmpty() || securityIds.isEmpty()) {
      return Map.of();
    }
    return transactionRepository.findTradesAsOf(accountIds, securityIds, asOf).stream()
        .collect(
            Collectors.groupingBy(
                TradeLeg::accountId, Collectors.groupingBy(TradeLeg::securityId)));
  }

  /** {@code securityId}'s trades on {@code accountId} out of {@link #tradesFor}; empty if none. */
  public static List<TradeLeg> tradesOf(
      Map<UUID, Map<UUID, List<TradeLeg>>> trades, UUID accountId, UUID securityId) {
    return trades.getOrDefault(accountId, Map.of()).getOrDefault(securityId, List.of());
  }

  /**
   * The cost basis of {@code heldQuantity} units of {@code securityId} held on {@code date}, from
   * the account's {@code trades}, in any order; empty when the orders do not explain the position
   * or a same-day buy and sell leave it open (see the class comment).
   *
   * @param positionCurrency the currency the position is quoted in, null when not reported
   */
  public static Optional<CostFromOrders> costBasis(
      List<TradeLeg> trades,
      UUID securityId,
      LocalDate date,
      BigDecimal heldQuantity,
      @Nullable String positionCurrency) {
    BigDecimal quantity = BigDecimal.ZERO;
    BigDecimal cost = BigDecimal.ZERO;
    String currency = null;
    List<TradeLeg> ordered =
        trades.stream()
            .filter(trade -> trade.securityId().equals(securityId))
            .filter(trade -> !trade.tradeDate().isAfter(date))
            .sorted(
                Comparator.comparing(TradeLeg::tradeDate)
                    .thenComparing(trade -> trade.quantity().signum() < 0))
            .toList();
    LocalDate checkedDay = null;
    for (TradeLeg trade : ordered) {
      if (!trade.tradeDate().equals(checkedDay)) {
        checkedDay = trade.tradeDate();
        if (sameDayOrderMatters(ordered, checkedDay, quantity, cost)) {
          return Optional.empty();
        }
      }
      if (currency != null && !currency.equals(trade.currency())) {
        return Optional.empty();
      }
      currency = trade.currency();
      if (trade.quantity().signum() > 0) {
        cost = cost.add(trade.quantity().multiply(trade.unitPrice()));
        quantity = quantity.add(trade.quantity());
        continue;
      }
      BigDecimal remaining = quantity.add(trade.quantity());
      if (remaining.signum() < 0) {
        // A sale of more than the orders bought: they do not explain the position.
        return Optional.empty();
      }
      cost =
          remaining.signum() == 0
              ? BigDecimal.ZERO
              : cost.multiply(remaining).divide(quantity, MathContext.DECIMAL128);
      quantity = remaining;
    }
    if (currency == null
        || quantity.compareTo(heldQuantity) != 0
        || positionCurrency != null && !positionCurrency.equals(currency)) {
      return Optional.empty();
    }
    return Optional.of(
        new CostFromOrders(
            cost.setScale(FxRateService.MONEY_SCALE, RoundingMode.HALF_UP), currency));
  }

  /**
   * Whether the unknown order of {@code day}'s buys and sells could change the cost: the day has
   * both, and the lots in play - {@code heldQuantity} held before the day at {@code heldCost}, and
   * the day's buys - do not all have one unit price.
   */
  private static boolean sameDayOrderMatters(
      List<TradeLeg> ordered, LocalDate day, BigDecimal heldQuantity, BigDecimal heldCost) {
    List<TradeLeg> sameDay =
        ordered.stream().filter(trade -> trade.tradeDate().equals(day)).toList();
    if (sameDay.stream().noneMatch(trade -> trade.quantity().signum() < 0)) {
      return false;
    }
    List<BigDecimal> buyPrices =
        sameDay.stream()
            .filter(trade -> trade.quantity().signum() > 0)
            .map(TradeLeg::unitPrice)
            .toList();
    if (buyPrices.isEmpty()) {
      return false;
    }
    BigDecimal price = buyPrices.get(0);
    if (buyPrices.stream().anyMatch(other -> other.compareTo(price) != 0)) {
      return true;
    }
    return heldQuantity.signum() > 0 && heldCost.compareTo(heldQuantity.multiply(price)) != 0;
  }
}
