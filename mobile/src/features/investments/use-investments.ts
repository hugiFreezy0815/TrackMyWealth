import { useQueries } from '@tanstack/react-query';

import {
  accountPositionsQuery,
  portfolioViewQuery,
  useAccount,
  useAccountBalance,
  useNetWorth,
  usePortfolios,
  useRecentAccountActivity,
  useWorkspace,
} from '@/api/queries/resources';

import { needsHoldsPositions } from '@/features/portfolio-view/model';
import { usePositionAccounts } from '@/features/portfolio-view/use-position-accounts';

import { investmentDepots, investmentFigures } from './model';

/** `scopeCurrency`: the currency of the Overview Depot tile that opened the screen, if any. */
export function useInvestments(accountId?: string, scopeCurrency?: string) {
  const portfolios = usePortfolios();
  const views = useQueries({
    queries: (portfolios.data ?? []).map(({ id }) => portfolioViewQuery(id)),
  });
  const summaryIds = views.flatMap(
    ({ data }) =>
      data?.sections.flatMap((section) =>
        section.accounts
          .filter((account) => needsHoldsPositions(account, section))
          .map(({ accountId }) => accountId),
      ) ?? [],
  );
  const positionAccounts = usePositionAccounts(summaryIds);
  const depots = investmentDepots(
    views.map(({ data }) => data),
    positionAccounts.ids,
  );
  const depot = depots.find(({ account }) => account.accountId === accountId);
  const depotView = depot
    ? views.find(({ data }) =>
        data?.sections.some(({ accounts }) =>
          accounts.some((account) => account.accountId === depot.account.accountId),
        ),
      )
    : undefined;
  const directoryQueries = [portfolios, ...views, ...positionAccounts.queries];
  const directoryPending = directoryQueries.some(({ isPending }) => isPending);
  const directoryComplete =
    portfolios.data != null &&
    views.every(({ data }) => data != null) &&
    positionAccounts.queries.every(({ data }) => data != null);
  const directoryFailed = directoryQueries.some(({ isError, data }) => isError && data == null);
  // Only a complete directory proves a depot does not exist: then the screen falls back to All
  // depots. While a view or summary that could list it has failed, the depot is not called
  // missing; the screen says it could not load, beside that request's error banner and retry.
  const directorySettled =
    portfolios.data != null &&
    views.every(({ isPending }) => !isPending) &&
    positionAccounts.queries.every(({ isPending }) => !isPending);
  const invalidScope = Boolean(accountId && !depot && directoryComplete);
  const scopeUnavailable = Boolean(accountId && !depot && directorySettled && !directoryComplete);
  const showAll = !accountId || invalidScope;
  const workspace = useWorkspace();
  const currency = scopeCurrency ?? workspace.data?.currency;
  const netWorth = useNetWorth(currency, { enabled: showAll && currency != null });
  const selectedId = depot?.account.accountId ?? '';
  const needsBalance = depot != null && depot.account.currency !== depot.portfolioCurrency;
  const balance = useAccountBalance(needsBalance ? selectedId : '', depot?.account.currency);
  const account = useAccount(selectedId);
  const hasActivity = account.data?.hasTransactions === true;
  const activity = useRecentAccountActivity(selectedId, hasActivity);
  const scopes = depot ? [depot] : showAll ? depots : [];
  const positions = useQueries({
    queries: scopes.map(({ account }) => accountPositionsQuery(account.accountId)),
  });

  return {
    depots,
    depot,
    showAll,
    invalidScope,
    scopeUnavailable,
    directoryPending,
    directoryComplete,
    directoryFailed,
    directoryQueries,
    portfolios: portfolios.data,
    valueQueries: showAll
      ? [workspace, ...(currency ? [netWorth] : [])]
      : needsBalance
        ? [balance]
        : [],
    account,
    // The selected depot's summary, unless the directory already lists that same query: one
    // error banner and one refetch per request.
    accountQueries: selectedId && !summaryIds.includes(selectedId) ? [account] : [],
    activity: hasActivity ? activity : undefined,
    figures: investmentFigures(
      depot,
      showAll ? netWorth.data : undefined,
      depot ? positions[0]?.data : undefined,
      balance.data,
      balance.isError && balance.data == null,
    ),
    // The queries behind the figures shown: the value and performance, and the positions.
    figureQueries: [
      ...(showAll
        ? currency
          ? [netWorth]
          : []
        : [...(depotView ? [depotView] : []), ...(needsBalance ? [balance] : [])]),
      ...positions,
    ],
    groups: scopes.map(({ account }, index) => ({ account, query: positions[index] })),
    positions,
  };
}
