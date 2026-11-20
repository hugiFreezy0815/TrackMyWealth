import {
  api,
  ApiError,
  DecimalString,
  NetworkError,
  REQUEST_TIMEOUT_MS,
  requestMultipart,
  SessionRefreshUnavailableError,
  setAccessToken,
  setRefreshHandler,
} from './client';
import { Platform } from 'react-native';

import { problemReaction } from './problems';
import { dismissUpdateRequired, updateRequiredSnapshot } from './update-required';
import { APP_VERSION } from './app-version';

// #576: jest's expo-constants carries no app.json version; the header needs one.
jest.mock('expo-constants', () => ({
  __esModule: true,
  default: { expoConfig: { version: '4.5.6' } },
}));

function mockFetchOnce(response: Partial<Response> & { json?: () => Promise<unknown> }) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({}),
    ...response,
  });
  globalThis.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

describe('api client', () => {
  afterEach(() => {
    setAccessToken(null);
    setRefreshHandler(null);
    jest.resetAllMocks();
  });

  it('builds the request URL from EXPO_PUBLIC_API_URL and sends JSON headers', async () => {
    const fetchMock = mockFetchOnce({ json: async () => ({ status: 'UP' }) });

    const result = await api.get('/actuator/health');

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/actuator/health',
      expect.objectContaining({
        method: 'GET',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
    expect(result).toEqual({ status: 'UP' });
  });

  it('sends the build version on every request (#576)', async () => {
    const fetchMock = mockFetchOnce({});

    await api.get('/api/v1/accounts');

    expect(APP_VERSION).toBe('4.5.6');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-App-Version': APP_VERSION }),
      }),
    );
  });

  it('reports a 426 as an update the member must install, and still fails the request', async () => {
    dismissUpdateRequired();
    mockFetchOnce({
      ok: false,
      status: 426,
      json: async () => ({ title: 'Upgrade Required', status: 426, code: 'APP_UPDATE_REQUIRED' }),
    });

    const failure = await api.get('/api/v1/accounts').catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect(problemReaction(failure as ApiError)).toMatchObject({
      reaction: 'UPDATE_APP',
      code: 'APP_UPDATE_REQUIRED',
    });
    expect(updateRequiredSnapshot()).toBe(true);
    dismissUpdateRequired();
  });

  it('reports no update for any other failure', async () => {
    dismissUpdateRequired();
    mockFetchOnce({ ok: false, status: 400, json: async () => ({}) });

    await api.get('/api/v1/accounts').catch(() => undefined);

    expect(updateRequiredSnapshot()).toBe(false);
  });

  it('attaches a bearer token once setAccessToken has been called', async () => {
    const fetchMock = mockFetchOnce({});
    setAccessToken('test-token');

    await api.get('/accounts');

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-token' }),
      }),
    );
  });

  it('sends the body as JSON on post', async () => {
    const fetchMock = mockFetchOnce({});

    await api.post('/accounts', { name: 'Checking' });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'Checking' }) }),
    );
  });

  // #176: a decimal read as a string goes back out byte for byte. As a JSON number it would not
  // even survive parsing - which is why API types declare decimals as DecimalString.
  it('round-trips an exact decimal string unchanged', async () => {
    const received = '{"quantity":"12345678.1234567891","unitPrice":"1.0000000001"}';
    mockFetchOnce({ json: async () => JSON.parse(received) });
    const row = await api.get<{ quantity: DecimalString; unitPrice: DecimalString }>('/row');

    const fetchMock = mockFetchOnce({});
    await api.post('/rows', { quantity: row.quantity, unitPrice: row.unitPrice });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ body: received }),
    );
    expect(String(JSON.parse('{"quantity":12345678.1234567891}').quantity)).not.toBe(
      '12345678.1234567891',
    );
  });

  it('sends a strong If-Match tag for versioned PUT requests', async () => {
    const fetchMock = mockFetchOnce({});

    await api.putVersioned('/accounts/id', 7, { name: 'Updated' });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ 'If-Match': '"7"' }),
        body: JSON.stringify({ name: 'Updated' }),
      }),
    );
  });

  it('sends If-Match on versioned POST and DELETE requests', async () => {
    const postFetch = mockFetchOnce({});
    await api.postVersioned('/accounts/id/archive', 3);
    expect(postFetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'If-Match': '"3"' }),
      }),
    );

    const deleteFetch = mockFetchOnce({ status: 204, json: jest.fn() });
    await api.deleteVersioned('/categories/id', 4);
    expect(deleteFetch).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({ 'If-Match': '"4"' }),
      }),
    );
  });

  it('sends If-Match and the JSON body on versioned PATCH requests', async () => {
    const fetchMock = mockFetchOnce({});

    await api.patchVersioned('/admin/users/id', 5, { language: 'DE' });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({
        method: 'PATCH',
        headers: expect.objectContaining({ 'If-Match': '"5"' }),
        body: JSON.stringify({ language: 'DE' }),
      }),
    );
  });

  it('throws ApiError with the status and parsed body when the response is not ok', async () => {
    mockFetchOnce({ ok: false, status: 404, json: async () => ({ message: 'not found' }) });

    await expect(api.get('/accounts/missing')).rejects.toMatchObject({
      name: 'ApiError',
      status: 404,
      body: { message: 'not found' },
    } satisfies Partial<ApiError>);
  });

  it('retries a protected request only once after a successful refresh', async () => {
    const refresh = jest.fn(async () => {
      setAccessToken('rotated-token');
      return 'rotated-token';
    });
    setAccessToken('expired-token');
    setRefreshHandler(refresh);
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 401, json: async () => ({}) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ id: 'ok' }) });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(api.get('/api/v1/accounts/one')).resolves.toEqual({ id: 'ok' });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer rotated-token');
  });

  it('does not retry endlessly when the refreshed access token is also rejected', async () => {
    const refresh = jest.fn(async () => 'new-token');
    setRefreshHandler(refresh);
    const fetchMock = jest.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ code: 'UNAUTHORIZED' }),
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(api.get('/api/v1/accounts')).rejects.toMatchObject({ status: 401 });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('reports temporary renewal failure as retryable without replaying the request', async () => {
    const unavailable = new SessionRefreshUnavailableError(30);
    setRefreshHandler(async () => unavailable);
    const fetchMock = mockFetchOnce({ ok: false, status: 401 });

    await expect(api.get('/api/v1/accounts')).rejects.toBe(unavailable);
    expect(problemReaction(unavailable)).toMatchObject({ reaction: 'RETRY_LATER' });
    expect(unavailable.retryAfterSeconds).toBe(30);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('resends without another rotation when the token was already rotated while in flight', async () => {
    const refresh = jest.fn(async () => 'unused');
    setAccessToken('old-token');
    setRefreshHandler(refresh);
    const fetchMock = jest
      .fn()
      .mockImplementationOnce(async () => {
        // A concurrent request's refresh completes before this 401 arrives.
        setAccessToken('rotated-token');
        return { ok: false, status: 401, json: async () => ({}) };
      })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ id: 'ok' }) });
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(api.get('/api/v1/accounts/one')).resolves.toEqual({ id: 'ok' });
    expect(refresh).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer rotated-token');
  });

  it('never invokes refresh for the authentication endpoints themselves', async () => {
    const refresh = jest.fn(async () => 'new-token');
    setRefreshHandler(refresh);
    mockFetchOnce({ ok: false, status: 401, json: async () => ({}) });

    await expect(api.post('/api/v1/auth/login', {})).rejects.toMatchObject({ status: 401 });
    expect(refresh).not.toHaveBeenCalled();
  });

  it('never renews the session for a request that opts out', async () => {
    const refresh = jest.fn(async () => 'new-token');
    setRefreshHandler(refresh);
    mockFetchOnce({ ok: false, status: 401, json: async () => ({}) });

    await expect(api.get('/api/v1/users/me', { renewSession: false })).rejects.toMatchObject({
      status: 401,
    });
    expect(refresh).not.toHaveBeenCalled();
  });

  it('reports an unreachable server as a network error, not an HTTP status', async () => {
    globalThis.fetch = jest.fn().mockRejectedValue(new TypeError('Network request failed'));

    await expect(api.get('/api/v1/accounts')).rejects.toBeInstanceOf(NetworkError);
  });

  it('gives up on a server that does not answer in time', async () => {
    jest.useFakeTimers();
    try {
      globalThis.fetch = jest.fn(
        (_url: string, init: RequestInit) =>
          new Promise((_resolve, reject) =>
            init.signal?.addEventListener('abort', () =>
              reject(Object.assign(new Error('Aborted'), { name: 'AbortError' })),
            ),
          ),
      ) as unknown as typeof fetch;

      const pending = api.get('/api/v1/accounts');
      jest.advanceTimersByTime(REQUEST_TIMEOUT_MS);

      await expect(pending).rejects.toThrow(/timed out/);
      await expect(pending).rejects.toBeInstanceOf(NetworkError);
    } finally {
      jest.useRealTimers();
    }
  });

  it("keeps a caller's own abort as it is", async () => {
    const caller = new AbortController();
    caller.abort();
    const aborted = Object.assign(new Error('Aborted'), { name: 'AbortError' });
    globalThis.fetch = jest.fn().mockRejectedValue(aborted);

    await expect(api.get('/api/v1/accounts', { signal: caller.signal })).rejects.toBe(aborted);
  });

  it('returns undefined for a 204 No Content response without parsing a body', async () => {
    const jsonSpy = jest.fn();
    mockFetchOnce({ status: 204, json: jsonSpy });

    const result = await api.delete('/accounts/some-id');

    expect(result).toBeUndefined();
    expect(jsonSpy).not.toHaveBeenCalled();
  });
});

