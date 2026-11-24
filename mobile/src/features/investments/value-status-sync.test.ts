// Investments derives a depot balance's value status on the client (model.ts, balanceStatus),
// because /accounts/{id}/balance sends no valueStatus. This keeps that copy in step with the
// backend rule it mirrors: a new approximate basis or another warning there fails here.
// Mobile Web CI also runs when either Java file changes.

import type { components } from '@/api/schema';

import { APPROXIMATE_VALUE_BASES, STATUS_WARNINGS, balanceStatus } from './model';

type Fs = { readFileSync: (path: string, encoding: 'utf8') => string };

// Jest runs from mobile/ (its rootDir); the app's tsconfig has no Node types for path/__dirname.
const dto = `${process.cwd()}/../backend/src/main/java/com/trackmywealth/backend/dto`;
const read = (file: string) =>
  jest.requireActual<Fs>('node:fs').readFileSync(`${dto}/${file}`, 'utf8');

/** The body of the Java method whose signature starts with `signature`. */
function methodBody(source: string, signature: string) {
  const start = source.indexOf(signature);
  expect(start).toBeGreaterThanOrEqual(0);
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let index = open; index < source.length; index++) {
    if (source[index] === '{') depth++;
    if (source[index] === '}' && --depth === 0) return source.slice(open, index + 1);
  }
  throw new Error(`No end to ${signature}`);
}

it('lists the approximate value bases of ValueBasisValues.isApproximate', () => {
  const source = read('ValueBasisValues.java');
  const constants = Object.fromEntries(
    [...source.matchAll(/public static final String (\w+)\s*=\s*"([^"]+)"/g)].map(
      ([, name, value]) => [name, value],
    ),
  );
  const approximate = [
    ...methodBody(source, 'public static boolean isApproximate(').matchAll(
      /(\w+)\.equals\(valueBasis\)/g,
    ),
  ].map(([, name]) => constants[name]);
  expect([...APPROXIMATE_VALUE_BASES].sort()).toEqual([...approximate].sort());
});

it('reads the warnings ValueStatus.of reads, in its order', () => {
  const body = methodBody(read('ValueStatus.java'), 'public static ValueStatus of(');
  const checks = [...body.matchAll(/DataQualityWarningValues\.(\w+)|return (\w+);/g)].map(
    ([, warning, status]) => warning ?? status,
  );
  expect(checks).toEqual([
    'UNKNOWN',
    STATUS_WARNINGS.unreconciled,
    'UNRECONCILED',
    'STALE',
    STATUS_WARNINGS.estimated,
    'ESTIMATED',
    'CURRENT',
  ]);
  // The client checks the same flags and basis rule as the Java method.
  for (const flag of ['valueKnown()', 'conversionRateStale()', 'valueSourceStale()']) {
    expect(body).toContain(`valuation.${flag}`);
  }
  expect(body).toContain('ValueBasisValues.isApproximate(valuation.valueBasis())');
});

const balance: components['schemas']['AccountValuation'] = {
  accountId: 'depot',
  name: 'Depot',
  nativeCurrency: 'EUR',
  currency: 'EUR',
  nature: 'ASSET',
  value: '10.00',
  valueKnown: true,
  valueSourceDate: '2026-11-20',
  valueSourceStale: false,
  conversionRate: null,
  conversionRateDate: null,
  conversionRateCarriedForward: false,
  conversionRateStale: false,
  valueBasis: 'LATEST_SNAPSHOT',
  warnings: [],
};

type Change = Partial<components['schemas']['AccountValuation']>;

it.each<[Change, string]>([
  [{}, 'CURRENT'],
  [{ valueKnown: false }, 'UNKNOWN'],
  [{ warnings: [STATUS_WARNINGS.unreconciled], conversionRateStale: true }, 'UNRECONCILED'],
  [{ conversionRateStale: true, valueBasis: 'LEDGER_EMPTY' }, 'STALE'],
  [{ valueSourceStale: true }, 'STALE'],
  ...APPROXIMATE_VALUE_BASES.map((valueBasis): [Change, string] => [{ valueBasis }, 'ESTIMATED']),
  [{ warnings: [STATUS_WARNINGS.estimated] }, 'ESTIMATED'],
])('derives the status of %o as %s', (change, status) => {
  expect(balanceStatus({ ...balance, ...change })).toBe(status);
});
