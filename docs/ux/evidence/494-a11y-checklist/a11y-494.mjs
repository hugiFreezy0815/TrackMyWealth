// #494 S3 accessibility checklist on the production web build (AT-01, AT-03, AT-04 keyboard part,
// AT-05 at 1k rows, AT-06, AT-07 incl. real 200 % browser zoom, focus after reload / Load more,
// Core Web Vitals in the lab). Synthetic data: scripts/seed_demo.py + bulk_bookings.py
// (1,250 rows, 8 uncategorised). Credentials from the environment only:
//   TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:8092 ACCOUNT=<id> OUT=<dir> SCRATCH=<dir> node a11y-494.mjs
import AxeBuilder from "@axe-core/playwright";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright-core";

const { TMW_EMAIL, TMW_PASSWORD, ACCOUNT } = process.env;
const APP = process.env.APP ?? "http://localhost:8092";
const OUT = process.env.OUT ?? ".";
const SCRATCH = process.env.SCRATCH ?? OUT;
const TOTAL = Number(process.env.TOTAL ?? 1250);
if (!TMW_EMAIL || !TMW_PASSWORD || !ACCOUNT)
  throw new Error("Set TMW_EMAIL, TMW_PASSWORD, ACCOUNT");
mkdirSync(OUT, { recursive: true });

const results = [];
const data = {};
const check = (name, pass, detail = "") => {
  results.push({ name, pass: Boolean(pass), detail: String(detail) });
  console.log(
    `${pass ? "PASS" : "FAIL"} ${name}${detail === "" ? "" : ` (${detail})`}`,
  );
};

async function axe(page, key) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  check(
    `axe: ${key}`,
    violations.length === 0,
    violations.map((v) => `${v.id}×${v.nodes.length}`).join(", "),
  );
}

// Records layout shifts and event timings from the first document on (Core Web Vitals in the lab).
const vitalsInit = () => {
  window.__vitals = { lcp: 0, cls: 0, events: [] };
  new PerformanceObserver((list) => {
    for (const e of list.getEntries())
      window.__vitals.lcp = Math.max(window.__vitals.lcp, e.startTime);
  }).observe({ type: "largest-contentful-paint", buffered: true });
  new PerformanceObserver((list) => {
    for (const e of list.getEntries())
      if (!e.hadRecentInput) window.__vitals.cls += e.value;
  }).observe({ type: "layout-shift", buffered: true });
  new PerformanceObserver((list) => {
    for (const e of list.getEntries())
      if (e.interactionId)
        window.__vitals.events.push({ name: e.name, duration: e.duration });
  }).observe({ type: "event", durationThreshold: 16, buffered: true });
};

async function signIn(page) {
  await page.goto(APP);
  await page.getByLabel("Email").first().fill(TMW_EMAIL);
  await page.getByLabel("Password").first().fill(TMW_PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page
    .getByRole("button", { name: "Sign in" })
    .waitFor({ state: "detached" });
  await page.waitForTimeout(800);
}

async function openAccount(page) {
  const started = Date.now();
  await page.evaluate((id) => {
    history.pushState({}, "", `/accounts/${id}`);
    dispatchEvent(new PopStateEvent("popstate"));
  }, ACCOUNT);
  await page
    .getByText(/^Showing 50 of \d+$/)
    .first()
    .waitFor({ timeout: 30000 });
  return Date.now() - started;
}

const showingText = (page) =>
  page.evaluate(
    () =>
      [...document.querySelectorAll("[aria-live]")]
        .map((n) => n.textContent.trim())
        .find((t) => /^Showing/.test(t)) ?? null,
  );
// Polled from Node: the production CSP has no 'unsafe-eval', which waitForFunction needs.
async function waitShowing(page, re, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const texts = await page.evaluate(() =>
      [...document.querySelectorAll("[aria-live]")].map((n) =>
        n.textContent.trim(),
      ),
    );
    if (texts.some((t) => re.test(t))) return;
    await page.waitForTimeout(100);
  }
  throw new Error(`timeout waiting for ${re}`);
}
const rowIds = (page) =>
  page.evaluate(() =>
    [...document.querySelectorAll("button[data-transaction-open]")].map(
      (b) => b.id,
    ),
  );
const active = (page) =>
  page.evaluate(() => {
    const a = document.activeElement;
    if (!a || a === document.body) return { tag: "BODY" };
    const s = getComputedStyle(a);
    const r = a.getBoundingClientRect();
    return {
      tag: a.tagName,
      id: a.id,
      role: a.getAttribute("role"),
      label: (a.getAttribute("aria-label") ?? a.textContent ?? "")
        .trim()
        .slice(0, 60),
      transaction: a.hasAttribute("data-transaction-open"),
      outline: s.outlineStyle !== "none" && parseFloat(s.outlineWidth) > 0,
      shadow: s.boxShadow !== "none",
      inView: r.top >= 0 && r.bottom <= innerHeight && r.height > 0,
    };
  });

