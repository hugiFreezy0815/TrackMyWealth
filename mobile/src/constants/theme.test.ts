import { Colors, DesignTokens, Radii, Spacing, TypeScale } from './theme';

jest.mock('@/global.css', () => ({}));

function luminance(color: string) {
  const channels = [1, 3, 5]
    .map((i) => parseInt(color.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}
function contrast(a: string, b: string) {
  const l1 = luminance(a),
    l2 = luminance(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}
describe('canonical UX-03 light and dark tokens', () => {
  it('defines the same semantic roles in both themes', () => {
    expect(Object.keys(DesignTokens.light)).toEqual(Object.keys(DesignTokens.dark));
  });
  it.each(['light', 'dark'] as const)(
    '%s provides AA text, buttons, control borders and chart marks',
    (mode) => {
      const theme = DesignTokens[mode];
      for (const surface of ['bgCanvas', 'bgSurface', 'bgSubtle'] as const) {
        for (const foreground of ['textPrimary', 'textSecondary', 'textTertiary'] as const) {
          expect(contrast(theme[foreground], theme[surface])).toBeGreaterThanOrEqual(4.5);
        }
      }
      expect(contrast(theme.textOnBrand, theme.brandPrimary)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(theme.borderInteractive, theme.bgSurface)).toBeGreaterThanOrEqual(3);
      for (const name of [
        'chart1',
        'chart2',
        'chart3',
        'chart4',
        'chart5',
        'chart6',
        'chartOther',
      ] as const) {
        expect(contrast(theme[name], theme.bgSurface)).toBeGreaterThanOrEqual(3);
      }
    },
  );
  // Every foreground/background pair the shared components and the gallery use (#488 AC3).
  it.each(['light', 'dark'] as const)('%s passes AA for every pair the components use', (mode) => {
    const theme = DesignTokens[mode];
    const textPairs: [keyof typeof theme, keyof typeof theme][] = [
      ['statusInfo', 'statusInfoSubtle'],
      ['statusWarning', 'statusWarningSubtle'],
      ['textPrimary', 'bgSubtle'],
      ['statusLoss', 'statusLossSubtle'],
      ['statusGain', 'statusGainSubtle'],
      ['textSecondary', 'bgSubtle'],
      ['textPrimary', 'statusLossSubtle'],
      ['textSecondary', 'statusLossSubtle'],
      ['textPrimary', 'statusWarningSubtle'],
      ['textSecondary', 'statusWarningSubtle'],
      ['textPrimary', 'brandSubtle'],
      ['bgSurface', 'bgInverse'],
      ['brandLink', 'bgSurface'],
      ['statusLoss', 'bgSurface'],
      ['textOnBrand', 'brandPrimary'],
    ];
    for (const [foreground, background] of textPairs) {
      expect([foreground, background, contrast(theme[foreground], theme[background])]).toEqual([
        foreground,
        background,
        expect.any(Number),
      ]);
      expect(contrast(theme[foreground], theme[background])).toBeGreaterThanOrEqual(4.5);
    }
  });
  it('retains the old Expo theme keys while moving to design-system roles', () => {
    expect(Colors.light.text).toBe(DesignTokens.light.textPrimary);
    expect(Colors.dark.background).toBe(DesignTokens.dark.bgCanvas);
    expect(Radii.card).toBe(16);
    expect(Spacing.compact).toBe(12);
    expect(TypeScale.bodyMedium.lineHeight).toBe(20);
  });
});
