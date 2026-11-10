import type { DecimalString } from '@/api/decimal';

import type { AmountIntl } from './amount';
import { decimalParts, isPlainDecimal, type FractionDigits, type SignRole } from './decimal-parts';

/**
 * Non-money `format: decimal` values from the API, in the UX-02 house format (#378 section 3):
 * - `quantity`: units of a position, every digit the server sends (no rounding, trailing zeros
 *   trimmed), e.g. `1'250`, `0.00125`;
 * - `percent`: a server-calculated percentage, one decimal as in Figma M05/M06, e.g. `+11.2%`;
 * - `rate`: an FX rate, four to six decimals, e.g. `0.9362`.
 * The client never derives these figures (FR-API-010); it only rounds them for display.
 */
export type FigureKind = 'quantity' | 'percent' | 'rate';

export type FigureOptions = {
  /** 'change' shows an explicit + on positive values (gains, returns). */
  signRole?: SignRole;
  /** Privacy masking is on: percentages, units and rates are hidden like amounts (UX-02 §7). */
  masked?: boolean;
};

const MINUS = '−'; // U+2212 MINUS SIGN
const HIDDEN = '••••';
const UNKNOWN = '–'; // U+2013 EN DASH
// The largest fraction length Intl.NumberFormat accepts; no API quantity comes near it.
const MAX_FRACTION = 100;

function fractionFor(kind: FigureKind, value: DecimalString): FractionDigits {
  if (kind === 'percent') return { min: 1, max: 1 };
  if (kind === 'rate') return { min: 4, max: 6 };
  const fraction = (value.split('.')[1] ?? '').replace(/0+$/, '').length;
  const exact = Math.min(fraction, MAX_FRACTION);
  return { min: exact, max: exact };
}

function parts(
  value: DecimalString,
  kind: FigureKind,
  locale: string,
  signRole: SignRole | undefined,
) {
  if (!isPlainDecimal(value)) throw new TypeError('Expected a plain DecimalString');
  return decimalParts(value, fractionFor(kind, value), locale, signRole);
}

export function formatFigure(
  value: DecimalString | null,
  kind: FigureKind,
  locale: string,
  options: FigureOptions = {},
): string {
  if (options.masked) return HIDDEN;
  if (value === null) return UNKNOWN;
  const { sign, grouped } = parts(value, kind, locale, options.signRole);
  const signText = sign === 'minus' ? MINUS : sign === 'plus' ? '+' : '';
  return `${signText}${grouped}${kind === 'percent' ? '%' : ''}`;
}

/** The spoken form: sign in words, no grouping, the reader's decimal separator (`figure.a11y`). */
export function a11yFigure(
  value: DecimalString | null,
  kind: FigureKind,
  intl: AmountIntl,
  options: FigureOptions = {},
): string {
  if (options.masked) return intl.formatMessage({ id: 'value.hidden' });
  if (value === null) return intl.formatMessage({ id: 'value.unknown' });
  const { sign, plain } = parts(value, kind, intl.locale, options.signRole);
  return intl.formatMessage(
    { id: 'figure.a11y' },
    { sign: sign || 'none', value: plain, percent: kind === 'percent' ? 'yes' : 'no' },
  );
}

/** The direction a gain/loss colour may show: from the displayed (rounded) sign, never raw. */
export function figureDirection(display: string): 'up' | 'down' | 'flat' {
  return display.startsWith(MINUS) ? 'down' : display.startsWith('+') ? 'up' : 'flat';
}
