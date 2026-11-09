import '@/global.css';

import { Platform } from 'react-native';

/**
 * UX-03 (#378) semantic tokens. These match docs/ux/02-visual-foundations.css,
 * including the AA dark-mode overrides. Never choose a colour by literal hex
 * inside a product component.
 */
export const DesignTokens = {
  light: {
    bgCanvas: '#F4F7F8',
    bgSurface: '#FFFFFF',
    bgSubtle: '#ECF1F4',
    bgInverse: '#0B1C3F',
    borderDefault: '#E1E7EC',
    borderStrong: '#C6D0D9',
    borderInteractive: '#64758A',
    textPrimary: '#0B1C3F',
    textSecondary: '#53627A',
    textTertiary: '#5E6D83',
    textOnBrand: '#FFFFFF',
    brandPrimary: '#00857A',
    brandPrimaryHover: '#006D64',
    brandLink: '#006D64',
    brandSubtle: '#E0F5F1',
    statusGain: '#0F7A3A',
    statusGainSubtle: '#E3F7E8',
    statusLoss: '#C42F37',
    statusLossSubtle: '#FDECEC',
    statusWarning: '#8F5200',
    statusWarningSubtle: '#FFF3D9',
    statusInfo: '#1F5CC4',
    statusInfoSubtle: '#E6EFFF',
    chart1: '#00A08F',
    chart2: '#0A3D7A',
    chart3: '#B7802A',
    chart4: '#E5677E',
    chart5: '#6C8EF5',
    chart6: '#8B5CF6',
    chartOther: '#868D9C',
    brandNavy: '#0B1C3F',
    brandDeep: '#0A3D7A',
    brandMint: '#14D3B0',
  },
  dark: {
    bgCanvas: '#07111F',
    bgSurface: '#0E1B2E',
    bgSubtle: '#152740',
    bgInverse: '#EEF3F7',
    borderDefault: '#22344D',
    borderStrong: '#2F4663',
    borderInteractive: '#9FB0C4',
    textPrimary: '#EEF3F7',
    textSecondary: '#9FB0C4',
    textTertiary: '#92A4B9',
    textOnBrand: '#07111F',
    brandPrimary: '#2DD4BF',
    brandPrimaryHover: '#5EEAD4',
    brandLink: '#2DD4BF',
    brandSubtle: '#0E3A38',
    statusGain: '#4ADE80',
    statusGainSubtle: '#12301C',
    statusLoss: '#FF6B70',
    statusLossSubtle: '#3A1A1C',
    statusWarning: '#F5B544',
    statusWarningSubtle: '#3A2C10',
    statusInfo: '#6AA5FF',
    statusInfoSubtle: '#18294A',
    chart1: '#2DD4BF',
    chart2: '#5B8DEF',
    chart3: '#F5BD62',
    chart4: '#F08A9C',
    chart5: '#93A8F8',
    chart6: '#A78BFA',
    chartOther: '#646C80',
    brandNavy: '#0B1C3F',
    brandDeep: '#1E4F99',
    brandMint: '#5EEAD4',
  },
} as const;

// Keep the Expo scaffold's existing keys for source compatibility.
export const Colors = {
  light: {
    ...DesignTokens.light,
    text: DesignTokens.light.textPrimary,
    background: DesignTokens.light.bgCanvas,
    backgroundElement: DesignTokens.light.bgSubtle,
    backgroundSelected: DesignTokens.light.brandSubtle,
    textSecondary: DesignTokens.light.textSecondary,
  },
  dark: {
    ...DesignTokens.dark,
    text: DesignTokens.dark.textPrimary,
    background: DesignTokens.dark.bgCanvas,
    backgroundElement: DesignTokens.dark.bgSubtle,
    backgroundSelected: DesignTokens.dark.brandSubtle,
    textSecondary: DesignTokens.dark.textSecondary,
  },
} as const;

export type ThemeColor = keyof typeof Colors.light & keyof typeof Colors.dark;

export const TypeScale = {
  displayLarge: { fontSize: 40, lineHeight: 48, fontWeight: '700' },
  displayMedium: { fontSize: 32, lineHeight: 40, fontWeight: '700' },
  headingLarge: { fontSize: 24, lineHeight: 32, fontWeight: '600' },
  headingMedium: { fontSize: 20, lineHeight: 28, fontWeight: '600' },
  headingSmall: { fontSize: 16, lineHeight: 24, fontWeight: '600' },
  bodyLarge: { fontSize: 16, lineHeight: 24, fontWeight: '400' },
  bodyMedium: { fontSize: 14, lineHeight: 20, fontWeight: '400' },
  labelMedium: { fontSize: 14, lineHeight: 20, fontWeight: '500' },
  labelSmall: { fontSize: 12, lineHeight: 16, fontWeight: '500' },
  caption: { fontSize: 12, lineHeight: 16, fontWeight: '400' },
  overline: { fontSize: 11, lineHeight: 16, fontWeight: '600', letterSpacing: 0.66 },
  numberLarge: { fontSize: 16, lineHeight: 24, fontWeight: '600' },
  numberMedium: { fontSize: 14, lineHeight: 20, fontWeight: '500' },
} as const;

export const Radii = { control: 10, card: 16, hero: 24, pill: 999 } as const;
export const Layout = { phoneMax: 599, tabletMax: 1023, sidebarWidth: 248, railWidth: 360 } as const;

export const Fonts = Platform.select({
  ios: { sans: 'system-ui', serif: 'ui-serif', rounded: 'ui-rounded', mono: 'ui-monospace' },
  default: { sans: 'normal', serif: 'serif', rounded: 'normal', mono: 'monospace' },
  web: {
    sans: 'var(--font-display)',
    serif: 'var(--font-serif)',
    rounded: 'var(--font-rounded)',
    mono: 'var(--font-mono)',
  },
});

export const Spacing = {
  half: 2,
  one: 4,
  two: 8,
  compact: 12,
  three: 16,
  four: 24,
  five: 32,
  wide: 40,
  larger: 48,
  six: 64,
} as const;

export const BottomTabInset = Platform.select({ ios: 50, android: 80 }) ?? 0;
export const MaxContentWidth = 800;
