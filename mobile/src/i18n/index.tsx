import { useEffect, useMemo, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { IntlProvider } from 'react-intl';

import { DEVICE_LOCALE } from './device-intl';
import en from './en.json';
import de from './de.json';
import deCH from './de-CH.json';
import deDE from './de-DE.json';

export type AppLocale = 'en' | 'de' | 'de-CH' | 'de-DE';

/** The member's chosen language (`/users/me` → `language`) wins over the device locale. */
export function resolveLocale(
  language?: string | null,
  deviceLocale: string = DEVICE_LOCALE,
): AppLocale {
  const locale = (language || deviceLocale || 'en').replace('_', '-').toLowerCase();
  if (locale.startsWith('de-ch')) return 'de-CH';
  if (locale.startsWith('de-de')) return 'de-DE';
  if (locale.startsWith('de')) return 'de';
  return 'en';
}

/** The catalogue of a locale: English as the base, then German, then the regional overrides. */
export function messagesFor(locale: AppLocale): Record<string, string> {
  return {
    ...en,
    ...(locale.startsWith('de') ? de : {}),
    ...(locale === 'de-CH' ? deCH : {}),
    ...(locale === 'de-DE' ? deDE : {}),
  };
}

export function AppIntlProvider({
  language,
  children,
}: {
  language?: string | null;
  children: ReactNode;
}) {
  const locale = resolveLocale(language);
  const messages = useMemo(() => messagesFor(locale), [locale]);
  useEffect(() => {
    // Expo's +html.tsx is rendered only once at build time. Reflect the member's chosen
    // BCP 47 locale after hydration and whenever the authenticated language changes.
    if (Platform.OS === 'web' && typeof document !== 'undefined') {
      document.documentElement.lang = locale;
    }
  }, [locale]);
  return (
    <IntlProvider locale={locale} defaultLocale="en" messages={messages}>
      {children}
    </IntlProvider>
  );
}
