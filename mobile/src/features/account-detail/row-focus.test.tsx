import { act, renderHook } from '@testing-library/react-native';
import { Platform } from 'react-native';

import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';
import { transactionElementId, useRowFocus } from './row-focus';

jest.mock('@/components/shared/accessibility-focus', () => ({
  moveAccessibilityFocus: jest.fn(),
}));

/** Minimal DOM stand-in: jest-expo runs without one. Every listed row has a focusable control. */
const fakeDocument = {
  activeElement: null as unknown,
  rows: new Map<string, { focus: jest.Mock }>(),
  getElementById(id: string) {
    return this.rows.get(id) ?? null;
  },
};
function showRows(ids: readonly string[]) {
  fakeDocument.rows.clear();
  for (const id of ids) {
    const node = {
      focus: jest.fn(() => {
        fakeDocument.activeElement = node;
      }),
    };
    fakeDocument.rows.set(transactionElementId(id), node);
  }
}
const focusedRow = () =>
  [...fakeDocument.rows].find(([, node]) => node === fakeDocument.activeElement)?.[0] ?? null;

const heading = { name: 'Account heading' };
const fallback = { current: heading as never };
const originalOS = Platform.OS;

async function renderList(ids: string[], settled = true) {
  showRows(ids);
  const view = await renderHook(
    (props: { ids: string[]; settled: boolean }) => useRowFocus(props.ids, props.settled, fallback),
    { initialProps: { ids, settled } },
  );
  return {
    hook: () => view.result.current,
    show: async (next: string[], nextSettled = true) => {
      showRows(next);
      await view.rerender({ ids: next, settled: nextSettled });
    },
  };
}

beforeEach(() => {
  Platform.OS = 'web';
  jest.mocked(moveAccessibilityFocus).mockReset();
  fakeDocument.activeElement = null;
  Object.defineProperty(globalThis, 'document', { value: fakeDocument, configurable: true });
});

afterEach(() => {
  Platform.OS = originalOS;
  delete (globalThis as { document?: unknown }).document;
});

describe('S3 list focus after a reload (#494)', () => {
  it('moves focus to the next row when a category save removes the row', async () => {
    const list = await renderList(['a', 'b', 'c']);
    await list.show(['a', 'c']);
    await act(() => list.hook().afterCategorySave('b', ['a', 'b', 'c']));
    expect(focusedRow()).toBe(transactionElementId('c'));
    expect(moveAccessibilityFocus).not.toHaveBeenCalled();
  });

  it('moves focus to the previous row when the last row leaves', async () => {
    const list = await renderList(['a', 'b']);
    await list.show(['a']);
    await act(() => list.hook().afterCategorySave('b', ['a', 'b']));
    expect(focusedRow()).toBe(transactionElementId('a'));
  });

  it('moves focus to the heading when the list is left empty', async () => {
    const list = await renderList(['a']);
    await list.show([]);
    await act(() => list.hook().afterCategorySave('a', ['a']));
    expect(moveAccessibilityFocus).toHaveBeenCalledWith(heading);
  });

  it('leaves focus alone when the saved row stays in the list', async () => {
    const list = await renderList(['a', 'b']);
    await act(() => list.hook().afterCategorySave('a', ['a', 'b']));
    expect(focusedRow()).toBeNull();
    expect(moveAccessibilityFocus).not.toHaveBeenCalled();
  });

  it('waits for a running reload before choosing the row', async () => {
    const list = await renderList(['a', 'b', 'c'], false);
    await act(() => list.hook().afterCategorySave('b', ['a', 'b', 'c']));
    expect(focusedRow()).toBeNull();
    await list.show(['a', 'c'], true);
    expect(focusedRow()).toBe(transactionElementId('c'));
  });

  it('moves focus to the first new row once Load more has appended rows', async () => {
    const list = await renderList(['a', 'b']);
    list.hook().beforeLoadMore(['a', 'b']);
    await list.show(['a', 'b'], false);
    expect(focusedRow()).toBeNull();
    await list.show(['a', 'b', 'c', 'd']);
    expect(focusedRow()).toBe(transactionElementId('c'));
  });

  it('keeps waiting after a failed page and drops the request when the list restarts', async () => {
    const list = await renderList(['a', 'b']);
    list.hook().beforeLoadMore(['a', 'b']);
    await list.show(['a', 'b']);
    expect(focusedRow()).toBeNull();
    await list.show(['a']);
    await list.show(['a', 'b', 'c']);
    expect(focusedRow()).toBeNull();
  });

  it('forgets a pending focus move when the filter or sort changes', async () => {
    const list = await renderList(['a', 'b']);
    list.hook().beforeLoadMore(['a', 'b']);
    list.hook().reset();
    await list.show(['a', 'b', 'c']);
    expect(focusedRow()).toBeNull();
  });

  it('does nothing on native, where screen-reader focus is not managed', async () => {
    Platform.OS = 'ios';
    const list = await renderList(['a', 'b']);
    await list.show(['a']);
    await act(() => list.hook().afterCategorySave('b', ['a', 'b']));
    expect(focusedRow()).toBeNull();
    expect(moveAccessibilityFocus).not.toHaveBeenCalled();
  });
});
