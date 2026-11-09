import { isValidIsoDate } from './fields';

export type DateFieldProps = {
  label: string;
  /** The chosen calendar date as `YYYY-MM-DD`, or null when none is chosen. */
  value: string | null;
  onChange: (value: string) => void;
  /** For an optional date: offers to clear it. Without it, a chosen date can only be changed. */
  onClear?: () => void;
  /** Earliest and latest selectable dates, `YYYY-MM-DD`. */
  minimumDate?: string;
  maximumDate?: string;
  error?: string;
  hint?: string;
  disabled?: boolean;
  testID?: string;
};

/** A calendar date as a local Date at midnight: the native picker works in the device's zone. */
export function isoToLocalDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}

export function localDateToIso(date: Date): string {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}

export const optionalLocalDate = (value?: string) =>
  value && isValidIsoDate(value) ? isoToLocalDate(value) : undefined;
