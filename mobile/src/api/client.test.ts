import { api, ApiError, setAccessToken } from './client';

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

  it('throws ApiError with the status and parsed body when the response is not ok', async () => {
    mockFetchOnce({ ok: false, status: 404, json: async () => ({ message: 'not found' }) });

    await expect(api.get('/accounts/missing')).rejects.toMatchObject({
      name: 'ApiError',
      status: 404,
      body: { message: 'not found' },
    } satisfies Partial<ApiError>);
  });

  it('returns undefined for a 204 No Content response without parsing a body', async () => {
    const jsonSpy = jest.fn();
    mockFetchOnce({ status: 204, json: jsonSpy });

    const result = await api.delete('/accounts/some-id');

    expect(result).toBeUndefined();
    expect(jsonSpy).not.toHaveBeenCalled();
  });
});
