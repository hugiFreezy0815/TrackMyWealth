import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';

import { typedApi } from '@/api/typed-api';

import { createdAccount, problem } from './test-fixtures';
import {
  useCreateAccount,
  useCreatePortfolio,
  useInstitutionCatalogue,
  useRecordOpeningBalance,
  useRecordValuation,
  useSaveIdentifier,
} from './use-create';

let mockUuids = 0;
jest.mock('expo-crypto', () => ({ randomUUID: jest.fn(() => `key-${++mockUuids}`) }));

jest.mock('@/api/typed-api', () => ({
  typedApi: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

const api = jest.mocked(typedApi);

function wrapper() {
  // gcTime Infinity: no GC timers keep a single-file Jest run alive.
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  mockUuids = 0;
  api.get.mockReset();
  api.post.mockReset();
  api.put.mockReset();
});

describe('add-flow API calls (#491)', () => {
  it('asks the catalogue for page 0 by name with the country filter only when chosen', async () => {
    api.get.mockResolvedValue({
      content: [],
      page: { number: 0, size: 50, totalElements: 0, totalPages: 0 },
    } as never);
    const all = await renderHook(() => useInstitutionCatalogue(' post ', null), {
      wrapper: wrapper(),
    });
    await waitFor(() => expect(all.result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenLastCalledWith('/api/v1/institutions/catalogue', {
      query: { query: 'post', page: 0, size: 50, sort: ['name,asc'] },
    });

    const de = await renderHook(() => useInstitutionCatalogue('', 'DE'), { wrapper: wrapper() });
    await waitFor(() => expect(de.result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenLastCalledWith('/api/v1/institutions/catalogue', {
      query: { query: '', country: ['DE'], page: 0, size: 50, sort: ['name,asc'] },
    });
  });

  it('posts portfolio and account bodies unchanged', async () => {
    api.post.mockResolvedValue({ id: 'x' } as never);
    const portfolio = await renderHook(() => useCreatePortfolio(), { wrapper: wrapper() });
    await act(() =>
      portfolio.result.current.mutateAsync({
        catalogueInstitutionId: 'dkb',
        portfolioCurrency: 'EUR',
      }),
    );
    expect(api.post).toHaveBeenCalledWith('/api/v1/portfolios', {
      body: { catalogueInstitutionId: 'dkb', portfolioCurrency: 'EUR' },
      idempotencyKey: 'key-1',
    });

    const account = await renderHook(() => useCreateAccount(), { wrapper: wrapper() });
    const body = { portfolioId: 'p-1', name: 'Giro', accountType: 'CASH', nativeCurrency: 'EUR' };
    await act(() => account.result.current.mutateAsync(body));
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/accounts', {
      body,
      idempotencyKey: 'key-2',
    });
  });

  it('saves the identifier with If-Match from the created account', async () => {
    api.put.mockResolvedValue({ accountId: 'a-1', version: 4 } as never);
    const { result } = await renderHook(() => useSaveIdentifier(), { wrapper: wrapper() });
    await act(() =>
      result.current.mutateAsync({
        account: createdAccount({ version: 3 }),
        identifier: 'DE89370400440532013000',
        kind: 'IBAN',
      }),
    );
    expect(api.put).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/identifier', {
      params: { accountId: 'a-1' },
      version: 3,
      body: { identifier: 'DE89370400440532013000', kind: 'IBAN' },
    });
  });

  it('repeats the idempotent identifier PUT once with the server version after a conflict', async () => {
    api.put
      .mockRejectedValueOnce(problem(409, 'VERSION_CONFLICT'))
      .mockResolvedValueOnce({ accountId: 'a-1', version: 5 } as never);
    api.get.mockResolvedValue({ accountId: 'a-1', version: 4 } as never);
    const { result } = await renderHook(() => useSaveIdentifier(), { wrapper: wrapper() });
    await act(() =>
      result.current.mutateAsync({
        account: createdAccount({ version: 3 }),
        identifier: '424242••••••4242',
        kind: 'MASKED_CARD',
      }),
    );
    expect(api.get).toHaveBeenCalledWith('/api/v1/accounts/{accountId}/identifier', {
      params: { accountId: 'a-1' },
    });
    expect(api.put).toHaveBeenCalledTimes(2);
    expect(api.put.mock.calls[1][1]).toEqual(expect.objectContaining({ version: 4 }));
  });

  it('does not repeat the identifier PUT for any other failure', async () => {
    api.put.mockRejectedValue(problem(422, 'ACCOUNT_IDENTIFIER_INVALID'));
    const { result } = await renderHook(() => useSaveIdentifier(), { wrapper: wrapper() });
    await expect(
      act(() =>
        result.current.mutateAsync({
          account: createdAccount(),
          identifier: 'DE00',
          kind: 'IBAN',
        }),
      ),
    ).rejects.toBeTruthy();
    expect(api.put).toHaveBeenCalledTimes(1);
    expect(api.get).not.toHaveBeenCalled();
  });

  it('records an opening balance in the account currency and a valuation by date', async () => {
    api.post.mockResolvedValue({} as never);
    const account = createdAccount({ nativeCurrency: 'CHF' });
    const opening = await renderHook(() => useRecordOpeningBalance(), { wrapper: wrapper() });
    await act(() =>
      opening.result.current.mutateAsync({ account, date: '2026-11-09', balance: '1000.00' }),
    );
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/accounts/{accountId}/opening-balance', {
      params: { accountId: 'a-1' },
      body: { date: '2026-11-09', balance: '1000.00', currency: 'CHF' },
      idempotencyKey: 'key-1',
    });

    const valuation = await renderHook(() => useRecordValuation(), { wrapper: wrapper() });
    await act(() =>
      valuation.result.current.mutateAsync({ account, date: '2026-11-09', value: '800000.45' }),
    );
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/accounts/{accountId}/valuations', {
      params: { accountId: 'a-1' },
      body: { valuationDate: '2026-11-09', value: '800000.45' },
      idempotencyKey: 'key-2',
    });
  });
});

