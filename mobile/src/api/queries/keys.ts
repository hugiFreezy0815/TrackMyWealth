/**
 * API-path-shaped keys; never share a financial cache across authenticated sessions.
 * Every key starts with one of the roots, so invalidation can address a whole resource family.
 */
export const queryRoots = {
  workspace: 'workspace',
  users: 'users',
  portfolios: 'portfolios',
  netWorth: 'net-worth',
  accounts: 'accounts',
  categories: 'categories',
  cashFlow: 'cash-flow',
} as const;

export const queryKeys = {
  workspace: [queryRoots.workspace] as const,
  me: [queryRoots.users, 'me'] as const,
  portfolios: [queryRoots.portfolios] as const,
  portfolio: (id: string) => [queryRoots.portfolios, id] as const,
  portfolioView: (id: string) => [queryRoots.portfolios, id, 'view'] as const,
  netWorth: (currency?: string) => [queryRoots.netWorth, currency ?? 'default'] as const,
  accounts: (filters?: object) => [queryRoots.accounts, filters ?? {}] as const,
  account: (id: string) => [queryRoots.accounts, id] as const,
  accountTransactions: (id: string, filters?: object) =>
    [queryRoots.accounts, id, 'transactions', filters ?? {}] as const,
  uncategorizedCount: (accountId: string) =>
    [queryRoots.accounts, accountId, 'transactions', 'uncategorized-count'] as const,
  accountPositions: (accountId: string) => [queryRoots.accounts, accountId, 'positions'] as const,
  transaction: (accountId: string, transactionId: string) =>
    [queryRoots.accounts, accountId, 'transactions', transactionId] as const,
  categories: (includeInactive?: boolean) =>
    [queryRoots.categories, includeInactive ?? false] as const,
  cashFlow: (month: string, currency?: string) =>
    [queryRoots.cashFlow, month, currency ?? 'default'] as const,
  importBatch: (accountId: string, batchId: string) =>
    [queryRoots.accounts, accountId, 'imports', batchId] as const,
  importRows: (accountId: string, batchId: string, status?: string) =>
    [queryRoots.accounts, accountId, 'imports', batchId, 'rows', status ?? 'all'] as const,
};