// No sideways page scroll, and no truncated date or amount in the transaction list.
const layoutProblems = (page) =>
  page.evaluate(() => {
    const problems = [];
    const root = document.scrollingElement;
    if (root.scrollWidth > root.clientWidth + 1)
      problems.push(`page ${root.scrollWidth}>${root.clientWidth}`);
    for (const el of document.querySelectorAll("div, main, section")) {
      const s = getComputedStyle(el);
      if (
        /(auto|scroll)/.test(s.overflowX) &&
        el.scrollWidth > el.clientWidth + 1 &&
        el.clientWidth > 0
      )
        problems.push(`scroll-x ${el.scrollWidth}>${el.clientWidth}`);
    }
    const money = /(CHF|EUR)\s?[\d'.]+|\d{1,2} [A-Z][a-z]{2} \d{4}/;
    for (const el of document.querySelectorAll('div[dir="auto"], span, td')) {
      if (el.children.length || !money.test(el.textContent)) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0) continue;
      if (
        el.scrollWidth > el.clientWidth + 1 ||
        (/ellipsis/.test(getComputedStyle(el).textOverflow) &&
          el.scrollWidth > el.clientWidth)
      )
        problems.push(`truncated "${el.textContent.trim().slice(0, 30)}"`);
    }
    return [...new Set(problems)].slice(0, 10);
  });