describe('Idempotency-Key on the add-flow creates (#629)', () => {
  const body = { portfolioId: 'p-1', name: 'Giro', accountType: 'CASH', nativeCurrency: 'EUR' };
  // A failed send is expected here; catching it inside act keeps the renderer usable.
  const failing = (send: () => Promise<unknown>) =>
    act(async () => {
      await send().catch(() => undefined);
    });
  const keyOf = (call: number) =>
    (api.post.mock.calls[call][1] as { idempotencyKey?: string }).idempotencyKey;

  it('retries a failed create with its key, so a lost response creates no second account', async () => {
    api.post
      .mockRejectedValueOnce(new TypeError('Network request failed'))
      .mockResolvedValueOnce(createdAccount() as never);
    const { result } = await renderHook(() => useCreateAccount(), { wrapper: wrapper() });

    await failing(() => result.current.mutateAsync(body));
    await act(() => result.current.mutateAsync({ ...body }));

    expect(api.post).toHaveBeenCalledTimes(2);
    expect(keyOf(1)).toBe(keyOf(0));
  });

  it('sends a changed request and a create after a success with new keys', async () => {
    api.post
      .mockRejectedValueOnce(new TypeError('Network request failed'))
      .mockResolvedValue(createdAccount() as never);
    const { result } = await renderHook(() => useCreateAccount(), { wrapper: wrapper() });

    await failing(() => result.current.mutateAsync(body));
    await act(() => result.current.mutateAsync({ ...body, name: 'Savings' }));
    await act(() => result.current.mutateAsync({ ...body, name: 'Savings' }));

    expect(new Set([keyOf(0), keyOf(1), keyOf(2)]).size).toBe(3);
  });

  it('keys an opening balance by its account, so another account never reuses the key', async () => {
    api.post.mockRejectedValue(new TypeError('Network request failed'));
    const { result } = await renderHook(() => useRecordOpeningBalance(), { wrapper: wrapper() });
    const values = { date: '2026-11-09', balance: '1000.00' as const };

    await failing(() => result.current.mutateAsync({ account: createdAccount(), ...values }));
    await failing(() =>
      result.current.mutateAsync({ account: createdAccount({ id: 'a-2' }), ...values }),
    );

    expect(keyOf(1)).not.toBe(keyOf(0));
  });
});
