# TrackMyWealth Mobile & Web

React Native + Expo (SDK 58) client for TrackMyWealth, built with Expo Router (file-based
routing) and TypeScript. See the root [README](../README.md) for how this fits into the rest of
the project.

This one codebase targets iOS, Android, **and web** (via `react-native-web`, already configured).
The web build is deployed independently from the native app-store builds - see "Web app" below -
but it is deliberately the _same_ screens and components, not a separate implementation, so the
web experience stays identical to mobile by construction rather than by manual upkeep.

## What's here

```
app.json                 Expo app config (name, icon, splash, plugins)
src/
  app/                    Expo Router routes (file-based routing) - _layout.tsx, index.tsx, ...
  components/             Shared UI components
  constants/              Theme tokens (theme.test.ts checks every text pair for WCAG AA)
  features/shell/         App shell: navigation per layout, offline banner, ShellScreen
  features/privacy/       Hide amounts, PrivacyAmount, app-switcher protection
  i18n/                   react-intl provider and the EN/DE catalogues (keys must match)
  hooks/                  Shared hooks (color scheme, theme, ...)
  api/
    client.ts             Transport: fetch, token refresh, timeouts, ApiError/NetworkError
    typed-api.ts          typedApi - every call typed from the contract (#375)
    schema.d.ts           Generated from api/openapi.yaml - never edit by hand
    problem-codes.generated.ts  Generated from api/problem-codes.json - never edit by hand
    problems.ts           problemReaction(): what to do about an error response
assets/                   Icons, splash images
.env.example              Copy to .env - backend base URL for local development
```

## SDK 58 upgrade notes

This project uses Expo SDK 58, which pins **React Native 0.88.0-rc.4** (a release candidate),
Reanimated 4.7.0 and Worklets 0.13.0. Versions follow Expo's bundled-module list
(`node_modules/expo/bundledNativeModules.json`); after changing a dependency, run
`npx expo install --check` (CI runs it too). Jest and `@types/jest` are deliberately on Jest 30
and excluded from that check via `expo.install.exclude` in `package.json`.

`.npmrc` temporarily enables `legacy-peer-deps`: npm never matches a prerelease against ranges
such as `>=0.78`, so packages with a `react-native` peer range reject the release candidate, and
`overrides` cannot rewrite peer ranges. Remove it once Expo SDK 58 ships a stable React Native
0.88. New releases must be 7 days old before they are resolved: `min-release-age` in `.npmrc`
(npm 11.10+, local installs) and Dependabot's cooldown (`.github/dependabot.yml`).

Mobile Web CI verifies `npm ci`, the Expo version check, TypeScript, ESLint, Prettier, Jest and
the static web export. Mobile Native CI builds the iOS and Android release apps and launches them
on a simulator and an emulator (`scripts/native-smoke.sh`) whenever the native dependency set or
the app config changes; check its `native-smoke-*` screenshots before marking an upgrade ready.
Building iOS locally needs **Xcode 26** (Expo SDK 58 requires Swift 6.2). A test on a physical
device is still advisable before a production rollout. Use `npm ci` for reproducible installs.

## Running locally

The backend and PostgreSQL must be running first - see the root README.

```bash
npm install
cp .env.example .env      # adjust EXPO_PUBLIC_API_URL if needed (see comments in the file)
npx expo start
```

From the Expo CLI output you can launch an iOS simulator, Android emulator, Expo Go on a
physical device, or the web target.

Notes on reaching the backend from each target:

- **iOS simulator / web**: `http://localhost:8080` works as-is.
- **Android emulator**: use `http://10.0.2.2:8080` instead of `localhost`.
- **Physical device (Expo Go)**: use your machine's LAN IP, e.g. `http://192.168.1.23:8080`, and
  make sure the device is on the same network.

## Web app

The web target is built as a static site and deployed on its own, independent of the iOS/Android
app-store release cycle:

```bash
npx expo export -p web    # -> dist/ (static HTML/JS/CSS)
```

The reference deployment uses nginx to serve the export and apply the security headers.
On a host with nginx and Python 3 installed, run the following from `mobile/`:

```bash
prefix="$(pwd)/web-host"
mkdir -p "$prefix"
cp deploy/nginx/nginx.conf /etc/nginx/mime.types "$prefix/"
ln -s "$(pwd)/dist" "$prefix/html"
python3 deploy/nginx/write-csp.py dist "$prefix/content-security-policy.conf" \
  --api-origin https://api.example.com
nginx -p "$prefix/" -c "$prefix/nginx.conf"
bash deploy/nginx/check-headers.sh http://127.0.0.1:8082 dist
```

Put the listener behind an HTTPS reverse proxy and redirect public HTTP requests to HTTPS.
Preserve the upstream headers and keep the web and API origins separate. HSTS applies to the app
hostname and its subdomains. Set `EXPO_PUBLIC_API_URL` when exporting to set the default server shown
at sign-in. Repeat `--api-origin` for each server members may enter and use the origin without any
path. Loopback HTTP origins are supported for local testing. Regenerate the policy with every
export and deploy the export and its policy together. Reload nginx after updating the policy.

