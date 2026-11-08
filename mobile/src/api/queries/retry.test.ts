import { ApiError } from '@/api/client';
import { retryDelay, shouldRetry } from './retry';

const problem = (status: number, code: string, retryAfterSeconds?: number) =>
  new ApiError('failed', status, { status, title: 'Problem', code }, retryAfterSeconds);

describe('query retry policy', () => {
  it('honours Retry-After for rate limiting', () => {
    const error = problem(429, 'RATE_LIMITED', 2);
    expect(shouldRetry(0, error)).toBe(true);
    expect(retryDelay(0, error)).toBe(2000);
  });
  it('never retries input errors or unknown problem codes', () => {
    expect(shouldRetry(0, problem(400, 'VALIDATION_FAILED'))).toBe(false);
    expect(shouldRetry(0, problem(503, 'SOMETHING_NEW'))).toBe(false);
  });
});
