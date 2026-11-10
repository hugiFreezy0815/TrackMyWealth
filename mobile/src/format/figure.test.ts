import { createIntl, createIntlCache } from 'react-intl';

import type { DecimalString } from '@/api/decimal';
import de from '@/i18n/de.json';
import en from '@/i18n/en.json';
import { a11yFigure, figureDirection, formatFigure, type FigureKind } from './figure';

const d = (value: string) => value as DecimalString;
const intl = (locale: string, messages: Record<string, string>) =>
  createIntl({ locale, messages }, createIntlCache());

describe('formatFigure (#493, UX-02 house format for non-money decimals)', () => {
  it.each<[string, FigureKind, 'balance' | 'change' | undefined, string]>([
    // Units: every digit the server sends, trailing zeros trimmed, never rounded.
    ['310.000000', 'quantity', undefined, '310'],
    ['1250.5', 'quantity', undefined, "1'250.5"],
    ['0.00012345', 'quantity', undefined, '0.00012345'],
    ['-12', 'quantity', undefined, '−12'],
    // Percentages: one decimal (Figma M05/M06), sign decided after rounding.
    ['11.24', 'percent', 'change', '+11.2%'],
    ['-3.75', 'percent', 'change', '−3.8%'],
    ['0.00', 'percent', 'change', '0.0%'],
    ['0.04', 'percent', 'change', '0.0%'],
    ['-0.04', 'percent', 'change', '0.0%'],
    ['1234.5', 'percent', undefined, "1'234.5%"],
    // Rates: four to six decimals.
    ['0.9362000000', 'rate', undefined, '0.9362'],
    ['0.0058123456', 'rate', undefined, '0.005812'],
    ['170.1', 'rate', undefined, '170.1000'],
  ])('%s as %s (%s) → %s', (value, kind, signRole, expected) => {
    expect(formatFigure(d(value), kind, 'en', { signRole })).toBe(expected);
  });

  it('displays the same format in every UI language', () => {
    expect(formatFigure(d('1250.5'), 'quantity', 'de-DE')).toBe("1'250.5");
    expect(formatFigure(d('11.24'), 'percent', 'de-CH', { signRole: 'change' })).toBe('+11.2%');
  });

  it('masks every figure and shows an unknown one as a dash', () => {
    expect(formatFigure(d('11.24'), 'percent', 'en', { masked: true })).toBe('••••');
    expect(formatFigure(d('310'), 'quantity', 'en', { masked: true })).toBe('••••');
    expect(formatFigure(null, 'percent', 'en')).toBe('–');
  });

  it.each(['1e3', '12,5', ' 1', 'NaN', ''])('rejects %j', (value) => {
    expect(() => formatFigure(d(value), 'quantity', 'en')).toThrow(TypeError);
  });

  it('derives the colour direction from the displayed sign only', () => {
    expect(figureDirection('+11.2%')).toBe('up');
    expect(figureDirection('−3.8%')).toBe('down');
    expect(figureDirection('0.0%')).toBe('flat');
    expect(figureDirection('••••')).toBe('flat');
  });
});

describe('a11yFigure', () => {
  const enIntl = intl('en', en);
  const deIntl = intl('de', { ...en, ...de });

  it('spells the sign and "percent", without group separators', () => {
    expect(a11yFigure(d('11.24'), 'percent', enIntl, { signRole: 'change' })).toBe(
      'plus 11.2 percent',
    );
    expect(a11yFigure(d('-3.75'), 'percent', deIntl, { signRole: 'change' })).toBe(
      'minus 3,8 Prozent',
    );
    expect(a11yFigure(d('1250.5'), 'quantity', enIntl)).toBe('1250.5');
  });

  it('never reads digits of a hidden or unknown figure', () => {
    expect(a11yFigure(d('310'), 'quantity', enIntl, { masked: true })).toBe('hidden');
    expect(a11yFigure(null, 'percent', deIntl)).toBe('Wert unbekannt');
  });
});
