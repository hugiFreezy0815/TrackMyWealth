import { NetworkError } from '@/api/client';

import { problem } from './fake-import-server';
import { isRetryableUploadError, retryAllowedAt } from './model';
import { supportsUploadReplay } from './upload-replay';

jest.mock('./upload-replay', () => ({ supportsUploadReplay: jest.fn(() => false) }));

const timeout = new NetworkError('Request timed out');

it('keeps an uncertain upload outcome non-retryable while the server cannot replay it', () => {
  jest.mocked(supportsUploadReplay).mockReturnValue(false);
  expect(isRetryableUploadError(timeout)).toBe(false);
  expect(retryAllowedAt(timeout)).toBeNull();
});

it('allows a retry after an uncertain upload outcome once replay is supported', () => {
  jest.mocked(supportsUploadReplay).mockReturnValue(true);
  expect(isRetryableUploadError(timeout)).toBe(true);
  expect(retryAllowedAt(timeout)).toBeNull();
});

it('ships with replay enabled now that the server replays a keyed upload (#574)', () => {
  expect(
    jest.requireActual<typeof import('./upload-replay')>('./upload-replay').supportsUploadReplay(),
  ).toBe(true);
});

it('retries an upload whose first request with the same key still runs', () => {
  const inUse = problem('IDEMPOTENCY_KEY_IN_USE', 409, 2);
  expect(isRetryableUploadError(inUse)).toBe(true);
  expect(retryAllowedAt(inUse, 1_000)).toBe(3_000);
});
