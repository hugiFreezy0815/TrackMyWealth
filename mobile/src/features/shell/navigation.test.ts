import { isCurrentRoute, layoutFor, navigationFor } from './navigation';

const hrefs = (layout: Parameters<typeof navigationFor>[0], administrator = false) =>
  navigationFor(layout, administrator).map((entry) => entry.href);

describe('approved Sprint-7 navigation', () => {
  it('uses viewport breakpoints, not the platform', () => {
    expect(layoutFor(390)).toBe('phone');
    expect(layoutFor(599)).toBe('phone');
    expect(layoutFor(600)).toBe('tablet');
    expect(layoutFor(1023)).toBe('tablet');
    expect(layoutFor(1024)).toBe('desktop');
    expect(layoutFor(1280)).toBe('desktop');
  });

  it('shows Overview and More on phone and tablet, Overview and Settings on desktop', () => {
    expect(hrefs('phone')).toEqual(['/overview', '/more']);
    expect(hrefs('tablet')).toEqual(['/overview', '/more']);
    expect(hrefs('desktop')).toEqual(['/overview', '/settings']);
  });

  it('never offers future areas or a duplicate Portfolios section', () => {
    const all = (['phone', 'tablet', 'desktop'] as const).flatMap((layout) => [
      ...hrefs(layout),
      ...hrefs(layout, true),
    ]);
    expect(all.some((href) => /portfolio|investment|cash/i.test(href))).toBe(false);
  });

  it('offers Reference data to the administrator only', () => {
    expect(hrefs('phone', true)).toEqual(['/overview', '/reference-data', '/more']);
    expect(hrefs('desktop', true)).toEqual(['/overview', '/reference-data', '/settings']);
    expect(hrefs('desktop', false)).not.toContain('/reference-data');
  });

  it('keeps portfolio detail in the Overview scope and Settings under More', () => {
    expect(isCurrentRoute('/overview/a', '/overview')).toBe(true);
    expect(isCurrentRoute('/accounts/a', '/overview')).toBe(false);
    expect(isCurrentRoute('/settings', '/more')).toBe(true);
    expect(isCurrentRoute('/settings', '/settings')).toBe(true);
    expect(isCurrentRoute('/reference-data', '/overview')).toBe(false);
  });
});
