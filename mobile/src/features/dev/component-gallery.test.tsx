import { screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';

// Route files are tested from outside src/app: Expo Router would export a test as a route.
import ComponentGallery from '@/app/dev/components';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-image', () => ({ Image: 'Image' }));
jest.mock('expo-symbols', () => ({ SymbolView: 'SymbolView' }));
jest.mock('@react-native-community/datetimepicker', () => ({
  __esModule: true,
  default: 'DateTimePicker',
  DateTimePickerAndroid: { open: jest.fn() },
}));
jest.mock('@/features/privacy/amount-visibility', () => ({ useAmountsHidden: () => false }));
jest.mock('expo-router', () => ({
  Redirect: function Redirect({ href }: { href: string }) {
    const { Text: MockText } = jest.requireActual('react-native');
    return <MockText>{'redirect:' + href}</MockText>;
  },
}));

// __DEV__ is a constant to the app; the test switches it to see what a release build renders.
function setDev(value: boolean) {
  (globalThis as unknown as { __DEV__: boolean }).__DEV__ = value;
}

describe('/dev/components (#488 gallery)', () => {
  const devBuild = __DEV__;
  afterEach(() => setDev(devBuild));

  it('redirects to the start screen in a release build, even from a deep link', async () => {
    setDev(false);
    await renderWithIntl(<ComponentGallery />);
    expect(screen.getByText('redirect:/')).toBeTruthy();
    expect(screen.queryByText(/Shared components/)).toBeNull();
  });

  it('shows the synthetic gallery in a development build', async () => {
    setDev(true);
    await renderWithIntl(<ComponentGallery />);
    expect(
      screen.getByRole('header', { name: /Shared components \(synthetic values\)/ }),
    ).toBeTruthy();
    expect(screen.queryByText(/^redirect:/)).toBeNull();
  });
});
