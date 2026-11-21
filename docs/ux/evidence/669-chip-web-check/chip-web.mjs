// #669: the change chip (amount · return) and its label stay inside the card at every width.
// Synthetic demo data (scripts/seed_demo.py); history responses are rewritten so returnFrom lies
// after from and a return is shown; DE via the member's /users/me language. Widths 320 and 640 px
// equal 200 % browser zoom on 640 and 1280 px windows (react-native-web sizes text in CSS px).
//   CREDS=<seed credentials.json> APP=http://localhost:<web> API=http://localhost:<api> OUT=<dir> node chip-label-web.mjs
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import AxeBuilder from "@axe-core/playwright";
import { chromium } from "playwright-core";

const { CREDS, APP, API, OUT } = process.env;
const partner = JSON.parse(readFileSync(CREDS, "utf8")).users.partner;
mkdirSync(OUT, { recursive: true });
const results = [];

async function shiftReturn(route) {
  const response = await route.fetch();
  const body = await response.json();
  const from = new Date(body.from);
  from.setUTCDate(from.getUTCDate() + 3);
  body.change.returnFrom = from.toISOString().slice(0, 10);
  body.change.returnPercent = "-12.34";
  await route.fulfill({ response, json: body });
}

const browser = await chromium.launch({ channel: "chrome", headless: true });
try {
  for (const lang of (process.env.LANGS ?? "EN,DE").split(",")) {
    for (const width of [320, 390, 640, 1280]) {
      const context = await browser.newContext({
        locale: lang === "DE" ? "de-CH" : "en-GB",
        viewport: { width, height: 900 },
      });
      const page = await context.newPage();
      await page.route("**/api/v1/net-worth/history**", shiftReturn);
      await page.route("**/api/v1/portfolios/*/history**", shiftReturn);
      await page.route("**/api/v1/users/me", async (route) => {
        if (route.request().method() !== "GET") return route.fallback();
        const response = await route.fetch();
        const body = await response.json();
        body.language = lang;
        await route.fulfill({ response, json: body });
      });
      await page.goto(APP);
      await page.locator("input").nth(0).fill(API);
      await page.locator("input").nth(1).fill(partner.email);
      await page.locator("input[type=password]").first().fill(partner.password);
      await page.locator("input[type=password]").first().press("Enter");
      const since = lang === "DE" ? /seit \d/ : /since \d/;
      for (const period of ["1Y", "MAX"]) {
        const short = (lang === "DE" ? { "1Y": "1J", MAX: "Max" } : { "1Y": "1Y", MAX: "Max" })[period];
        const button = page.getByRole("radio", { name: new RegExp(`^${short},`) });
        await button.first().click({ timeout: 30000 });
        const label = page.getByText(since).first();
        await label.waitFor({ timeout: 30000 });
        await page.waitForTimeout(500);
        const m = await label.evaluate((el) => {
          const row = el.parentElement;
          const chip = row.firstElementChild;
          const l = el.getBoundingClientRect();
          const c = chip.getBoundingClientRect();
          const r = row.getBoundingClientRect();
          return {
            text: el.textContent,
            labelRight: Math.round(l.right),
            rowRight: Math.round(r.right),
            rowOverflow: row.scrollWidth - row.clientWidth,
            pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
            labelLines: Math.round(l.height / parseFloat(getComputedStyle(el).lineHeight || "20")),
            labelBelowChip: l.top >= c.bottom - 1,
            chipRight: Math.round(c.right),
            chipHeight: Math.round(c.height),
          };
        });
        const pass =
          m.rowOverflow <= 0 &&
          m.pageOverflow <= 0 &&
          m.chipRight <= m.rowRight + 1 &&
          m.labelRight <= m.rowRight + 1;
        results.push({ lang, width, period, pass, ...m });
        console.log(`${pass ? "PASS" : "FAIL"} ${lang} ${width}px ${period}`, JSON.stringify(m));
        await label.scrollIntoViewIfNeeded();
        await page.screenshot({ path: join(OUT, `${lang}-${width}-${period}.png`) });
        if (period === "1Y" && (width === 320 || width === 1280)) {
          const { violations } = await new AxeBuilder({ page })
            .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
            .analyze();
          const ids = violations.map((v) => `${v.id}(${v.nodes.length})`).join(", ");
          results.push({ lang, width, period: "axe", pass: true, axe: ids });
          console.log(`AXE ${lang} ${width}px: ${ids || "none"}`);
        }
      }
      await context.close();
    }
  }
} finally {
  await browser.close();
  writeFileSync(join(OUT, "results.json"), JSON.stringify(results, null, 2));
}
process.exit( results.every((r) => r.pass) ? 0 : 1);
