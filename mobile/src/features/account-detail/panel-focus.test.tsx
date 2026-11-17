import { act, renderHook } from '@testing-library/react-native';

import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';
import { usePanelFocusReturn } from './panel-focus';

jest.mock('@/components/shared/accessibility-focus', () => ({
  moveAccessibilityFocus: jest.fn(),
}));

/** Minimal `document.activeElement` stand-in: jest-expo runs without a DOM. */
const fakeDocument = { activeElement: null as unknown };
const element = (name: string) => {
  const node = {
    name,
    isConnected: true,
    focus: jest.fn(() => {
      if (node.isConnected) fakeDocument.activeElement = node;
    }),
  };
  return node;
};

const heading = { name: 'Account detail' };
const fallback = { current: heading as never };

async function renderPanel() {
  const view = await renderHook(
    ({ open }: { open: boolean }) => usePanelFocusReturn(open, fallback),
    {
      initialProps: { open: false },
    },
  );
  return {
    open: async (opener: ReturnType<typeof element>) => {
      fakeDocument.activeElement = opener;
      view.result.current.rememberOpener();
      await view.rerender({ open: true });
    },
    /** A related transaction swaps the panel content without a new opener. */
    followRelated: async () => {
      fakeDocument.activeElement = { name: 'link in panel', focus: jest.fn() };
      await view.rerender({ open: true });
    },
    close: async () => {
      await act(() => view.result.current.returnFocusOnClose());
      fakeDocument.activeElement = { name: 'body' };
      await view.rerender({ open: false });
    },
  };
}

beforeEach(() => {
  jest.mocked(moveAccessibilityFocus).mockReset();
  fakeDocument.activeElement = null;
  Object.defineProperty(globalThis, 'document', { value: fakeDocument, configurable: true });
});

afterEach(() => {
  delete (globalThis as { document?: unknown }).document;
});

describe('usePanelFocusReturn (web transaction panel, #494)', () => {
  it('returns focus to the transaction row that opened the panel', async () => {
    const opener = element('Coffee');
    const panel = await renderPanel();

    await panel.open(opener);
    await panel.close();

    expect(opener.focus).toHaveBeenCalledTimes(1);
    expect(fakeDocument.activeElement).toBe(opener);
    expect(moveAccessibilityFocus).not.toHaveBeenCalled();
  });

  it('returns to the original row after following a related transaction', async () => {
    const opener = element('Corrected purchase');
    const panel = await renderPanel();

    await panel.open(opener);
    await panel.followRelated();
    await panel.close();

    expect(fakeDocument.activeElement).toBe(opener);
    expect(moveAccessibilityFocus).not.toHaveBeenCalled();
  });

  it('remembers the latest row when another row opens while the panel is open', async () => {
    const first = element('Coffee');
    const second = element('Rent');
    const panel = await renderPanel();

    await panel.open(first);
    await panel.open(second);
    await panel.close();

    expect(first.focus).not.toHaveBeenCalled();
    expect(fakeDocument.activeElement).toBe(second);
  });

  it('moves focus to the screen heading when a filter removed the opening row', async () => {
    const opener = element('Coffee');
    const panel = await renderPanel();

    await panel.open(opener);
    opener.isConnected = false;
    await panel.close();

    expect(opener.focus).not.toHaveBeenCalled();
    expect(moveAccessibilityFocus).toHaveBeenCalledWith(heading);
  });

  it('moves focus to the heading when nothing focusable opened the panel', async () => {
    const panel = await renderPanel();

    fakeDocument.activeElement = null;
    await panel.open(null as never);
    await panel.close();

    expect(moveAccessibilityFocus).toHaveBeenCalledWith(heading);
  });

  it('does not move focus on mount or when the panel closes without a return request', async () => {
    const view = await renderHook(
      ({ open }: { open: boolean }) => usePanelFocusReturn(open, fallback),
      { initialProps: { open: true } },
    );
    await view.rerender({ open: false });

    expect(moveAccessibilityFocus).not.toHaveBeenCalled();
  });
});
