import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { IntlProvider } from 'react-intl';

import { DEVICE_LOCALE } from './device-intl';
import { catalogues } from './catalogue-context';
import { createLocaleCatalogues } from './locale-catalogues';

/**
 * A locale tag such as `de-CH`. Catalogues are discovered at build time (`<locale>.json`), so this
 * cannot be a fixed union; `appLocales` lists the ones this build ships.
 */
export type AppLocale = string;

const localeCatalogues = createLocaleCatalogues(catalogues);

/** Every locale this build has a catalogue for, e.g. `['de', 'de-CH', 'de-DE', 'en']`. */
export const appLocales: readonly AppLocale[] = Object.keys(catalogues).sort();

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
  const [hasMounted, setHasMounted] = useState(false);
  // Match the English static HTML on the first web render. The browser locale is only
  // available on the client and must not change the restoring-session text during hydration.
  const locale = Platform.OS === 'web' && !hasMounted ? 'en' : resolveLocale(language);
  const messages = useMemo(() => messagesFor(locale), [locale]);
  useEffect(() => {
    // A second render applies the browser locale after the static HTML has hydrated.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (Platform.OS === 'web') setHasMounted(true);
  }, []);
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
