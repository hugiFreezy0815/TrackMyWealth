// #494 "Load more growth": loads an account with ~10,000 bookings page by page on the web table
// and records the cost per page, DOM size, JS heap and interaction latency (AT-05 at 10k).
//   TMW_EMAIL=… TMW_PASSWORD=… APP=… ACCOUNT=<id> TOTAL=<n> OUT=<dir> node growth-494.mjs
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright-core";

const { TMW_EMAIL, TMW_PASSWORD, ACCOUNT } = process.env;
const APP = process.env.APP ?? "http://localhost:8092";
const OUT = process.env.OUT ?? ".";
const TOTAL = Number(process.env.TOTAL);
mkdirSync(OUT, { recursive: true });

const results = [];
const check = (name, pass, detail = "") => {
  results.push({ name, pass: Boolean(pass), detail: String(detail) });
  console.log(
    `${pass ? "PASS" : "FAIL"} ${name}${detail === "" ? "" : ` (${detail})`}`,
  );
};
const shown = (page) =>
  page.evaluate(() => {
    const t = [...document.querySelectorAll("[aria-live]")]
      .map((n) => n.textContent.trim())
      .find((x) => /^Showing/.test(x));
    return t ? Number(t.match(/^Showing ([\d']+)/)[1].replace(/'/g, "")) : 0;
  });
const sample = (page) =>
  page.evaluate(() => ({
    rows: document.querySelectorAll("button[data-transaction-open]").length,
    domNodes: document.getElementsByTagName("*").length,
    heapMb: Math.round((performance.memory?.usedJSHeapSize ?? 0) / 1e6),
    slowestEventMs: Math.max(0, ...(window.__events ?? [])),
  }));

// Times a real (trusted) mouse click until the row count changes and the next frame is painted.
// The target is found with plain DOM queries: Playwright's role locators compute accessible names
// over the whole DOM on the main thread and would distort the numbers.
async function timedInPage(page, kind) {
  const box = await page.evaluate((k) => {
    const target =
      k === "loadMore"
        ? [...document.querySelectorAll('[role="button"], button')].find(
            (b) => b.textContent.trim() === "Load more",
          )
        : [...document.querySelectorAll('[role="radio"]')].find((b) =>
            b.textContent.trim().startsWith("Uncategorised"),
          );
    target.scrollIntoView({ block: "center" });
    const r = target.getBoundingClientRect();
    const count = () =>
      document.querySelectorAll("button[data-transaction-open]").length;
    const before = count();
    window.__events = [];
    window.__done = new Promise((resolve) => {
      const tick = () =>
        count() !== before
          ? requestAnimationFrame(() => resolve(performance.now()))
          : requestAnimationFrame(tick);
      requestAnimationFrame(tick);
    });
    window.__t0 = 0;
    target.addEventListener(
      "pointerdown",
      () => (window.__t0 = performance.now()),
      { once: true, capture: true },
    );
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  }, kind);
  await page.mouse.click(box.x, box.y);
  return page.evaluate(async () => {
    const end = await window.__done;
    return {
      totalMs: Math.round(end - window.__t0),
      eventMs: Math.max(0, ...window.__events),
    };
  });
}

const browser = await chromium.launch({
  channel: "chrome",
  headless: true,
  args: ["--enable-precise-memory-info"],
});
const context = await browser.newContext({
  locale: "en-GB",
  viewport: { width: 1280, height: 900 },
});
await context.addInitScript(() => {
  window.__events = [];
  new PerformanceObserver((list) => {
    for (const e of list.getEntries())
      if (e.interactionId) window.__events.push(e.duration);
  }).observe({ type: "event", durationThreshold: 16, buffered: true });
});
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
await page.goto(APP);
await page.getByLabel("Email").first().fill(TMW_EMAIL);
await page.getByLabel("Password").first().fill(TMW_PASSWORD);
await page.getByRole("button", { name: "Sign in" }).click();
await page
  .getByRole("button", { name: "Sign in" })
  .waitFor({ state: "detached" });
await page.waitForTimeout(800);
await page.evaluate((id) => {
  history.pushState({}, "", `/accounts/${id}`);
  dispatchEvent(new PopStateEvent("popstate"));
}, ACCOUNT);
await page
  .getByText(/^Showing 50 of /)
  .first()
  .waitFor({ timeout: 30000 });

const marks = new Set([1000, 2500, 5000, 7500, TOTAL]);
const timeline = [];
while ((await shown(page)) < TOTAL) {
  const before = await shown(page);
  await page.evaluate(() => (window.__events = []));
  const timing = await timedInPage(page, "loadMore");
  const now = await shown(page);
  if ([...marks].some((m) => before < m && now >= m)) {
    const s = await sample(page);
    timeline.push({
      shown: now,
      loadMoreMs: timing.totalMs,
      loadMoreEventMs: timing.eventMs,
      ...s,
    });
    console.log(JSON.stringify(timeline.at(-1)));
  }
}
check("all rows loaded", (await shown(page)) === TOTAL, await shown(page));
const slowestLoadMore = Math.max(...timeline.map((t) => t.loadMoreEventMs));
check(
  "Load more: INP ≤ 200 ms at every size up to 10k",
  slowestLoadMore > 0 && slowestLoadMore <= 200,
  `${slowestLoadMore} ms`,
);

// AT-05 at 10k: arrow down 200 rows from deep in the list; the row scrolls into view, keeps focus.
const ids = await page.evaluate(() =>
  [...document.querySelectorAll("button[data-transaction-open]")].map(
    (b) => b.id,
  ),
);
await page.evaluate(() => (window.__events = []));
await page.locator(`#${ids[ids.length - 201]}`).focus();
const t0 = Date.now();
for (let i = 0; i < 200; i += 1) await page.keyboard.press("ArrowDown");
const arrowMs = (Date.now() - t0) / 200;
const state = await page.evaluate(() => {
  const a = document.activeElement;
  const r = a.getBoundingClientRect();
  return { id: a.id, inView: r.top >= 0 && r.bottom <= innerHeight };
});
const arrows = await sample(page);
check("AT-05 (10k): ArrowDown reaches the last row", state.id === ids.at(-1));
check("AT-05 (10k): focused row scrolled into view", state.inView);
check(
  "AT-05 (10k): arrow key latency ≤ 200 ms",
  arrows.slowestEventMs <= 200,
  `slowest ${arrows.slowestEventMs} ms, ${arrowMs.toFixed(0)} ms per press incl. driver`,
);

// A filter switch at full size: the list restarts at page 0 and the DOM shrinks again.
await page.evaluate(() => (window.__events = []));
const filterTiming = await timedInPage(page, "filter");
await page.waitForTimeout(1000);
const after = await sample(page);
check(
  "filter switch at 10k: INP ≤ 200 ms",
  filterTiming.eventMs > 0 && filterTiming.eventMs <= 200,
  `event ${filterTiming.eventMs} ms, rows replaced and painted after ${filterTiming.totalMs} ms`,
);
check(
  "filter switch at 10k: DOM back to one page",
  after.rows <= 50,
  `${after.rows} rows, ${after.domNodes} nodes`,
);
check("no page errors", errors.length === 0, errors.join(" | "));
await browser.close();
writeFileSync(
  join(OUT, "growth-results.json"),
  JSON.stringify(
    { results, timeline, arrows, afterFilter: after, filterTiming },
    null,
    2,
  ),
);
process.exit(results.every((r) => r.pass) ? 0 : 1);
