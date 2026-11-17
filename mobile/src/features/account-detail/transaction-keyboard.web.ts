type ArrowKeyEvent = {
  key: string;
  altKey: boolean;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
  currentTarget: Pick<Element, 'closest'>;
  preventDefault: () => void;
};

/**
 * Arrow Up / Arrow Down on a transaction button moves focus to the open button of the row above
 * or below (#494, S3 accessibility). Modified keys and other keys stay with the browser. At the
 * first or last row the key is swallowed and focus stays, like a listbox.
 */
export function focusAdjacentTransaction(event: ArrowKeyEvent): void {
  const { key, currentTarget, altKey, ctrlKey, metaKey, shiftKey } = event;
  if ((key !== 'ArrowUp' && key !== 'ArrowDown') || altKey || ctrlKey || metaKey || shiftKey)
    return;

  event.preventDefault();
  const row = currentTarget.closest('tr');
  const nextRow = key === 'ArrowUp' ? row?.previousElementSibling : row?.nextElementSibling;
  nextRow?.querySelector<HTMLButtonElement>('button[data-transaction-open]:enabled')?.focus();
}

type RowFocusEvent = {
  currentTarget: Pick<Element, 'contains' | 'scrollIntoView'>;
  relatedTarget: unknown;
};

/**
 * Scrolls a row into the account scroll container when focus enters it from outside (arrow keys
 * or Tab), so the focused control is never hidden below the fold. Focus moving between controls
 * of the same row does not scroll again.
 */
export function revealFocusedRow(event: RowFocusEvent): void {
  const { currentTarget, relatedTarget } = event;
  if (currentTarget.contains(relatedTarget as Node | null)) return;
  currentTarget.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}
