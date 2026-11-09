# UX-03 — Visual foundations and component inventory (#378)

**Status:** Proposed for independent review and PO approval; implementation-ready visual contract, **not** proof that Sprint 7 screens have already been built.  
**Figma source:** [TrackMyWealth design](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth), **📘 Read me + Design System** (colour `38:324`, typography `38:468`, components `14:2`), [mobile EN `21:123`](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=21-123) and [web EN `29:123`](https://www.figma.com/design/5iqhBoU58DIwEokRNL1Cbw/TrackMyWealth?node-id=29-123).  
**Reference:** [Open the static light/dark gallery](02-visual-foundations.html) (with [CSS tokens](02-visual-foundations.css)). `node docs/ux/check-visual-contrast.mjs` checks the WCAG AA colour pairings and `node docs/ux/check-amount-format.mjs` the normative amount strings; CI (**UX docs**) runs both on every change under `docs/ux/`.

## Sources, precedence and scope

Figma's **Read me + Design System** defines visual foundations and component examples.
The accepted [UX-01 information architecture](01-information-architecture.md),
[UX-02 Sprint 7 screen specs](screens/README.md) and their S1–S7 source-to-field mappings
override outdated *content or navigation* shown in Figma. Figma's old desktop sidebar
entries for Transactions, Budget and Import are not a license to add them in the MVP:
the approved navigation is desktop **Overview + Settings** and mobile **Overview + More**,
with portfolio tabs inside the Overview. Never use screenshots as production UI assets.

The preview is **static, synthetic documentation**. It does not depend on authentication
or access a server, and it is not a hidden Expo route. This ticket establishes design tokens,
formatting, component specifications and a chart approach. Actual component implementations
and integration tests belong to Sprint 7 [#486](https://github.com/hugiFreezy0815/TrackMyWealth/issues/486),
[#488](https://github.com/hugiFreezy0815/TrackMyWealth/issues/488) and the screen stories.
All app display strings must use translated message keys from [#379](https://github.com/hugiFreezy0815/TrackMyWealth/issues/379).

## 1. Palette and semantic tokens

**Canonical implementation names:** Figma variable `bg/canvas` maps to CSS `--bg-canvas`
and, in Sprint 7, to a matching typed theme token in Expo: #488 replaces the Expo template
colours in `mobile/src/constants/theme.ts` with these roles (light and dark), keeping the file as
the single theme entry point. Use **semantic roles** rather than embedding hex values in components. The table is transcribed from Figma
design-system swatches `38:324` on 2026-11-06. Dark-mode screen *frames have not
been drawn* in Figma; the dark values are the documented theme variant, not a
pixel-matched dark screenshot.

| Token (Figma naming) | Light | Dark | Use |
| --- | --- | --- | --- |
| `bg/canvas` | `#F4F7F8` | `#07111F` | App/page background |
| `bg/surface` | `#FFFFFF` | `#0E1B2E` | Cards, dialogs |
| `bg/subtle` | `#ECF1F4` | `#152740` | Quiet panels, table headings |
| `bg/inverse` | `#0B1C3F` | `#EEF3F7` | Inverted pills and selection |
| `border/default` | `#E1E7EC` | `#22344D` | Decorative separators |
| `border/strong` | `#C6D0D9` | `#2F4663` | Decorative stronger border |
| `border/interactive` *(added)* | `#64758A` | `#9FB0C4` | Control boundary, ≥3:1 on canvas and surface |
| `text/primary` | `#0B1C3F` | `#EEF3F7` | Main text, amount |
| `text/secondary` | `#53627A` | `#9FB0C4` | Secondary labels |
| `text/tertiary` | `#5E6D83` | **`#92A4B9`** | Metadata; dark corrected for AA |
| `text/on-brand` | `#FFFFFF` | **`#07111F`** | Text on teal primary button; dark corrected |
| `brand/primary` | `#00857A` | `#2DD4BF` | Teal button background |
| `brand/primary-hover` | `#006D64` | `#5EEAD4` | Teal hovered background, hovered link |
| `brand/link` *(added)* | `#006D64` | `#2DD4BF` | Link text on canvas and surface |
| `brand/subtle` | `#E0F5F1` | `#0E3A38` | Brand-tinted surface |
| `status/gain` | `#0F7A3A` | `#4ADE80` | Gain/positive text with +/arrow |
| `status/gain-subtle` | `#E3F7E8` | `#12301C` | Gain badge background |
| `status/loss` | `#C42F37` | `#FF6B70` | Loss/negative text with −/arrow |
| `status/loss-subtle` | `#FDECEC` | `#3A1A1C` | Loss badge background |
| `status/warning` | `#8F5200` | `#F5B544` | Unreconciled/incomplete warning text |
| `status/warning-subtle` | `#FFF3D9` | `#3A2C10` | Warning background |
| `status/info` | `#1F5CC4` | `#6AA5FF` | Estimated/info/focus |
| `status/info-subtle` | `#E6EFFF` | `#18294A` | Info background |
| `chart/1–6` | **`#00A08F`**, `#0A3D7A`, **`#B7802A`**, `#E5677E`, `#6C8EF5`, `#8B5CF6` | `#2DD4BF`, `#5B8DEF`, `#F5BD62`, `#F08A9C`, `#93A8F8`, `#A78BFA` | Chart series in this order, up to six; light 1 and 3 corrected |
| `chart/other` | **`#868D9C`** | **`#646C80`** | Seventh/composite "Other"; corrected |
| `brand/navy` | `#0B1C3F` | `#0B1C3F` | Brand artwork, **not automatically text** |
| `brand/deep` | `#0A3D7A` | `#1E4F99` | Brand/decorative |
| `brand/mint` | `#14D3B0` | `#5EEAD4` | Brand/decorative |

**Accessibility overrides (deliberate departures from the current Figma dark swatches):**

- Figma dark `text/tertiary = #6C7F96` on `bg/surface = #0E1B2E` gives **4.21:1**:
  below **4.5:1** for ordinary 12–14 px text. The implementation theme uses `#92A4B9`
  (**6.78:1** on that surface).
- Figma dark `text/on-brand = #FFFFFF` on `brand/primary = #2DD4BF` gives **1.86:1**.
  The implementation uses `#07111F` (**10.17:1**); preserve the teal background.
- Figma light `brand/primary = #00857A` gives **4.53:1** on white but only **4.21:1**
  on canvas. Use **`brand/link = #006D64` on canvas** (dark `#2DD4BF`) and keep
  the original `brand/primary` as the button fill.
- The Figma `border/default` / `border/strong` strokes are decorative and **not**
  sufficient alone to identify interactive elements (WCAG 1.4.11). The gallery adds
  `border/interactive = #64758A` (light) / `#9FB0C4` (dark), giving
  **at least 3:1** against the corresponding surface; keep a visible focus indicator.
- Chart marks are graphical objects needed to read the chart (WCAG 1.4.11), so every
  series needs **3:1 against `bg/surface`**. Figma light `chart/1 #00A896` (2.98:1),
  `chart/3 #F2A93B` (2.00:1) and `chart/other #A7AFC2` (2.20:1), and dark
  `chart/other #5C6478` (2.92:1) fall short; the theme darkens/lightens them to
  `#00A08F` (3.27:1), `#B7802A` (3.42:1), `#868D9C` (3.33:1) and `#646C80` (3.29:1),
  keeping the hue. Neighbouring series are *not* required to contrast with each other
  (dark `chart/1` vs `chart/2` is 1.74:1) because they are always separated by a
  2px `bg/surface` gap (section 6).

Use `color-scheme` appropriately and support system dark-mode preference and user
theme selection in the product. The HTML reference follows the system setting by default
(and live when it changes) and offers System / Light / Dark.
High-contrast/forced-colour and platform Dynamic Type behavior require Sprint 7 testing.

### WCAG 2.2 AA audit

The committed [audit script](check-visual-contrast.mjs) checks **88 theme/pair
combinations** from CSS: **every foreground/background combination the reference page
uses** — text on canvas, surface, subtle and brand-tinted hero cards, status text on
badges and banners, links and hovered links, primary and pressed buttons — plus control
borders, focus rings, banner accent bars and every chart series. Text requires
**at least 4.5:1**; boundaries, focus indicators and chart marks **3:1**. It also fails
when a colour token exists in only one theme or is not a 6-digit hex value. A new
combination in the CSS or HTML must be added to the script's `PAIRS` list in the same
change. It does not replace device/browser audits of composed screens.

Examples (ratio rounded to two decimals):

| Foreground on background | Light | Dark | Minimum |
| --- | ---: | ---: | ---: |
| `text/primary` / `bg/surface` | 16.77 | 15.47 | 4.5 |
| `text/secondary` / `bg/surface` | 6.18 | 7.80 | 4.5 |
| `text/tertiary` / `bg/surface` | 5.26 | 6.78 | 4.5 |
| `text/on-brand` / `brand/primary` | 4.53 | 10.17 | 4.5 |
| `status/gain` / `status/gain-subtle` | 4.85 | 8.22 | 4.5 |
| `status/loss` / `status/loss-subtle` | 4.83 | 5.64 | 4.5 |
| `status/warning` / `status/warning-subtle` | 5.65 | 7.48 | 4.5 |
| `status/info` / `status/info-subtle` | 5.36 | 5.80 | 4.5 |
| `status/gain` / `brand/subtle` (hero change) | 4.79 | 7.17 | 4.5 |
| `border/interactive` / `bg/canvas` | 4.38 | 8.55 | 3 |
| `chart/1` / `bg/surface` | 3.27 | 9.29 | 3 |

No brand gradient, chart segment or colour alone may carry a financial meaning:
always include a numeric label, arrow/sign, text status, legend or textual alternative.
Verify the actual composed UI and raster/logo contrast independently; the palette
test covers **token roles**, not third-party asset colours or every possible pair.

## 2. Typography, spacing, radii and elevation

Font: **Inter** for web and native when bundled; SF Pro (iOS), Roboto (Android)
or the configured platform sans are acceptable native fallbacks. Do not distribute
font files through documentation. All figures use tabular numerals
(`font-variant-numeric: tabular-nums` on web); preserve the visual width of
localized amounts at 200% browser zoom / Dynamic Type. The Figma type scale:

| Text style | Size / line-height | Weight | Use |
| --- | --- | --- | --- |
| Display/L | 40 / 48 px | 700 | Net-worth hero |
| Display/M | 32 / 40 px | 700 | Main card value |
| Heading/L | 24 / 32 px | 600 | Screen title |
| Heading/M | 20 / 28 px | 600 | Sheet title |
| Heading/S | 16 / 24 px | 600 | Section heading |
| Body/L | 16 / 24 px | 400 | Main explanatory copy |
| Body/M | 14 / 20 px | 400 | Standard body / table |
| Label/M | 14 / 20 px | 500 | Control label |
| Label/S | 12 / 16 px | 500 | Value-status badge |
| Caption | 12 / 16 px | 400 | Secondary hint / timestamp |
| Overline | 11 / 16 px | 600 | Group label; letter spacing 0.66 px |
| Number/L | 16 / 24 px | 600 | Tabular amount |
| Number/M | 14 / 20 px | 500 | Compact tabular amount |

Spacing tokens **4, 8, 12, 16, 24, 32, 40, 48, 64 px** are defined in
[the CSS](02-visual-foundations.css); round controls at **10 px**, cards at
**16 px**, hero cards at **24 px**, chips/badges at full pill radius. Ordinary
cards use a 1px decorative border without a shadow. Use `Shadow/Card` for hero
cards / floating panels and `Shadow/Overlay` for sticky action bars, popovers
and sheets. Avoid shadows as the sole affordance.

The approved [UX-01](01-information-architecture.md) breakpoints replace Figma's
older generic web breakpoint suggestion: **phone <600 px**, **tablet 600–1023 px**,
**desktop ≥1024 px**; web sidebar **248 px** and optional contextual rail **360 px**.
Figma uses approximately **16 px** mobile side padding / card gap and **40 px**
desktop content padding / **24 px** grid gap. Collapse columns before clipping
long amounts (exception: the S1 section tiles keep Figma's two columns and step the figure's
size down instead; PO 2026-11-09); at 200% zoom keep actions reachable and never truncate a financial
amount. Use a minimum **44×44 pt target** for interactions.

## 3. Amount formatting: DecimalString in, display string out

The wire contract's `format: decimal` maps to **`DecimalString`**, never to a
JavaScript `number` for money. Per [ADR 0008](../architecture/adr/0008-app-data-and-i18n-stack.md)
the app formats with **`Intl.NumberFormat`, passing the `DecimalString` itself** (NumberFormat v3
formats decimal strings exactly; on Hermes through the `@formatjs/intl-numberformat` polyfill).
No decimal library is needed for display. **Do not use `Number(...)` or `parseFloat` as a money
pipeline.** Rounding is for display, not persistence. The server owns FX conversions, totals,
financial percentages, as-of dates and status, per FR-API-010.

`Intl`'s own currency style does **not** produce the house format (Node 26 ICU gives
`CHF-1'234.50`, `-1.234,50 EUR` and `-CHF 1,234.50` for `-1234.5`, all with a hyphen-minus,
and some ICU builds group de-CH with `’` U+2019). The amount is therefore **assembled**:

1. Reject anything that is not a plain decimal string (`^-?\d+(\.\d+)?$`); masked → `••••`,
   unknown or `null` → `–` (see below).
2. Scale = the currency's minor units from CLDR (`resolvedOptions().maximumFractionDigits` of a
   currency formatter: CHF/EUR 2, JPY 0).
3. Format the **absolute** value with `style: 'decimal'`, min = max fraction digits = scale,
   `roundingMode: 'halfExpand'` (commercial rounding, half away from zero), grouping on, via
   `formatToParts`.
4. **One display format in every UI language** (PO 2026-11-09): format with `en` (Latin
   digits), then group with the **ASCII apostrophe `'`** and use the **decimal point `.`**,
   e.g. `1'122.00`, `1'111'111.00`. The locale does not change the displayed number; it only
   shapes the accessible label (see below).
5. Sign, decided **after** rounding: a value that displays as zero has no sign; otherwise
   negative → **U+2212 `−`**; positive → `+` only for a *change* (`signRole = change`), nothing
   for a balance.
6. Text = sign + ISO currency code + **U+00A0 no-break space** + number. The code is always
   before the number, also in `de-DE` (a deliberate house rule against the German CLDR order,
   so all locales read alike and the sign stays at the start).

[`check-amount-format.mjs`](check-amount-format.mjs) is the executable reference of these steps
with the normative cases (CI runs it); #488's production formatter must pass the **same cases**
in the ADR 0008 Jest test under the Hermes polyfills.

**Exact, normative display cases for value `-1234.5`** (␣ = U+00A0):

| Locale | Currency | Display | Separators |
| --- | --- | --- | --- |
| `de-CH` | CHF | **`−CHF␣1'234.50`** | ASCII apostrophe group, decimal dot |
| `de-DE` | EUR | **`−EUR␣1'234.50`** | The same: ASCII apostrophe group, decimal dot |
| `en` | CHF | **`−CHF␣1'234.50`** | The same: ASCII apostrophe group, decimal dot |

Further normative cases: `1234.565` CHF de-CH → `CHF␣1'234.57`; `-0.004` CHF → `CHF␣0.00`
(no sign); `6842.1` as a change → `+CHF␣6'842.10`; `-1234.5` JPY en → `−JPY␣1'235`; `1111111` CHF en → `CHF␣1'111'111.00`;
`12345678901234567.89` keeps every digit. `+CHF 6'842.10` is an explicit positive change,
not a normal unsigned positive balance. In accessible labels spell out the sign and currency
without group separators (screen readers read them aloud), e.g. “minus 1234.50 Swiss francs”
or “minus 1234,50 Schweizer Franken”: the label keeps the reader's decimal separator and
words. The no-break space keeps
code and number together; if the whole amount does not fit, wrap it below its label rather
than truncate it.

**Machine-readable output is not display output.** CSV export, clipboard copies of a raw value
and any `value` attribute use the API's `DecimalString` (ASCII `-`, `.` decimal, no grouping, no
currency code), so that spreadsheets and re-imports parse it; U+2212 and grouping are for
display only.

- `valueKnown=false` / `UNKNOWN`: display **“–”** and announce “Value unknown”,
  never zero and never an empty unlabeled region.
- Privacy masking: display **“••••”** and announce **“Amount hidden”**, with the
  value not leaked through `title`, `aria-label`, `accessibilityValue`, or a
  secondary offscreen label.
- EUR in a CHF workspace: show the *API-supplied* converted value on a separate
  line, preceded by **≈**; never compute the exchange rate or the converted figure
  on the client.
- Negative liability, gain/loss: always display a true minus or plus with optional
  down/up arrow, even if the text is also red/green.
- Errors, loading and unknown value must not show invented `0.00`.

## 4. One visual treatment for uncertain and incomplete values

These rules apply **identically** to an individual AccountRow and an API-provided
portfolio/net-worth total. The total must use its **own** `valueStatus`; do not
infer it from rows. Status descriptions below are **draft EN/DE copy** for #379
to finalize as complete ICU messages; keys follow UX-02.

| API state | Inline / total treatment | Text or accessible alternative |
| --- | --- | --- |
| `CURRENT` | Amount only; **no badge** | Amount and currency |
| `ESTIMATED` | `≈` plus blue `ValueStatusBadge` on `status/info-subtle` | `valueStatus.estimated` “Estimated” / “Geschätzt”; explanation that the source is approximate |
| `STALE` | Clock icon + neutral `ValueStatusBadge`, source date when supplied | `valueStatus.stale` “Price from {date}” / “Kurs vom {date}”; state that the feed/statement is older than usual |
| `UNRECONCILED` | Warning icon + amber `ValueStatusBadge`, not loss-red | `valueStatus.unreconciled` “Differs from statement” / “Weicht vom Auszug ab”; identify the ledger/statement discrepancy |
| `UNKNOWN` or future unrecognized status | “–” instead of amount, neutral `ValueStatusBadge` | `valueStatus.unknown` “Value unknown” / “Wert unbekannt”; safe fallback (#452) |
| `complete=false` (additional independent flag) | Line beneath the total + warning icon, regardless of other badge | `total.incomplete` “Not all values are included” / “Nicht alle Werte sind enthalten”; show API warnings |
| Financial `gain` / `loss` | Green ↗+ / red ↘− alongside an amount; separate from data quality | Spoken direction and amount, never colour-only |

All badge contrast is audited on the designated surface. Explain the status
using an accessible tooltip/disclosure; keep the explanation available on mobile
without hover. A status badge is not a substitute for a warning explaining why
the data is incomplete or cannot be reconciled. A date is not invented when the
backend omits it.

## 5. Shared component inventory (Sprint 7)

The following names and minimal contracts come from
[UX-02's S1–S7 component use map](screens/README.md#component-use).
Names are implementation guidance for [#488](https://github.com/hugiFreezy0815/TrackMyWealth/issues/488);
none of them is claimed to exist in the current Expo code. **Every component**
must support translated labels, responsive and high-contrast themes, empty and
loading states where applicable, focus labels, and correct hidden/disabled
semantics.

| Component | Minimal inputs and states | Screen(s) / implementation boundary |
| --- | --- | --- |
| `Amount` | `DecimalString\|null`, currency, locale, sign role, `valueKnown`, `masked`; no float math | All; tabular font, wraps, accessible sign/currency |
| `ValueStatusBadge` | `valueStatus`, `valueSourceDate?`, warnings and details callback; current hides | S1–S3, rows and totals |
| `ScopeBar` | selected authorised scope, currency, action; expanded/loading | S1/S2 and web navigation; always visible when scoped |
| `PortfolioTabs` | overview name/fallback, portfolios in user order, selected tab, logo | S1/S2; first tab uses typed name or “All portfolios” |
| `SectionCard`, `SectionHeader`, `KpiCard` | title, optional action, content/status, loading | S1/S2 and other grouped pages |
| `AccountRow` | account summary/value/status/currency, navigation action | S1/S2; native and web layouts |
| `PositionRow` | position summary, units/price/value, actions | S2 Depot section (requires #478) |
| `TransactionRow` | date/description/category/amount/value status, focus/action | S3 **native** date-grouped transaction list; S4 detail entry |
| `DataTable.web` / native list adapter | server-paged rows, sorting/filter state, selection and S4 rail | S3 **web** semantic four-column `<table>`; `*.web.tsx` vs `*.native.tsx` per accepted [ADR 0007](../architecture/adr/0007-web-client-approach.md) |
| `CategoryPicker`, `FilterChips` | selected category, available values, change/clear, pending/error | S3/S4; mobile sheet, web context panel / inline picker |
| `KeyValueList`, `HistoryTimeline` | label/value rows, dated events, empty and loading | S4 |
| `ValueChart`, `PeriodSwitch`, `ChangeChip` | server-provided series/period/change, accessible summary; no client-derived totals | S1/S2; chart hidden until #477 |
| `EmptyState`, `ErrorBanner`, `OfflineBanner` | message keys, retry/recovery, last updated; error by API code | Every S1–S7 screen |
| `LoadingSkeleton`, `ConflictDialog` | geometry, loading status; stale edit + `If-Match` resolution | Every S1–S7 screen |
| `InstitutionLogo` | catalogue logo when available; safe initials fallback, accessible name | S1–S7; Figma component `71:210`; **never ship Figma placeholder wordmarks as bank logos** |
| `Stepper`, `ChipGroup`, `TextField` | progress/state/selection/input error; correct 44pt targets | S5–S7 |
| `AmountField`, `CurrencyPicker`, `DateField` | locale-aware decimal input emitting `DecimalString`, accessible error | S5/S6; no conversion to float |
| `FilePicker`, `CountTile`, `ImportRow` | file/format/progress, row include switch, duplicate state | S7; no real data in snapshots |
| `BottomSheet`, `Dialog` | title, focus trap and restoration, keyboard/dismiss behavior | S4–S7, mobile/web respectively |
| `Button`, `Badge`, `Chip`, `Toggle`, `Checkbox`, `Input`, `Progress`, `NavItem` | variant, selected/disabled/pressed/loading and accessible name | Figma atoms `14:206`, nav `15:25`, molecules `15:28` |

A component's accessibility contract is part of its public props and tests,
not a post-implementation styling detail. The scope bar never performs
authorization: server-side workspace/RLS enforcement remains authoritative.
Actions not delivered in the current sprint are hidden per UX-02, rather
than displayed as broken or permanently disabled placeholders.

### Web table and native list — #384 handover

Use **semantic React DOM `<table>`** on web with `<caption>`, labelled `<th scope="col">`,
sortable header buttons, `aria-sort` on the sorted `<th>`, and a focusable row
action that opens S4 in the right rail. Use the approved **four columns**:
Date, Description, Category, Amount; the benchmark's five columns were synthetic
and do not override S3. Avoid a generic `role="grid"` unless full composite
widget keyboard semantics are implemented. Sorting, filtering and CSV operate
over authorised **server-paged** results rather than local 10k-row arrays.

Native uses a date-grouped `TransactionRow` / `FlatList`-style adapter.
The two presentations share API client, TanStack Query cache, domain helpers,
`DecimalString` formatting, i18n and mutations. Required production acceptance
in [#494](https://github.com/hugiFreezy0815/TrackMyWealth/issues/494):
keyboard Tab/Shift+Tab; arrow navigation only if promised by UX; focus return
from detail; offscreen row scroll/focus persistence; 200% zoom to 320 CSS px;
and **NVDA on Windows with Chrome** before web launch. These were **not**
demonstrated to pass by the disposable #384 spike.

## 6. Chart library decision

**Choose `react-native-svg` as the cross-platform SVG chart-rendering
library** for Sprint 7's limited `ValueChart` vocabulary (line/area,
a composition donut, and assets-vs-liabilities stacked bar), wrapped by the
project's own accessible `ValueChart` API. Install the Expo-compatible release
with `npx expo install react-native-svg` in **#488 / the first actual chart
implementation**, verify native and web builds, and pin using the lockfile.

Rationale: the project needs only a small number of relatively static,
server-aggregated plots. SVG primitives are portable to Expo's native and web
targets, let us use Figma's `chart/1–6` colours and native/web text equivalents,
and avoid adding a heavyweight animation engine solely for MVP charts.
Expo documents `react-native-svg` at
<https://docs.expo.dev/versions/latest/sdk/svg/>. The package provides paths,
rectangles, circles and other SVG elements; **it is not a general purpose
ready-made charting framework**. The application owns a thin chart composition
module (no duplicated per-screen chart mathematics). Track full chart and
platform verification in #488.

Alternatives considered:
- **Victory Native XL** relies on Skia, Reanimated and Gesture Handler, with
  significantly more dependencies and web-specific verification; unnecessary
  for MVP's static charts. See <https://nearform.com/open-source/victory-native/docs/>.
- **Recharts** is an ergonomic React DOM chart system for web, but would require
  an independently implemented native chart presentation; do not introduce a
  second stack without a demonstrated need.
- **Chart screenshots or canvas-only drawing** do not give the accessible
  text table and focus interactions required by WCAG.

**Chart colours:** charts sit on `bg/surface`; every series token reaches **3:1** against it
(checked by the contrast script), and neighbouring segments of a donut or stacked bar are
separated by a **2px `bg/surface` gap** so they need not contrast with each other. Lines are at
least 2px wide.

**Chart accessibility:** use a nearby screen-readable title and prose/data
summary with start/end/date/change **as delivered by the API**, a textual
legend, optional list/table view for plotted points, and patterns/labels rather
than colour alone. Donuts have **at most six slices plus Other**. Never compute
net worth, percentages or FX on the client. The chart is hidden until its
backend fields are available, per [UX-02](screens/README.md).

## 7. Product implementation, PO and QA handoff

The [HTML gallery](02-visual-foundations.html) showcases tokens, the system/light/dark
theme choice, amount masking (every amount, change and percentage; the real text is kept
only in memory and announced as “Amount hidden”), statuses, scope, row/table,
loading/offline/error and a synthetic chart. It uses only local CSS/JS, makes no network requests and ships
with **no bank records, branded screenshots or font files**. It is a documentation
preview, not an accepted app component implementation.

**Acceptance review checklist:**

- [x] Extract the Figma **light and dark** semantic swatches and type scale;
      document accessible dark exceptions instead of copying failing combinations.
- [x] Provide a deterministic WCAG contrast checker for every pairing the reference
      uses (text, boundaries, focus, chart series) in both themes, run in CI.
- [x] Define exact CHF/de-CH and EUR/de-DE output strings for `-1234.5`, the
      `Intl`-based assembly rule and an executable reference with normative cases.
- [x] Document a uniform inline/total status treatment including uncertainty,
      unreconciled, unknown and incomplete states.
- [x] Map all required S1–S7 components to props, states and Figma/UX owners.
- [x] Select an Expo-compatible chart-rendering library and document alternatives.
- [x] Provide a working standalone static reference page.
- [ ] Independent design and accessibility review confirms representative
      dark/light compositions, forced colours and 200% zoom in a browser.
- [ ] **PO reviews/approves** the tokens, chart approach and component contracts.
- [ ] `#488` implements and tests the production theme/component integration.

**Suggested review commands:** `node docs/ux/check-visual-contrast.mjs` and
`node docs/ux/check-amount-format.mjs`. Open
`docs/ux/02-visual-foundations.html` in Chrome/Firefox and test theme switch,
masking, keyboard focus, 200% zoom and narrow viewport. This preview does not
replace WCAG testing on real screen implementations; any defect discovered
must be corrected before the design is marked approved.

**Decision needed from PO:** approve this accessible mapping of Figma's visual
foundation (including the documented dark-colour corrections) and the
`react-native-svg` chart implementation approach, or request changes.
**No PO approval is assumed by creating the PR.**
