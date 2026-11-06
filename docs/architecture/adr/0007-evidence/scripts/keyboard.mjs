// Automated keyboard / zoom checks for AT-01, AT-03 (sort state), AT-05, AT-06, AT-07.
// Real key presses only; no element is focused by script. Not a screen-reader test.
// Usage: node keyboard.mjs <origin> <outFile>
import { chromium } from 'playwright';
import fs from 'node:fs/promises';

const [origin, outFile] = process.argv.slice(2);
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const results = [];
const describe = () => {
  const el = document.activeElement;
  if (!el || el === document.body) return { tag: 'body' };
  const cs = getComputedStyle(el);
  return {
    tag: el.tagName.toLowerCase(),
    role: el.getAttribute('role'),
    label: (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 60),
    inTableBody: !!el.closest('tbody'),
    isListRoot: (el.getAttribute('aria-label') ?? '').startsWith('Transaction list.'),
    isRow: el.getAttribute('role') === 'button' && !!el.closest('[data-benchmark-list]') || !!el.closest('tbody'),
    focusVisible: el.matches(':focus-visible'),
    indicator: (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || cs.boxShadow !== 'none',
  };
};
const ready = (page, n) => page.waitForFunction(
  (x) => Number(document.querySelector('[data-benchmark-count]')?.getAttribute('data-benchmark-count')) === x, n);

for (const renderer of ['dom', 'rnw']) {
  const rows = 10000;
  const url = `${origin}/spike-web-transactions?renderer=${renderer}&rows=${rows}`;

  // AT-01: Tab from the top of the page; then Shift+Tab back.
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto(url); await ready(page, rows);
    const stops = [];
    let firstRowAt = null;
    for (let i = 1; i <= 40; i++) {
      await page.keyboard.press('Tab');
      const d = await page.evaluate(describe);
      stops.push(d);
      if (firstRowAt == null && d.isRow) firstRowAt = i;
    }
    const back = [];
    for (let i = 0; i < 5; i++) { await page.keyboard.press('Shift+Tab'); back.push(await page.evaluate(describe)); }
    const listRootAt = stops.findIndex((s) => s.isListRoot) + 1 || null;
    const unindicated = stops.filter((s) => s.tag !== 'body' && !s.indicator).map((s) => `${s.tag} "${s.label}"`);
    results.push({
      id: 'AT-01', renderer, rows,
      result: firstRowAt ? `A transaction row is reached by Tab at stop ${firstRowAt}` : `No transaction row is reached by Tab in 40 stops; the list container is reached at stop ${listRootAt ?? 'never'}`,
      focusWithoutVisibleIndicator: [...new Set(unindicated)],
      tabStops: stops.slice(0, 15).map((s) => `${s.tag}${s.role ? '[' + s.role + ']' : ''} "${s.label}"`),
      shiftTabWorks: back.every((b) => b.tag !== 'body'),
    });
    await page.close();
  }

  // AT-03 (state only): sort by amount and reverse; read aria-sort on headers.
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto(url); await ready(page, rows);
    await page.locator('select').nth(2).selectOption('amount');
    await page.getByRole('button', { name: 'Reverse sort' }).click();
    const ariaSort = await page.evaluate(() => [...document.querySelectorAll('th, [role="columnheader"]')]
      .map((h) => `${h.textContent.trim().slice(0, 20)}=${h.getAttribute('aria-sort') ?? 'none'}`));
    results.push({ id: 'AT-03 (sort state)', renderer, rows,
      result: ariaSort.length === 0 ? 'No column headers exist; sort direction cannot be exposed on a header'
        : ariaSort.some((s) => /=(ascending|descending)$/.test(s)) ? 'aria-sort set on the sorted header' : 'Sorted, but no header has aria-sort',
      headers: ariaSort });
    await page.close();
  }

  // AT-05: reach the list by Tab, press ArrowDown 60 times; selected row must stay visible and focus stay put.
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto(url); await ready(page, rows);
    let reached = false;
    for (let i = 0; i < 40 && !reached; i++) { await page.keyboard.press('Tab'); reached = await page.evaluate(() => (document.activeElement?.getAttribute('aria-label') ?? '').startsWith('Transaction list.')); }
    let r = { result: 'List container not reachable by Tab' };
    if (reached) {
      for (let i = 0; i < 60; i++) await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(200);
      r = await page.evaluate((mode) => {
        const sel = mode === 'dom' ? document.querySelector('tbody tr[aria-selected="true"]')
          : [...document.querySelectorAll('[data-benchmark-list] [role="button"]')].find((b) => b.getAttribute('aria-selected') === 'true' || b.getAttribute('aria-pressed') === 'true' || /selected/i.test(b.getAttribute('aria-label') ?? ''));
        const scroller = document.querySelector(mode === 'dom' ? '[aria-label="React DOM virtualised transaction table"]' : '[aria-label="React Native Web virtualised transaction list"]');
        const details = document.querySelector('#spike-details')?.textContent ?? '';
        let visible = null;
        if (sel && scroller) { const a = sel.getBoundingClientRect(), b = scroller.getBoundingClientRect(); visible = a.top >= b.top - 1 && a.bottom <= b.bottom + 1; }
        return { selectedRowInDom: !!sel, selectedRowVisible: visible, scrollTop: scroller?.scrollTop ?? null,
          focusStillOnList: (document.activeElement?.getAttribute('aria-label') ?? '').startsWith('Transaction list.'),
          detailsShowSomething: details.length > 0 };
      }, renderer);
      r.result = r.selectedRowVisible ? 'Selected row 61 is scrolled into view' : `After 60 × ArrowDown the selected row is ${r.selectedRowInDom ? 'mounted but outside the visible scroll area' : 'not mounted / not marked selected'} (scrollTop ${r.scrollTop})`;
    }
    results.push({ id: 'AT-05', renderer, rows, ...r });
    await page.close();
  }

  // AT-06: filter, empty result, CSV of filtered rows, live-region text.
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, acceptDownloads: true });
    await page.goto(url); await ready(page, rows);
    await page.locator('select').nth(1).selectOption('Transport'); await ready(page, rows / 5);
    const dl = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download filtered CSV' }).click();
    const csv = await fs.readFile(await (await dl).path(), 'utf8');
    const csvRows = csv.trimEnd().split('\r\n').length - 1;
    await page.getByLabel('Search description or account').fill('zzzz-no-match'); await ready(page, 0);
    const live = await page.evaluate(() => [...document.querySelectorAll('[aria-live], [role="status"]')].map((e) => e.textContent.trim().slice(0, 120)));
    results.push({ id: 'AT-06', renderer, rows,
      result: `CSV holds ${csvRows} rows for a filter showing ${rows / 5}${csvRows === rows / 5 ? ' (match)' : ' (MISMATCH)'}; empty search shows 0 rows`,
      liveRegionTextOnEmpty: live });
    await page.close();
  }

  // AT-07: 320 px width, 200 % zoom (720×450 CSS px at scale 2), forced colors.
  for (const [name, opts, media] of [
    ['320px', { viewport: { width: 320, height: 640 } }, {}],
    ['200% zoom', { viewport: { width: 720, height: 450 }, deviceScaleFactor: 2 }, {}],
    ['forced colors', { viewport: { width: 1440, height: 900 } }, { forcedColors: 'active' }],
  ]) {
    const page = await browser.newPage(opts);
    await page.emulateMedia(media);
    await page.goto(url); await ready(page, rows);
    for (let i = 0; i < 6; i++) await page.keyboard.press('Tab');
    const r = await page.evaluate(() => {
      const doc = document.documentElement;
      const clipped = [...document.querySelectorAll('td, th, [data-benchmark-list] [role="button"] *, button, select, input')]
        .filter((e) => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflow !== 'visible').length;
      const cs = getComputedStyle(document.activeElement);
      return { pageScrollsSideways: doc.scrollWidth > doc.clientWidth + 1, pageWidth: doc.scrollWidth, viewport: doc.clientWidth,
        clippedElements: clipped, focusIndicatorVisible: (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || cs.boxShadow !== 'none' };
    });
    await page.screenshot({ path: outFile.replace(/\.json$/, `-${renderer}-${name.replace(/[^a-z0-9]+/gi, '_')}.png`) });
    results.push({ id: `AT-07 ${name}`, renderer, rows,
      result: `${r.pageScrollsSideways ? `page scrolls sideways (${r.pageWidth}px content in ${r.viewport}px)` : 'no sideways page scroll'}; ${r.clippedElements} clipped cells/controls; focus indicator ${r.focusIndicatorVisible ? 'visible' : 'NOT visible'}`, ...r });
    await page.close();
  }
}

await fs.writeFile(outFile, JSON.stringify({
  generatedAt: new Date().toISOString(),
  test: 'Automated keyboard and zoom checks (AT-01, AT-03 state, AT-05, AT-06, AT-07) on the production export of tag spike-384-evidence; real key presses, no scripted focus. NOT a screen-reader test.',
  browser: 'Google Chrome ' + browser.version() + ' (headless), macOS',
  results,
}, null, 2) + '\n');
await browser.close();
for (const r of results) console.log(r.id.padEnd(22), r.renderer, '|', r.result);
