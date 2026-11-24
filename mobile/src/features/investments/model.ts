import {
  compareMagnitude,
  isNegativeAmount,
  type NetWorth,
} from '@/features/overview/overview-model';
import {
  isInvestmentDepot,
  type PortfolioAccount,
  type PortfolioView,
  type PositionsResponse,
} from '@/features/portfolio-view/model';
import { shownGain } from '@/features/portfolio-view/position-gain';

export type Depot = {
  account: PortfolioAccount;
  historyPortfolioId?: string;
};

export function investmentDepots(views: readonly (PortfolioView | undefined)[]): Depot[] {
  return views.flatMap((view) => {
    if (!view) return [];
    const onlyAccount = view.sections.flatMap((section) => section.accounts).length === 1;
    return view.sections.flatMap((section) =>
      section.accounts
        .filter((account) => isInvestmentDepot(account, section))
        .map((account) => ({
          account,
          historyPortfolioId: onlyAccount ? view.portfolioId : undefined,
        })),
    );
  });
}

/** Project the authoritative fields for the selected scope. */
export function investmentFigures(
  depot: Depot | undefined,
  netWorth: NetWorth | undefined,
  positions: PositionsResponse | undefined,
) {
  if (depot) {
    const { account } = depot;
    return {
      value: {
        amount: account.balance,
        currency: account.currency,
        known: account.valueKnown,
        status: account.valueStatus,
        asOf: account.valueSourceDate,
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
      section && netWorth
        ? {
            amount: section.subtotal,
            currency: netWorth.currency,
            known: section.valueStatus !== 'UNKNOWN',
            status: section.valueStatus,
            asOf: netWorth.asOf,
            complete: section.complete,
          }
        : undefined,
    performance: undefined,
  };
}

export type PositionOrder = 'value' | 'name' | 'gain';

export function orderPositions(response: PositionsResponse, order: PositionOrder, locale: string) {
  if (order === 'value') return response.positions;
  return [...response.positions].sort((left, right) => {
    if (order === 'name') return left.displayName.localeCompare(right.displayName, locale);
    const a = shownGain(left, response.currency)?.percent;
    const b = shownGain(right, response.currency)?.percent;
    if (a == null) return b == null ? 0 : 1;
    if (b == null) return -1;
    const aNegative = isNegativeAmount(a);
    const bNegative = isNegativeAmount(b);
    if (aNegative !== bNegative) return aNegative ? 1 : -1;
    return aNegative ? compareMagnitude(a, b) : compareMagnitude(b, a);
  });
}
