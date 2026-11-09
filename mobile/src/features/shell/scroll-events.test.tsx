import type { ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react-native';

import { ShellScrollProvider, useShellScrollEvents, useWhenOnScreen } from './scroll-events';

const mockWindow = { width: 390, height: 844, scale: 2, fontScale: 1 };
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => mockWindow,
}));

/** A view whose window position the test moves, as scrolling would. */
function fakeView(initialY: number) {
  const view = {
    y: initialY,
    current: {
      measureInWindow: (callback: (x: number, y: number, w: number, h: number) => void) =>
        callback(0, view.y, 390, 0),
    },
  };
  return view;
}

async function renderVisibility(view: ReturnType<typeof fakeView>, enabled = true) {
  const onVisible = jest.fn();
  const scroll = await renderHook(() => useShellScrollEvents());
  const wrapper = ({ children }: { children: ReactNode }) => (
    <ShellScrollProvider events={scroll.result.current.events}>{children}</ShellScrollProvider>
  );
  const hook = await renderHook(({ on }: { on: boolean }) => useWhenOnScreen(view, onVisible, on), {
    wrapper,
    initialProps: { on: enabled },
  });
  return { onVisible, notify: () => scroll.result.current.notify(), hook };
}

describe('useWhenOnScreen (phone infinite scroll)', () => {
  it('reports the view once a scroll brings it into the window', async () => {
    const view = fakeView(2000);
    const { onVisible, notify, hook } = await renderVisibility(view);

    await act(async () => hook.result.current());
    expect(onVisible).not.toHaveBeenCalled();

    view.y = 800;
    await act(async () => notify());
    expect(onVisible).toHaveBeenCalledTimes(1);
  });

  it('reports a view that is already on screen after its layout', async () => {
    const { onVisible, hook } = await renderVisibility(fakeView(300));
    await act(async () => hook.result.current());
    expect(onVisible).toHaveBeenCalledTimes(1);
  });

  it('stays quiet while disabled and after unmount', async () => {
    const view = fakeView(100);
    const { onVisible, notify, hook } = await renderVisibility(view, false);
    await act(async () => {
      hook.result.current();
      notify();
    });
    expect(onVisible).not.toHaveBeenCalled();

    await hook.rerender({ on: true });
    await hook.unmount();
    await act(async () => notify());
    expect(onVisible).not.toHaveBeenCalled();
  });
});
