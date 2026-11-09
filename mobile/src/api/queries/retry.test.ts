import { QueryClient } from '@tanstack/react-query';

import { ApiError, NetworkError, parseRetryAfter, request } from '@/api/client';
import { retryDelay, shouldRetry } from './retry';

const problem = (status: number, code: string, retryAfterSeconds?: number) =>
  new ApiError('failed', status, { status, title: 'Problem', code }, retryAfterSeconds);

function respond(status: number, code: string, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ status, title: 'Problem', code }), { status, headers });
}

describe('Retry-After header', () => {
  const now = Date.parse('2026-11-08T10:00:00Z');

  it.each([
    ['2', 2],
    [' 120 ', 120],
    ['Sun, 08 Nov 2026 10:00:05 GMT', 5],
    ['Sun, 08 Nov 2026 09:59:00 GMT', 0],
  ])('reads %j as %p seconds', (header, seconds) => {
    expect(parseRetryAfter(header, now)).toBe(seconds);
  });

  it.each([null, undefined, '', '2.5', '-1', 'soon'])('ignores %j', (header) => {
    expect(parseRetryAfter(header, now)).toBeUndefined();
  });

  it('is carried by the ApiError of a failed request', async () => {
    globalThis.fetch = jest.fn(async () =>
      respond(429, 'RATE_LIMITED', { 'Retry-After': '2' }),
    ) as unknown as typeof fetch;
    const error = await request('/api/v1/net-worth').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).retryAfterSeconds).toBe(2);
  });
});

describe('query retry policy', () => {
  afterEach(() => {
    jest.useRealTimers();
    jest.resetAllMocks();
  });

  it('retries RATE_LIMITED after the Retry-After delay of 2 s', async () => {
    jest.useFakeTimers();
    const fetchMock = jest
      .fn()
      .mockResolvedValueOnce(respond(429, 'RATE_LIMITED', { 'Retry-After': '2' }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ total: '1.00' }), { status: 200 }));
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    const client = new QueryClient({
      defaultOptions: { queries: { retry: shouldRetry, retryDelay, gcTime: Infinity } },
    });

    const result = client.fetchQuery({
      queryKey: ['net-worth'],
      queryFn: () => request('/api/v1/net-worth'),
    });

    await jest.advanceTimersByTimeAsync(1999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await jest.advanceTimersByTimeAsync(1);
    await expect(result).resolves.toEqual({ total: '1.00' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('honours Retry-After and caps it at one minute', () => {
    expect(retryDelay(0, problem(429, 'RATE_LIMITED', 2))).toBe(2000);
    expect(retryDelay(0, problem(429, 'RATE_LIMITED', 3600))).toBe(60_000);
    expect(retryDelay(2, problem(503, 'LOCKED'))).toBe(4000);
  });

  it('retries transient problems and network failures at most three times', () => {
    expect(shouldRetry(0, problem(429, 'RATE_LIMITED', 2))).toBe(true);
    expect(shouldRetry(0, new NetworkError('offline'))).toBe(true);
    expect(shouldRetry(3, problem(429, 'RATE_LIMITED', 2))).toBe(false);
  });

  it('never retries input errors or unknown problem codes', () => {
    expect(shouldRetry(0, problem(400, 'VALIDATION_FAILED'))).toBe(false);
    expect(shouldRetry(0, problem(503, 'SOMETHING_NEW'))).toBe(false);
    expect(shouldRetry(0, new Error('bug'))).toBe(false);
  });
});
