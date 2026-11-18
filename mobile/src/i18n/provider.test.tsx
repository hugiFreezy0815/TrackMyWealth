import { render } from '@testing-library/react-native';
import { Platform, Text } from 'react-native';
import { useIntl } from 'react-intl';

import * as deviceIntl from './device-intl';
import { AppIntlProvider } from './index';

const originalOS = Platform.OS;

afterEach(() => {
  Platform.OS = originalOS;
  jest.restoreAllMocks();
});

it.each(['de-DE', 'de-CH'])('hydrates in English before applying the %s locale', async (locale) => {
  Platform.OS = 'web';
  jest.replaceProperty(deviceIntl, 'DEVICE_LOCALE', locale);
  const renders: string[] = [];

  function RestoringLabel() {
    const intl = useIntl();
    const label = intl.formatMessage({ id: 'session.restoring' });
    renders.push(label);
    return <Text>{label}</Text>;
  }

  const result = await render(
    <AppIntlProvider>
      <RestoringLabel />
    </AppIntlProvider>,
  );

  expect(renders[0]).toBe('Restoring session…');
  expect(result.getByText('Sitzung wird wiederhergestellt …')).toBeOnTheScreen();

  await result.rerender(
    <AppIntlProvider language="EN">
      <RestoringLabel />
    </AppIntlProvider>,
  );

  expect(result.getByText('Restoring session…')).toBeOnTheScreen();
});

it('uses the member locale on the first native render', async () => {
  Platform.OS = 'ios';
  const locales: string[] = [];

  function CurrentLocale() {
    locales.push(useIntl().locale);
    return null;
  }

  await render(
    <AppIntlProvider language="de-CH">
      <CurrentLocale />
    </AppIntlProvider>,
  );

  expect(locales[0]).toBe('de-CH');
});
