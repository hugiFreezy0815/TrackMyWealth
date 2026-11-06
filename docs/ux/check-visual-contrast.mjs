#!/usr/bin/env node
/**
 * UX-03 #378: check every colour pairing that the reference page and the spec use against WCAG 2.2
 * relative contrast, in both themes. CI runs this on every change under docs/ux/ (UX docs CI).
 *
 * Run: node docs/ux/check-visual-contrast.mjs
 * No dependencies; this is not a full browser or visual accessibility audit.
 * A new foreground/background combination in 02-visual-foundations.css or .html must be added to
 * PAIRS; a new colour token must be defined in both themes or the check fails.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(join(here, '02-visual-foundations.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Colour tokens of one theme: every rule whose selector list names html[data-theme="<theme>"],
 * wherever it sits in the file (also inside @media). Formatting of the selector does not matter.
 */
function themeTokens(theme) {
  const selector = `html[data-theme="${theme}"]`;
  const found = new Map();
  for (const [, selectors, body] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const names = selectors.split(',').map((s) => s.replace(/\s+/g, ''));
    if (!names.includes(selector)) continue;
    for (const [, key, value] of body.matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) {
      const colour = value.trim().toUpperCase();
      if (!/^#[0-9A-F]{6}$/.test(colour)) {
        throw new Error(`${theme}: --${key} must be a 6-digit hex colour, found "${value.trim()}"`);
      }
      if (found.has(key) && found.get(key) !== colour) {
        throw new Error(`${theme}: --${key} is defined twice with different values`);
      }
      found.set(key, colour);
    }
  }
  if (found.size === 0) throw new Error(`No tokens found for ${selector}`);
  return found;
}

function luminance(color) {
  const channels = [1, 3, 5]
    .map((i) => parseInt(color.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function ratio(a, b) {
  const x = luminance(a);
  const y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

const TEXT = 4.5; // WCAG 1.4.3, ordinary text (all badge/caption text is below 18.66px bold)
const UI = 3; // WCAG 1.4.11, control boundaries, focus indicators and chart marks

// [foreground, background, minimum, where it is used]
// Figma's decorative borders are intentionally NOT treated as a sole control cue.
const PAIRS = [
  ['text-primary', 'bg-canvas', TEXT, 'page text'],
  ['text-secondary', 'bg-canvas', TEXT, 'page intro, footer'],
  ['text-tertiary', 'bg-canvas', TEXT, 'metadata'],
  ['text-primary', 'bg-surface', TEXT, 'card text, amounts'],
  ['text-secondary', 'bg-surface', TEXT, 'captions, notes, legend'],
  ['text-tertiary', 'bg-surface', TEXT, 'metadata'],
  ['text-primary', 'bg-subtle', TEXT, 'unknown badge'],
  ['text-secondary', 'bg-subtle', TEXT, 'table headings, stale badge'],
  ['text-tertiary', 'bg-subtle', TEXT, 'metadata on quiet panels'],
  ['text-primary', 'brand-subtle', TEXT, 'hero amount'],
  ['text-secondary', 'brand-subtle', TEXT, 'hero title and note'],
  ['status-gain', 'brand-subtle', TEXT, 'hero ChangeChip'],
  ['status-loss', 'brand-subtle', TEXT, 'negative ChangeChip in hero'],
  ['text-on-brand', 'brand-primary', TEXT, 'primary button'],
  ['text-on-brand', 'brand-primary-hover', TEXT, 'primary button, hovered'],
  ['bg-surface', 'bg-inverse', TEXT, 'pressed toggle button'],
  ['brand-primary', 'bg-surface', TEXT, 'teal text on cards'],
  ['brand-link', 'bg-canvas', TEXT, 'link on page'],
  ['brand-link', 'bg-surface', TEXT, 'link on card'],
  ['brand-primary-hover', 'bg-canvas', TEXT, 'hovered link on page'],
  ['brand-primary-hover', 'bg-surface', TEXT, 'hovered link on card'],
  ['status-gain', 'bg-surface', TEXT, 'positive amount cell'],
  ['status-loss', 'bg-surface', TEXT, 'negative amount cell'],
  ['status-warning', 'bg-surface', TEXT, 'warning text'],
  ['status-info', 'bg-surface', TEXT, 'info text'],
  ['status-gain', 'status-gain-subtle', TEXT, 'gain badge'],
  ['status-loss', 'status-loss-subtle', TEXT, 'loss badge'],
  ['status-warning', 'status-warning-subtle', TEXT, 'unreconciled/incomplete badge'],
  ['status-info', 'status-info-subtle', TEXT, 'estimated badge'],
  ['text-primary', 'status-warning-subtle', TEXT, 'warning / offline banner'],
  ['text-primary', 'status-loss-subtle', TEXT, 'error banner'],
  ['border-interactive', 'bg-surface', UI, 'control border on card'],
  ['border-interactive', 'bg-canvas', UI, 'control border on page'],
  ['status-info', 'bg-canvas', UI, 'focus ring on page'],
  ['status-info', 'bg-surface', UI, 'focus ring on card'],
  ['status-warning', 'status-warning-subtle', UI, 'banner accent bar'],
  ['status-loss', 'status-loss-subtle', UI, 'error banner accent bar'],
  // Charts sit on bg-surface; adjacent series are separated by a 2px bg-surface gap, so every
  // series needs 3:1 against the surface rather than against its neighbour (spec section 6).
  ...['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5', 'chart-6', 'chart-other'].map(
    (chart) => [chart, 'bg-surface', UI, 'chart series mark'],
  ),
];

const themes = [['light', themeTokens('light')], ['dark', themeTokens('dark')]];
let failures = 0;

const [[, light], [, dark]] = themes;
for (const key of new Set([...light.keys(), ...dark.keys()])) {
  if (!light.has(key) || !dark.has(key)) {
    console.error(`FAIL --${key} is defined only in the ${light.has(key) ? 'light' : 'dark'} theme`);
    failures++;
  }
}

for (const [theme, tokenMap] of themes) {
  for (const [foreground, background, min, use] of PAIRS) {
    const fg = tokenMap.get(foreground);
    const bg = tokenMap.get(background);
    if (!fg || !bg) {
      console.error(`FAIL ${theme}: missing --${!fg ? foreground : background}`);
      failures++;
      continue;
    }
    const actual = ratio(fg, bg);
    const pass = actual >= min;
    console.log(
      `${pass ? 'PASS' : 'FAIL'} ${theme.padEnd(5)} --${foreground} / --${background}: ` +
        `${actual.toFixed(2)}:1 (min ${min}:1, ${use})`,
    );
    if (!pass) failures++;
  }
}
if (failures > 0) {
  console.error(`Contrast check: ${failures} problem(s); fix tokens before merge.`);
  process.exitCode = 1;
} else {
  console.log(`Contrast check passed: ${PAIRS.length * 2} light/dark pairings.`);
}
