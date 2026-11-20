import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { DecimalString } from '@/api/decimal';
import { useIdempotencyKeys } from '@/api/idempotency-key';
import { ifMatch } from '@/api/queries/conflict';
import { invalidateFinancialData } from '@/api/queries/invalidation';
import { queryKeys } from '@/api/queries/keys';
import { typedApi } from '@/api/typed-api';

import {
  catalogueKey,
  hasProblemCode,
  type Account,
  type AccountRequest,
  type IdentifierKind,
  type PortfolioRequest,
} from './model';

export const CATALOGUE_PAGE_SIZE = 50;

export function usePortfolio(portfolioId: string) {
  return useQuery({
    queryKey: queryKeys.portfolio(portfolioId),
    queryFn: () => typedApi.get('/api/v1/portfolios/{portfolioId}', { params: { portfolioId } }),
    enabled: !!portfolioId,
  });
}

/** S5 catalogue: paged by name; a new search or country starts again at page 0. */
export function useInstitutionCatalogue(query: string, country: string | null) {
  return useInfiniteQuery({
    queryKey: catalogueKey(query, country),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      typedApi.get('/api/v1/institutions/catalogue', {
        query: {
          query: query.trim(),
          ...(country ? { country: [country] } : {}),
          page: pageParam,
          size: CATALOGUE_PAGE_SIZE,
          sort: ['name,asc'],
        },
      }),
    getNextPageParam: (last) =>
      last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined,
  });
}

// A retried create reuses its Idempotency-Key (#629): a lost response never creates a second
// portfolio, account, opening balance or valuation.

export function useCreatePortfolio() {
  const client = useQueryClient();
  const keys = useIdempotencyKeys();
  return useMutation({
    mutationFn: (body: PortfolioRequest) =>
      typedApi.post('/api/v1/portfolios', { body, idempotencyKey: keys.keyFor(body) }),
    onSuccess: () => {
      keys.succeeded();
      return invalidateFinancialData(client);
    },
  });
}

export function useCreateAccount() {
  const client = useQueryClient();
  const keys = useIdempotencyKeys();
  return useMutation({
    mutationFn: (body: AccountRequest) =>
      typedApi.post('/api/v1/accounts', { body, idempotencyKey: keys.keyFor(body) }),
    onSuccess: () => {
      keys.succeeded();
      return invalidateFinancialData(client);
    },
  });
}

export type SaveIdentifier = { account: Account; identifier: string; kind: IdentifierKind };

/**
 * The identifier is its own resource (US-07-10), saved after the account exists, guarded by the
 * account's version. A VERSION_CONFLICT on a brand-new account means an earlier attempt of this
 * flow got through although its response was lost (or another device touched it): the PUT is
 * idempotent, so it is sent once more with the server's current version instead of failing.
 */
export function useSaveIdentifier() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async ({ account, identifier, kind }: SaveIdentifier) => {
      const put = (version: number) =>
        typedApi.put('/api/v1/accounts/{accountId}/identifier', {
          params: { accountId: account.id },
          version,
          body: { identifier, kind },
        });
      try {
        return await put(ifMatch(account));
      } catch (error) {
        if (!hasProblemCode(error, 'VERSION_CONFLICT')) throw error;
        const current = await typedApi.get('/api/v1/accounts/{accountId}/identifier', {
          params: { accountId: account.id },
        });
        return put(ifMatch(current));
      }
    },
    onSuccess: (_result, { account }) =>
      client.invalidateQueries({ queryKey: queryKeys.account(account.id) }),
  });
}

export function useRecordOpeningBalance() {
  const client = useQueryClient();
  const keys = useIdempotencyKeys();
  return useMutation({
    mutationFn: ({
      account,
      date,
      balance,
    }: {
      account: Account;
      date: string;
      balance: DecimalString;
    }) => {
      const body = { date, balance, currency: account.nativeCurrency };
      return typedApi.post('/api/v1/accounts/{accountId}/opening-balance', {
        params: { accountId: account.id },
        body,
        idempotencyKey: keys.keyFor({ accountId: account.id, body }),
      });
    },
    onSuccess: () => {
      keys.succeeded();
      return invalidateFinancialData(client);
    },
  });
}

export function useRecordValuation() {
  const client = useQueryClient();
  const keys = useIdempotencyKeys();
  return useMutation({
    mutationFn: ({
      account,
      date,
      value,
    }: {
      account: Account;
      date: string;
      value: DecimalString;
    }) => {
      const body = { valuationDate: date, value };
      return typedApi.post('/api/v1/accounts/{accountId}/valuations', {
        params: { accountId: account.id },
        body,
        idempotencyKey: keys.keyFor({ accountId: account.id, body }),
      });
    },
    onSuccess: () => {
      keys.succeeded();
      return invalidateFinancialData(client);
    },
  });
}
