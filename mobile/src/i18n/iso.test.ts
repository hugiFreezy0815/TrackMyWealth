import {
  ISO_COUNTRIES,
  ISO_CURRENCIES,
  countryName,
  countryOptions,
  currencyLabel,
  currencyName,
  currencyOptions,
} from './iso';

describe('ISO pickers (#491)', () => {
  it('holds well-formed, unique codes', () => {
    expect(ISO_CURRENCIES).toHaveLength(155);
    expect(ISO_COUNTRIES).toHaveLength(249);
    expect(ISO_CURRENCIES.every((code) => /^[A-Z]{3}$/.test(code))).toBe(true);
    expect(ISO_COUNTRIES.every((code) => /^[A-Z]{2}$/.test(code))).toBe(true);
    expect(new Set(ISO_CURRENCIES).size).toBe(ISO_CURRENCIES.length);
    expect(new Set(ISO_COUNTRIES).size).toBe(ISO_COUNTRIES.length);
    expect(ISO_CURRENCIES).toEqual(expect.arrayContaining(['CHF', 'EUR', 'USD', 'SEK', 'JPY']));
  });

  it('names codes in the app language and falls back to the code', () => {
    expect(countryName('CH', 'de')).toBe('Schweiz');
    expect(countryName('CH', 'en')).toBe('Switzerland');
    expect(currencyName('CHF', 'de')).toBe('Schweizer Franken');
    expect(currencyLabel('EUR', 'en')).toBe('EUR · Euro');
    expect(currencyLabel('QQQ', 'en')).toBe('QQQ');
    expect(countryName('QQ', 'en')).toBe('QQ');
  });

  it('lists the likely choices first, then the rest by name', () => {
    const currencies = currencyOptions('en', ['EUR', 'CHF', 'EUR', '']);
    expect(currencies.slice(0, 2).map((option) => option.value)).toEqual(['EUR', 'CHF']);
    expect(currencies).toHaveLength(ISO_CURRENCIES.length);
    const rest = currencies.slice(2).map((option) => option.label);
    expect(rest).toEqual([...rest].sort((a, b) => a.localeCompare(b, 'en')));

    const countries = countryOptions('de', ['CH', 'DE']);
    expect(countries[0]).toEqual({ value: 'CH', label: 'Schweiz' });
    expect(countries[1]).toEqual({ value: 'DE', label: 'Deutschland' });
    expect(countries).toHaveLength(249);
  });
});
