export type Messages = Record<string, string>;
export type Catalogues = Record<string, Messages>;

const DEFAULT_LOCALE = 'en';
const normalize = (locale: string) => locale.trim().replace(/_/g, '-').toLowerCase();
const primaryLanguage = (locale: string) => locale.split('-')[0];

export function createLocaleCatalogues(catalogues: Catalogues) {
  const available = new Map(Object.keys(catalogues).map((locale) => [normalize(locale), locale]));

  function resolveLocale(language: string | null | undefined, deviceLocale: string): string {
    const preferred = normalize(language ?? '');
    const device = normalize(deviceLocale);
    // A preference saved as a base language can use the device's region in that same language.
    let candidate = !preferred || preferred === primaryLanguage(device) ? device : preferred;
    while (candidate) {
      const locale = available.get(candidate);
      if (locale) return locale;
      const separator = candidate.lastIndexOf('-');
      candidate = separator === -1 ? '' : candidate.slice(0, separator);
    }
    return DEFAULT_LOCALE;
  }

  function messagesFor(locale: string): Messages {
    const selected = normalize(resolveLocale(locale, ''));
    const messages = { ...catalogues[DEFAULT_LOCALE] };
    let prefix = '';
    for (const subtag of selected.split('-')) {
      prefix = prefix ? `${prefix}-${subtag}` : subtag;
      const key = available.get(prefix);
      if (key && key !== DEFAULT_LOCALE) Object.assign(messages, catalogues[key]);
    }
    return messages;
  }

  return { resolveLocale, messagesFor };
}
