// Where a signed-in person lands (PO 2026-11-09): a member on Overview, the administrator, who is
// never a workspace member, on Reference data, also when opening /overview directly.
import { Slot } from 'expo-router';
import { renderRouter } from 'expo-router/testing-library';

import IndexRoute from '@/app/index';
import OverviewRoute from '@/app/overview';
import type { Identity } from '@/features/auth/session';
import { withIntl } from '@/i18n/test-utils';

const mockIdentity: { current: Identity | null } = { current: null };

jest.mock('@/global.css', () => ({}));
// This suite verifies redirects, not data fetching; the Overview screen has its own query tests.
jest.mock('@/features/overview/overview-screen', () => ({ OverviewScreen: () => null }));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => {}),
}));
jest.mock('@/features/auth/session', () => ({
  ...jest.requireActual('@/features/auth/session'),
  sessionSnapshot: () => ({ signedIn: true, identity: mockIdentity.current, mfaRequired: false }),
  subscribeSession: () => () => {},
}));

const MEMBER: Identity = { userId: 'm', role: 'STANDARD_USER', language: 'EN', sessionId: 's1' };
const ADMIN: Identity = {
  userId: 'a',
  role: 'SYSTEM_ADMINISTRATOR',
  language: 'EN',
  sessionId: 's2',
};

const routes = {
  _layout: () => withIntl(<Slot />),
  index: IndexRoute,
  overview: OverviewRoute,
  'reference-data': () => null,
};

describe('home route', () => {
  it.each([
    ['member', MEMBER, '/', '/overview'],
    ['administrator', ADMIN, '/', '/reference-data'],
    ['administrator', ADMIN, '/overview', '/reference-data'],
    ['member', MEMBER, '/overview', '/overview'],
  ] as const)('sends the %s from %s to %s', async (_, identity, from, to) => {
    mockIdentity.current = identity;

    const view = await renderRouter(routes, { initialUrl: from });

    expect(view.getPathname()).toBe(to);
  });
});
