import type { ReactElement } from 'react';

import { AppIntlProvider } from './index';

/** Wraps a component in the app's real catalogues, so tests see the copy a member sees. */
export function withIntl(ui: ReactElement, language: 'en' | 'de' = 'en'): ReactElement {
  return <AppIntlProvider language={language}>{ui}</AppIntlProvider>;
}
