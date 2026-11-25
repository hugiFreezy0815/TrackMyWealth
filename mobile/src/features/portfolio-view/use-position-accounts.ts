import { useQueries } from '@tanstack/react-query';

import { accountQuery } from '@/api/queries/resources';

/**
 * Reads `holdsPositions` (account summary) for depots whose view row cannot show it: those without
 * a holdings snapshot yet. Returns the ids of the accounts that hold positions. See
 * isInvestmentDepot.
 */
export function usePositionAccounts(accountIds: readonly string[]) {
  const queries = useQueries({ queries: accountIds.map((id) => accountQuery(id)) });
  const ids = new Set(accountIds.filter((_, index) => queries[index]?.data?.holdsPositions));
  return { queries, ids };
}
