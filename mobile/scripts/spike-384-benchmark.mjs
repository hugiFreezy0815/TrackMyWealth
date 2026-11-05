/**
 * Headless, synthetic-only browser evidence for US-01-08 / issue #384.
 * Run only on spike/384-evidence-only. This is NOT a shipping web feature.
 *
 * Prerequisites: npm ci && npm install --no-save --package-lock=false playwright@1.56.1
 * Start Expo web on port 8081; then node scripts/spike-384-benchmark.mjs.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import process from 'node:process';
import { chromium } from 'playwright';

const root = new URL('../spike-reports/', import.meta.url);
await fs.mkdir(root, { recursive: true });
const origin = process.env.SPIKE_BASE_URL || 'http://localhost:8081';
const repeats = Number(process.env.SPIKE_REPEATS || 5);
assert(Number.isInteger(repeats) && repeats >= 1 && repeats <= 20);
const browser = await chromium.launch({ headless: true, args: ['--disable-background-timer-throttling'] });
const browserVersion = browser.version();
const records = [];
const errors = [];
const round = (n) => Number(n.toFixed(2));
const percentile = (values, fraction) => {
  const a = [...values].sort((x, y) => x - y);
  return a[Math.min(a.length - 1, Math.ceil(a.length * fraction) - 1)];
};

benchmark: for (const renderer of ['rnw', 'dom']) {
  for (const size of [1000, 10000]) {
    for (let run = 1; run <= repeats; run++) {
      console.log('START', renderer, size, run);
      const context = await browser.newContext({
        viewport: { width: 1440, height: 900 },
        acceptDownloads: true,
        reducedMotion: 'reduce',
      });
      await context.addInitScript(() => {
        window.__spike = { longTasks: [], lcp: 0 };
        try {
          new PerformanceObserver((list) => {
            for (const e of list.getEntries()) window.__spike.longTasks.push(e.duration);
          }).observe({ type: 'longtask', buffered: true });
          new PerformanceObserver((list) => {
            for (const e of list.getEntries()) window.__spike.lcp = e.startTime;
          }).observe({ type: 'largest-contentful-paint', buffered: true });
        } catch {
          // Some browser versions do not expose one of these entries.
        }
      });
      const page = await context.newPage();
      page.on('pageerror', (error) => errors.push({ renderer, size, run, message: error.message }));
      const startedAt = Date.now();
      const href = origin + '/spike-web-transactions?renderer=' + renderer + '&rows=' + size;
      try {
        await page.goto(href, { waitUntil: 'domcontentloaded', timeout: 45000 });
        await page.locator('[data-benchmark-list="' + renderer + '"]').waitFor({ timeout: 45000 });
        await page.waitForFunction(
          (expected) => Number(document.querySelector('[data-benchmark-count]')?.getAttribute('data-benchmark-count')) === expected,
          size,
          { timeout: 45000 },
        );
        const firstVisibleMs = Date.now() - startedAt;
        const initialMounted = renderer === 'dom'
          ? await page.locator('[data-benchmark-list] tbody tr').count()
          : await page.locator('[data-benchmark-list] [role="button"]').count();
        assert(initialMounted > 0 && initialMounted <= 45,
          'Expected windowed rows; found ' + initialMounted + ' in ' + renderer);

        const category = page.getByLabel('Category', { exact: true });
        const beforeFilter = await page.evaluate(() => performance.now());
        await category.selectOption('Transport');
        await page.waitForFunction(
          (expected) => Number(document.querySelector('[data-benchmark-count]')?.getAttribute('data-benchmark-count')) === expected,
          size / 5,
        );
        await page.evaluate(() => new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const filterMs = round((await page.evaluate(() => performance.now())) - beforeFilter);
        await page.getByLabel('Sort', { exact: true }).selectOption('amount');
        const listRoot = page.locator('div[aria-label^="Transaction list."]');
        await listRoot.focus();
        await page.keyboard.press('ArrowDown');
        await page.waitForFunction(() => document.querySelector('#spike-details dl') != null);
        await page.keyboard.press('Enter');
        const focusedDetail = await page.evaluate(() => document.activeElement?.id === 'spike-details');
        assert(focusedDetail, 'Keyboard Enter failed to focus details');

        if (run === 1) {
          const downloaded = page.waitForEvent('download');
          await page.getByRole('button', { name: 'Download filtered CSV' }).click();
          const download = await downloaded;
          const filename = await download.path();
          assert(filename, 'CSV download was not saved');
          const csv = await fs.readFile(filename, 'utf8');
          assert.equal(csv.trimEnd().split('\r\n').length, size / 5 + 1,
            'CSV must contain all filtered rows, not only the virtual window');
          assert(!csv.includes('Sample shop'), 'CSV included excluded category');
        }

        const scroller = renderer === 'dom'
          ? page.locator('[aria-label="React DOM virtualised transaction table"]')
          : page.locator('[aria-label="React Native Web virtualised transaction list"]');
        await scroller.scrollIntoViewIfNeeded();
        for (let i = 0; i < 12; i++) {
          await scroller.hover();
          await page.mouse.wheel(0, 550);
          await page.waitForTimeout(30);
        }
        const metrics = await page.evaluate(() => ({
          longTasks: window.__spike?.longTasks ?? [],
          lcp: window.__spike?.lcp ?? 0,
          heapBytes: performance.memory?.usedJSHeapSize ?? null,
          mounted: document.querySelectorAll(
            '[data-benchmark-list] tbody tr, [data-benchmark-list] [role="button"]',
          ).length,
        }));
        const cdp = await context.newCDPSession(page);
        const ax = await cdp.send('Accessibility.getFullAXTree');
        const axRoles = ax.nodes.reduce((acc, node) => {
          const key = node.role?.value;
          if (key) acc[key] = (acc[key] ?? 0) + 1;
          return acc;
        }, {});
        records.push({
          renderer,
          rows: size,
          run,
          firstVisibleMs,
          filterMs,
          lcpMs: round(metrics.lcp),
          longTasks: metrics.longTasks.length,
          longTaskTotalMs: round(metrics.longTasks.reduce((s, n) => s + n, 0)),
          heapBytes: metrics.heapBytes,
          mountedAfterScroll: metrics.mounted,
          axColumnHeaders: axRoles.columnheader ?? 0,
          axRows: axRoles.row ?? 0,
          keyboardDetailsFocused: focusedDetail,
        });
        if (run === 1) {
          await page.screenshot({
            path: new URL('spike-' + renderer + '-' + size + '.png', root).pathname,
            fullPage: false,
          });
        }
        console.log(renderer, size, run, JSON.stringify(records.at(-1)));
      } catch (error) {
        errors.push({ renderer, size, run, error: String(error) });
        console.error('FAIL', renderer, size, run, String(error));
      } finally {
        await context.close();
      }
      if (errors.length) break benchmark;
    }
  }
}
await browser.close();
const summary = [];
for (const renderer of ['rnw', 'dom']) {
  for (const size of [1000, 10000]) {
    const values = records.filter((x) => x.renderer === renderer && x.rows === size);
    if (!values.length) continue;
    summary.push({
      renderer,
      rows: size,
      observedRuns: values.length,
      firstVisibleMedianMs: percentile(values.map((x) => x.firstVisibleMs), 0.5),
      firstVisibleP95Ms: percentile(values.map((x) => x.firstVisibleMs), 0.95),
      filterMedianMs: percentile(values.map((x) => x.filterMs), 0.5),
      filterP95Ms: percentile(values.map((x) => x.filterMs), 0.95),
      longTaskCountMedian: percentile(values.map((x) => x.longTasks), 0.5),
      longTaskTotalMsMedian: percentile(values.map((x) => x.longTaskTotalMs), 0.5),
      maxMountedRows: Math.max(...values.map((x) => x.mountedAfterScroll)),
      accessibilityColumnHeadersObserved: values[0].axColumnHeaders,
      accessibilityRowsObserved: values[0].axRows,
    });
  }
}
const output = {
  generatedAt: new Date().toISOString(),
  tool: 'Chromium + Playwright synthetic transaction spike; not production performance',
  runner: { platform: process.platform, architecture: process.arch, cpus: os.cpus().length, browserVersion },
  methodology: {
    repetitions: repeats,
    viewport: '1440x900',
    caveats: [
      'Expo development server timings include dev-mode compilation and are not production LCP.',
      'One app bundle includes both renderers; this is not a valid standalone bundle-size comparison.',
      'AX tree role counts do not substitute for a human NVDA or VoiceOver audit.',
      'Synthetic client-side filtering differs from backend-paged production queries.',
      'Scrolling uses automated wheel events; manual input latency testing still required.',
    ],
  },
  summary, runs: records, errors,
};
await fs.writeFile(new URL('spike-384-results.json', root), JSON.stringify(output, null, 2) + '\n');
console.log('SUMMARY', JSON.stringify(summary));
if (errors.length || records.length !== repeats * 4) {
  console.error('Benchmark failures', JSON.stringify(errors));
  process.exitCode = 1;
}
