import { renderHook } from '@testing-library/react-native';

import { useEscapeKey } from './escape-key.web';

const key = (name: string) => Object.assign(new Event('keydown'), { key: name });

describe('useEscapeKey (web dialogs)', () => {
  it('closes on Escape only while the dialog is open', async () => {
    const target = new EventTarget();
    const onEscape = jest.fn();
    const view = await renderHook(
      ({ open }: { open: boolean }) => useEscapeKey(open, onEscape, target),
      {
        initialProps: { open: true },
      },
    );

    target.dispatchEvent(key('Enter'));
    target.dispatchEvent(key('Escape'));
    expect(onEscape).toHaveBeenCalledTimes(1);

    await view.rerender({ open: false });
    target.dispatchEvent(key('Escape'));
    expect(onEscape).toHaveBeenCalledTimes(1);
  });
});
