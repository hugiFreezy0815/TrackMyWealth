import { NetworkError } from '@/api/client';

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

it('ships with replay disabled until the backend part of #574 is in', () => {
  expect(
    jest.requireActual<typeof import('./upload-replay')>('./upload-replay').supportsUploadReplay(),
  ).toBe(false);
});
