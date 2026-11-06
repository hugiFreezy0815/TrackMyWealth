// PERF-01: production-build traces of the #384 spike, both renderers, 1k/10k rows.
// Usage: node perf.mjs <origin> <outDir>
import { chromium } from 'playwright';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const [origin, outDir] = process.argv.slice(2);
await fs.mkdir(path.join(outDir, 'traces'), { recursive: true });
const REPEATS = 5;
const profiles = [{ name: 'native', cpu: 1 }, { name: 'cpu4x', cpu: 4 }];
const round = (n) => Math.round(n * 10) / 10;
const pct = (arr, p) => {
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
};

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const records = [];
const errors = [];

async function measure(context, renderer, rows, profile, run, temperature, trace) {
  const page = await context.newPage();
  page.on('pageerror', (e) => errors.push({ renderer, rows, profile: profile.name, run, temperature, message: e.message }));
  const cdp = await context.newCDPSession(page);
  if (profile.cpu > 1) await cdp.send('Emulation.setCPUThrottlingRate', { rate: profile.cpu });
  if (trace) await browser.startTracing(page, { screenshots: false, categories: ['devtools.timeline', 'blink.user_timing', 'loading', 'v8.execute', 'disabled-by-default-devtools.timeline.frame'] });
  await page.goto(`${origin}/spike-web-transactions?renderer=${renderer}&rows=${rows}`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    (n) => Number(document.querySelector('[data-benchmark-count]')?.getAttribute('data-benchmark-count')) === n,
    rows, { polling: 'raf', timeout: 60000 });
  const firstVisibleMs = await page.evaluate(() => performance.now());
  const load = await page.evaluate(() => new Promise((resolve) => {
    let lcp = 0; const longTasks = [];
    new PerformanceObserver((l) => { for (const e of l.getEntries()) lcp = e.startTime; }).observe({ type: 'largest-contentful-paint', buffered: true });
    new PerformanceObserver((l) => { for (const e of l.getEntries()) longTasks.push(e.duration); }).observe({ type: 'longtask', buffered: true });
    setTimeout(() => resolve({ lcp, longTasks }), 300);
  }));

  // Filter latency: change category, wait for count and two frames.
  const t0 = await page.evaluate(() => performance.now());
  await page.locator('select').nth(1).selectOption('Transport');
  await page.waitForFunction((n) => Number(document.querySelector('[data-benchmark-count]')?.getAttribute('data-benchmark-count')) === n, rows / 5, { polling: 'raf' });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
  const filterMs = (await page.evaluate(() => performance.now())) - t0;
  await page.locator('select').nth(1).selectOption('');
  await page.waitForFunction((n) => Number(document.querySelector('[data-benchmark-count]')?.getAttribute('data-benchmark-count')) === n, rows, { polling: 'raf' });

  // Scroll: 40 wheel steps through the list while recording frame intervals and long tasks.
  const scroller = renderer === 'dom'
    ? page.locator('[aria-label="React DOM virtualised transaction table"]')
    : page.locator('[aria-label="React Native Web virtualised transaction list"]');
  await scroller.scrollIntoViewIfNeeded();
  await scroller.hover();
  await page.evaluate(() => {
    window.__frames = []; window.__scrollLong = [];
    let last = performance.now();
    const tick = (t) => { window.__frames.push(t - last); last = t; if (!window.__stop) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
    new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__scrollLong.push(e.duration); }).observe({ type: 'longtask' });
  });
  for (let i = 0; i < 40; i++) { await page.mouse.wheel(0, 400); await page.waitForTimeout(16); }
  await page.waitForTimeout(300);
  const scroll = await page.evaluate(() => { window.__stop = true; return { frames: window.__frames.slice(1), longTasks: window.__scrollLong }; });
  const mounted = await page.evaluate(() => document.querySelectorAll('[data-benchmark-list] tbody tr, [data-benchmark-list] [role="button"]').length);
  const heapBytes = await page.evaluate(() => performance.memory?.usedJSHeapSize ?? null);

  if (trace) {
    const buf = await browser.stopTracing();
    await fs.writeFile(path.join(outDir, 'traces', `${renderer}-${rows}-${profile.name}.json.gz`), zlib.gzipSync(buf));
  }
  records.push({
    renderer, rows, profile: profile.name, run, temperature,
    firstVisibleMs: round(firstVisibleMs), lcpMs: round(load.lcp),
    loadLongTasks: load.longTasks.length, loadLongTaskMs: round(load.longTasks.reduce((a, b) => a + b, 0)),
    filterMs: round(filterMs),
    scrollFrames: scroll.frames.length,
    scrollFrameP50Ms: round(pct(scroll.frames, 50)), scrollFrameP95Ms: round(pct(scroll.frames, 95)),
    scrollFramesOver50Ms: scroll.frames.filter((f) => f > 50).length,
    scrollLongTasks: scroll.longTasks.length, scrollLongTaskMs: round(scroll.longTasks.reduce((a, b) => a + b, 0)),
    mountedAfterScroll: mounted, heapBytes,
  });
  await page.close();
}

