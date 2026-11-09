import { render } from '@testing-library/react-native';
import type { ReactElement, ReactNode } from 'react';
import { IntlProvider } from 'react-intl';

import { messagesFor, type AppLocale } from './index';

/**
 * Render inside the app's catalogue for `locale`. A message id missing from the catalogue fails
 * the test instead of silently showing the id.
 */
export function renderWithIntl(ui: ReactElement, locale: AppLocale = 'en') {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <IntlProvider
        locale={locale}
        defaultLocale="en"
        messages={messagesFor(locale)}
        onError={(error) => {
          throw error;
        }}
      >
        {children}
      </IntlProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}
