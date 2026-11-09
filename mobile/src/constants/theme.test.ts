import { Colors } from './theme';

jest.mock('@/global.css', () => ({}));

/** WCAG 2.x relative luminance of a #RRGGBB colour. */
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((start) => {
    const channel = parseInt(hex.slice(start, start + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(foreground: string, background: string): number {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

// WCAG 2.2 AA 1.4.3: normal-size text needs 4.5:1 against what it is drawn on.
const TEXT_PAIRS = [
  ['text', 'background'],
  ['text', 'backgroundElement'],
  ['textSecondary', 'background'],
  ['textSecondary', 'backgroundElement'],
  ['primary', 'background'],
  ['primary', 'backgroundElement'],
  ['onPrimary', 'primary'],
  ['onWarning', 'warningBackground'],
] as const;

describe.each(['light', 'dark'] as const)('%s palette', (scheme) => {
  it.each(TEXT_PAIRS)('%s on %s meets WCAG AA for text', (foreground, background) => {
    const palette = Colors[scheme];
    expect(contrast(palette[foreground], palette[background])).toBeGreaterThanOrEqual(4.5);
  });
});
