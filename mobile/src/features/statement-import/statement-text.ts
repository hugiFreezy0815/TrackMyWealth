import { useCallback } from 'react';
import { useIntl, type IntlShape } from 'react-intl';

export type Text = (id: string, values?: Record<string, string | number>) => string;

/** The catalogue lookup S7's components share. */
export function useText(): Text {
  const intl = useIntl();
  return useCallback((id, values) => intl.formatMessage({ id }, values), [intl]);
}

/**
 * `transactionType.{code}`. A type this app version does not know shows no label rather than the
 * server's code (spec README: no technical codes in the UI).
 */
export function transactionTypeLabel(intl: IntlShape, code: string): string | null {
  const id = `transactionType.${code}`;
  return id in intl.messages ? intl.formatMessage({ id }) : null;
}
