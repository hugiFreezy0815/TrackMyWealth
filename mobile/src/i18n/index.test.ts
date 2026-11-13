/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { createIntl, createIntlCache } from 'react-intl';

import { PROBLEM_CODE_REACTIONS } from '@/api/problem-codes.generated';

import { DEVICE_LOCALE } from './device-intl';
import { appLocales, messagesFor, resolveLocale } from './index';
import de from './de.json';
import en from './en.json';
import fr from './fixtures/fr.json';
import italian from './fixtures/it.json';
import { createLocaleCatalogues } from './locale-catalogues';

describe('locales and ICU messages', () => {
  it('chooses the member language over the device locale', () => {
    expect(resolveLocale('de-CH', 'en-US')).toBe('de-CH');
    expect(resolveLocale('en', 'de-CH')).toBe('en');
    expect(resolveLocale('de', 'de_CH')).toBe('de-CH');
    expect(resolveLocale('de', 'de-DE')).toBe('de-DE');
    expect(resolveLocale('de', 'en-US')).toBe('de');
  });

  it('falls back to the device locale when the member chose none', () => {
    expect(resolveLocale(null, 'de-DE')).toBe('de-DE');
    expect(resolveLocale('', 'de_CH')).toBe('de-CH');
    expect(resolveLocale(undefined, 'de-AT')).toBe('de');
    expect(resolveLocale(undefined, 'fr-CH')).toBe('en');
  });

  it('reads the device locale from Intl by default', () => {
    expect(DEVICE_LOCALE).toBe(new Intl.DateTimeFormat().resolvedOptions().locale);
    expect(resolveLocale(null)).toBe(resolveLocale(null, DEVICE_LOCALE));
  });

  it('renders singular and plural in German', () => {
    const intl = createIntl({ locale: 'de', messages: de }, createIntlCache());
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 1 })).toBe('1 Buchung');
    expect(intl.formatMessage({ id: 'import.rows' }, { count: 3 })).toBe('3 Buchungen');
  });

  it('has the same keys in every catalogue', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort());
  });

  it('provides EN and DE copy for every known API problem', () => {
    for (const code of Object.keys(PROBLEM_CODE_REACTIONS)) {
      const key = `problem.${code}`;
      expect(messagesFor('en')[key]).toMatch(/\S/);
      expect(messagesFor('de')[key]).toMatch(/\S/);
    }
  });

  it('uses the regional account term and spelling', () => {
    expect(messagesFor('de-DE')['accountType.CASH']).toBe('Girokonto');
    expect(messagesFor('de-CH')['accountType.CASH']).toBe('Privatkonto');
    expect(messagesFor('de-DE')['ui.close']).toBe('Schließen');
    expect(messagesFor('de-CH')['ui.close']).toBe('Schliessen');
  });

  it.each([
    ['fr-CH', 'fr', '2 opérations'],
    ['it-CH', 'it', '2 movimenti'],
  ])(
    'uses the %s catalogue fallback when its base catalogue is added',
    (device, base, expected) => {
      const localeCatalogues = createLocaleCatalogues({ en, de, fr, it: italian });
      const locale = localeCatalogues.resolveLocale(undefined, device);
      expect(locale).toBe(base);
      expect(localeCatalogues.resolveLocale(base, device)).toBe(base);
      expect(localeCatalogues.resolveLocale('en', device)).toBe('en');
      const messages = localeCatalogues.messagesFor(locale);
      const intl = createIntl({ locale, messages }, createIntlCache());
      expect(intl.formatMessage({ id: 'import.rows' }, { count: 2 })).toBe(expected);
      expect(messages['nav.overview']).toBe(en['nav.overview']);
    },
  );

  it('layers regional translations over the base catalogue', () => {
    const regional = { 'nav.overview': 'Vue suisse' };
    const localeCatalogues = createLocaleCatalogues({ en, fr, 'fr-CH': regional });
    expect(localeCatalogues.resolveLocale('fr', 'fr-CH')).toBe('fr-CH');
    expect(localeCatalogues.messagesFor('fr-CH')).toMatchObject({ ...fr, ...regional });
  });
});

describe('catalogue files', () => {
  const catalogueFiles = [
    ...readdirSync(__dirname).filter((name) => name.endsWith('.json')),
    ...readdirSync(join(__dirname, 'fixtures')).map((name) => join('fixtures', name)),
  ];
  // The flat catalogues are formatted by Prettier: one top-level key per line, indented by two.
  const TOP_LEVEL_KEY = /^ {2}"((?:[^"\\]|\\.)*)":/gm;

  // JSON.parse keeps the last of two equal keys silently, so a merge that adds a key twice would
  // replace copy without any failing test.
  it.each(catalogueFiles)('%s has no duplicate keys', (file) => {
    const text = readFileSync(join(__dirname, file), 'utf8');
    const keys = [...text.matchAll(TOP_LEVEL_KEY)].map((match) => match[1]);
    expect(keys.filter((key, index) => keys.indexOf(key) !== index)).toEqual([]);
    expect(keys).toHaveLength(Object.keys(JSON.parse(text) as object).length);
  });

  // Jest cannot run Metro's require.context; its loader must pick the same files.
  it('loads the same catalogue files in Jest as in the Metro bundle', () => {
    const pattern = (file: string) =>
      readFileSync(join(__dirname, file), 'utf8').match(/\/\^\S*\.json\$\//)?.[0];
    expect(pattern('jest-catalogue-context.cjs')).toBeDefined();
    // Metro's keys start with "./"; the Jest loader matches bare file names.
    expect(pattern('catalogue-context.ts')?.replace('^\\.\\/', '^')).toBe(
      pattern('jest-catalogue-context.cjs'),
    );
    expect(appLocales).toEqual(
      readdirSync(__dirname)
        .filter((name) => /^[a-z]{2,3}(?:-[A-Z]{2})?\.json$/.test(name))
        .map((name) => name.slice(0, -5))
        .sort(),
    );
  });
});
