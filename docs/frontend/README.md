# Frontend onboarding

Start with a clean TrackMyWealth checkout. This walkthrough uses a local backend and synthetic data to take you from sign-in to a typed account request.

## Start the local stack

Install Docker with Compose and Node.js 22, as used by [Mobile Web CI](../../.github/workflows/mobile-web-ci.yml). For native targets, set up an [Expo SDK 58](https://docs.expo.dev/versions/v58.0.0/) development environment. The iOS simulator requires macOS and Xcode. Android requires Android Studio and a running emulator.

1. From the repository root, follow the [root README's Compose setup](../../README.md#running-on-a-nas-or-any-single-machine-self-hosted-setup) to create your private local `.env`, including a generated `MFA_ENCRYPTION_KEY`, and start PostgreSQL and the backend. Use a local database with no real data.
2. Check the backend with `curl --fail http://localhost:8080/actuator/health`. Wait for `UP` before provisioning a user.
3. In a second terminal, run `cd mobile`, `npm ci`, and `cp .env.example .env`. Set `EXPO_PUBLIC_API_URL` before starting Expo.

| Target           | API URL in `mobile/.env` | Start in `mobile/`         |
| ---------------- | ------------------------ | -------------------------- |
| Web              | `http://localhost:8080`  | `npx expo start --web`     |
| iOS simulator    | `http://localhost:8080`  | `npx expo start --ios`     |
| Android emulator | `http://10.0.2.2:8080`   | `npx expo start --android` |

Use the browser URL printed by Expo. The local CORS defaults cover a web origin on `localhost`. See [Mobile & Web setup](../../mobile/README.md#running-locally) for device networking and [the web section](../../mobile/README.md#web-app) for CORS. Expo bundles `EXPO_PUBLIC_*` values into the client, so put only the backend URL there.

## Create a local demo login

The automated [demo seed in #385](https://github.com/hugiFreezy0815/TrackMyWealth/issues/385) is pending. These steps provision a demo login through the API, then create one account through the app. Use an HTTP client against `http://localhost:8080` and consult the [OpenAPI contract](../../api/openapi.yaml) for each request. Enter generated local-only passwords directly in the client. Keep passwords, tokens and the authenticator setup key out of saved requests, logs and screenshots.

1. On an empty database, send `POST /api/v1/setup/administrator` with `email` (for example, `frontend-admin@example.test`), `password` (at least 12 characters) and `currencyCode` (`CHF`). The response includes an `accessToken`. If the local database is already initialized, obtain a token with `POST /api/v1/auth/login` using its local administrator credentials. If the login response requires MFA, send its `mfaChallengeToken` as `challengeToken` with the current `code` to `POST /api/v1/auth/mfa/verify` and use the returned `accessToken`.
2. Send `POST /api/v1/admin/users` with the administrator's bearer token. Set `email` to `frontend-demo@example.test`, `password` to a separate generated password, `role` to `STANDARD_USER`, and `language` to `EN`. Reuse the member if you already provisioned it.
3. Send `POST /api/v1/auth/login` with the demo member's `email` and `password` to get a member access token from `tokens.accessToken`. Use that token for `POST /api/v1/users/me/mfa/enroll` with the member's `password`. Add the returned `secret` to an authenticator app, then send its current six-digit `code` to `POST /api/v1/users/me/mfa/confirm` with the same bearer token.
4. Sign in to TrackMyWealth with the demo member's email and password and enter the current authenticator code. The app completes the challenge through `POST /api/v1/auth/mfa/verify`. Use a fresh code if the one from enrollment is rejected.
5. On first run, name the overview **Frontend demo** and select CHF. Choose **Add portfolio**, then **Not in the list? Create your own**. Create **Demo Bank** with country Switzerland, type Bank and currency CHF. Add a CHF current account named **Demo Current**, leave the optional identifier blank and record an opening balance of **100.00 CHF** dated today.
6. On Overview, open the **Demo Bank** tab. Verify that **Demo Current** appears and opens Account detail. Turn off **Hide amounts** with the eye control to check the balance.

The API client renews an expired access token through `POST /api/v1/auth/refresh` and retries the original request once. To verify this, leave the signed-in app open until the access token expires (15 minutes by default), then trigger a request using the control for your platform:

- **Web:** Keep the same page open. On the main Overview tab, select **Refresh**. A full browser reload clears the web session.
- **iOS and Android:** Open Account detail before the token expires, then pull to refresh after it expires.

In the network trace, confirm that the original request receives a 401, `POST /api/v1/auth/refresh` succeeds, and the original request succeeds on retry. Verify that the app stays signed in. Keep tokens and authorization headers out of any recorded evidence.

Native restores a saved session when the app restarts. Use **Sign out** to confirm you return to sign-in. See [session handling](../../mobile/src/features/auth/session.ts) and [API client behavior](../../mobile/README.md#api-client).

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

Call this with **Demo Bank**'s portfolio ID from `GET /api/v1/portfolios`, within the signed-in app. The response should include **Demo Current**. The Portfolio View screen itself uses the view endpoint in the map below.

After a contract change, run `npm run api:types`, `npm run api:types:check`, and `npx tsc --noEmit` from `mobile/`. Include the generated files with the contract change. See the [API client README](../../mobile/README.md#api-client) for the generator and transport details.

- Keep monetary values as `DecimalString`, use server-calculated totals, and render amounts through `PrivacyAmount`. See the [screen rules](../ux/screens/README.md#rules-for-every-screen).
- Pass the server version to `typedApi` for writes that require `If-Match`. Follow the [concurrency ADR](../architecture/adr/0004-http-optimistic-concurrency.md) and the screen's conflict flow.
- Handle API problem codes with `problemReaction`. For a paged list, follow the endpoint's sort and page parameters, then refresh from page 0 after filters or data change. See the [API conventions](../api/conventions.md).

## Sprint 7 screen-to-endpoint map

All paths below are relative to `/api/v1`. Paths labeled as backend-only belong to the specs and have frontend integration tracked separately.

| Screen                                                       | Reads                                                                                                                                                                                                                                                                                           | Writes                                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [S1 Overview, setup and Settings](../ux/screens/overview.md) | `GET /workspace`, `GET /portfolios`, `GET /net-worth`, `GET /accounts`, `GET /cash-flow`. Backend-only on main at the time of writing: `GET /net-worth/history` (#496).                                                                                                                         | `PUT /workspace/name`, `PUT /workspace/currency`                                                                                                                                                                                                                                                                                                        |
| [S2 Portfolio View](../ux/screens/portfolio-view.md)         | `GET /portfolios/{portfolioId}/view`, `GET /accounts/{accountId}/positions`. Backend-only on main at the time of writing: `GET /portfolios/{portfolioId}/history` (#496).                                                                                                                       |                                                                                                                                                                                                                                                                                                                                                         |
| [S3 Account detail](../ux/screens/account-detail.md)         | `GET /accounts/{accountId}`, `GET /accounts?portfolioId={portfolioId}`, `GET /accounts/{accountId}/transactions` (also with `uncategorized=true&size=1` for the count), `GET /categories`, `GET /accounts/{accountId}/positions`                                                                | `PUT /accounts/{accountId}/transactions/{transactionId}/category`, `POST /accounts/{accountId}/archive`, `POST /accounts/{accountId}/restore`                                                                                                                                                                                                           |
| [S4 Transaction detail](../ux/screens/transaction-detail.md) | `GET /accounts/{accountId}/transactions/{transactionId}`, `GET /accounts/{accountId}/transactions/{transactionId}/history`, `GET /categories`, `GET /securities/{id}`                                                                                                                           | `PUT /accounts/{accountId}/transactions/{transactionId}/category`, `DELETE /accounts/{accountId}/transactions/{transactionId}/category`, `DELETE /accounts/{accountId}/transactions/{transactionId}`                                                                                                                                                    |
| [S5 Add portfolio](../ux/screens/add-portfolio.md)           | `GET /institutions/catalogue` (search and country filter)                                                                                                                                                                                                                                       | `POST /portfolios`                                                                                                                                                                                                                                                                                                                                      |
| [S6 Add account](../ux/screens/add-account.md)               | `GET /portfolios/{portfolioId}`, `GET /portfolios` (portfolio picker), `GET /accounts/{accountId}/identifier` (reload on identifier conflict)                                                                                                                                                   | `POST /accounts`, `PUT /accounts/{accountId}/identifier`, `POST /accounts/{accountId}/opening-balance`, `POST /accounts/{accountId}/valuations`                                                                                                                                                                                                         |
| [S7 Import statement](../ux/screens/import-statement.md)     | `GET /portfolios`, `GET /accounts`, `GET /accounts/{accountId}`, `GET /accounts/{accountId}/imports`, `GET /accounts/{accountId}/imports/{batchId}`, `GET /accounts/{accountId}/imports/{batchId}/rows`, `GET /accounts/{accountId}/imports/{batchId}/errors.csv`, `GET /import-templates/{id}` | `POST /accounts/{accountId}/imports` (multipart), `POST /accounts/{accountId}/imports/{batchId}/parse`, `POST /accounts/{accountId}/imports/{batchId}/confirm-account`, `PATCH /accounts/{accountId}/imports/{batchId}/rows/{rowNumber}`, `POST /accounts/{accountId}/imports/{batchId}/commit`, `POST /accounts/{accountId}/imports/{batchId}/discard` |

## Find the code and run checks

- [Screen specs](../ux/screens/README.md) cover layout, states, accessibility and acceptance checks. [ADR 0008](../architecture/adr/0008-app-data-and-i18n-stack.md) covers data fetching and translations.
- Routes live in [src/app](../../mobile/src/app), screen implementations in [src/features](../../mobile/src/features), shared UI in [src/components](../../mobile/src/components), design tokens in [theme.ts](../../mobile/src/constants/theme.ts), and EN/DE messages in [src/i18n](../../mobile/src/i18n).
- From `mobile/`, run `npm run api:types:check`, `npx tsc --noEmit`, `npx expo lint`, `npx prettier --check .`, and `npx jest --ci`. Start Expo once before type-checking so it generates the ignored `expo-env.d.ts`.
- [Mobile Web CI](../../.github/workflows/mobile-web-ci.yml) also checks Expo dependencies and exports the web app. [Mobile Native CI](../../.github/workflows/mobile-native-ci.yml) builds and launches the app on a simulator and emulator. See the screen spec for what to verify on web and native.

## Record the onboarding dry run

Before closing [#386](https://github.com/hugiFreezy0815/TrackMyWealth/issues/386), have a frontend developer unfamiliar with the backend follow this guide from a clean checkout. Record the platform, whether provisioning was manual or used #385, and the outcomes for sign-in, MFA, token refresh and the typed request returning **Demo Current**. Fix or file any gaps found during the run.
