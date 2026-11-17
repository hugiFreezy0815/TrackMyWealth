import { useLayoutEffect, useRef } from 'react';

import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';

type FocusTarget = { focus: () => void; isConnected?: boolean };
type Fallback = { readonly current: Parameters<typeof moveAccessibilityFocus>[0] };

function activeElement(): FocusTarget | null {
  if (typeof document === 'undefined') return null;
  const active = document.activeElement as Partial<FocusTarget> | null;
  return typeof active?.focus === 'function' ? (active as FocusTarget) : null;
}

/**
 * Focus return for the web transaction panel (S4 beside S3, #494).
 *
 * `rememberOpener` stores the focused element when a transaction opens from the table. Opening
 * a related transaction inside the panel must not call it, so focus still goes back to the row
 * the user started from. After `returnFocusOnClose` and the panel closing (`open` false), focus
 * goes back to that opener. If a filter or reload removed it meanwhile, focus moves to `fallback`
 * (the screen heading, `tabIndex={-1}`) instead of getting lost on the page body.
 */
export function usePanelFocusReturn(open: boolean, fallback: Fallback) {
  const opener = useRef<FocusTarget | null>(null);
  const pending = useRef(false);

  useLayoutEffect(() => {
    if (open || !pending.current) return;
    pending.current = false;
    const target = opener.current;
    opener.current = null;
    if (target?.isConnected && activeElement() !== target) target.focus();
    if (!target || activeElement() !== target) moveAccessibilityFocus(fallback.current);
  }, [open, fallback]);

  return {
    rememberOpener: () => {
      opener.current = activeElement();
    },
    returnFocusOnClose: () => {
      pending.current = true;
    },
  };
}
