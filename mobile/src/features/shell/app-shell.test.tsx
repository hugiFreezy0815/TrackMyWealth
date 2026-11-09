import { onlineManager } from '@tanstack/react-query';
import { fireEvent, renderRouter, screen, within } from 'expo-router/testing-library';
import { Platform, useWindowDimensions } from 'react-native';
import * as ScreenCapture from 'expo-screen-capture';

import type { Identity } from '@/features/auth/session';
import { withIntl } from '@/i18n/test-utils';

import { AppShell } from './app-shell';
import { ShellScreen, UnavailableScreen } from './screen';

jest.mock('@/global.css', () => ({}));
jest.mock('@/features/overview-name/setup-gate', () => ({
  OverviewSetupGate: ({ children }: { children: React.ReactNode }) => children,
}));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));
jest.mock('expo-screen-capture', () => ({
  enableAppSwitcherProtectionAsync: jest.fn(async () => {}),
  disableAppSwitcherProtectionAsync: jest.fn(async () => {}),
  preventScreenCaptureAsync: jest.fn(async () => {}),
  allowScreenCaptureAsync: jest.fn(async () => {}),
}));
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: jest.fn(),
}));

const MEMBER: Identity = { userId: 'm', role: 'STANDARD_USER', language: 'EN', sessionId: 's1' };
const ADMIN: Identity = {
  userId: 'a',
  role: 'SYSTEM_ADMINISTRATOR',
  language: 'EN',
  sessionId: 's2',
};
const ORIGINAL_OS = Platform.OS;

function viewport(width: number, os: typeof Platform.OS = 'ios') {
  Platform.OS = os;
  jest.mocked(useWindowDimensions).mockReturnValue({ width, height: 800, scale: 2, fontScale: 1 });
}

function renderShell(identity: Identity | null = MEMBER, initialUrl = '/overview') {
  return renderRouter(
    {
      _layout: () => withIntl(<AppShell identity={identity} />),
      overview: () => <ShellScreen title="Overview screen">{null}</ShellScreen>,
      more: () => null,
      settings: () => null,
      'reference-data': () => null,
      'accounts/[accountId]/index': UnavailableScreen,
    },
    { initialUrl },
  );
}

afterEach(() => {
  Platform.OS = ORIGINAL_OS;
  onlineManager.setOnline(true);
  jest.clearAllMocks();
});

describe('AppShell navigation per device (US-01-13)', () => {
  it('shows a bottom tab bar with Overview and More on a phone', async () => {
    viewport(390);
    await renderShell();

    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((tab) => tab.props.accessibilityLabel)).toEqual(['Overview', 'More']);
    expect(screen.queryByTestId('shell-sidebar')).toBeNull();
    expect(screen.getByRole('tab', { name: 'Overview', selected: true })).toBeTruthy();
  });

  it('keeps the phone layout on a 600–1023 px tablet', async () => {
    viewport(800);
    await renderShell();

    expect(screen.getAllByRole('tab')).toHaveLength(2);
    expect(screen.queryByTestId('shell-sidebar')).toBeNull();
  });

  it('shows a 248 px sidebar with Overview and Settings in a 1280 px browser', async () => {
    viewport(1280, 'web');
    await renderShell();

    const sidebar = screen.getByTestId('shell-sidebar');
    expect(sidebar).toHaveStyle({ width: 248 });
    // A grouping landmark is not an accessibility element itself, so it is found by its name.
    const nav = within(sidebar).getByLabelText('Main navigation');
    expect(nav.props.role).toBe('navigation');
    const links = within(nav).getAllByRole('link');
    expect(links.map((link) => link.props.accessibilityLabel)).toEqual(['Overview', 'Settings']);
    expect(links[0].props['aria-current']).toBe('page');
    expect(links[1].props['aria-current']).toBeUndefined();
  });

  it('gives the administrator Reference data and no financial Overview', async () => {
    viewport(390);
    await renderShell(ADMIN, '/reference-data');
    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((tab) => tab.props.accessibilityLabel)).toEqual(['Reference data', 'More']);

    await renderShell(MEMBER);
    expect(screen.queryByRole('tab', { name: 'Reference data' })).toBeNull();
  });

  it('navigates to the chosen destination', async () => {
    viewport(390);
    const view = await renderShell();

    await fireEvent.press(screen.getByRole('tab', { name: 'More' }));

    expect(view.getPathname()).toBe('/more');
  });

  it('opens a deep link to a screen that is not built yet safely, without data', async () => {
    viewport(390);
    const view = await renderShell(MEMBER, '/accounts/acc-1');

    expect(view.getPathname()).toBe('/accounts/acc-1');
    expect(screen.getByText('This screen is not available yet')).toBeTruthy();
  });
});

describe('AppShell protections (FR-CLI-005, M24)', () => {
  it('shows the offline banner above every screen', async () => {
    viewport(390);
    onlineManager.setOnline(false);
    await renderShell(MEMBER, '/accounts/acc-1');

    expect(screen.getByRole('alert')).toHaveTextContent('You are offline. Showing saved data.');
  });

  it('protects the app switcher natively while signed in', async () => {
    viewport(390, 'ios');
    const view = await renderShell();
    expect(ScreenCapture.enableAppSwitcherProtectionAsync).toHaveBeenCalled();

    await view.unmount();
    expect(ScreenCapture.disableAppSwitcherProtectionAsync).toHaveBeenCalled();
  });
});
