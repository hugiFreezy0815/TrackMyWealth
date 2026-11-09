import { DESKTOP_NAVIGATION, NAVIGATION, isCurrentRoute } from './navigation';
it('shows only approved destinations without duplicate portfolios or future tabs', () => {
  expect(NAVIGATION.map((entry) => entry.href)).toEqual(['/overview', '/more']);
  expect(DESKTOP_NAVIGATION.map((entry) => entry.href)).toEqual(['/overview', '/settings']);
});
it('keeps portfolio detail in the Overview navigation scope', () => {
  expect(isCurrentRoute('/overview/a', '/overview')).toBe(true);
  expect(isCurrentRoute('/accounts/a', '/overview')).toBe(false);
});
