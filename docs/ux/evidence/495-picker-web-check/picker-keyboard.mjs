// Run from a directory with playwright-core installed. Export the app with
// EXPO_PUBLIC_API_URL=http://localhost:8080 before running this check.
// Set CHROMIUM_EXECUTABLE to use an installed Chromium binary.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const { chromium } = createRequire(resolve("package.json"))("playwright-core");
const dist = new URL("../../../../mobile/dist/", import.meta.url);
const api = "http://localhost:8080/api/v1";
const accountPath = "/accounts/demo-account";
const detailPath = accountPath + "/transactions/demo-transaction";
const account = {
  id: "demo-account",
  name: "Demo account",
  status: "ACTIVE",
  nativeCurrency: "CHF",
};
const transaction = {
  id: "demo-transaction",
  accountId: account.id,
  accountName: account.name,
  portfolioName: "Demo bank",
  transactionType: "CREDIT_CARD_PURCHASE",
  bookingDate: "2026-11-08",
  amount: "-84.35",
  currency: "CHF",
  merchantDescription: "Demo market",
  source: "CSV",
  categoryId: "groceries",
  categoryAssignedBy: "RULE",
  removal: "VOID",
  version: 7,
  warnings: [],
};
const categories = [
  ["food", "FOOD", "Food", null],
  ["groceries", "GROCERIES", "Groceries", "food"],
  ["dining", "DINING", "Dining out", "food"],
].map(([id, code, nameEn, parentId]) => ({
  id,
  code,
  nameEn,
  nameDe: nameEn,
  parentId,
  active: true,
}));
const fixtures = {
  "/auth/login": {
    mfaRequired: false,
    tokens: {
      accessToken: "fixture-access",
      refreshToken: "fixture-refresh",
      tokenType: "Bearer",
      expiresInSeconds: 900,
    },
  },
  "/users/me": {
    userId: "member",
    role: "STANDARD_USER",
    language: "EN",
    sessionId: "demo",
  },
  "/workspace": { id: "demo", name: "Demo", currency: "CHF", version: 1 },
  "/portfolios": [
    {
      id: "portfolio",
      name: "Demo bank",
      personalAssetsDefault: false,
      activeAccountCount: 1,
    },
  ],
  [accountPath]: account,
  [detailPath]: transaction,
  [detailPath + "/history"]: {
    content: [],
    page: { number: 0, totalPages: 1, totalElements: 0, size: 50 },
  },
  "/categories": categories,
};
const mime = {
  html: "text/html",
  js: "text/javascript",
  css: "text/css",
  woff2: "font/woff2",
};
const server = createServer(async (request, response) => {
  const { pathname } = new URL(request.url, "http://localhost");
  const file =
    pathname === detailPath
      ? "/accounts/[accountId]/transactions/[transactionId].html"
      : pathname;
  try {
    const body = await readFile(new URL("." + file, dist));
    response.writeHead(200, {
      "content-type":
        mime[file.split(".").at(-1)] ?? "application/octet-stream",
    });
    response.end(body);
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
const origin = "http://127.0.0.1:" + server.address().port;

async function withPicker({ width, height, zoom = 1, saveStatus }, check) {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_EXECUTABLE,
    args: process.env.CHROMIUM_SINGLE_PROCESS
      ? ["--single-process", "--no-zygote"]
      : [],
  });
  const refetch = Promise.withResolvers();
  try {
    const page = await browser.newPage({
      locale: "en-GB",
      viewport: { width, height },
    });
    page.setDefaultTimeout(8000);
    let attemptedSave = false;
    await page.route(api + "/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname.replace("/api/v1", "");
      const headers = {
        "access-control-allow-origin": origin,
        "access-control-allow-headers": "*",
        "access-control-allow-methods": "*",
      };
      if (request.method() === "OPTIONS")
        return route.fulfill({ status: 204, headers });
      if (path === detailPath + "/category" && request.method() === "PUT") {
        attemptedSave = true;
        return route.fulfill({
          headers,
          status: saveStatus,
          contentType: "application/problem+json",
          json: {
            code: saveStatus === 412 ? "VERSION_CONFLICT" : "INTERNAL_ERROR",
            status: saveStatus,
            title: "Fixture save error",
          },
        });
      }
      if (path === detailPath && attemptedSave && saveStatus === 412) {
        await refetch.promise;
        return route.fulfill({ headers, json: { ...transaction, version: 8 } });
      }
      if (Object.hasOwn(fixtures, path))
        return route.fulfill({ headers, json: fixtures[path] });
      return route.fulfill({ headers, status: 404 });
    });

    await page.goto(origin + detailPath);
    await page.getByLabel("Email", { exact: true }).fill("picker@example.test");
    await page
      .getByLabel("Password", { exact: true })
      .fill("synthetic-demo-password");
    await page
      .getByRole("button", { name: "Sign in", exact: true })
      .last()
      .click();
    const trigger = page.getByRole("button", {
      name: "Change category",
      exact: true,
    });
    await trigger.waitFor();
    if (zoom !== 1) {
      // CSS zoom exercises dialog reflow in headless Chromium, which has no browser zoom UI.
      await page.evaluate((factor) => {
        document.documentElement.style.zoom = factor;
      }, zoom);
    }
    await trigger.focus();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog", { name: "Choose a category" });
    await dialog.waitFor();
    await check({ page, dialog, trigger, releaseRefetch: refetch.resolve });
  } finally {
    refetch.resolve();
    await browser.close();
  }
}

