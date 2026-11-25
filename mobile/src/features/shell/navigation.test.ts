import { homeFor, isCurrentRoute, layoutFor, navigationFor } from './navigation';

const hrefs = (layout: Parameters<typeof navigationFor>[0], administrator = false) =>
  navigationFor(layout, administrator).map((entry) => entry.href);

describe('member and administrator navigation', () => {
  it('uses viewport breakpoints, not the platform', () => {
    expect(layoutFor(390)).toBe('phone');
    expect(layoutFor(599)).toBe('phone');
    expect(layoutFor(600)).toBe('tablet');
    expect(layoutFor(1023)).toBe('tablet');
    expect(layoutFor(1024)).toBe('desktop');
    expect(layoutFor(1280)).toBe('desktop');
  });

  it('offers Investments on phone, tablet and desktop', () => {
    expect(hrefs('phone')).toEqual(['/overview', '/investments', '/more']);
    expect(hrefs('tablet')).toEqual(['/overview', '/investments', '/more']);
    expect(hrefs('desktop')).toEqual(['/overview', '/investments', '/settings']);
  });

  it('never offers future areas or a duplicate Portfolios section', () => {
    const all = (['phone', 'tablet', 'desktop'] as const).flatMap((layout) => [
      ...hrefs(layout),
      ...hrefs(layout, true),
    ]);
    expect(all.some((href) => /portfolio|cash/i.test(href))).toBe(false);
  });

  it('gives the administrator Reference data instead of the financial Overview', () => {
    expect(hrefs('phone', true)).toEqual(['/reference-data', '/more']);
    expect(hrefs('desktop', true)).toEqual(['/reference-data', '/settings']);
    expect(hrefs('desktop', false)).not.toContain('/reference-data');
  });

  it('lands a member on Overview and the administrator on Reference data', () => {
    expect(homeFor(false)).toBe('/overview');
    expect(homeFor(true)).toBe('/reference-data');
  });

  it('keeps portfolio detail in the Overview scope and Settings under More', () => {
    expect(isCurrentRoute('/overview/a', '/overview')).toBe(true);
    expect(isCurrentRoute('/accounts/a', '/overview')).toBe(false);
    expect(isCurrentRoute('/settings', '/more')).toBe(true);
    expect(isCurrentRoute('/settings', '/settings')).toBe(true);
    expect(isCurrentRoute('/reference-data', '/overview')).toBe(false);
  });
});
