import { setAccessToken, setRefreshHandler, type DecimalString } from './client';
import type { components } from './schema';
import { typedApi } from './typed-api';

function mockFetch(body: unknown, status = 200) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: status < 400,
    status,
    json: async () => body,
  });
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

function sent(fetchMock: jest.Mock): { url: string; init: RequestInit } {
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  return { url, init };
}

describe('typedApi', () => {
  afterEach(() => {
    setAccessToken(null);
    setRefreshHandler(null);
    jest.resetAllMocks();
  });

  it('calls the operation of the path and answers its typed response body', async () => {
    const fetchMock = mockFetch({
      userId: 'u1',
      role: 'STANDARD_USER',
      language: 'de',
      sessionId: 's1',
    });

    const me = await typedApi.get('/api/v1/users/me');

    const { url, init } = sent(fetchMock);
    expect(url).toBe('http://localhost:8080/api/v1/users/me');
    expect(init.method).toBe('GET');
    const sessionId: string = me.sessionId;
    expect(sessionId).toBe('s1');
  });

  it('fills the path parameters encoded, sends the body and the version as If-Match', async () => {
    const fetchMock = mockFetch({ accountId: 'a/1', kind: 'IBAN', version: 4 });

    await typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
      params: { accountId: 'a/1' },
      version: 3,
      body: { importAccountId: 'a2', batchId: 'b1' },
    });

    const { url, init } = sent(fetchMock);
    expect(url).toBe('http://localhost:8080/api/v1/accounts/a%2F1/identifier/from-import');
    expect(init.method).toBe('PUT');
    expect(init.headers).toEqual(expect.objectContaining({ 'If-Match': '"3"' }));
    expect(JSON.parse(init.body as string)).toEqual({ importAccountId: 'a2', batchId: 'b1' });
  });

  it('writes the query, repeating a list and leaving out unset values', async () => {
    const fetchMock = mockFetch({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });

    await typedApi.get('/api/v1/transactions', {
      query: { accountId: ['a1', 'a2'], q: 'café', amountMin: '10.50', currency: undefined },
    });

    expect(sent(fetchMock).url).toBe(
      'http://localhost:8080/api/v1/transactions?accountId=a1&accountId=a2&q=caf%C3%A9&amountMin=10.50',
    );
  });

  it('answers undefined for a 204 and sends no If-Match without a version', async () => {
    const fetchMock = mockFetch(undefined, 204);

    const result = await typedApi.delete(
      '/api/v1/accounts/{accountId}/amortisation-schedule/{entryId}',
      { params: { accountId: 'a1', entryId: 'e1' }, version: 2 },
    );
    expect(result).toBeUndefined();

    mockFetch({ userId: 'u', role: 'r', language: 'en', sessionId: 's' });
    await typedApi.get('/api/v1/users/me', { renewSession: false });
    expect((globalThis.fetch as jest.Mock).mock.calls[0][1].headers['If-Match']).toBeUndefined();
    expect(sent(fetchMock).init.method).toBe('DELETE');
  });

  it('fails before sending when a path parameter is missing at runtime', () => {
    const fetchMock = mockFetch({});
    const untyped = typedApi.get as unknown as (
      path: string,
      options: { params: Record<string, string> },
    ) => Promise<unknown>;

    expect(() => untyped('/api/v1/accounts/{accountId}/identifier', { params: {} })).toThrow(
      'Path parameter accountId of /api/v1/accounts/{accountId}/identifier is missing',
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  // Compile-time checks: `npx tsc --noEmit` (Mobile Web CI) fails if any line below compiles.
  // They never run; each call is inside a function that is not called.
  it('rejects at compile time what the contract does not allow', () => {
    function unchecked(): void {
      // @ts-expect-error - no such path in the contract
      void typedApi.get('/api/v1/no-such-path');
      // @ts-expect-error - the path takes its parameters
      void typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
        version: 1,
        body: { importAccountId: 'a', batchId: 'b' },
      });
      // @ts-expect-error - a versioned operation needs the version it protects
      void typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
        params: { accountId: 'a' },
        body: { importAccountId: 'a', batchId: 'b' },
      });
      void typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
        params: { accountId: 'a' },
        version: 1,
        // @ts-expect-error - a field the request schema does not have
        body: { importAccountId: 'a', batch: 'b' },
      });
      void typedApi.get('/api/v1/users/me').then((me) => {
        // @ts-expect-error - a renamed or removed response field is a compile error
        void me.sessionKey;
      });
    }
    expect(unchecked).toBeInstanceOf(Function);
  });
});

describe('the generated schema', () => {
  type Value = components['schemas']['AccountValuation']['value'];
  // A decimal field is DecimalString (nullable here); assigning it to a number does not compile.
  const decimal: Value = '12.30' satisfies DecimalString;

  it('types a decimal as DecimalString, never number', () => {
    expect(decimal).toBe('12.30');
    function unchecked(): void {
      // @ts-expect-error - a decimal is never a number
      const asNumber: number = decimal;
      void asNumber;
    }
    expect(unchecked).toBeInstanceOf(Function);
  });
});
