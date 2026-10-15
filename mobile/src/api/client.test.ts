import { api, ApiError, DecimalString, setAccessToken, setRefreshHandler } from './client';

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

  it('returns undefined for a 204 No Content response without parsing a body', async () => {
    const jsonSpy = jest.fn();
    mockFetchOnce({ status: 204, json: jsonSpy });

    const result = await api.delete('/accounts/some-id');

    expect(result).toBeUndefined();
    expect(jsonSpy).not.toHaveBeenCalled();
  });
});
