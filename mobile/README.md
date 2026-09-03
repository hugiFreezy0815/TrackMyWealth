# TrackMyWealth Mobile & Web

React Native + Expo (SDK 57) client for TrackMyWealth, built with Expo Router (file-based
routing) and TypeScript. See the root [README](../README.md) for how this fits into the rest of
the project.

This one codebase targets iOS, Android, **and web** (via `react-native-web`, already configured).
The web build is deployed independently from the native app-store builds - see "Web app" below -
but it is deliberately the *same* screens and components, not a separate implementation, so the
web experience stays identical to mobile by construction rather than by manual upkeep.

## What's here

```
app.json                 Expo app config (name, icon, splash, plugins)
src/
  app/                    Expo Router routes (file-based routing) - _layout.tsx, index.tsx, ...
  components/             Shared UI components
  constants/              Theme tokens
  hooks/                  Shared hooks (color scheme, theme, ...)
  api/
    client.ts             Thin fetch wrapper around the Spring Boot backend (EXPO_PUBLIC_API_URL)
assets/                   Icons, splash images
.env.example              Copy to .env - backend base URL for local development
```

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

`dist/` can be deployed to any static host (Vercel, Netlify, Cloudflare Pages, an S3 bucket, ...).
`.github/workflows/mobile-web-ci.yml` builds this export on every push/PR touching `mobile/**` as
a build-verification smoke test; wire your host's own deploy step into that workflow (or a
separate one) once you've picked one.

**The backend must allow the web app's origin via CORS**, since a browser enforces same-origin
restrictions that a native app is not subject to - see `backend/src/main/java/.../config/
SecurityConfig.java` and the `CORS_ALLOWED_ORIGINS` environment variable in the root README. The
default (`http://localhost:*`) covers `expo start --web`'s dev server out of the box; add the
deployed web origin (e.g. `https://app.trackmywealth.example`) in every other environment.

## API client

`src/api/client.ts` is a minimal `fetch` wrapper (`api.get`, `api.post`, `api.put`, `api.delete`)
pointed at `EXPO_PUBLIC_API_URL`. It has a placeholder `setAccessToken`/bearer-token hookup ready
for when the auth flow (`docs/user-stories/EPIC-02-user-administration-and-auth.md`) is
implemented on both sides. There is no generated API client yet - once the backend exposes its
OpenAPI spec (`/api-docs`), consider generating typed request/response types from it instead of
hand-written types here.

## Status

This is the initial Expo Router scaffold only - navigation shell, theming, and the API client
plumbing. Screens for the actual product (accounts, transactions, budgets, net worth, ...) are
not yet built; they follow the same epic/story backlog under `docs/user-stories/` that drives the
backend.
