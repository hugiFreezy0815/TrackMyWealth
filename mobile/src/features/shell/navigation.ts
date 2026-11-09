/** Sprint-7 approved destinations. Future investment/cash-flow tabs stay absent. */
export const NAVIGATION = [
  { href: '/overview', labelKey: 'nav.overview', icon: '◉' },
  { href: '/more', labelKey: 'nav.more', icon: '☰' },
] as const;
export const DESKTOP_NAVIGATION = [
  { href: '/overview', labelKey: 'nav.overview', icon: '◉' },
  { href: '/settings', labelKey: 'nav.settings', icon: '⚙' },
] as const;
export function isCurrentRoute(pathname: string, href: string): boolean {
  return pathname === href || (href === '/overview' && pathname.startsWith('/overview/')) ||
    (href === '/more' && pathname === '/settings');
}
