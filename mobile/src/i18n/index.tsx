import { useMemo, type ReactNode } from 'react';
import { IntlProvider } from 'react-intl';
import { Platform } from 'react-native';

import en from './en.json';
import de from './de.json';
import deCH from './de-CH.json';
import deDE from './de-DE.json';

export type AppLocale = 'en' | 'de' | 'de-CH' | 'de-DE';

export function resolveLocale(language?: string | null, deviceLocale?: string): AppLocale {
  const raw = language || deviceLocale || systemLocale();
  const locale = raw.replace('_', '-').toLowerCase();
  if (locale.startsWith('de-ch')) return 'de-CH';
  if (locale.startsWith('de-de')) return 'de-DE';
  if (locale.startsWith('de')) return 'de';
  return 'en';
}

/** The device language before sign-in: the browser's on web, the OS locale (Hermes Intl) on native. */
function systemLocale(): string {
  if (Platform.OS === 'web') {
    return typeof navigator !== 'undefined' && navigator.language ? navigator.language : 'en';
  }
  try {
    return Intl.DateTimeFormat().resolvedOptions().locale || 'en';
  } catch {
    return 'en';
  }
}

export function AppIntlProvider({
  language,
  children,
}: {
  language?: string | null;
  children: ReactNode;
}) {
  const locale = resolveLocale(language);
  const messages = useMemo(
    () => ({
      ...en,
      ...(locale.startsWith('de') ? de : {}),
      ...(locale === 'de-CH' ? deCH : {}),
      ...(locale === 'de-DE' ? deDE : {}),
    }),
    [locale],
  );
  return (
    <IntlProvider locale={locale} defaultLocale="en" messages={messages}>
      {children}
    </IntlProvider>
  );
}