for (const profile of profiles) {
  for (const renderer of ['rnw', 'dom']) {
    for (const rows of [1000, 10000]) {
      for (let run = 1; run <= REPEATS; run++) {
        // Fresh context = empty HTTP cache (cold); second navigation in it = warm.
        const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        await measure(context, renderer, rows, profile, run, 'cold', run === 1);
        await measure(context, renderer, rows, profile, run, 'warm', false);
        await context.close();
      }
    }
  }
}

const summary = [];
for (const profile of profiles) for (const renderer of ['rnw', 'dom']) for (const rows of [1000, 10000]) for (const temperature of ['cold', 'warm']) {
  const rs = records.filter((r) => r.profile === profile.name && r.renderer === renderer && r.rows === rows && r.temperature === temperature);
  const m = (k, p = 50) => round(pct(rs.map((r) => r[k]), p));
  summary.push({
    profile: profile.name, renderer, rows, temperature, runs: rs.length,
    firstVisibleMedianMs: m('firstVisibleMs'), firstVisibleP95Ms: m('firstVisibleMs', 95),
    lcpMedianMs: m('lcpMs'), filterMedianMs: m('filterMs'), filterP95Ms: m('filterMs', 95),
    scrollFrameP95MedianMs: m('scrollFrameP95Ms'), scrollFramesOver50MsMedian: m('scrollFramesOver50Ms'),
    scrollLongTaskMsMedian: m('scrollLongTaskMs'), mountedAfterScroll: m('mountedAfterScroll'),
  });
}

const out = {
  generatedAt: new Date().toISOString(),
  test: 'PERF-01, #384 spike at tag spike-384-evidence (fa37fdf8), production `expo export -p web`, served statically from 127.0.0.1',
  environment: {
    machine: 'Apple ' + os.cpus()[0].model + ', ' + os.cpus().length + ' cores, ' + Math.round(os.totalmem() / 2 ** 30) + ' GB',
    os: 'macOS ' + os.release(), browser: 'Google Chrome ' + browser.version() + ' (headless)',
    viewport: '1440x900', repeats: REPEATS,
    profiles: 'native = no throttling; cpu4x = CDP CPU throttling 4x as a stand-in for a slower laptop',
  },
  method: [
    'cold = new browser context (empty cache); warm = second navigation in the same context.',
    'firstVisibleMs = performance.now() when the list shows the expected row count (navigation start = 0).',
    'Scroll = 40 mouse-wheel steps of 400px with rAF frame intervals and long tasks recorded.',
    'One Chrome performance trace per profile/renderer/size (cold run 1) is in traces/.',
    'Synthetic data only. The spike sorts and filters on the client; production pages from the server (max 200 rows).',
  ],
  summary, runs: records, errors,
};
await fs.writeFile(path.join(outDir, 'perf-01-results.json'), JSON.stringify(out, null, 2) + '\n');
await browser.close();
console.table(summary);
console.log('errors', errors.length);
