import { useFocusEffect } from 'expo-router';
import { useCallback, useLayoutEffect, useRef } from 'react';

import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';

import { focusRow } from './row-focus';

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

/** The row to focus on the account screen that a closing transaction route navigates to. */
let rowAfterNavigation: { accountId: string; transactionId: string } | null = null;

/**
 * On a desktop transaction route the URL owns the open transaction, so closing it navigates to
 * the account route: the row that opened it is then on another screen. Call this right before
 * that navigation; `useRowFocusAfterNavigation` on the account screen focuses the row (web).
 */
export function focusRowAfterNavigation(accountId: string, transactionId: string) {
  rowAfterNavigation = { accountId, transactionId };
}

/** Takes the row handed over by `focusRowAfterNavigation` once this account screen is shown. */
export function useRowFocusAfterNavigation(accountId: string, fallback: Fallback) {
  useFocusEffect(
    useCallback(() => {
      const pending = rowAfterNavigation;
      rowAfterNavigation = null;
      if (!pending || pending.accountId !== accountId) return;
      if (!focusRow(pending.transactionId)) moveAccessibilityFocus(fallback.current);
    }, [accountId, fallback]),
  );
}
