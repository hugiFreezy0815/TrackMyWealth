/** API-path-shaped keys; never share a financial cache across authenticated sessions. */
export const queryKeys = {
  workspace: ['workspace'] as const,
  me: ['users', 'me'] as const,
  portfolios: ['portfolios'] as const,
  portfolio: (id: string) => ['portfolios', id] as const,
  portfolioView: (id: string) => ['portfolios', id, 'view'] as const,
  netWorth: (currency?: string) => ['net-worth', currency ?? 'default'] as const,
  accounts: (filters?: object) => ['accounts', filters ?? {}] as const,
  account: (id: string) => ['accounts', id] as const,
  accountTransactions: (id: string, filters?: object) =>
    ['accounts', id, 'transactions', filters ?? {}] as const,
  transaction: (accountId: string, transactionId: string) =>
    ['accounts', accountId, 'transactions', transactionId] as const,
  categories: (includeInactive?: boolean) => ['categories', includeInactive ?? false] as const,
  cashFlow: (month: string, currency?: string) =>
    ['cash-flow', month, currency ?? 'default'] as const,
  importBatch: (accountId: string, batchId: string) =>
    ['accounts', accountId, 'imports', batchId] as const,
  importRows: (accountId: string, batchId: string, status?: string) =>
    ['accounts', accountId, 'imports', batchId, 'rows', status ?? 'all'] as const,
};
