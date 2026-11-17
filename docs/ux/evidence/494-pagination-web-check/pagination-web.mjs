// #494 / PR #604: signed-in web check of "filter or sort change restarts at page 0" plus axe.
// Local stack seeded with scripts/seed_demo.py and add_bookings.py (72 rows, 14 uncategorised).
//   TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:8092 ACCOUNT=<id> OUT=<dir> node pagination-web.mjs
import AxeBuilder from "@axe-core/playwright";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright-core";

const { TMW_EMAIL, TMW_PASSWORD, ACCOUNT } = process.env;
const APP = process.env.APP ?? "http://localhost:8092";
const OUT = process.env.OUT ?? ".";
if (!TMW_EMAIL || !TMW_PASSWORD || !ACCOUNT)
  throw new Error("Set TMW_EMAIL, TMW_PASSWORD, ACCOUNT");
const VIEWPORTS = {
  desktop: { width: 1280, height: 900 },
  phone: { width: 390, height: 844 },
};

const results = [];
const axeReport = {};
const check = (name, pass, detail = "") => {
  results.push({ name, pass: Boolean(pass), detail });
  console.log(
    `${pass ? "PASS" : "FAIL"} ${name}${detail ? ` (${detail})` : ""}`,
  );
};

async function axe(page, key) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  axeReport[key] = violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    nodes: v.nodes.length,
    targets: v.nodes.slice(0, 3).map((n) => n.target.join(" ")),
  }));
  check(
    `${key}: axe`,
    violations.length === 0,
    violations.map((v) => v.id).join(", "),
  );
}

const showing = (page, text) => page.getByText(text, { exact: true }).first();
const loadMore = (page) =>
  page.getByRole("button", { name: "Load more", exact: true });

