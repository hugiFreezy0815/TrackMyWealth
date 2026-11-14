# Frontend onboarding

Start with a clean TrackMyWealth checkout. This walkthrough uses a local backend and synthetic data to take you from sign-in to a typed account request.

## Start the local stack

Install Docker with Compose, Node.js 22, as used by [Mobile Web CI](../../.github/workflows/mobile-web-ci.yml), and Python 3.10 or newer for the demo seed. macOS includes Python 3.9, so install a newer one, for example with `brew install python`. For native targets, set up an [Expo SDK 58](https://docs.expo.dev/versions/v58.0.0/) development environment. The iOS simulator requires macOS and Xcode. Android requires Android Studio and a running emulator.

1. From the repository root, follow the [root README's Compose setup](../../README.md#running-on-a-nas-or-any-single-machine-self-hosted-setup) to create your private local `.env`, including a generated `MFA_ENCRYPTION_KEY`, and start PostgreSQL and the backend. Use a local database with no real data.
2. Check the backend with `curl --fail http://localhost:8080/actuator/health`. Wait for `UP` before seeding the demo data.
3. In a second terminal, run `cd mobile`, `npm ci`, and `cp .env.example .env`. Set `EXPO_PUBLIC_API_URL` before starting Expo.

| Target           | API URL in `mobile/.env` | Start in `mobile/`         |
| ---------------- | ------------------------ | -------------------------- |
| Web              | `http://localhost:8080`  | `npx expo start --web`     |
| iOS simulator    | `http://localhost:8080`  | `npx expo start --ios`     |
| Android emulator | `http://10.0.2.2:8080`   | `npx expo start --android` |

On an Android 17 (API 37) emulator, Expo Go cannot reach the backend until it has the local network permission, and it does not ask for it. Sign-in then fails with "Could not reach the server". Grant the permission once with `adb shell pm grant host.exp.exponent android.permission.ACCESS_LOCAL_NETWORK`, or use an emulator with Android 16 or older.

Use the browser URL printed by Expo. The local CORS defaults cover a web origin on `localhost`. See [Mobile & Web setup](../../mobile/README.md#running-locally) for device networking and [the web section](../../mobile/README.md#web-app) for CORS. Expo bundles `EXPO_PUBLIC_*` values into the client, so put only the backend URL there.

## Seed the demo data

Seed the stack with synthetic data before you sign in. Follow [Seed a synthetic demo](../../README.md#seed-a-synthetic-demo) in the root README: with the backend up on a fresh, disposable stack, run `python3 scripts/seed_demo.py` from the repository root (Python 3.10+). The seed creates the demo logins, portfolios, accounts, six months of activity and a committed statement import through the public API, and writes the generated logins to `.demo-credentials.json`. Git ignores that file; keep it private and out of logs and screenshots.

1. Add `users.member.totpSecret` from `.demo-credentials.json` to an authenticator app.
2. Sign in to TrackMyWealth with `users.member.email` and `users.member.password`, then enter the current authenticator code. The app completes the challenge through `POST /api/v1/auth/mfa/verify`. If the seed used the current code, wait for the next one. If every code is rejected as invalid or expired, compare the backend container clock with your device clock (`docker compose exec backend date -u`). Docker Desktop's clock can fall minutes behind after the computer sleeps. Restart Docker Desktop to resynchronize it.
3. Overview opens on the seeded workspace, **TrackMyWealth Synthetic Demo**. Turn off **Hide amounts** with the eye control to see the figures. The overview notes that not all values are included, because the seeded depot stays "Value unknown" until holdings are valued.
4. Open the **Demo · Cloudberry Bank** tab. Verify that **Demo · Cloudberry Current** appears and opens Account detail with its transactions.

The API client renews an expired access token through `POST /api/v1/auth/refresh` and retries the original request once. To verify this, leave the signed-in app open until the access token expires (15 minutes by default), then trigger a request using the control for your platform:

- **Web:** Keep the same page open. On the main Overview tab, select **Refresh overview**. A full browser reload clears the web session.
- **iOS and Android:** Open Account detail before the token expires, then pull to refresh after it expires.

In the network trace, confirm that the original request receives a 401, `POST /api/v1/auth/refresh` succeeds, and the original request succeeds on retry. Verify that the app stays signed in. Keep tokens and authorization headers out of any recorded evidence.

Native restores a saved session when the app restarts. Use **Sign out** to confirm you return to sign-in. It is under **More** → **Settings** on phones and under **Settings** in the web sidebar. See [session handling](../../mobile/src/features/auth/session.ts) and [API client behavior](../../mobile/README.md#api-client).

## Make a typed account request

Use the existing query options in [resources.ts](../../mobile/src/api/queries/resources.ts) to read account data in a screen. The underlying request uses the generated response type:

```ts
import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';

export function listAccounts(
  portfolioId: string,
): Promise<components['schemas']['AccountListItemResponse'][]> {
  return typedApi.get('/api/v1/accounts', { query: { portfolioId } });
}
```

Call this with **Demo · Cloudberry Bank**'s portfolio ID from `GET /api/v1/portfolios`, within the signed-in app. The response should include **Demo · Cloudberry Current**. The Portfolio View screen itself uses the view endpoint in the map below.

After a contract change, run `npm run api:types`, `npm run api:types:check`, and `npx tsc --noEmit` from `mobile/`. Include the generated files with the contract change. See the [API client README](../../mobile/README.md#api-client) for the generator and transport details.

- Keep monetary values as `DecimalString`, use server-calculated totals, and render amounts through `PrivacyAmount`. See the [screen rules](../ux/screens/README.md#rules-for-every-screen).
- Pass the server version to `typedApi` for writes that require `If-Match`. Follow the [concurrency ADR](../architecture/adr/0004-http-optimistic-concurrency.md) and the screen's conflict flow.
- Handle API problem codes with `problemReaction`. For a paged list, follow the endpoint's sort and page parameters, then refresh from page 0 after filters or data change. See the [API conventions](../api/conventions.md).

## Sprint 7 screen-to-endpoint map

All paths below are relative to `/api/v1`.

| Screen                                                       | Reads                                                                                                                                                                                                                                                                                           | Writes                                                                                                                                                                                                                                                                                                                                                                                              |
| ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [S1 Overview, setup and Settings](../ux/screens/overview.md) | `GET /workspace`, `GET /portfolios`, `GET /net-worth`, `GET /accounts`, `GET /cash-flow`, `GET /net-worth/history`                                                                                                                                                                              | `PUT /workspace/name`, `PUT /workspace/currency`                                                                                                                                                                                                                                                                                                                                                    |
| [S2 Portfolio View](../ux/screens/portfolio-view.md)         | `GET /portfolios/{portfolioId}/view`, `GET /accounts/{accountId}/positions`, `GET /portfolios/{portfolioId}/history`                                                                                                                                                                            |                                                                                                                                                                                                                                                                                                                                                                                                     |
| [S3 Account detail](../ux/screens/account-detail.md)         | `GET /accounts/{accountId}`, `GET /accounts?portfolioId={portfolioId}`, `GET /accounts/{accountId}/transactions` (also with `uncategorized=true&size=1` for the count), `GET /categories`, `GET /accounts/{accountId}/positions`                                                                | `PUT /accounts/{accountId}/transactions/{transactionId}/category`, `POST /accounts/{accountId}/archive`, `POST /accounts/{accountId}/restore`, `PUT /accounts/{accountId}/savings-pocket`                                                                                                                                                                                                           |
| [S4 Transaction detail](../ux/screens/transaction-detail.md) | `GET /accounts/{accountId}/transactions/{transactionId}`, `GET /accounts/{accountId}/transactions/{transactionId}/history`, `GET /categories`, `GET /securities/{id}`                                                                                                                           | `PUT /accounts/{accountId}/transactions/{transactionId}/category`, `DELETE /accounts/{accountId}/transactions/{transactionId}/category`, `DELETE /accounts/{accountId}/transactions/{transactionId}`                                                                                                                                                                                                |
| [S5 Add portfolio](../ux/screens/add-portfolio.md)           | `GET /institutions/catalogue` (search and country filter)                                                                                                                                                                                                                                       | `POST /portfolios`                                                                                                                                                                                                                                                                                                                                                                                  |
| [S6 Add account](../ux/screens/add-account.md)               | `GET /portfolios/{portfolioId}`, `GET /portfolios` (portfolio picker), `GET /accounts/{accountId}/identifier` (reload on identifier conflict)                                                                                                                                                   | `POST /accounts`, `PUT /accounts/{accountId}/identifier`, `POST /accounts/{accountId}/opening-balance`, `POST /accounts/{accountId}/valuations`                                                                                                                                                                                                                                                     |
| [S7 Import statement](../ux/screens/import-statement.md)     | `GET /portfolios`, `GET /accounts`, `GET /accounts/{accountId}`, `GET /accounts/{accountId}/imports`, `GET /accounts/{accountId}/imports/{batchId}`, `GET /accounts/{accountId}/imports/{batchId}/rows`, `GET /accounts/{accountId}/imports/{batchId}/errors.csv`, `GET /import-templates/{id}` | `POST /accounts/{accountId}/imports` (multipart), `POST /accounts/{accountId}/imports/{batchId}/parse`, `POST /accounts/{accountId}/imports/{batchId}/confirm-account`, `PATCH /accounts/{accountId}/imports/{batchId}/rows/{rowNumber}`, `POST /accounts/{accountId}/imports/{batchId}/commit`, `POST /accounts/{accountId}/imports/{batchId}/discard`, `PUT /accounts/{accountId}/savings-pocket` |

## Find the code and run checks

- [Screen specs](../ux/screens/README.md) cover layout, states, accessibility and acceptance checks. [ADR 0008](../architecture/adr/0008-app-data-and-i18n-stack.md) covers data fetching and translations.
- Routes live in [src/app](../../mobile/src/app), screen implementations in [src/features](../../mobile/src/features), shared UI in [src/components](../../mobile/src/components), design tokens in [theme.ts](../../mobile/src/constants/theme.ts), and EN/DE messages in [src/i18n](../../mobile/src/i18n).
- From `mobile/`, run `npm run api:types:check`, `npx tsc --noEmit`, `npx expo lint`, `npx prettier --check .`, and `npx jest --ci`. Start Expo once before type-checking so it generates the ignored `expo-env.d.ts`.
- [Mobile Web CI](../../.github/workflows/mobile-web-ci.yml) also checks Expo dependencies and exports the web app. [Mobile Native CI](../../.github/workflows/mobile-native-ci.yml) builds and launches the app on a simulator and emulator. See the screen spec for what to verify on web and native.

## Record the onboarding dry run

Before closing [#386](https://github.com/hugiFreezy0815/TrackMyWealth/issues/386), have a frontend developer unfamiliar with the backend follow this guide from a clean checkout. Record the platform, whether the demo seed ran cleanly, and the outcomes for sign-in, MFA, token refresh and the typed request returning **Demo · Cloudberry Current**. Fix or file any gaps found during the run.
