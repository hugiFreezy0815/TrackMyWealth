import { type ReactNode } from 'react';
import { renderRouter, screen } from 'expo-router/testing-library';

import { type Identity } from '@/features/auth/session';
import { loadReferenceData } from '@/features/reference-data/reference-data';
import { ReferenceDataScreen } from '@/features/reference-data/reference-data-screen';

// Two platform files of one component: the lint resolver sees both as app-tabs.web.
// eslint-disable-next-line import/no-duplicates
import AppTabs from './app-tabs';
// eslint-disable-next-line import/no-duplicates
import WebAppTabs from './app-tabs.web';

jest.mock('@/global.css', () => ({}));
jest.mock('expo-secure-store', () => ({}));
jest.mock('@/features/reference-data/reference-data', () => ({ loadReferenceData: jest.fn() }));
// Native tabs need the native tab bar; the stand-in renders each trigger's label so the test sees
// which tabs AppTabs offers.
jest.mock('expo-router/unstable-native-tabs', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  const NativeTabs = function NativeTabs({ children }: { children: ReactNode }) {
    return children;
  };
  const Trigger = function Trigger({ children }: { children: ReactNode }) {
    return children;
  };
  Trigger.Label = function Label({ children }: { children: ReactNode }) {
    return <Text>{children}</Text>;
  };
  Trigger.Icon = function Icon() {
    return null;
  };
  NativeTabs.Trigger = Trigger;
  return { NativeTabs };
});

const ADMIN: Identity = {
  userId: 'admin',
  role: 'SYSTEM_ADMINISTRATOR',
  language: 'EN',
  sessionId: 's1',
};
const MEMBER: Identity = {
  userId: 'member',
  role: 'STANDARD_USER',
  language: 'EN',
  sessionId: 's2',
};

const routes = (tabs: () => ReactNode) => ({
  _layout: tabs,
  index: () => null,
  explore: () => null,
  'reference-data': () => null,
});

describe.each([
  ['native', AppTabs],
  ['web', WebAppTabs],
])('AppTabs (%s)', (_, Tabs) => {
  it('offers Reference data to the administrator', async () => {
    await renderRouter(routes(() => <Tabs identity={ADMIN} />));

    expect(screen.getByText('Reference data')).toBeTruthy();
  });

  it('does not offer Reference data to a member or an unverified identity', async () => {
    for (const identity of [MEMBER, null]) {
      await renderRouter(routes(() => <Tabs identity={identity} />));

      expect(screen.getByText('Home')).toBeTruthy();
      expect(screen.queryByText('Reference data')).toBeNull();
    }
  });
});

describe('AppTabs (web) deep link to /reference-data', () => {
  const deepLink = (identity: Identity) =>
    renderRouter(
      {
        ...routes(() => <WebAppTabs identity={identity} />),
        'reference-data': () => <ReferenceDataScreen identity={identity} />,
      },
      { initialUrl: '/reference-data' },
    );

  beforeEach(() => jest.mocked(loadReferenceData).mockReset());

  it('lands a member on Home without requesting reference data', async () => {
    const view = await deepLink(MEMBER);

    // Without its trigger the tab is not part of the navigator, so the link falls back to Home.
    expect(view.getPathname()).toBe('/');
    expect(loadReferenceData).not.toHaveBeenCalled();
  });

  it('opens the screen for the administrator', async () => {
    jest.mocked(loadReferenceData).mockReturnValueOnce(new Promise(() => {}));
    const view = await deepLink(ADMIN);

    expect(view.getPathname()).toBe('/reference-data');
    expect(screen.getByText('Loading reference data…')).toBeTruthy();
  });
});
