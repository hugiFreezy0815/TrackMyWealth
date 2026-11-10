import { createElement, type ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { ApiError } from '@/api/client';
import { ifMatch, retryVersionRequired, useConflict } from './conflict';

const problem = (status: number, code: string) =>
  new ApiError('failed', status, { status, title: 'Problem', code });

async function renderConflict() {
  // gcTime Infinity: no garbage-collection timer keeps Jest running after the tests.
  const client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } });
  client.setQueryData(['accounts', 'a1'], { version: 3 });
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  const view = await renderHook(() => useConflict(), { wrapper });
  return { client, ...view };
}

describe('useConflict', () => {
  it('opens the conflict state and reloads only the conflicting resource', async () => {
    const { client, result } = await renderConflict();
    client.setQueryData(['portfolios'], []);

    let handled = false;
    await act(async () => {
      handled = await result.current.handle(problem(412, 'VERSION_CONFLICT'), ['accounts', 'a1']);
    });

    expect(handled).toBe(true);
    expect(result.current.conflicted).toBe(true);
    expect(client.getQueryState(['accounts', 'a1'])?.isInvalidated).toBe(true);
    expect(client.getQueryState(['portfolios'])?.isInvalidated).toBe(false);

    await act(async () => result.current.dismiss());
    expect(result.current.conflicted).toBe(false);
  });

  it('restarts a paged list behind the conflict at page 0', async () => {
    const { client, result } = await renderConflict();
    const key = ['accounts', 'a1', 'transactions', {}];
    client.setQueryData(key, { pages: ['p0', 'p1', 'p2'], pageParams: [0, 1, 2] });

    await act(async () => {
      await result.current.handle(problem(412, 'VERSION_CONFLICT'), ['accounts', 'a1']);
    });

    expect(client.getQueryData(key)).toEqual({ pages: ['p0'], pageParams: [0] });
    expect(client.getQueryState(key)?.isInvalidated).toBe(true);
  });

  it.each([
    ['another problem', problem(400, 'VALIDATION_FAILED')],
    ['a non-API error', new Error('boom')],
  ])('leaves %s to the caller', async (_, error) => {
    const { client, result } = await renderConflict();
    let handled = true;
    await act(async () => {
      handled = await result.current.handle(error, ['accounts', 'a1']);
    });
    expect(handled).toBe(false);
    expect(result.current.conflicted).toBe(false);
    expect(client.getQueryState(['accounts', 'a1'])?.isInvalidated).toBe(false);
  });
});

describe('retryVersionRequired', () => {
  it('reloads once and resends a known HTTP 428 with the new server version', async () => {
    const write = jest
      .fn()
      .mockRejectedValueOnce(problem(428, 'VERSION_REQUIRED'))
      .mockResolvedValue('ok');
    const reload = jest.fn().mockResolvedValue({ version: 3 });
    await expect(retryVersionRequired(write, 2, reload)).resolves.toBe('ok');
    expect(write.mock.calls).toEqual([[2], [3]]);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('does not retry a second 428 or a 412, or an unknown error code', async () => {
    const reload = jest.fn().mockResolvedValue({ version: 3 });
    const second428 = jest.fn().mockRejectedValue(problem(428, 'VERSION_REQUIRED'));
    await expect(retryVersionRequired(second428, 2, reload)).rejects.toThrow(ApiError);
    expect(second428).toHaveBeenCalledTimes(2);
    expect(reload).toHaveBeenCalledTimes(1);

    reload.mockClear();
    for (const error of [problem(412, 'VERSION_CONFLICT'), problem(428, 'NEW_CODE')]) {
      const write = jest.fn().mockRejectedValue(error);
      await expect(retryVersionRequired(write, 2, reload)).rejects.toThrow(ApiError);
      expect(write).toHaveBeenCalledTimes(1);
    }
    expect(reload).not.toHaveBeenCalled();
  });
});

describe('ifMatch', () => {
  it('sends the server version of the cached resource', () => {
    expect(ifMatch({ version: 7 })).toBe(7);
    expect(ifMatch({ version: 0 })).toBe(0);
  });

  it.each([-1, 1.5, Number.NaN])('rejects %p as a version', (version) => {
    expect(() => ifMatch({ version })).toThrow(TypeError);
  });
});
