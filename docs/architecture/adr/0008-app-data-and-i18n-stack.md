# ADR 0008: App data layer, i18n and file picking

## Status
Accepted (PO, 2026-11-05, while planning sprint 7 in #387). The chart library stays with #378.
The web-client approach stays with #384 (ADR 0007).

## Context

Sprint 7 builds the first product screens (`docs/ux/screens/`). The app (`mobile/`, Expo 58,
React 19) has a typed API client (`typedApi`, #375), problem-code reactions and enum fallbacks
(#469). It has no:

- **server-state cache.** Each screen would fetch, retry and refresh on its own. The specs need
  a shared cache: S1 reloads after an import, paged lists restart at page 0 after a change
  (conventions.md), and a detail screen renders from the list row first.
- **i18n.** The specs use message keys with complete ICU messages (plural, select, dates,
  amounts) in EN and DE, with "du" (PO, 2026-11-05) and Swiss number formats (`1'234.56`).
  French and Italian must stay addable without code changes (NFR-USE-02a).
- **file picker** for the import flow (S7).

## Decision

1. **Server state: TanStack Query v5 (`@tanstack/react-query`).**
   - Query keys follow the API path, e.g. `['portfolios', id, 'view']`.
   - Mutations invalidate by prefix. A commit, rollback, or account or transaction change
     invalidates `net-worth`, `portfolios`, `accounts` and the affected lists.
   - Paged lists use `useInfiniteQuery`. A refetch after invalidation restarts at page 0, as
     conventions.md requires.
   - Retries follow the problem catalogue's `reaction`: `RETRY` and `RETRY_LATER` (with
     `Retry-After`) are retried, all others are not. `REAUTHENTICATE` goes through the existing
     session rotation.
   - `If-Match` comes from the cached resource's `version`. A `VERSION_CONFLICT` invalidates
     the resource and opens the conflict dialog.
   - Offline: `onlineManager` from `@react-native-community/netinfo` (web: `navigator.onLine`).
     Cached data stays visible.
2. **i18n: FormatJS (`react-intl`).**
   - Messages are ICU, one JSON catalogue per locale under `mobile/src/i18n/`: `en`, `de`
     (with the `de-CH` / `de-DE` differences as overrides). Keys are the ones in the specs.
   - Amounts are formatted with `Intl.NumberFormat` from the `DecimalString`. They are never
     turned into a JS `number` for arithmetic; the string is passed to a formatter that keeps
     every digit (#378 defines the exact output).
   - The locale is the device's, unless the member chose a language (`/users/me` → `language`).
3. **File picking: `expo-document-picker`.** It covers iOS, Android and web (an `<input
   type="file">` underneath). Uploads go through `typedApi` as multipart.

## Consequences

- The foundation story (sprint 7) adds the three libraries and a `QueryClientProvider` and
  `IntlProvider` in the root layout. It also adds a lint rule against literal UI strings in
  `src/features/**`.
- Screens never call `fetch` or `typedApi` from a component; they use query hooks per resource
  in `src/api/queries/`.
- A new locale is a new catalogue file, not a code change.
- Hermes needs the `Intl` polyfills for `PluralRules`, `NumberFormat` and `DateTimeFormat`
  (`@formatjs/intl-*`) on Android. The foundation story adds them and a test that formats a de-CH
  amount and a plural message under the Jest native preset.

## Addendum 2026-11-09: date picking and date display (#488)

PO decisions while integrating the shared components (#488, PR #534) with this stack:

- **Date picking: `@react-native-community/datetimepicker`** (the version Expo SDK 58 expects).
  `DateField` opens the system picker: the Android dialog, and on iOS the inline calendar in a
  bottom sheet. Dates are not typed. The library has no web implementation, so the web build of
  `DateField` (`date-field.web.tsx`) uses the browser's `<input type="date">`. Both emit
  `YYYY-MM-DD`.
- **Date display:** English "1 Oct 2026", German "01.10.2026" (`src/format/date.ts`, built from
  `formatToParts` so the order does not depend on the regional locale data a polyfill ships).
- **Unreadable server dates and amounts** show "–" (with a warning in development builds) instead
  of throwing while a screen renders.
- **One amount formatter:** `src/format/amount.ts`. Components never format amounts or dates
  themselves; the literal-UI-string lint rule also covers `src/components/shared/**`.
