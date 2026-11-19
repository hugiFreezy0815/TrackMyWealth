import { NetworkError } from '@/api/client';

import { isRetryableUploadError, retryAllowedAt } from './model';

it('allows a retry after an uncertain upload outcome without imposing a server delay', () => {
  const timeout = new NetworkError('Request timed out');
  expect(isRetryableUploadError(timeout)).toBe(true);
  expect(retryAllowedAt(timeout)).toBeNull();
});
