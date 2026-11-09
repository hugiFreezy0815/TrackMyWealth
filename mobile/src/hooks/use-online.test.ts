import { act, renderHook } from '@testing-library/react-native';
import { onlineManager } from '@tanstack/react-query';

import { useOnline } from './use-online';

describe('useOnline', () => {
  afterEach(() => onlineManager.setOnline(true));

  it('follows the query client connectivity source', async () => {
    onlineManager.setOnline(true);
    const { result } = await renderHook(() => useOnline());
    expect(result.current).toBe(true);
    await act(async () => onlineManager.setOnline(false));
    expect(result.current).toBe(false);
    await act(async () => onlineManager.setOnline(true));
    expect(result.current).toBe(true);
  });
});
