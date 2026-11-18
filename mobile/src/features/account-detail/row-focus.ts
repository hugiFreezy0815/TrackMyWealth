import { useLayoutEffect, useRef, useState } from 'react';
import { Platform } from 'react-native';

import { moveAccessibilityFocus } from '@/components/shared/accessibility-focus';

type Fallback = { readonly current: Parameters<typeof moveAccessibilityFocus>[0] };

/** The DOM id (web) of a transaction row's open control, in the table and in the phone list. */
export const transactionElementId = (transactionId: string) => `transaction-${transactionId}`;

type Pending =
  | { kind: 'removed'; transactionId: string; before: readonly string[]; dismissed: boolean }
  | { kind: 'appended'; before: readonly string[] };

/** The rows nearest to a removed one: the following rows first, then the preceding ones. */
function nearestRows(transactionId: string, before: readonly string[]): string[] {
  const index = before.indexOf(transactionId);
  if (index < 0) return [];
  return [...before.slice(index + 1), ...before.slice(0, index).reverse()];
}

/** True when focus sits on nothing: the control that had it was removed from the page. */
function focusLost(): boolean {
  if (typeof document === 'undefined') return false;
  const current = document.activeElement;
  return !current || current === document.body;
}

function focusRow(transactionId: string | undefined): boolean {
  if (!transactionId || typeof document === 'undefined') return false;
  const target = document.getElementById(transactionElementId(transactionId));
  if (!target) return false;
  target.focus();
  return document.activeElement === target;
}

/**
 * Keyboard focus after the transaction list changes under the user (#494, S3 accessibility).
 * Web only: native screen-reader focus is not a requirement.
 *
 * - After a category save, the reload can drop the row (Uncategorised filter). Focus then moves
 *   to the next row, else the previous one, else `fallback` (the account heading,
 *   `tabIndex={-1}`). A row that stays keeps the focus the closing dialog gives back. The move
 *   waits for `dialogDismissed`: until then the web modal's focus trap pulls focus back in.
 * - After "Load more", focus moves to the first new row: the button may be gone (last page), and
 *   reading continues where the new rows start. The "Showing …" live region announces the count.
 *
 * `ids` is the loaded list in display order; `settled` is false while the list is fetching.
 */
export function useRowFocus(ids: readonly string[], settled: boolean, fallback: Fallback) {
  const pending = useRef<Pending | null>(null);
  const [request, setRequest] = useState(0);

  useLayoutEffect(() => {
    const current = pending.current;
    if (!current || !settled) return;
    if (current.kind === 'appended') {
      if (ids.length < current.before.length) pending.current = null;
      if (ids.length <= current.before.length) return;
      pending.current = null;
      focusRow(ids[current.before.length]);
      return;
    }
    if (!current.dismissed) return;
    pending.current = null;
    // A row that stays got focus back from the dialog. When the filter fell back to All (count
    // 0) the row is listed again, but its old control is gone and focus with it.
    if (ids.includes(current.transactionId) && !focusLost()) return;
    const next = nearestRows(current.transactionId, current.before).find((id) => ids.includes(id));
    if (!focusRow(next)) moveAccessibilityFocus(fallback.current);
  }, [ids, settled, fallback, request]);

  const enabled = Platform.OS === 'web';
  return {
    /** Call once the save and its list reload are done, with the rows shown before the save. */
    afterCategorySave: (transactionId: string, before: readonly string[]) => {
      if (!enabled) return;
      pending.current = { kind: 'removed', transactionId, before, dismissed: false };
    },
    /** The category dialog has closed and released focus (its `onDismiss`). */
    dialogDismissed: () => {
      const current = pending.current;
      if (current?.kind !== 'removed') return;
      pending.current = { ...current, dismissed: true };
      setRequest((value) => value + 1);
    },
    /** Call when "Load more" is pressed, with the rows shown before it. */
    beforeLoadMore: (before: readonly string[]) => {
      if (!enabled) return;
      pending.current = { kind: 'appended', before };
    },
    /** A new filter or sort replaces the list: nothing to restore. */
    reset: () => {
      pending.current = null;
    },
  };
}
