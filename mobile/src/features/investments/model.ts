import type { components } from '@/api/schema';
import {
  compareMagnitude,
  isNegativeAmount,
  type NetWorth,
} from '@/features/overview/overview-model';
import {
  isInvestmentDepot,
  type PortfolioAccount,
  type PortfolioView,
  type Position,
  type PositionSort,
  type PositionsResponse,
} from '@/features/portfolio-view/model';
import { shownGain } from '@/features/portfolio-view/position-gain';

type AccountBalance = components['schemas']['AccountValuation'];
type ValueStatus = PortfolioAccount['valueStatus'];

export type Depot = {
  account: PortfolioAccount;
  portfolioCurrency: string;
};

export function investmentDepots(views: readonly (PortfolioView | undefined)[]): Depot[] {
  return views.flatMap(
    (view) =>
      view?.sections.flatMap((section) =>
        section.accounts
          .filter((account) => isInvestmentDepot(account, section))
          .map((account) => ({ account, portfolioCurrency: view.portfolioCurrency })),
      ) ?? [],
  );
}

// The balance endpoint supplies the basis and flags for its own currency, as used by ValueStatus.of.
function balanceStatus(balance: AccountBalance): ValueStatus {
  if (!balance.valueKnown || balance.value == null) return 'UNKNOWN';
  if (balance.warnings.includes('OPEN_RECONCILIATION_DIFFERENCE')) return 'UNRECONCILED';
  if (balance.conversionRateStale || balance.valueSourceStale) return 'STALE';
  if (
    ['LEDGER_EMPTY', 'ORIGINAL_PRINCIPAL', 'ORIGINAL_PRINCIPAL_LESS_REPAYMENTS'].some(
      (basis) => basis === balance.valueBasis,
    ) ||
    balance.warnings.includes('TRANSACTIONS_BEFORE_OPENING_BALANCE')
  )
    return 'ESTIMATED';
  return 'CURRENT';
}

/** Select the server's figures and metadata that apply to the displayed currency. */
export function investmentFigures(
  depot: Depot | undefined,
  netWorth: NetWorth | undefined,
  positions: PositionsResponse | undefined,
  balance?: AccountBalance,
) {
  if (depot) {
    const { account, portfolioCurrency } = depot;
    const ownBalance =
      balance?.accountId === account.accountId && balance.currency === account.currency
        ? balance
        : undefined;
    const sharesCurrency = account.currency === portfolioCurrency;
    const amount = ownBalance ? ownBalance.value : account.balance;
    const status = ownBalance
      ? balanceStatus(ownBalance)
      : sharesCurrency
        ? account.valueStatus
        : undefined;
    return {
      value: {
        amount,
        currency: account.currency,
        known: ownBalance
          ? ownBalance.valueKnown && amount != null
          : amount != null && (!sharesCurrency || account.valueKnown),
        status,
        asOf: ownBalance
          ? ownBalance.valueSourceDate
          : sharesCurrency
            ? account.valueSourceDate
            : null,
        statusUnavailable: !ownBalance && !sharesCurrency && amount != null,
        complete: positions?.complete !== false,
      },
      performance: {
        amount: account.totalReturn,
        percent: account.totalReturnPercent,
        missingCostBasisCount: account.missingCostBasisCount,
        foreignCurrencyCostBasisCount: account.foreignCurrencyCostBasisCount,
        asOf: account.positionsAsOf,
      },
    };
  }
  const section = netWorth?.sections.find(({ section }) => section === 'DEPOT');
  return {
    value:
      section && netWorth && section.accountCount > 0
        ? {
            amount: section.subtotal,
            currency: netWorth.currency,
            known: section.valueStatus !== 'UNKNOWN',
            status: section.valueStatus,
            asOf: netWorth.asOf,
            statusUnavailable: false,
            complete: section.complete,
          }
        : undefined,
    performance: undefined,
  };
}

function compareDecimal(left: string, right: string) {
  const leftNegative = isNegativeAmount(left);
  const rightNegative = isNegativeAmount(right);
  if (leftNegative !== rightNegative) return leftNegative ? -1 : 1;
  return leftNegative ? compareMagnitude(right, left) : compareMagnitude(left, right);
}

function sortValue(position: Position, order: PositionSort['key'], currency: string) {
  if (order === 'units') return { amount: position.quantity };
  if (order === 'value') return { amount: position.valueInAccountCurrency };
  if (order === 'gain') return { amount: shownGain(position, currency)?.percent };
  return {
    amount: position.costBasisCurrency ? position.costBasis : null,
    currency: position.costBasisCurrency,
  };
}

/** Sort reported figures without converting amounts or calculating gains. */
export function orderPositions(response: PositionsResponse, sort: PositionSort, locale: string) {
  const { key, direction } = sort;
  if (key === 'value' && direction === 'descending') return response.positions;
  const sign = direction === 'ascending' ? 1 : -1;
  return [...response.positions].sort((left, right) => {
    if (key === 'name') return sign * left.displayName.localeCompare(right.displayName, locale);
    const a = sortValue(left, key, response.currency);
    const b = sortValue(right, key, response.currency);
    if (a.amount == null) return b.amount == null ? 0 : 1;
    if (b.amount == null) return -1;
    if (a.currency && b.currency && a.currency !== b.currency) {
      return a.currency.localeCompare(b.currency);
    }
    return sign * compareDecimal(a.amount, b.amount);
  });
}