async function assertFocused(locator) {
  const focus = await locator.evaluate((node) => ({
    matches: document.activeElement === node,
    actual: document.activeElement?.outerHTML.split(">")[0],
  }));
  assert.equal(
    focus.matches,
    true,
    "Expected focus on " + locator + ", found " + focus.actual,
  );
}

async function checkPickerControls({ page, dialog }) {
  const search = dialog.getByRole("textbox", { name: "Search categories" });
  const groceries = dialog.getByRole("radio", { name: "Food › Groceries" });
  const dining = dialog.getByRole("radio", { name: "Food › Dining out" });
  const save = dialog.getByRole("button", { name: "Save category" });
  const cancel = dialog.getByRole("button", { name: "Cancel" });
  await assertFocused(search);
  await page.keyboard.press("Shift+Tab");
  await assertFocused(cancel);
  await page.keyboard.press("Tab");
  await assertFocused(search);
  await page.keyboard.press("Tab");
  await assertFocused(groceries);
  await page.keyboard.press("ArrowDown");
  await assertFocused(dining);
  assert.equal(await dining.isChecked(), true);
  await page.keyboard.press("Tab");
  await assertFocused(save);
  await page.keyboard.press("Shift+Tab");
  await assertFocused(dining);
  await page.keyboard.press("Shift+Tab");
  await assertFocused(search);
  await page.keyboard.type("Groceries");
  await page.keyboard.press("Tab");
  await assertFocused(groceries);
  assert.equal(await groceries.isChecked(), false);
  await page.keyboard.press("Space");
  assert.equal(await groceries.isChecked(), true);
  await page.keyboard.press("Tab");
  await page.keyboard.press("Enter");
  await dialog.getByText(/Couldn't save the category/).waitFor();
  await page.keyboard.press("Tab");
  await assertFocused(cancel);
  await page.keyboard.press("Shift+Tab");
  await assertFocused(save);
  await page.keyboard.press("Shift+Tab");
  await assertFocused(groceries);
  await page.keyboard.press("Shift+Tab");
  await assertFocused(search);
  for (const control of [search, groceries, save, cancel]) {
    await control.scrollIntoViewIfNeeded();
    const box = await control.boundingBox();
    assert.ok(
      box && box.y >= 0 && box.y + box.height <= page.viewportSize().height,
    );
  }
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden" });
}

async function checkDiscard({ page, dialog, trigger, releaseRefetch }) {
  await page.keyboard.press("Tab");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Tab");
  await Promise.all([
    page.waitForRequest(
      (request) =>
        request.method() === "GET" && request.url() === api + detailPath,
    ),
    page.keyboard.press("Enter"),
  ]);
  await dialog.waitFor({ state: "hidden" });
  const conflict = page.getByRole("dialog", {
    name: "This item was changed meanwhile",
  });
  await conflict.waitFor();
  assert.equal(await trigger.isDisabled(), true);
  await assertFocused(
    conflict.getByRole("button", { name: "Review and save again" }),
  );
  await page.keyboard.press("Tab");
  await assertFocused(
    conflict.getByRole("button", { name: "Discard my edit" }),
  );
  await page.keyboard.press("Enter");
  await conflict.waitFor({ state: "hidden" });
  const heading = page.getByRole("heading", { name: "Demo market" });
  await heading.evaluate(
    () => new Promise((ready) => requestAnimationFrame(() => ready())),
  );
  await assertFocused(heading);
  releaseRefetch();
}

try {
  for (const size of [
    { width: 568, height: 240 },
    { width: 880, height: 480, zoom: 2 },
  ]) {
    await withPicker({ ...size, saveStatus: 500 }, checkPickerControls);
    console.log("Picker controls passed", size);
  }
  await withPicker({ width: 568, height: 500, saveStatus: 412 }, checkDiscard);
  console.log(
    "Conflict discard with a pending refetch restored focus to the detail heading",
  );
} finally {
  server.close();
}
