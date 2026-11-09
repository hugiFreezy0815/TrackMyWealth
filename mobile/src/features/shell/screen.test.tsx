import { render, screen } from '@testing-library/react-native';
import { Text, useWindowDimensions } from 'react-native';

import { withIntl } from '@/i18n/test-utils';

import { DETAIL_PANEL_WIDTH, ShellScreen } from './screen';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: jest.fn(),
}));

function viewport(width: number) {
  jest.mocked(useWindowDimensions).mockReturnValue({ width, height: 900, scale: 2, fontScale: 1 });
}

const renderScreen = (props: Partial<Parameters<typeof ShellScreen>[0]> = {}) =>
  render(
    withIntl(
      <ShellScreen title="Overview" aside={<Text>Detail</Text>} {...props}>
        <Text>Content</Text>
      </ShellScreen>,
    ),
  );

describe('ShellScreen', () => {
  it('puts the 360 px detail panel beside the content on desktop (W01)', async () => {
    viewport(1280);
    await renderScreen();

    expect(screen.getByTestId('shell-detail-panel')).toHaveStyle({ width: DETAIL_PANEL_WIDTH });
  });

  it('stacks the detail panel below the content on phone and tablet', async () => {
    for (const width of [390, 800]) {
      viewport(width);
      await renderScreen();

      expect(screen.getByTestId('shell-detail-panel')).toHaveStyle({ width: '100%' });
    }
  });

  it('renders no panel when the screen has no detail', async () => {
    viewport(1280);
    await renderScreen({ aside: undefined });

    expect(screen.queryByTestId('shell-detail-panel')).toBeNull();
  });

  it('offers the eye control in the screen header unless the screen opts out', async () => {
    viewport(390);
    await renderScreen();
    expect(screen.getByRole('switch', { name: 'Hide amounts' })).toBeTruthy();
    expect(screen.getByRole('heading')).toHaveTextContent('Overview');

    await renderScreen({ privacyToggle: false });
    expect(screen.queryByRole('switch', { name: 'Hide amounts' })).toBeNull();
  });
});