const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  for (const [label, size] of Object.entries(VIEWPORTS)) {
    const context = await browser.newContext({
      locale: "en-GB",
      viewport: size,
    });
    const page = await context.newPage();
    const consoleErrors = [];
    page.on(
      "console",
      (m) => m.type() === "error" && consoleErrors.push(m.text()),
    );
    page.on("pageerror", (e) => consoleErrors.push(String(e)));

    // Page requests of the activity list (size=50); the count request uses size=1.
    const listCalls = [];
    page.on("request", (req) => {
      const url = new URL(req.url());
      if (!url.pathname.endsWith(`/accounts/${ACCOUNT}/transactions`)) return;
      if (url.searchParams.get("size") !== "50") return;
      listCalls.push({
        page: Number(url.searchParams.get("page")),
        uncategorized: url.searchParams.get("uncategorized"),
        sort: url.searchParams.get("sort"),
      });
    });
    const callsSince = (n) =>
      listCalls.slice(n).map((c) => `${c.page}/${c.uncategorized}/${c.sort}`);

    await page.goto(APP);
    await page.getByLabel("Email").first().fill(TMW_EMAIL);
    await page.getByLabel("Password").first().fill(TMW_PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await page
      .getByRole("button", { name: "Sign in" })
      .waitFor({ state: "detached" });
    await page.waitForTimeout(1000);
    // In-app navigation: the web session lives in memory, a full load would sign out.
    await page.evaluate((id) => {
      history.pushState({}, "", `/accounts/${id}`);
      dispatchEvent(new PopStateEvent("popstate"));
    }, ACCOUNT);

    await showing(page, "Showing 50 of 72").waitFor();
    await axe(page, `${label} account detail`);
    await page.screenshot({ path: join(OUT, `${label}-1-page0.png`) });

    // 1. Revisit the filter.
    await loadMore(page).click();
    await showing(page, "Showing 72 of 72").waitFor();
    await page.getByRole("radio", { name: /^Uncategorised/ }).click();
    await showing(page, "Showing 14 of 14").waitFor();
    await axe(page, `${label} uncategorised filter`);
    let mark = listCalls.length;
    await page.getByRole("radio", { name: "All", exact: true }).click();
    await showing(page, "Showing 50 of 72").waitFor();
    await page.waitForTimeout(800);
    check(
      `${label}: back to All shows page 0 only`,
      !(await showing(page, "Showing 72 of 72").isVisible()),
    );
    check(
      `${label}: back to All requests page 0 once`,
      JSON.stringify(callsSince(mark)) ===
        JSON.stringify(["0/false/bookingDate,desc"]),
      callsSince(mark).join(" "),
    );

    // 2. Revisit the sort.
    await loadMore(page).click();
    await showing(page, "Showing 72 of 72").waitFor();
    await page.getByRole("button", { name: /^Newest first/ }).click();
    await page.getByRole("radio", { name: "Oldest first" }).click();
    await page.waitForResponse(
      (r) =>
        r.url().includes("sort=bookingDate%2Casc") ||
        r.url().includes("sort=bookingDate,asc"),
    );
    await showing(page, "Showing 50 of 72").waitFor();
    mark = listCalls.length;
    await page.getByRole("button", { name: /^Oldest first/ }).click();
    await page.getByRole("radio", { name: "Newest first" }).click();
    await showing(page, "Showing 50 of 72").waitFor();
    await page.waitForTimeout(800);
    check(
      `${label}: back to Newest first shows page 0 only`,
      !(await showing(page, "Showing 72 of 72").isVisible()),
    );
    check(
      `${label}: back to Newest first requests page 0 once`,
      JSON.stringify(callsSince(mark)) ===
        JSON.stringify(["0/false/bookingDate,desc"]),
      callsSince(mark).join(" "),
    );

    // 3. A failed page-0 refresh keeps cached rows and blocks paging until Retry succeeds.
    await page.getByRole("radio", { name: /^Uncategorised/ }).click();
    await showing(page, "Showing 14 of 14").waitFor();
    const failAllPage0 = (route) => {
      const url = new URL(route.request().url());
      const isAllPage0 =
        url.searchParams.get("size") === "50" &&
        url.searchParams.get("page") === "0" &&
        url.searchParams.get("uncategorized") === "false";
      return isAllPage0
        ? route.abort("internetdisconnected")
        : route.fallback();
    };
    await page.route(`**/accounts/${ACCOUNT}/transactions**`, failAllPage0);
    mark = listCalls.length;
    await page.getByRole("radio", { name: "All", exact: true }).click();
    const retry = page.getByRole("button", { name: "Try again" }).first();
    await retry.waitFor();
    check(
      `${label}: cached page 0 stays visible`,
      await showing(page, "Showing 50 of 72").isVisible(),
    );
    const disabled = await loadMore(page)
      .isDisabled()
      .catch(() => false);
    check(`${label}: Load more disabled after failed refresh`, disabled);
    await loadMore(page)
      .click({ force: true })
      .catch(() => {});
    await loadMore(page).scrollIntoViewIfNeeded();
    await page.mouse.wheel(0, 20000);
    await page.waitForTimeout(1000);
    check(
      `${label}: no page 1 while page 0 is stale`,
      callsSince(mark).every((c) => c.startsWith("0/")),
      callsSince(mark).join(" "),
    );
    await axe(page, `${label} failed refresh`);
    await retry.scrollIntoViewIfNeeded();
    await page.screenshot({ path: join(OUT, `${label}-2-failed-refresh.png`) });

    await page.unroute(`**/accounts/${ACCOUNT}/transactions**`, failAllPage0);
    await retry.click();
    await page
      .getByRole("button", { name: "Try again" })
      .waitFor({ state: "detached" });
    check(
      `${label}: Load more enabled after Retry`,
      await loadMore(page).isEnabled(),
    );
    await loadMore(page).click();
    await showing(page, "Showing 72 of 72").waitFor();
    check(
      `${label}: paging resumes after Retry`,
      true,
      callsSince(mark).join(" "),
    );
    await page.screenshot({ path: join(OUT, `${label}-3-after-retry.png`) });

    const unexpected = consoleErrors.filter(
      (e) => !/ERR_INTERNET_DISCONNECTED|Failed to load resource/.test(e),
    );
    check(
      `${label}: no console errors (besides the injected failure)`,
      unexpected.length === 0,
      unexpected.join(" | "),
    );
    await context.close();
  }
} finally {
  await browser.close();
  writeFileSync(
    join(OUT, "results.json"),
    JSON.stringify({ results, axeReport }, null, 2),
  );
}
const failed = results.filter((r) => !r.pass);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
