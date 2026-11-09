/**
 * Sprint-7 navigation (docs/ux/01-information-architecture.md §1, PO 2026-11-05): phone and
 * tablet show Overview + More, desktop Overview + Settings. Investments and Cash flow stay absent
 * (never disabled), and portfolios live in the Overview tab strip, not in a second section.
 */
export type Layout = 'phone' | 'tablet' | 'desktop';

export type NavigationItem = {
  href: '/overview' | '/more' | '/settings' | '/reference-data';
  labelKey: 'nav.overview' | 'nav.more' | 'nav.settings' | 'nav.referenceData';
  icon: string;
};

/** CSS viewport breakpoints from the IA §3, never the platform: phone <600, tablet <1024. */
export function layoutFor(width: number): Layout {
  if (width >= 1024) return 'desktop';
  if (width >= 600) return 'tablet';
  return 'phone';
}

const OVERVIEW: NavigationItem = { href: '/overview', labelKey: 'nav.overview', icon: '◉' };
const MORE: NavigationItem = { href: '/more', labelKey: 'nav.more', icon: '☰' };
const SETTINGS: NavigationItem = { href: '/settings', labelKey: 'nav.settings', icon: '⚙' };
const REFERENCE_DATA: NavigationItem = {
  href: '/reference-data',
  labelKey: 'nav.referenceData',
  icon: '▤',
};

/**
 * The entries for a layout. Reference data (US-01-05) is offered to the administrator only;
 * that is visibility, not authorization: the screen and the server check the role again.
 */
export function navigationFor(layout: Layout, administrator: boolean): NavigationItem[] {
  return [
    OVERVIEW,
    ...(administrator ? [REFERENCE_DATA] : []),
    layout === 'desktop' ? SETTINGS : MORE,
  ];
}

export function isCurrentRoute(pathname: string, href: NavigationItem['href']): boolean {
  if (pathname === href) return true;
  // Portfolio tabs share the Overview destination (S2 lives inside S1's tab strip).
  if (href === '/overview') return pathname.startsWith('/overview/');
  // Settings is reached from More on phone and tablet.
  if (href === '/more') return pathname === '/settings';
  return false;
}
