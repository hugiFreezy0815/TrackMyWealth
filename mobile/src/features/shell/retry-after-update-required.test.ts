import { waitFor } from '@testing-library/react-native';

import { ApiError, NetworkError, setAccessToken, setRefreshHandler } from '@/api/client';
import { queryClient } from '@/api/queries/client';
import {
  dismissUpdateRequired,
  reportUpdateRequired,
  updateRequiredSnapshot,
} from '@/api/update-required';

import { retryAfterUpdateRequired } from './retry-after-update-required';

jest.mock('expo-constants', () => ({
  __esModule: true,
  default: { expoConfig: { version: '1.0.0' } },
}));

function answer(status: number): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => (status === 426 ? { code: 'APP_UPDATE_REQUIRED' } : {}),
  } as Response;
}

describe('retryAfterUpdateRequired integration (#649)', () => {
  let invalidate: jest.SpyInstance;

  beforeEach(() => {
    dismissUpdateRequired();
    reportUpdateRequired();
    setAccessToken('retained-access-token');
    invalidate = jest.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);
  });

  afterEach(() => {
    invalidate.mockRestore();
    setAccessToken(null);
    setRefreshHandler(null);
    dismissUpdateRequired();
    jest.resetAllMocks();
  });

  it('keeps the barrier and retained token when the server still returns 426', async () => {
    const fetchMock = jest.fn().mockResolvedValue(answer(426));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(retryAfterUpdateRequired()).rejects.toMatchObject({
      status: 426,
    } satisfies Partial<ApiError>);
    expect(updateRequiredSnapshot()).toBe(true);
    expect(invalidate).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/users/me'),
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-App-Version': '1.0.0',
          Authorization: 'Bearer retained-access-token',
        }),
      }),
    );
  });

  it('waits for an accepted version before dismissing the barrier', async () => {
    let finishRefresh: (() => void) | undefined;
    invalidate.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finishRefresh = resolve;
        }),
    );
    const fetchMock = jest.fn().mockResolvedValue(answer(200));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const retry = retryAfterUpdateRequired();
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(1));
    expect(updateRequiredSnapshot()).toBe(true);

    finishRefresh?.();
    await retry;
    expect(updateRequiredSnapshot()).toBe(false);
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it.each([401, 403])('recognizes authorization status %s as version accepted', async (status) => {
    globalThis.fetch = jest.fn().mockResolvedValue(answer(status)) as unknown as typeof fetch;

    await expect(retryAfterUpdateRequired()).resolves.toBeUndefined();
    expect(updateRequiredSnapshot()).toBe(false);
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('keeps the barrier and retries after a network failure', async () => {
    const fetchMock = jest
      .fn()
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(answer(200));
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    await expect(retryAfterUpdateRequired()).rejects.toBeInstanceOf(NetworkError);
    expect(updateRequiredSnapshot()).toBe(true);
    expect(invalidate).not.toHaveBeenCalled();

    await expect(retryAfterUpdateRequired()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(updateRequiredSnapshot()).toBe(false);
  });

  it('does not dismiss for unrelated server errors', async () => {
    globalThis.fetch = jest.fn().mockResolvedValue(answer(503)) as unknown as typeof fetch;

    await expect(retryAfterUpdateRequired()).rejects.toMatchObject({ status: 503 });
    expect(updateRequiredSnapshot()).toBe(true);
  });
});