HTML and errors use `Cache-Control: no-store`. Fingerprinted Expo JS and CSS use immutable
caching. All responses carry the CSP, `Referrer-Policy: no-referrer`,
`Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
and a `Permissions-Policy` that disables camera, microphone, geolocation and payment. If you use
another host, apply the same headers and dynamic-route rewrites there. Mobile Web CI serves each
export through the reference config and checks the HTTP responses. The CSP hashes the Expo
hydration script and static style attributes. It allows inline style elements for Expo's
runtime-generated styles, including its mobile viewport adjustments.

**The backend must allow the web app's origin via CORS**, since a browser enforces same-origin
restrictions that a native app is not subject to - see `backend/src/main/java/.../config/
SecurityConfig.java` and the `CORS_ALLOWED_ORIGINS` environment variable in the root README. The
default (`http://localhost:*`) covers `expo start --web`'s dev server out of the box; add the
deployed web origin (e.g. `https://app.trackmywealth.example`) in every other environment.

## API client

The app's request and response types come from the committed contract `api/openapi.yaml`, never
by hand (US-29-05, #375). A backend change therefore shows up in the app as a type error, not as a
broken screen.

- **Generate:** `npm run api:types` writes `src/api/schema.d.ts` (openapi-typescript) and
  `src/api/problem-codes.generated.ts` (from `api/problem-codes.json`). Run it after the backend
  changed the contract, then `npx tsc --noEmit` lists every place in the app the change affects.
  Commit the generated files; never edit them by hand.
- **CI:** Mobile Web CI runs on every change to `mobile/`, `api/openapi.yaml` or
  `api/problem-codes.json`. `npm run api:types:check` fails, naming the file, when a generated file
  is stale. A backend PR that changes the contract regenerates the app's types in the same PR.
- **Calls:** `typedApi` (`src/api/typed-api.ts`) takes a contract path and checks the path
  parameters, query, JSON body and response against it. An operation that requires `If-Match`
  takes its `version`, which is sent as `If-Match: "<version>"`:

  ```ts
  const me = await typedApi.get('/api/v1/users/me');
  await typedApi.put('/api/v1/accounts/{accountId}/identifier/from-import', {
    params: { accountId },
    version,
    body: { importAccountId, batchId },
  });
  ```

  Name a schema type as `components['schemas']['AccountIdentifierResponse']` (from
  `@/api/schema`). The untyped `api` and `request` in `client.ts` are the transport only; ESLint
  rejects them outside `src/api`.

- **Transport:** `client.ts` sends JSON with the access token, renews the session once on a 401
  (never for `/api/v1/auth/*`), aborts after 15 s with a `NetworkError`, and throws `ApiError`
  (status and body) for any other failure.
- **Decimals:** every `format: decimal` field is `DecimalString` (`src/api/decimal.ts`), never
  `number`; the generator fails if one is not. Keep the string as received and send it back
  unchanged.
- **Errors:** problem codes are an open set (#374): the server may send a code this app version
  does not know. `problemReaction(error)` (`src/api/problems.ts`) gives a known code's own
  reaction from the contract, and an unknown one a reaction by HTTP status: 401 sign in again,
  403 not allowed, 409/412 reload, another 4xx the generic error, 5xx the generic error with the
  `correlationId` to report. It never retries a request because of an unknown code.
  `KnownProblemCode` and `KnownImportRowCode` list the known codes for exhaustive switches;
  `ProblemCode` and `ImportRowCode` also accept any other string. For an unknown import row code,
  show the row's `message`, which the server has already translated.

## App shell and screen protection (US-01-13)

- **Navigation** follows docs/ux/01-information-architecture.md: phone and tablet (<1024 px) show
  bottom tabs Overview + More, desktop (>=1024 px) a 248 px sidebar with Overview + Settings.
  Breakpoints are viewport widths (`layoutFor`), never the platform. Future areas are absent, not
  disabled. The administrator, never a workspace member, gets Reference data instead of Overview
  and lands there (`homeFor`); the screen and the server check the role again.
- **Screens** use `ShellScreen` (title, eye control, optional 360 px detail panel). A route whose
  screen story has not shipped renders `UnavailableScreen`: no data, a way back to Overview.
- **Money** is rendered with `PrivacyAmount` only; ESLint fails a direct `formatAmount` import
  outside it, so every amount honours "Hide amounts". The preference is device-local (secure store
  on native, localStorage on web) and loads hidden-first, so no amount flashes on start-up.
- **App switcher** (FR-CLI-005) is protected natively while signed in, via `expo-screen-capture`:
  iOS blurs the window before the snapshot, Android sets `FLAG_SECURE` (recents shows a blank
  card; the flag also blocks screenshots and recordings of the app). The package's storage
  permissions (its pre-Android-14 screenshot listener) are blocked in app.json.
  `DETECT_SCREEN_CAPTURE` must stay: on Android 14+ the module registers a screen-capture
  callback at start-up and crashes the app without it (install-time, no prompt, no data access). Web cannot protect browser
  thumbnails; Hide amounts is the protection there.
- **Sign-out** moves to /overview, ends the session and, on web, loads a fresh document, so
  nothing of the session stays in memory. A signed-out page that the browser restores from its
  back-forward cache is reloaded. A page cannot erase browser history: earlier URLs (IDs only)
  stay, and opening one asks for sign-in. Restored documents check a device-local sign-out marker
  before reusing the in-memory session.
- **Copy** comes from `src/i18n/*.json` via react-intl. ESLint fails literal UI strings in
  `src/app` and `src/features` (legacy reference-data and imports migrate with their tickets).

## Status

The app shell, sign-in, theming and the API client plumbing are in place. Screens for the actual
product (accounts, transactions, budgets, net worth, ...) are not yet built; they follow the same epic/story backlog under `docs/user-stories/` that drives the
backend.
