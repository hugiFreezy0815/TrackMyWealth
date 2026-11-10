import {
  NetworkError,
  UPLOAD_TIMEOUT_MS,
  REQUEST_TIMEOUT_MS,
  setAccessToken,
  setRefreshHandler,
} from '@/api/client';
import { typedApi } from '@/api/typed-api';

import {
  commitStatement,
  confirmStatementAccount,
  discardStatement,
  downloadErrorsCsv,
  parseStatement,
  setStatementRowIncluded,
  uploadStatement,
} from './api';
import type { Batch } from './model';

// The JSON calls are checked for their options; upload and text run the real transport on fetch.
jest.mock('@/api/typed-api', () => ({
  typedApi: {
    ...jest.requireActual('@/api/typed-api').typedApi,
    get: jest.fn(),
    post: jest.fn(),
    patch: jest.fn(),
  },
}));
const batch = { id: 'b-1', accountId: 'a-1', version: 7 } as Batch;

beforeEach(() => {
  jest.clearAllMocks();
  setAccessToken(null);
  setRefreshHandler(null);
});

describe('versioned import requests (#492)', () => {
  it('sends each row inclusion PATCH with the latest server version', async () => {
    await setStatementRowIncluded('a-1', batch, 23, true);
    expect(typedApi.patch).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/imports/{batchId}/rows/{rowNumber}',
      {
        params: { accountId: 'a-1', batchId: 'b-1', rowNumber: 23 },
        version: 7,
        body: { included: true },
      },
    );
  });
  it('sends selected template and If-Match for parse', async () => {
    await parseStatement('a-1', batch, 'template-dkb');
    expect(typedApi.post).toHaveBeenCalledWith(
      '/api/v1/accounts/{accountId}/imports/{batchId}/parse',
      {
        params: { accountId: 'a-1', batchId: 'b-1' },
        version: 7,
        body: { templateId: 'template-dkb' },
      },
    );
  });
  it('requires version for confirm, commit and discard', async () => {
    await confirmStatementAccount('a-1', batch);
    await commitStatement('a-1', batch);
    await discardStatement('a-1', batch);
    expect(typedApi.post).toHaveBeenCalledTimes(3);
    for (const [, options] of jest.mocked(typedApi.post).mock.calls) {
      expect(options).toEqual(expect.objectContaining({ version: 7 }));
    }
  });
});

const file = { name: 'synthetic-dkb.csv', uri: 'file:///dkb.csv', size: 100, mimeType: 'text/csv' };

/** A fetch that never answers until its signal aborts. */
function hangingFetch() {
  return jest.fn(
    (_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) =>
        init.signal?.addEventListener('abort', () =>
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' })),
        ),
      ),
  ) as unknown as typeof fetch;
}

describe('authenticated multipart and CSV transport (#492)', () => {
  it('uploads to the contract path with the bearer token but no JSON Content-Type', async () => {
    const mock = jest.fn(async () => new Response(JSON.stringify({ id: 'b-1' }), { status: 201 }));
    globalThis.fetch = mock as unknown as typeof fetch;
    setAccessToken('token-123');

    await expect(uploadStatement('a 1', file)).resolves.toEqual({ id: 'b-1' });

    const [url, init] = mock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toMatch(/\/api\/v1\/accounts\/a%201\/imports$/);
    expect(init.method).toBe('POST');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token-123');
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('renews the session once on a 401 and sends the upload again with the new token', async () => {
    const mock = jest
      .fn()
      .mockResolvedValueOnce(new Response('{}', { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 'b-1' }), { status: 201 }));
    globalThis.fetch = mock as unknown as typeof fetch;
    setAccessToken('old');
    setRefreshHandler(async () => {
      setAccessToken('new');
      return 'new';
    });

    await uploadStatement('a-1', file);

    expect(mock).toHaveBeenCalledTimes(2);
    const [, retry] = mock.mock.calls[1] as [string, RequestInit];
    expect((retry.headers as Record<string, string>).Authorization).toBe('Bearer new');
    expect(retry.body).toBeInstanceOf(FormData);
  });

  it('gives an upload the upload deadline, not the JSON one', async () => {
    jest.useFakeTimers();
    try {
      globalThis.fetch = hangingFetch();
      const pending = uploadStatement('a-1', file);
      const outcome = jest.fn();
      pending.then(outcome, outcome);

      await jest.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS + 1);
      expect(outcome).not.toHaveBeenCalled();

      await jest.advanceTimersByTimeAsync(UPLOAD_TIMEOUT_MS);
      await expect(pending).rejects.toBeInstanceOf(NetworkError);
      await expect(pending).rejects.toThrow(/timed out/);
    } finally {
      jest.useRealTimers();
    }
  });

  it("keeps the member's Cancel an abort, not a network error", async () => {
    globalThis.fetch = hangingFetch();
    const cancel = new AbortController();
    const pending = uploadStatement('a-1', file, cancel.signal);

    cancel.abort();

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('downloads the errors CSV as text from the contract path', async () => {
    const mock = jest.fn(
      async () =>
        new Response('row,error\n1,invalid', {
          status: 200,
          headers: { 'Content-Type': 'text/csv' },
        }),
    );
    globalThis.fetch = mock as unknown as typeof fetch;

    expect(await downloadErrorsCsv('a-1', 'b-1')).toBe('row,error\n1,invalid');
    const [url, init] = mock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toMatch(/\/api\/v1\/accounts\/a-1\/imports\/b-1\/errors\.csv$/);
    expect(init.method).toBe('GET');
  });

  it('keeps the problem code and Retry-After of a busy parser', async () => {
    globalThis.fetch = jest.fn(
      async () =>
        new Response(JSON.stringify({ status: 503, title: 'busy', code: 'IMPORT_PDF_BUSY' }), {
          status: 503,
          headers: { 'Retry-After': '3' },
        }),
    ) as unknown as typeof fetch;

    await expect(uploadStatement('a-1', file)).rejects.toMatchObject({
      status: 503,
      retryAfterSeconds: 3,
      body: { code: 'IMPORT_PDF_BUSY' },
    });
  });
});