const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  // ---- Desktop 1280: keyboard, focus, live region, AT-05 at 1k, Core Web Vitals ----
  {
    const context = await browser.newContext({
      locale: "en-GB",
      viewport: { width: 1280, height: 900 },
    });
    await context.addInitScript(vitalsInit);
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
    await signIn(page);
    const lcpSignIn = await page.evaluate(() => window.__vitals.lcp);
    data.listVisibleMs = await openAccount(page);
    const accountName = (
      await page.getByRole("heading").allTextContents()
    ).find((t) => t.includes("Paper Moon"));

    // AT-01: Tab from the top of the document to the first row; Shift+Tab goes back.
    await page.evaluate(() => document.activeElement?.blur());
    const stops = [];
    let firstRowStop = -1;
    for (let i = 1; i <= 60; i += 1) {
      await page.keyboard.press("Tab");
      const a = await active(page);
      stops.push(a);
      if (a.transaction) {
        firstRowStop = i;
        break;
      }
    }
    check(
      "AT-01: first transaction row reachable by Tab",
      firstRowStop > 0,
      `Tab stop ${firstRowStop}`,
    );
    check(
      "AT-01: focus visible on every stop",
      stops.every((s) => s.outline || s.shadow),
      stops
        .filter((s) => !(s.outline || s.shadow))
        .map((s) => s.label)
        .join(" | "),
    );
    await page.keyboard.press("Shift+Tab");
    const back = await active(page);
    check(
      "AT-01: Shift+Tab moves back",
      back.tag !== "BODY" && !back.transaction,
      back.label,
    );
    data.tabStops = stops.map((s) => `${s.tag}:${s.label}`);

    // AT-03: every sort order by keyboard; focus returns to the sort control, order is right.
    const orders = [
      ["Oldest first", "date", 1],
      ["Largest amount", "amount", -1],
      ["Smallest amount", "amount", 1],
      ["Newest first", "date", -1],
    ];
    for (const [label, field, direction] of orders) {
      await page
        .getByRole("button", { name: /(first|amount) ⌄$/ })
        .first()
        .focus();
      await page.keyboard.press("Enter");
      const option = page.getByRole("radio", { name: label });
      await option.waitFor();
      await option.focus();
      await page.keyboard.press("Enter");
      await option.waitFor({ state: "detached" });
      await waitShowing(page, new RegExp(`^Showing 50 of ${TOTAL}$`));
      await page.waitForTimeout(500);
      const a = await active(page);
      check(
        `AT-03: ${label}: focus back on the sort control`,
        a.label.startsWith(label),
        a.label,
      );
      const values = await page.evaluate((f) => {
        const rows = [...document.querySelectorAll("tbody tr")].slice(0, 20);
        return rows.map((r) => {
          const cells = r.querySelectorAll("td");
          if (f === "date") return Date.parse(cells[0].textContent);
          // The amount sorts by the value in the account currency: the "≈" line when converted.
          const parts = cells[3].textContent.split("≈");
          const text = parts.length > 1 ? parts[1].split("·")[0] : parts[0];
          return (
            Math.abs(parseFloat(text.replace(/[^\d.]/g, ""))) *
            (/[−-]/.test(text) ? -1 : 1)
          );
        });
      }, field);
      const sorted = values.every(
        (v, i) => i === 0 || (v - values[i - 1]) * direction >= 0,
      );
      check(
        `AT-03: ${label}: rows in order`,
        sorted,
        values.slice(0, 4).join(", "),
      );
    }

    // Load more by keyboard: focus moves to the first new row, the count is announced.
    const before = await rowIds(page);
    await page.getByRole("button", { name: "Load more" }).focus();
    await page.keyboard.press("Enter");
    await waitShowing(page, new RegExp(`^Showing 100 of ${TOTAL}$`));
    await page.waitForTimeout(300);
    let a = await active(page);
    const after = await rowIds(page);
    check(
      "Load more: focus on the first new row",
      a.id === after[before.length] && a.inView,
      `${a.label}`,
    );
    check(
      "Load more: count announced in the polite live region",
      (await showingText(page)) === `Showing 100 of ${TOTAL}`,
    );

    // AT-05 at 1,000 rows: arrow down to the last row; it scrolls into view and keeps focus.
    const loadTimes = [];
    while ((await rowIds(page)).length < 1000) {
      const n = (await rowIds(page)).length;
      const t0 = Date.now();
      await page.getByRole("button", { name: "Load more" }).click();
      await waitShowing(page, new RegExp(`^Showing ${n + 50} of ${TOTAL}$`));
      loadTimes.push(Date.now() - t0);
    }
    data.loadMoreMsTo1000 = loadTimes;
    const ids = await rowIds(page);
    await page.locator(`#${ids[0]}`).focus();
    for (let i = 0; i < 999; i += 1) await page.keyboard.press("ArrowDown");
    a = await active(page);
    check(
      "AT-05 (1k): ArrowDown reaches row 1,000",
      a.id === ids[999],
      a.label,
    );
    check("AT-05 (1k): focused row scrolled into view", a.inView);
    // AT-04 keyboard part: open S4, go back, focus returns to the same row.
    await page.keyboard.press("Enter");
    await page.waitForTimeout(800);
    const inPanel = await active(page);
    check(
      "AT-04: details panel takes focus",
      inPanel.tag !== "BODY" && !inPanel.transaction,
      inPanel.label,
    );
    await page
      .getByRole("button", { name: /^(Back|Close)/ })
      .last()
      .click();
    await page.waitForTimeout(500);
    a = await active(page);
    check("AT-04: focus returns to the same row", a.id === ids[999], a.label);

    // AT-06 and focus after a category save under the Uncategorised filter.
    await page.getByRole("radio", { name: /^Uncategorised/ }).click();
    await waitShowing(page, /^Showing 8 of 8$/);
    const liveRegions = await page.evaluate(() =>
      [...document.querySelectorAll("[aria-live]")]
        .filter((n) => n.textContent.trim())
        .map((n) => `${n.getAttribute("aria-live")}: ${n.textContent.trim()}`),
    );
    check(
      "AT-06: one polite count after filtering, no diagnostics",
      liveRegions.length === 1 && liveRegions[0] === "polite: Showing 8 of 8",
      liveRegions.join(" | "),
    );
    await axe(page, "desktop, Uncategorised filter");
    async function categorise(index) {
      const list = await rowIds(page);
      const category = page
        .locator("tbody tr")
        .nth(index)
        .getByRole("button", { name: /^Change category/ });
      await category.focus();
      await page.keyboard.press("Enter");
      const options = page
        .getByRole("dialog")
        .getByRole("button")
        .filter({ hasNotText: /Uncategorised|Close/ });
      await options.first().waitFor();
      await options.first().focus();
      await page.keyboard.press("Enter");
      await waitShowing(
        page,
        new RegExp(
          `^Showing ${list.length - 1} of ${list.length - 1}$|^Showing 50 of ${TOTAL}$`,
        ),
      );
      await page.waitForTimeout(800);
      return list;
    }
    let list = await categorise(2);
    a = await active(page);
    check(
      "Category save: focus moves to the next row",
      a.id === list[3],
      a.label,
    );
    check(
      "Category save: new count announced",
      (await showingText(page)) === "Showing 7 of 7",
    );
    list = await categorise(6);
    a = await active(page);
    check(
      "Category save on the last row: focus moves to the previous row",
      a.id === list[5],
      a.label,
    );
    while ((await rowIds(page)).length > 1) await categorise(0);
    await categorise(0);
    a = await active(page);
    check(
      "Category save of the only row: focus moves to the account heading",
      a.label === accountName,
      a.label,
    );

    // Core Web Vitals (lab, desktop, no throttling).
    const vitals = await page.evaluate(() => window.__vitals);
    const inp = Math.max(0, ...vitals.events.map((e) => e.duration));
    data.vitals = {
      lcpSignIn,
      cls: vitals.cls,
      inpApprox: inp,
      interactions: vitals.events.length,
      listVisibleMs: data.listVisibleMs,
    };
    check(
      "CWV: LCP ≤ 2.5 s (sign-in, first load)",
      lcpSignIn <= 2500,
      `${Math.round(lcpSignIn)} ms`,
    );
    check(
      "CWV: account list visible ≤ 2.5 s after navigation",
      data.listVisibleMs <= 2500,
      `${data.listVisibleMs} ms`,
    );
    check(
      "CWV: CLS ≤ 0.1 (whole session)",
      vitals.cls <= 0.1,
      vitals.cls.toFixed(3),
    );
    check(
      "CWV: INP ≤ 200 ms (slowest interaction, ~1,000 arrow keys and clicks)",
      inp <= 200,
      `${inp} ms`,
    );
    check(
      "desktop: no console errors",
      errors.length === 0,
      errors.join(" | "),
    );
    await page.screenshot({
      path: join(OUT, "desktop-after-focus-checks.png"),
    });
    await context.close();
  }

  // ---- AT-07: 320 px CSS width ----
  {
    const context = await browser.newContext({
      locale: "en-GB",
      viewport: { width: 320, height: 640 },
    });
    const page = await context.newPage();
    await signIn(page);
    await openAccount(page);
    const problems = await layoutProblems(page);
    check(
      "AT-07: 320 px: no sideways scroll, nothing truncated",
      problems.length === 0,
      problems.join(" | "),
    );
    await axe(page, "320 px");
    await page.screenshot({ path: join(OUT, "at07-320.png") });
    await context.close();
  }

  // ---- AT-07: real 200 % browser zoom (Chrome's own zoom level, not a DPR simulation) ----
  {
    const dir = mkdtempSync(join(SCRATCH, "zoom200-"));
    mkdirSync(join(dir, "Default"), { recursive: true });
    writeFileSync(
      join(dir, "Default", "Preferences"),
      JSON.stringify({
        partition: { default_zoom_level: { x: Math.log(2) / Math.log(1.2) } },
      }),
    );
    for (const [w, h] of [
      [1280, 900],
      [780, 900],
    ]) {
      const context = await chromium.launchPersistentContext(dir, {
        channel: "chrome",
        headless: true,
        locale: "en-GB",
        viewport: null,
        args: [`--window-size=${w},${h}`],
      });
      const page = context.pages()[0] ?? (await context.newPage());
      await signIn(page);
      const zoom = await page.evaluate(() => ({
        dpr: devicePixelRatio,
        css: innerWidth,
        window: outerWidth,
      }));
      check(
        `AT-07: 200 % zoom active (window ${w})`,
        zoom.dpr === 2 && zoom.css * 2 <= zoom.window,
        JSON.stringify(zoom),
      );
      await openAccount(page);
      const problems = await layoutProblems(page);
      check(
        `AT-07: 200 % zoom, window ${w} (${zoom.css} CSS px): no sideways scroll, nothing truncated`,
        problems.length === 0,
        problems.join(" | "),
      );
      // page.screenshot() under browser zoom captures only the top-left quarter: use CDP.
      const { data } = await (
        await context.newCDPSession(page)
      ).send("Page.captureScreenshot", { format: "png" });
      writeFileSync(
        join(OUT, `at07-zoom200-${w}-top.png`),
        Buffer.from(data, "base64"),
      );
      await context.close();
    }
  }

  // ---- AT-07: forced colours (Windows high contrast) ----
  {
    const context = await browser.newContext({
      locale: "en-GB",
      viewport: { width: 1280, height: 900 },
      forcedColors: "active",
    });
    const page = await context.newPage();
    await signIn(page);
    await openAccount(page);
    await page.evaluate(() => document.activeElement?.blur());
    const stops = [];
    for (let i = 0; i < 20; i += 1) {
      await page.keyboard.press("Tab");
      stops.push(await active(page));
    }
    check(
      "AT-07: forced colours: focus visible",
      stops.every((s) => s.outline || s.shadow),
      stops
        .filter((s) => !(s.outline || s.shadow))
        .map((s) => s.label)
        .join(" | "),
    );
    await page.screenshot({ path: join(OUT, "at07-forced-colours.png") });
    await context.close();
  }
} finally {
  await browser.close();
  writeFileSync(
    join(OUT, "a11y-results.json"),
    JSON.stringify({ results, data }, null, 2),
  );
}
const failed = results.filter((r) => !r.pass);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
