import { useEffect, useMemo, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { IntlProvider } from 'react-intl';

import { DEVICE_LOCALE } from './device-intl';
import { catalogues } from './catalogue-context';
import { createLocaleCatalogues } from './locale-catalogues';

export type AppLocale = string;

const localeCatalogues = createLocaleCatalogues(catalogues);

/** The member's chosen language (`/users/me` → `language`) wins over the device language. */
export function resolveLocale(
  language?: string | null,
  deviceLocale: string = DEVICE_LOCALE,
): AppLocale {
  return localeCatalogues.resolveLocale(language, deviceLocale);
}

export const { messagesFor } = localeCatalogues;

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
