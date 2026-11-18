// #620 / PR #622: signed-in web check of one retry for an Account Detail outage.
// Every API read fails (connection refused) while the app refetches on focus, then the API is back
// and one "Try again" must recover everything. Synthetic data; credentials from the environment.
//   TMW_EMAIL=… TMW_PASSWORD=… APP=http://localhost:8092 ACCOUNT=<id> TOTAL=<rows> OUT=<dir> node outage-web.mjs
import AxeBuilder from "@axe-core/playwright";
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
const banners = (page) => page.getByText(/^Something went wrong/);
const retries = (page) => page.getByRole("button", { name: "Try again" });
const showing = (page) =>
  page.evaluate(() =>
    [...document.querySelectorAll("[aria-live]")]
      .map((n) => n.textContent.trim())
      .find((t) => /^Showing/.test(t)),
  );

const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  for (const [label, viewport] of Object.entries({
    desktop: { width: 1280, height: 900 },
    phone: { width: 390, height: 844 },
  })) {
    const context = await browser.newContext({ locale: "en-GB", viewport });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    const reads = [];
    page.on("request", (r) => {
      const u = new URL(r.url());
      if (r.method() === "GET" && u.pathname.startsWith("/api/v1/"))
        reads.push(
          `${u.pathname.replace(ACCOUNT, "{id}")}${u.searchParams.get("size") ? `?size=${u.searchParams.get("size")}&page=${u.searchParams.get("page")}` : ""}`,
        );
    });
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
    await page.getByText(`Showing 50 of ${TOTAL}`).first().waitFor();
    await page.getByRole("button", { name: "Load more" }).click();
    await page.getByText(`Showing 100 of ${TOTAL}`).first().waitFor();
    // The app's staleTime is 30 s: only stale reads refetch when the tab becomes visible again.
    await page.waitForTimeout(31000);

    // Outage: every API read is refused; returning to the tab refetches the screen's reads.
    const refuse = (route) =>
      route.request().method() === "GET"
        ? route.abort("connectionrefused")
        : route.fallback();
    await page.route("**/api/v1/**", refuse);
    await page.evaluate(() => {
      Object.defineProperty(document, "visibilityState", {
        value: "hidden",
        configurable: true,
      });
      document.dispatchEvent(new Event("visibilitychange"));
      window.dispatchEvent(new Event("visibilitychange"));
      Object.defineProperty(document, "visibilityState", {
        value: "visible",
        configurable: true,
      });
      document.dispatchEvent(new Event("visibilitychange"));
      window.dispatchEvent(new Event("visibilitychange"));
    });
    await retries(page).first().waitFor({ timeout: 60000 });
    await page.waitForTimeout(8000); // let the app's own retries settle
    check(
      `${label}: outage shows one error banner`,
      (await banners(page).count()) === 1,
      await banners(page).count(),
    );
    check(
      `${label}: outage shows one Try again`,
      (await retries(page).count()) === 1,
      await retries(page).count(),
    );
    check(
      `${label}: loaded rows stay`,
      (await showing(page)) === `Showing 100 of ${TOTAL}`,
      await showing(page),
    );
    const loadMore = page.getByRole("button", { name: "Load more" });
    check(
      `${label}: Load more disabled while page 0 is stale`,
      await loadMore.isDisabled(),
    );
    const { violations } = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
      .analyze();
    check(
      `${label}: axe in the outage state`,
      violations.length === 0,
      violations.map((v) => v.id).join(", "),
    );
    await retries(page).first().scrollIntoViewIfNeeded();
    await page.screenshot({ path: join(OUT, `${label}-1-outage.png`) });

    // API back: one Try again recovers every read.
    await page.unroute("**/api/v1/**", refuse);
    const mark = reads.length;
    await retries(page).first().click();
    await banners(page).first().waitFor({ state: "detached", timeout: 30000 });
    await page.waitForTimeout(1500);
    check(
      `${label}: one Try again clears every banner`,
      (await banners(page).count()) === 0 &&
        (await retries(page).count()) === 0,
    );
    check(`${label}: Load more enabled again`, await loadMore.isEnabled());
    check(
      `${label}: retry refetched the shared reads`,
      true,
      reads.slice(mark).sort().join(" | "),
    );
    await loadMore.click();
    await page
      .getByText(`Showing ${Math.min(TOTAL, 150)} of ${TOTAL}`)
      .first()
      .waitFor();
    check(`${label}: paging works after recovery`, true, await showing(page));
    await page.screenshot({ path: join(OUT, `${label}-2-recovered.png`) });
    check(`${label}: no page errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }
} finally {
  await browser.close();
  writeFileSync(
    join(OUT, "outage-results.json"),
    JSON.stringify(results, null, 2),
  );
}
process.exit(results.every((r) => r.pass) ? 0 : 1);