// #640: only a failed connection, or an answer lost on its way back, leaves an upload's outcome
// unknown. A failure in the app before the request left is no connection problem.
describe('statement upload failures (#640)', () => {
  const ORIGINAL_OS = Platform.OS;
  const upload = () => requestMultipart('/api/v1/accounts/a-1/imports', { body: new FormData() });
  let warn: jest.SpyInstance;

  beforeEach(() => {
    warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
  });
  afterEach(() => {
    Platform.OS = ORIGINAL_OS;
    warn.mockRestore();
  });

  it.each([
    ['ios', new Error('Unsupported FormDataPart implementation')],
    ['android', new TypeError('Unsupported BodyInit type')],
    ['web', new Error('Unsupported FormDataPart implementation')],
  ] as const)(
    'on %s, rethrows an app error from before sending and logs it',
    async (os, failure) => {
      Platform.OS = os;
      globalThis.fetch = jest.fn().mockRejectedValue(failure) as unknown as typeof fetch;

      const rejection = await upload().catch((error: unknown) => error);

      expect(rejection).toBe(failure);
      expect(rejection).not.toBeInstanceOf(NetworkError);
      expect(warn).toHaveBeenCalledWith('Statement upload failed in the app:', failure.message);
    },
  );

  it.each([
    ['ios', new Error('fetch failed: The network connection was lost.')],
    ['android', new Error('fetch failed: Unable to resolve host')],
    ['web', new TypeError('Failed to fetch')],
  ] as const)('on %s, reports a failed connection as a NetworkError', async (os, failure) => {
    Platform.OS = os;
    globalThis.fetch = jest.fn().mockRejectedValue(failure) as unknown as typeof fetch;

    await expect(upload()).rejects.toBeInstanceOf(NetworkError);
    expect(warn).not.toHaveBeenCalled();
  });

  it('keeps an answer lost after the headers uncertain, whatever the error type', async () => {
    Platform.OS = 'ios';
    mockFetchOnce({
      status: 201,
      json: async () => {
        throw new Error('stream reset');
      },
    });

    await expect(upload()).rejects.toThrow(
      new NetworkError('Statement upload could not finish receiving its response'),
    );
    expect(warn).not.toHaveBeenCalled();
  });

  it('passes a problem response through unchanged', async () => {
    mockFetchOnce({
      ok: false,
      status: 409,
      headers: new Headers(),
      json: async () => ({ status: 409, title: 'busy', code: 'IDEMPOTENCY_KEY_IN_USE' }),
    });

    await expect(upload()).rejects.toBeInstanceOf(ApiError);
    expect(warn).not.toHaveBeenCalled();
  });

  it("keeps the member's Cancel an abort, not an app error", async () => {
    const cancel = new AbortController();
    const aborted = Object.assign(new Error('Aborted'), { name: 'AbortError' });
    globalThis.fetch = jest.fn(async () => {
      cancel.abort();
      throw aborted;
    }) as unknown as typeof fetch;

    await expect(
      requestMultipart('/api/v1/accounts/a-1/imports', {
        body: new FormData(),
        signal: cancel.signal,
      }),
    ).rejects.toBe(aborted);
    expect(warn).not.toHaveBeenCalled();
  });
});
