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

import { investmentDepots, investmentFigures } from './model';

export function useInvestments(accountId?: string) {
  const portfolios = usePortfolios();
  const views = useQueries({
    queries: (portfolios.data ?? []).map(({ id }) => portfolioViewQuery(id)),
  });
  const depots = investmentDepots(views.map(({ data }) => data));
  const depot = depots.find(({ account }) => account.accountId === accountId);
  const directoryQueries = [portfolios, ...views];
  const directoryPending = directoryQueries.some(({ isPending }) => isPending);
  const directoryComplete = portfolios.data != null && views.every(({ data }) => data != null);
  const directoryFailed = directoryQueries.some(({ isError, data }) => isError && data == null);
  const invalidScope = Boolean(accountId && !depot && directoryComplete);
  const showAll = !accountId || invalidScope;
  const workspace = useWorkspace();
  const currency = workspace.data?.currency;
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
    activity: hasActivity ? activity : undefined,
    figures: investmentFigures(
      depot,
      showAll ? netWorth.data : undefined,
      depot ? positions[0]?.data : undefined,
      balance.data,
    ),
    groups: scopes.map(({ account }, index) => ({ account, query: positions[index] })),
    positions,
  };
}
