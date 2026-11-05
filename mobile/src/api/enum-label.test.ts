import type { components } from './schema';
import { labelFor, UNKNOWN_ENUM_LABELS, type DisplayEnum } from './enum-label';

type ValueStatus = components['schemas']['AccountListItemResponse']['valueStatus'];

const STATUS_LABELS = {
  CURRENT: 'Current',
  ESTIMATED: 'Estimated',
  STALE: 'Stale',
  UNRECONCILED: 'Unreconciled',
  UNKNOWN: 'Unknown',
} satisfies Record<ValueStatus, string>;

describe('API enum display boundary (#452)', () => {
  afterEach(() => jest.restoreAllMocks());

  it('keeps the generated known-value union checked at compile time', () => {
    const known: ValueStatus = 'CURRENT';
    expect(labelFor(STATUS_LABELS, known, UNKNOWN_ENUM_LABELS.en)).toBe('Current');
    function unchecked(): void {
      // @ts-expect-error - the generated union rejects new values at compile time
      const invalid: ValueStatus = 'ART';
      void invalid;
    }
    expect(unchecked).toBeInstanceOf(Function);
  });

  it('uses an EN/DE neutral fallback for a value from a newer backend', () => {
    const future: DisplayEnum<ValueStatus> = 'ART';
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(labelFor(STATUS_LABELS, future, UNKNOWN_ENUM_LABELS.en)).toBe('Other');
    expect(labelFor(STATUS_LABELS, future, UNKNOWN_ENUM_LABELS.de)).toBe('Sonstiges');
  });

  it('never treats inherited object properties as known enum members', () => {
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(labelFor(STATUS_LABELS, 'toString', UNKNOWN_ENUM_LABELS.en)).toBe('Other');
  });

  it('warns only once per new value in development', () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    labelFor(STATUS_LABELS, 'NEW_STATUS_452', UNKNOWN_ENUM_LABELS.en);
    labelFor(STATUS_LABELS, 'NEW_STATUS_452', UNKNOWN_ENUM_LABELS.en);
    labelFor(STATUS_LABELS, 'OTHER_STATUS_452', UNKNOWN_ENUM_LABELS.en);
    expect(warn).toHaveBeenCalledTimes(2);
    expect(warn).toHaveBeenCalledWith('Unknown API enum value:', 'NEW_STATUS_452');
  });

  it('never logs an unknown value in production, and still shows the fallback', () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const environment = process.env.NODE_ENV;
    process.env.NODE_ENV = 'production';
    try {
      // A fresh module, so no value is remembered as already warned about.
      jest.isolateModules(() => {
        const production = jest.requireActual<typeof import('./enum-label')>('./enum-label');
        expect(production.labelFor(STATUS_LABELS, 'PROD_STATUS_452', 'Other')).toBe('Other');
        expect(production.labelFor(STATUS_LABELS, 'CURRENT', 'Other')).toBe('Current');
      });
    } finally {
      process.env.NODE_ENV = environment;
    }
    expect(warn).not.toHaveBeenCalled();
  });
});
