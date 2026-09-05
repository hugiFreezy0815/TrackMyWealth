# Software Requirements Analysis — Personal Wealth Platform
 
**Document type:** Requirements analysis & draft specification (pre-SRS)
**Basis:** Competitive teardown of Parqet, getquin, Ghostfolio, BlueBudget
**Date:** 2026-08-12
**Status:** Draft v0.1 — for review, contains open decisions
 
---
 
## 1. Product definition
 
### 1.1 Scope (confirmed with stakeholder)
 
| Dimension | Decision |
|---|---|
| Primary market v1 | **Switzerland and Germany** (revised scope). Austria is the natural third market; wider Europe only after CH+DE are fully served. |
| Functional scope | Investment tracking · Budget & cashflow · Performance (TWR + MWR) · Combined net worth · Pillar 3a / retirement |
| Business model | **Undecided** — see §9, this is a blocking decision |
| Persistence | PostgreSQL (mandated) |
| Languages | **German and English** at launch (see NFR-USE-02a for the Romandie caveat) |
| Structural model | **Institution → Account → Positions/Transactions**, plus a consolidation layer across all institutions (see §4.1) |
 
### 1.2 Product thesis
 
Two gaps, and the revised scope sits precisely where they cross.
 
**Gap 1 — nobody covers both sides of the balance sheet.** Parqet and Ghostfolio cover assets only. getquin is retrofitting cashflow onto an investment data model and is visibly struggling — it had to split cash out into a separate account type in mid-2026, relocating existing user balances and confusing users. BlueBudget covers cashflow excellently but has no investment depth.
 
**Gap 2 — nobody covers Switzerland and Germany properly in one product.** Parqet is the DACH leader but is functionally a German product: its depth is German broker parsing and German tax logic, neither of which transfers to Switzerland, where private capital gains are largely untaxed and the relevant outputs are the Wertschriftenverzeichnis and DA-1. BlueBudget is Swiss-only and has no securities side. getquin is broad but German-centric and thin on Swiss pension. **No analysed product handles Swiss pillar 3a and German investment tax competently in the same application.**
 
That intersection is the opportunity, and it is larger than it looks: cross-border households are common — Swiss residents with German brokers, German residents with Swiss accounts, cross-border commuters earning CHF and spending EUR. These users are currently forced to run two tools and reconcile by hand.
 
**Effect of removing tax from scope.** Tax reporting was the highest-value-per-effort differentiator available in this market — no analysed competitor produces a Wertschriftenverzeichnis or DA-1 export. With it removed, the differentiation rests on the remaining four pillars, each still defensible:
 
1. **Both sides of the balance sheet in one coherent model** — assets, liabilities and cashflow designed together, not merged later
2. **Pension folded into the whole picture** — pillar 3a holdings counted in consolidated allocation and concentration analysis, which no analysed product does
3. **Dual-currency CH/DE households treated as normal**, not as an edge case
4. **Measurement quality** — TWR and MWR side by side, currency attribution, reconciliation against provider-reported balances, standards-based classification
These are architecture-led advantages, which makes them slower for a competitor to copy than a tax export would have been — but also slower to demonstrate in marketing. That trade-off should be made consciously (see D14).
 
### 1.3 Explicit non-goals for v1
 
| Out of scope | Rationale |
|---|---|
| Order execution / brokerage | Regulatory licence required; changes the entire compliance profile |
| Personalised investment recommendations | Crosses into MiFID II investment advice — see §3.3 |
| Stock screening / research terminal | Ghostfolio deliberately omits this and remains viable; large scope, low differentiation |
| **Tax reporting and filing** | Removed by stakeholder decision. Per-canton and per-Bundesland complexity is high, the liability exposure is real, and correctness expectations for tax output are absolute. See §5.10 for what is retained as return mechanics. |
| Social network / community feed | getquin's differentiator, but a separate product with its own moderation, trust & safety and abuse-handling burden |
 
---
 
## 2. Stakeholders and personas
 
| ID | Persona | Description | Primary need |
|---|---|---|---|
| P1 | **Multi-broker accumulator** | 30–50, 2–5 brokers across 2 countries, ETF-heavy, buy & hold | One consolidated truth; correct performance; low maintenance |
| P2 | **Swiss household** | CHF salary, Pillar 2 + 3a, possibly cross-border assets | Net worth incl. pension; 3a optimisation; tax-return support (Wertschriftenverzeichnis, DA-1) |
| P3 | **Cashflow-first user** | Wants to know where money goes before optimising investments | Automatic budgets from real transactions; overspend alerts |
| P4 | **FIRE planner** | Models time-to-independence | Withdrawal projections; savings-rate tracking; scenario modelling |
| P2b | **Cross-border household** | CH resident with a German broker, DE resident with Swiss accounts, or a cross-border commuter earning CHF and spending EUR | Dual-currency consolidation; correct treatment under two tax regimes; currently forced to run two tools |
| P5 | **Privacy-motivated user** | Rejects cloud aggregation of financial data | Local/self-hosted option; full export; no third-party data sharing |
| P6 | **Couple / household unit** | Shared and individual finances co-existing | Shared budgets and shared portfolios with per-person visibility rules |
| S1 | *(Secondary)* Advisor / family office | Manages many client portfolios | Multi-tenant portfolio management, reporting |
| S2 | *(Secondary)* Bank partner | White-label / multibanking integration | Embeddability, branding, compliance posture |
 
P1–P4 drive v1. P5 constrains architecture. P6 constrains the permission model. S1/S2 are business-model dependent (§9).
 
---
 
## 3. Constraints and context
 
### 3.1 Regulatory / market fragmentation — the dominant constraint
 
This is the single largest source of hidden complexity in the confirmed scope.
 
| Region | Bank/broker data access | Consequence for us |
|---|---|---|
| **Germany (EU/EEA)** | PSD2-mandated APIs; regulated TPP licence (AISP) required, or use a licensed aggregator | Either obtain an AISP licence or integrate a licensed aggregator as a subprocessor — a build-vs-buy decision with licensing, cost and liability implications. Coverage is buyable off the shelf. |
| **Switzerland** | **No PSD2 equivalent.** Access is contractual, via the SIX **bLink** platform and bilateral agreements | Swiss coverage **cannot** be bought off the shelf. BlueBudget's route was a bank partnership (Hypothekarbank Lenzburg) plus bLink onboarding. Budget separately — this is the single least substitutable item in the plan. |
| **Austria / wider EU** | PSD2, same as Germany | Connectivity largely reusable from the German build; tax logic is not |
 
**Requirement implication:** the connectivity layer must be an abstraction over *multiple heterogeneous provider types* (PSD2 aggregator, bLink, broker-specific APIs, document parsing, manual), not a single integration.
 
### 3.2 Accounting fragmentation *(tax reporting now out of scope — §5.10)*
 
Tax **reporting** is out of scope, but two things below still bind because they affect displayed returns rather than tax outputs: cost-basis method and withholding at source. The remainder of this section is retained as market context and as the specification to work from should tax reporting be reinstated.
 
Cost-basis logic **cannot be hard-coded**. Parqet's competitive depth comes precisely from getting German rules right (FIFO matching German tax treatment, documented handling of transferred-in positions with no reported cost basis). That depth does not port to Switzerland.
 
| Jurisdiction | Key rules the engine must express |
|---|---|
| **DE** | FIFO cost basis; Sparerpauschbetrag / Freistellungsauftrag; Vorabpauschale on accumulating funds; Teilfreistellung for equity funds; Kapitalertragsteuer + Soli + church tax |
| **CH** | **Private capital gains are generally tax-free** (but "professional trader" reclassification risk); wealth tax on year-end asset values; income tax on dividends/interest; 35% Verrechnungssteuer reclaim; **DA-1** for foreign withholding tax; annual **Wertschriftenverzeichnis** |
| ~~AT~~ | *Deferred with the scope change; listed for future reference: FIFO, KESt, ausschüttungsgleiche Erträge* |
| **Generic EU** | Foreign withholding tax + double-taxation treaty rates |
 
**This is a differentiation opportunity.** A correct Swiss Wertschriftenverzeichnis / DA-1 export is a feature no analysed competitor offers and is worth a paid tier on its own.
 
### 3.3 Advice boundary
 
getquin is explicitly moving toward hybrid advisory, funded by a strategic investor. That crosses into regulated territory. For v1 the product must remain an **information and analysis tool**:
 
- **NFR-REG-01 (Must):** No output shall constitute a personal recommendation to buy, sell or hold a specific financial instrument.
- **NFR-REG-02 (Must):** Projection and planning outputs shall be labelled as illustrative model results with stated assumptions, not forecasts.
- **NFR-REG-03 (Must):** Any AI-generated output shall carry an accuracy disclaimer. getquin does exactly this and it is the correct precedent.
### 3.4 Market data licensing
 
A business constraint disguised as a technical one. Ghostfolio's paid tier exists substantially to fund professional data providers. Free sources (Yahoo, CoinGecko) carry terms that may prohibit commercial redistribution.
 
- **CON-01:** Price/reference data provider must be selected before pricing is finalised; per-user data cost is a floor under the subscription price.
- **CON-02:** The data provider must be swappable — see FR-DAT-40.
- **CON-03:** **GICS is licensed intellectual property of MSCI and S&P Dow Jones Indices** and has been selected as the sector standard (FR-CAT-40..50). A commercial licence, most likely via GICS Direct or a data vendor redistributing it, must be secured before any user-facing release showing sector data. Cost scales with users or usage and is therefore an input to pricing, not an afterthought. CFI (ISO 10962) and the ISO identifier standards carry no such restriction and continue to carry asset class, country and currency.
---
 
## 4. Domain model
 
The core architectural asset. Deliberately unified across investments and cashflow.
 
### 4.1 Core entities
 
#### Target information architecture (stakeholder-confirmed)
 
*Institution names below are illustrative only. The structure is generic: any bank (UBS, ZKB, Deutsche Bank, DKB, …), broker, 3a provider (VIAC, finpension, …), discretionary manager (True Wealth, frankly, Selma, …), pension fund or card issuer must fit without code changes. See DM-13.*
 
```
TrackMyWealth
│
├── Institution: PostFinance          [BANK, CH]
│   ├── Cash account          (CHF)          ASSET
│   ├── Third pillar (3a)     (CHF)          ASSET   → holds positions
│   └── Depot                 (CHF/multi)    ASSET   → holds positions
│
├── Institution: Yuh                  [BANK/BROKER, CH]
│   ├── Cash account                         ASSET
│   └── Depot                                ASSET   → holds positions
│
├── Institution: Sparkasse            [BANK, DE]
│   ├── Cash account          (EUR)          ASSET
│   ├── Tagesgeld / savings   (EUR)          ASSET
│   ├── Credit card           (EUR)          LIABILITY → has transactions
│   └── Hypothek / mortgage   (EUR)          LIABILITY → has amortisation
│
├── Institution: VIAC                 [PENSION_PROVIDER, CH]
│   └── Third pillar (3a)                    ASSET   → holds positions
│
└── Consolidated view (across all institutions)
    ├── Net worth
    ├── Cashflow
    ├── Asset allocation
    ├── Portfolio performance
    ├── TWR
    └── MWR
```
 
#### The container abstraction (core architectural rule)
 
An **Institution is a generic container**. It holds zero or more Accounts of *any* type, in any combination. It is a grouping and naming device only — it has no balance, no currency and no behaviour of its own.
 
| Rule | Statement |
|---|---|
| C1 | A container holds **0..n Accounts of heterogeneous types** — cash, savings, depot, third pillar, managed mandate, credit card, mortgage, loan, crypto, custom asset — in any mixture |
| C2 | An Account belongs to **exactly one** container at a time (mandatory, enforces the tree; reassignable per FR-NAV-04) |
| C3 | **Container type never constrains account type.** A `BANK` may hold a third pillar; a `PENSION_PROVIDER` may hold a cash account. There is no validation rule coupling the two. |
| C4 | The **same account type may occur many times** in one container — three cash accounts at UBS, two 3a accounts at VIAC (which is normal practice for staggered withdrawal) |
| C5 | A container may hold **accounts in different currencies** simultaneously |
| C6 | A container may mix **assets and liabilities**, so a container roll-up can legitimately be **negative** (a Sparkasse container with one current account and one mortgage) |
| C7 | A container may be **empty** and remain valid (created before its accounts are added) |
| C8 | All container-level figures are **derived**, never stored |
| C9 | A container **need not be an institution.** A default *Personal assets* container holds things held with no provider — property, vehicles, precious metals, collectibles. Every account has a parent; some parents are not companies. |
 
Consequence: adding a new account type is a data-model and UI task confined to that type. It must never require changes to the container, to the tree navigation, or to the consolidation layer.
 
**Canonical entity name: `FinancialInstitution`.** Referred to informally in this document as *institution* or *container*.
 
#### Account specialisation
 
```
FinancialInstitution
    1
    │
    └──── 0..n  Account                        «abstract»
                    △
                    │  disjoint, total
    ┌───────┬───────┼──────────┬───────────┬──────────┬────────┬─────────┐
    │       │       │          │           │          │        │         │
  Cash   Savings Securities Pension   CreditCard  Mortgage   Loan     Crypto ... CustomAsset
 Account Account  Account   Account     Account    Account  Account   Account      Account
                  (Depot)
```
 
| Constraint | Statement |
|---|---|
| G1 | `Account` is **abstract** — no instance exists without a concrete subtype |
| G2 | Specialisation is **disjoint**: an account is exactly one subtype, never two |
| G3 | Specialisation is **total**: every account belongs to some subtype |
| G4 | `FinancialInstitution` 1 ──── 0..n `Account`; `Account` 1 ──── 1 `FinancialInstitution` (mandatory parent, per C2/C9) |
| G5 | The subtype is **immutable after creation**. A depot does not become a mortgage. Conversion, if ever required, is close-and-recreate with an explicit migration, never an update. |
| G6 | The subtype set is **open for extension**: new subtypes are added without modifying existing ones or the consolidation layer (FR-NAV-17) |
 
#### Capability matrix
 
The subtype answers *what it is*. Capabilities answer *what it can do* — and that is where the behaviour actually lives.
 
| Subtype | Nature | Holds positions | Has transactions | Value derived from |
|---|---|---|---|---|
| `CashAccount` | Asset | no | yes | Balance from transactions, reconciled to snapshot |
| `SavingsAccount` | Asset | no | yes | Balance plus interest postings |
| `SecuritiesAccount` (Depot) | Asset | **yes** | yes (cash leg) | Σ positions × market price |
| `ManagedMandateAccount` | Asset | **yes** | limited | Σ positions × market price; discretionary (DM-15) |
| `PensionAccount` | Asset | **conditional** | contributions | Positions *or* balance — see DM-20 |
| `CreditCardAccount` | **Liability** | no | yes | Outstanding balance; statement cycle (DM-11) |
| `MortgageAccount` | **Liability** | no | payments | Amortisation schedule |
| `LoanAccount` | **Liability** | no | payments | Amortisation schedule |
| `CryptoAccount` | Asset | **yes** | yes | Σ holdings × market price |
| `CustomAssetAccount` | Asset | no | no | Manual or scheduled valuation |
 
#### Entity model
 
```
User ──< Household >── User            (P6: shared finances)
  │
  └──< Institution >                   e.g. PostFinance, Yuh, Sparkasse, VIAC
         │  type: BANK | BROKER | PENSION_PROVIDER | PENSION_FUND |
         │        ASSET_MANAGER | CARD_ISSUER | CRYPTO_EXCHANGE |
         │        INSURER | PLATFORM | OTHER      -- descriptive only (DM-09)
         │  country, display_name, identifier (BIC/LEI), logo
         │  connection_id?          (0..n Accounts may be auto-synced)
         │
         └──< Account >
                │  type: CASH | SAVINGS | DEPOT | MANAGED_MANDATE |
                │        THIRD_PILLAR | SECOND_PILLAR | VESTED_BENEFITS |
                │        CREDIT_CARD | MORTGAGE | LOAN |
                │        CRYPTO_WALLET | CUSTOM_ASSET
                │  discretionary: bool     (provider trades, not the user)
                │  nature: ASSET | LIABILITY          (derived from type)
                │  holds_positions: bool              (DEPOT, THIRD_PILLAR,
                │                                      CRYPTO_WALLET = true)
                │  currency, masked_number, jurisdiction,
                │  opened_at, closed_at, is_active
                │
                ├──< Activity >        (immutable event log — position-bearing
                │     type: BUY | SELL | DIVIDEND | INTEREST | FEE | TAX |     accounts)
                │           DEPOSIT | WITHDRAWAL | TRANSFER_IN | TRANSFER_OUT |
                │           CONTRIBUTION | VALUATION | SPLIT | MERGE | SPINOFF
                │     instrument_id?, quantity, unit_price, fee, tax,
                │     currency, fx_rate, trade_date, settlement_date,
                │     source (MANUAL | CSV | DOCUMENT | API | AGGREGATOR),
                │     external_id, import_batch_id
                │
                ├──< Transaction >     (cash & card movements)
                │     amount, booking_date, value_date, merchant,
                │     category_id, counter_account_id?,
                │     is_internal_transfer, statement_id?
                │
                ├──< Snapshot >        (provider-reported balances & holdings)
                ├──< Position >        (derived from Activity + Snapshot)
                └──< CardStatement >   (CREDIT_CARD only: period, closing
                                        balance, due date, settlement_txn_id?)
 
Portfolio          user-defined grouping of Accounts, may cross Institutions
Instrument ──< PriceHistory >
   isin, wkn, ticker, cusip, type, currency, domicile,
   sectors[], countries[], look_through_holdings[]
TaxLot             (derived, recomputable; method per jurisdiction)
Budget ──< BudgetLine >
Goal               (e.g. 3a max-out, house deposit, FIRE target)
```
 
### 4.2 Model decisions with rationale
 
| ID | Decision | Rationale |
|---|---|---|
| DM-01 | **Activity log is append-only and immutable**; corrections are new compensating entries | Enables reproducible recomputation, audit, and safe re-import. Ghostfolio's clean six-type activity model is the reference; ours is wider because cashflow is in scope. |
| DM-02 | **Cash is a first-class Account type from day one**, never an implicit residual of a portfolio | Directly avoids getquin's July 2026 restructuring, which relocated user balances and caused confusion |
| DM-03 | **Snapshot is a separate entity from Activity** | Broker transaction history is never complete. Parqet's key evolution was moving from transaction-only reconstruction to transaction + broker-reported holdings/cost/cash reconciliation. Reconciliation is impossible without both. |
| DM-04 | **Tax lots are derived, never stored as source of truth** | Cost-basis method is jurisdiction- and user-configurable; must be recomputable when the method or an old activity changes |
| DM-05 | **Internal transfers are explicitly flagged** | Otherwise a broker deposit is double-counted as both an expense (cashflow) and an investment inflow — the classic failure of combined trackers |
| DM-06 | **Every monetary value carries a currency; every cross-currency value carries the FX rate used** | Required for FX-attributed performance (§5.3) and for auditability |
| DM-07 | **Pension accounts are Accounts with jurisdiction-specific rule plugins**, not a bespoke module | 3a (CH), Riester/Rürup/bAV (DE), Zukunftsvorsorge (AT) differ in contribution limits, tax treatment and withdrawal rules but share the account shape |
| DM-08 | **Institution is a first-class entity**, not a text attribute of an Account | Mirrors how users actually think about their money ("my Sparkasse stuff"). Also the correct anchor for a connection: one authorisation at PostFinance yields several accounts. Attribute-modelling institutions makes connection management and the navigation tree both awkward. |
| DM-09 | **An Institution is not necessarily a bank.** VIAC is a 3a foundation; a card issuer or crypto exchange is neither bank nor broker | Prevents a `bank_id` field that later needs renaming. Institution `type` is descriptive metadata only — it must not gate which account types are permitted, because providers keep expanding (Yuh is bank + broker; PostFinance is bank + 3a + broker). |
| DM-10 | **`holds_positions` is a property of the Account, not of the Institution or account type name** | A VIAC third pillar holds actual fund positions and must be performance-measurable exactly like a Depot. A PostFinance 3a may be interest-bearing only. The same type label therefore behaves differently per provider — this must be per-account data. |
| DM-11 | **Credit cards are LIABILITY accounts with their own transaction feed and statement cycle** | See the double-counting rule in FR-CSH-20. Modelling a card as a plain expense category loses the outstanding balance from net worth. |
| DM-12 | **Account `nature` (asset/liability) is derived from type, and net worth sums signed values** | Avoids the common bug of a mortgage being added to rather than subtracted from net worth |
| DM-13 | **The set of institutions is open and user-extensible — never a hardcoded enumeration.** A curated catalogue ships as *seed data*; users may create an institution not in it. | Confirmed with stakeholder: the named providers are examples. The addressable set across Europe is thousands of banks, brokers, 3a foundations, pension funds and asset managers, and it changes constantly through mergers and new entrants. Any design that requires a code release to support "my bank" caps the market and the support queue never closes. |
| DM-14 | **A missing connector must never block an institution.** Any institution and account may exist with manual or file-based data only, with automated sync as an optional enhancement layered on top. | This is precisely Ghostfolio's position — no broker connectivity at all, yet fully usable — and it is what lets you claim Europe-wide coverage on day one rather than after every connector is built |
| DM-15 | **Discretionary mandates (robo-advisors, asset managers, some 3a products) are a distinct account shape**, flagged `discretionary` | The provider trades on the user's behalf: holdings change without user action, there are no user-initiated orders to import, and a management fee is levied periodically. Import is therefore snapshot-led rather than transaction-led, and the user must not be prompted to "add a missing trade". |
| DM-16 | **Occupational pension (CH Pillar 2 / Pensionskasse, DE bAV) is balance-and-entitlement data, not a portfolio** | There are no visible underlying positions, only a vested benefit, an interest credit, employer/employee contributions and insured benefits. Model as a balance-only account updated from the annual certificate, with optional purchase-capacity (Einkauf) tracking. Forcing it into a holdings model produces meaningless performance figures. |
| DM-17 | **Account is a polymorphic type behind one uniform interface.** Every account type, whatever its internals, exposes the same contract to the rest of the system: a signed value in its own currency at any date, a currency, a nature, and a container. | This is what makes C1–C9 work. Consolidation, net worth, allocation and the navigation tree consume only that interface and never branch on account type. Without it, every new account type means touching the consolidation layer — which is how these products ossify. |
| DM-18 | **Persistence: one `account` table with a `type` discriminator plus type-specific extension tables** (`account_credit_card`, `account_mortgage`, `account_pension`, …), i.e. class-table inheritance. JSONB only for attributes never filtered or aggregated on. | A single wide table leaves most columns null and unconstrained; pure JSONB loses referential integrity and check constraints on things that genuinely need them (amortisation schedules, statement cycles, contribution limits). **Do not use PostgreSQL table inheritance (`INHERITS`)** — foreign keys and unique constraints are not inherited by child tables, which silently breaks integrity. |
| DM-19 | **Value determination is per account type, behind the DM-17 interface** | A depot is valued from positions × market price; a mortgage from its amortisation schedule; a Pillar 2 from its last certificate; a custom asset from its last manual valuation. Four different mechanisms, one contract. |
| DM-20 | **Keep the hierarchy one level deep. Vary behaviour by composed capability flags, not by sub-subclasses.** | `PensionAccount` is the proof case: a VIAC 3a holds fund positions, a PostFinance 3a is interest-bearing, a Pillar 2 is a vested benefit, a German Rürup is an insurance contract. Modelling those as `SecuritiesPensionAccount`, `CashPensionAccount`, `OccupationalPensionAccount`, `InsurancePensionAccount` multiplies with every jurisdiction added and is why financial data models rot. Instead: one `PensionAccount` subtype with `holds_positions`, `contribution_limit_rule_id`, `withdrawal_rule_id`, `is_occupational`. Same argument applies to `SecuritiesAccount` vs `ManagedMandateAccount`, which differ only by `discretionary` — merge them if the UI does not need to distinguish them by name. |
| DM-21 | **Capabilities are declared, queryable data — not inferred from the subtype in application code** | `holds_positions`, `has_transactions`, `has_statement_cycle`, `has_amortisation`, `has_contribution_limit`, `discretionary`, `manual_valuation`. This is what lets the consolidation layer, the import router and the UI decide what to do without a `switch` on subtype — the concrete mechanism behind FR-NAV-17. |
| DM-22 | **Three-layer classification: source codes → canonical taxonomy → standard mappings** (FR-CAT). All product logic reads the canonical layer only. | External standards change, differ by provider, and are sometimes licensed. Binding reports directly to MCC or GICS means every provider change or licence lapse breaks the reports. The canonical layer is the stable contract; the mappings absorb the churn. |
| DM-23 | **Classification is an annotation on an entity, never a mutation of it.** Source codes are stored verbatim and preserved. | Enables full re-categorisation when the rules improve — without it, an early bad mapping is baked into the user's history permanently |
| DM-24 | **A fund is not an asset class; it is a weighted set of asset classes.** Position → asset class is one-to-many with weights, not one-to-one. | This is a schema decision, not a reporting one. A one-to-one `position.asset_class` column makes correct allocation for a multi-asset or bond ETF impossible to express, and retrofitting it means rewriting every allocation query. |
 
### 4.3 PostgreSQL-specific requirements
 
| ID | Requirement | Priority |
|---|---|---|
| DB-01 | All monetary amounts stored as `NUMERIC` (money `NUMERIC(20,4)`, quantities `NUMERIC(28,10)`). **Floating point is prohibited** for any financial value. | Must |
| DB-09 | **Class-table inheritance mapping of the G1–G6 hierarchy:** one `account` table holding everything common (id, financial_institution_id, subtype, name, currency, jurisdiction, capability flags, lifecycle dates) plus one extension table per subtype that actually has distinct constrained attributes — `account_credit_card` (statement day, credit limit, due-date offset), `account_mortgage` (principal, rate, fixation end, amortisation schedule), `account_pension` (scheme, contribution-limit rule, withdrawal rule). Subtypes with no distinct attributes get no extension table. | Must |
| DB-10 | `subtype` is a `NOT NULL` enum or FK to a reference table, enforcing G3 (total); one extension row at most per account, enforcing G2 (disjoint) via a shared PK FK on `account_id` | Must |
| DB-11 | `subtype` is protected against update by trigger or check, enforcing G5 (immutable) | Must |
| DB-12 | `nature` as a `GENERATED ALWAYS AS` column derived from `subtype`, so no application code can write an inconsistent asset/liability sign (DM-12) | Should |
| DB-13 | **Do not use PostgreSQL `INHERITS`** for this hierarchy — foreign keys and unique constraints are not inherited by child tables, so integrity fails silently. Use explicit extension tables. | Must |
| DB-02 | `PriceHistory` and daily valuation series are the volume-dominant tables. Partition by time range (native declarative partitioning, or TimescaleDB if operationally acceptable). | Must |
| DB-03 | All timestamps `timestamptz`; trade/settlement dates as `date` in the instrument's market timezone context. Mixing these is a classic source of off-by-one performance errors. | Must |
| DB-04 | Unique constraint on `(account_id, external_id, source)` to make imports **idempotent**; plus a fuzzy duplicate-detection pass for document/CSV imports lacking stable IDs | Must |
| DB-05 | Soft delete + `created_at` / `updated_at` / `version` on all user-owned entities | Must |
| DB-06 | Row-level security or equivalent enforced tenancy on `household_id` / `user_id` | Must |
| DB-07 | Derived data (tax lots, daily valuations, aggregates) in separate tables/materialised views, fully rebuildable from the activity log | Must |
| DB-08 | Schema migrations must be forward-only and reversible-by-compensation; no destructive migration of user-visible balances without an in-app explanation | Must |
 
---
 
## 5. Functional requirements
 
Priority: **M** = Must (v1) · **S** = Should (v1 if capacity) · **C** = Could (v2) · **W** = Won't (this release)
 
### 5.1 Data ingestion (FR-DAT)
 
The competitive battleground. Parqet's moat is ingestion depth; Ghostfolio's ceiling is ingestion absence.
 
| ID | Requirement | Pri |
|---|---|---|
| FR-DAT-01 | Manual entry of any Activity type | M |
| FR-DAT-02 | CSV import with a user-configurable column mapping, saved as a reusable named template | M |
| FR-DAT-03 | Pre-built import templates for the top ~20 European brokers | M |
| FR-DAT-04 | **Import preview with full deduplication before commit** — nothing is written without explicit confirmation. This is the pattern used by mature tools in the Parqet ecosystem and is a hard requirement, not UX polish. | M |
| FR-DAT-05 | Import batches are individually reversible (rollback of a whole import) | M |
| FR-DAT-06 | Document import (PDF broker statements/contract notes) with extraction to Activities | S |
| FR-DAT-07 | AI/LLM-assisted extraction for unknown document layouts, with mandatory human review of every extracted row. getquin ships this for PDF/CSV/Excel but excludes cash movements and crypto — scope similarly. | S |
| FR-DAT-08 | Aggregator-based account connection (PSD2 EU) via pluggable provider | M |
| FR-DAT-09 | Swiss bank connection via bLink or equivalent | S |
| FR-DAT-10 | Connections are **read-only**; the system shall never hold credentials enabling order placement or payment initiation | M |
| FR-DAT-11 | Migration importers from Parqet, getquin, Portfolio Performance and Ghostfolio | S |
| FR-DAT-40 | Market-data provider behind an interface with ≥2 implementations; failover and per-instrument source override | M |
 
**Design note — connector fragility.** Parqet publicly attributed multi-week Autosync outages to an unannounced backend change at Trade Republic, outside their control. Screen-scraping and unofficial APIs must be treated as a support-cost and reputation liability:
 
- **FR-DAT-20 (M):** Every connector exposes a health status; degraded connectors are surfaced in-app with a plain-language explanation and a manual fallback path.
- **FR-DAT-21 (M):** No connector failure may block access to already-imported data.
### 5.2 Structure, navigation & holdings (FR-NAV / FR-POR)
 
#### Institution and account structure
 
| ID | Requirement | Pri |
|---|---|---|
| FR-NAV-01 | Users create Institutions and add Accounts beneath them; the primary navigation mirrors this two-level tree | M |
| FR-NAV-02 | Any account type may be created under any institution — the system must not assume a bank has no depot or that a pension provider has no positions (DM-09) | M |
| FR-NAV-03 | An institution-level roll-up: total value, period change, and per-account breakdown | M |
| FR-NAV-04 | Accounts can be reassigned to a different institution without data loss (institutions merge, rebrand and get acquired) | S |
| FR-NAV-05 | Closed/archived accounts are retained with full history and excluded from current totals but included in historical series | M |
| FR-NAV-06 | A connection authorisation is held at institution level and may provision several accounts at once, with per-account opt-in | S |
| FR-NAV-07 | Institution logos/branding, with a manual fallback for unrecognised providers | C |
| FR-NAV-08 | **Curated institution catalogue** shipped as seed data (name, country, type, identifier, logo, available connection methods), searchable at account creation, maintained as configuration and updatable without a code release | M |
| FR-NAV-09 | **User-created institutions** for anything not in the catalogue, with the same capabilities as catalogued ones except automated sync. No provider may be unsupported. | M |
| FR-NAV-10 | Catalogue entries carry a per-institution capability flag set (aggregator-supported, file import template available, manual only) so the UI can set expectations before the user invests effort | S |
| FR-NAV-11 | Users may propose a missing institution; proposals feed the catalogue backlog | C |
| FR-NAV-12 | Institution merge/rename handled as an administrative operation preserving all account history (DM-13) | S |
| FR-NAV-13 | A container accepts **any mixture of account types** and is valid when empty (C1, C3, C7) | M |
| FR-NAV-14 | The same account type may be added to one container repeatedly, distinguished by user-defined name — required for staggered 3a accounts and multiple current accounts (C4) | M |
| FR-NAV-15 | Container roll-up correctly handles mixed currencies and mixed asset/liability, and must display a **negative total** where that is the true position (C5, C6) | M |
| FR-NAV-16 | A default **Personal assets** container exists for holdings with no provider — property, vehicles, metals, collectibles (C9) | M |
| FR-NAV-17 | Adding a new account type shall require no change to container logic, tree navigation or the consolidation layer (DM-17). *Verification: introduce a new type in a test build and confirm no consolidation code is modified.* | M |
 
#### Consolidated view (across all institutions)
 
| ID | Requirement | Pri |
|---|---|---|
| FR-NAV-20 | Consolidated net worth across all institutions and account types, signed by nature (DM-12) | M |
| FR-NAV-21 | Consolidated cashflow across all cash and card accounts, with internal transfers netted out (FR-CSH-10, FR-CSH-20) | M |
| FR-NAV-22 | Consolidated asset allocation across all position-bearing accounts **including third-pillar holdings** — a user's 3a fund exposure must appear in their overall equity/region/sector allocation | M |
| FR-NAV-23 | Consolidated portfolio performance, TWR and MWR (§5.3) across a user-selectable scope: all accounts, one institution, one account, or a custom portfolio | M |
| FR-NAV-24 | Every consolidated figure is drillable to institution → account → position → activity | M |
| FR-NAV-25 | Scope filters must be consistent across all views, and the active scope always visible (a user must never be unsure whether a number includes their pension) | M |
| FR-NAV-26 | Multi-currency consolidation into the base currency, with the FX rate and date used disclosed | M |
 
> **FR-NAV-22 is a differentiator.** Swiss users routinely hold a large equity allocation inside VIAC or a similar 3a that no tracker in the comparison set folds into overall allocation and risk analysis. Concentration analysis that ignores the pillar 3a is wrong for exactly the users you are targeting.
 
#### Holdings
 
| ID | Requirement | Pri |
|---|---|---|
| FR-POR-01 | Unlimited accounts; user-defined portfolios as groupings across accounts and institutions | M |
| FR-POR-02 | Multi-currency holdings with a user-selected base currency; ≥15 currencies at launch | M |
| FR-POR-03 | Asset classes: equities, ETFs/funds, bonds, crypto, cash, precious metals, real estate, custom/illiquid assets, liabilities | M |
| FR-POR-04 | Real estate modelled at **net value** (market value minus outstanding financing) — Parqet's approach via a linked liability | M |
| FR-POR-05 | **Corporate actions:** splits, reverse splits, mergers, spin-offs, ISIN changes, symbol changes, delistings | M |
| FR-POR-06 | **Custodian transfer handling:** where the transferring broker reports no cost basis, allow user entry and attempt reconstruction from the historical price at transfer date | M |
| FR-POR-07 | Cost-basis method configurable per account/jurisdiction: FIFO, LIFO, average cost | M |
| FR-POR-08 | Fees may be included in or excluded from cost basis (configurable) — a documented source of divergence between trackers and broker dashboards | S |
| FR-POR-09 | **Reconciliation engine:** compare derived holdings/cash against broker Snapshot; surface discrepancies with amount, suspected cause and a guided resolution | M |
| FR-POR-10 | Watchlists | S |
| FR-POR-11 | **Discretionary mandates** (robo-advisors, managed 3a, asset-manager accounts): snapshot-led import, since the provider rebalances without user action. The system must not treat unexplained holding changes as missing data or prompt the user to add trades. | S |
| FR-POR-12 | Management fees on discretionary mandates recorded as `FEE` activities so they appear in fee analysis (FR-ANL-05) and reduce measured return — the whole point of tracking a mandate independently of the provider's own reporting | S |
| FR-POR-13 | **TWR is the primary metric for discretionary mandates**, MWR for self-directed accounts, since TWR isolates the manager's contribution from the user's contribution timing. The default metric per account follows the `discretionary` flag; the user may override. | S |
| FR-POR-14 | **Occupational pension accounts** (CH Pillar 2, DE bAV) as balance-only accounts updated from the annual certificate: vested benefit, interest credit, employer/employee contributions, optional purchase capacity. No position-level modelling, no synthetic performance figure. (DM-16) | S |
 
> **FR-POR-05/06/09 are the requirements most likely to be underestimated.** Corporate actions, transfers and missing purchase prices are named across reviews as the primary cause of wrong return figures in getquin. Budget real engineering time here.
 
### 5.3 Performance measurement (FR-PRF)
 
Explicitly requested: **TWR and MWR**. Neither Parqet nor Ghostfolio nor getquin presents both cleanly side by side with an explanation of the difference — this is a credible differentiator for sophisticated users.
 
| ID | Requirement | Pri |
|---|---|---|
| FR-PRF-01 | **Money-weighted return (MWR)** via IRR/XIRR over all external cashflows. Answers "how did *I* do?" | M |
| FR-PRF-02 | **Time-weighted return (TWR)** via geometric linking of sub-period returns bounded by external cashflows. Answers "how did the *strategy/manager* do?" | M |
| FR-PRF-03 | Modified Dietz as documented approximation where an intraday valuation is unavailable; the method used must be recorded per period | S |
| FR-PRF-04 | Both metrics for: Today, WTD, MTD, YTD, 1Y, 3Y, 5Y, Max, and arbitrary custom ranges | M |
| FR-PRF-05 | Annualised and cumulative variants, clearly distinguished | M |
| FR-PRF-06 | **Simple absolute return and unrealised/realised P&L must remain available** — most users need this, not TWR. Do not force the sophisticated metric on the default view. | M |
| FR-PRF-07 | **Currency attribution:** decompose return into local-currency return vs FX contribution. Materially important for a Swiss user holding USD assets; absent from all four analysed products. | S |
| FR-PRF-08 | Performance contribution/attribution by holding, asset class, sector, region | S |
| FR-PRF-09 | Benchmark comparison against user-selectable indices, using the same methodology and the same cashflow timing | M |
| FR-PRF-10 | Every performance figure exposes an "explain this number" view: method, period boundaries, cashflows applied, data gaps | S |
 
**Architectural consequence:** TWR requires a **daily valuation series per account** (DM/DB-02). This must be built in v1 — it cannot be added later without a full historical recomputation.
 
### 5.4 Analytics (FR-ANL)
 
| ID | Requirement | Pri |
|---|---|---|
| FR-ANL-01 | Allocation by asset class, region, country, sector, currency, account, instrument | M |
| FR-ANL-02 | Concentration / cluster-risk detection with configurable thresholds | M |
| FR-ANL-03 | **Fund look-through ("X-Ray"):** decompose ETF/fund holdings to true underlying exposure and detect overlap between funds. Parqet's and Ghostfolio's headline analytic; table stakes. | M |
| FR-ANL-04 | Static rule-based risk analysis (Ghostfolio's model: deterministic, explainable rules rather than a black box) | S |
| FR-ANL-05 | Fee analysis: transaction costs and fund TER drag over time | S |
| FR-ANL-06 | Dividend calendar, forecast, yield-on-cost, dividend growth, payment history | M |
| FR-ANL-07 | Cashflow analysis: contributions vs market movement decomposition of net worth change | M |
| FR-ANL-08 | Rebalancing suggestions against a target allocation, with drift thresholds. *(Parqet notably lacks this in-app.)* Must be framed as allocation drift, not investment advice — see NFR-REG-01. | S |
 
### 5.5 Classification & categorisation (FR-CAT)
 
Two distinct problems — spending categories and instrument asset classes — solved with the same three-layer pattern.
 
#### Layering principle
 
| Layer | Content | Owner |
|---|---|---|
| **L1 — Source codes** | Whatever the provider actually delivers: MCC, ISO 20022 bank transaction code, provider asset type, CFI, fund factsheet data | Immutable, stored as received |
| **L2 — Canonical taxonomy** | The system's own category / asset-class tree. Every figure in the product is computed on this layer. | Us |
| **L3 — Standard mappings** | Deterministic mapping from L2 to external reporting standards (COICOP, regulatory asset classes, tax categories) | Configuration |
 
| ID | Requirement | Pri |
|---|---|---|
| FR-CAT-01 | L1 source codes are stored verbatim and never overwritten by categorisation; reclassification is always reversible and re-runnable | M |
| FR-CAT-02 | Every classification records **provenance**: source (imported code / rule / model / user), confidence, timestamp | M |
| FR-CAT-03 | **User override always wins** and is never silently replaced by a later automatic run | M |
| FR-CAT-04 | Uncategorised items are visible and actionable, never hidden in an "Other" bucket that quietly absorbs them | M |
 
#### Cash & credit card transaction categorisation
 
The standard the stakeholder refers to for cards is **MCC (Merchant Category Code, ISO 18245)** — a four-digit code assigned by the acquirer and carried in the card transaction feed. Bank transactions have a different standard: **ISO 20022 External Code Sets**, specifically the structured `BankTransactionCode` (Domain / Family / SubFamily) and `Purpose` code delivered in **camt.052 / camt.053 / camt.054** statements and in PSD2 API responses. Both are input signals, not a user-facing taxonomy.
 
| ID | Requirement | Pri |
|---|---|---|
| FR-CAT-10 | **MCC (ISO 18245)** ingested where available and used as the primary auto-categorisation signal for card transactions | M |
| FR-CAT-11 | **ISO 20022 bank transaction code and purpose code** ingested from camt files and PSD2 responses and used as the primary signal for bank transactions | M |
| FR-CAT-12 | Shipped default mapping from MCC and ISO 20022 codes to the canonical category tree, maintained as configuration (NFR-MNT-01) | M |
| FR-CAT-13 | Fallback categorisation from merchant name, counterparty IBAN and amount pattern where no standard code is present — necessary because MCC is frequently absent, wrong, or reflects the acquirer rather than the merchant | M |
| FR-CAT-14 | Canonical category tree is **hierarchical, ≤3 levels, user-extensible**, with the shipped default set editable but not deletable where mappings depend on it | M |
| FR-CAT-15 | Merchant-level rules with retroactive application (also FR-CSH-02) | M |
| FR-CAT-16 | Transaction splitting across several categories (one supermarket receipt containing groceries and a household appliance) | S |
| FR-CAT-17 | **L3 mapping to COICOP** (UN Classification of Individual Consumption According to Purpose). This is the closest thing to an official standard for household spending and is the basis of national household-budget surveys and CPI baskets in both target markets. | C |
| FR-CAT-18 | Because of FR-CAT-17, spending can be benchmarked against published national household averages by category — a differentiating report no analysed competitor offers | C |
| FR-CAT-19 | Transfer/settlement detection is a classification outcome, not a category: internal transfers and card settlements are flagged and excluded from spend (FR-CSH-10, FR-CSH-21) | M |
 
#### Instrument asset-class classification
 
There is no single official standard covering everything needed here, so three are used for three different purposes:
 
| Standard | Covers | Status |
|---|---|---|
| **CFI — ISO 10962** | Instrument *category* and attributes: E = equities, D = debt, C = collective investment vehicles, R = entitlements, O/F/S = derivatives | **ISO standard, tied to the ISIN record.** The correct backbone for asset class. |
| **GICS** (S&P/MSCI) or **ICB** (FTSE Russell) | Sector / industry | **Commercially licensed** — see CON-03 |
| **ISO 3166 / ISO 4217 / ISO 6166** | Country, currency, instrument identity | Free, mandatory |
 
| ID | Requirement | Pri |
|---|---|---|
| FR-CAT-30 | **CFI (ISO 10962) is the primary source for asset-class assignment**, mapped to the canonical asset-class taxonomy | M |
| FR-CAT-31 | Canonical asset classes: Equity · Fixed Income · Cash & equivalents · Real estate · Commodities · Crypto · Alternatives · Multi-asset · Derivatives. Sub-classes (e.g. Equity → Developed / Emerging; Fixed Income → Government / Corporate / by maturity bucket) | M |
| FR-CAT-32 | **Funds and ETFs must be decomposed, not labelled.** A multi-asset or bond ETF classified as a single "Fund" asset class makes the entire allocation view wrong. Look-through (FR-ANL-03) resolves a fund into its constituent asset classes, regions, sectors and currencies, weighted. | M |
| FR-CAT-33 | Where look-through data is unavailable, the fund's own declared allocation is used and the result is **flagged as estimated** (NFR-COR-03) | M |
| FR-CAT-34 | **Sector grouping of depot positions uses GICS** — see the dedicated block below | M |
| FR-CAT-35 | Classification is stored per instrument with an effective date, so historical allocation charts are reproducible after a reclassification | S |
| FR-CAT-36 | User override of asset class per instrument, for custom and illiquid holdings especially (Ghostfolio permits patching country/sector/holding weights on an asset profile — a good precedent) | S |
| FR-CAT-37 | Pension holdings are classified on the same taxonomy so 3a fund exposure appears in consolidated allocation (FR-NAV-22) | M |
| FR-CAT-38 | Every allocation view is drillable from asset class → instrument → the classification source that produced it (FR-CAT-02) | S |
 
#### GICS sector grouping (stakeholder-confirmed)
 
**Decision:** GICS is the standard for grouping depot positions by sector. Closes D9.
 
<cite index="45-1">GICS is a four-tier hierarchical system of 11 sectors, 25 industry groups, 74 industries and 163 sub-industries, developed by and the exclusive property of MSCI and S&P Dow Jones Indices.</cite> <cite index="43-1">The eight-digit coding system is explicitly designed to absorb structural change as sectors, industry groups, industries and sub-industries are added or divided.</cite>
 
**Two scope boundaries must be respected in the design:**
 
1. **GICS classifies companies, not instruments.** <cite index="43-1">It applies to companies globally and classifies each according to its principal business activity.</cite> <cite index="44-1">The structure is designed to reflect the equity investment universe.</cite> It therefore says nothing about bonds, commodities, crypto, cash, physical property or funds. **GICS is a grouping dimension inside the equity sleeve — it layers on top of the CFI-derived asset class (FR-CAT-30), it does not replace it.**
2. **GICS is licensed intellectual property.** <cite index="45-1">MSCI states the information may not be reproduced or redisseminated in whole or part without prior written permission.</cite> <cite index="47-1">The commercial data product is GICS Direct, a joint S&P/MSCI service covering more than 44,000 companies.</cite> Displaying GICS to end users requires a licence — see CON-03 and R8.
| ID | Requirement | Pri |
|---|---|---|
| FR-CAT-40 | Store the full **8-digit GICS sub-industry code** per instrument; derive Industry (6), Industry Group (4) and Sector (2) by truncation rather than storing four separate fields | M |
| FR-CAT-41 | Allocation views support drill-down across all four GICS levels, defaulting to Sector | M |
| FR-CAT-42 | GICS applies **only to equity-nature positions**. Non-equity holdings are never forced into a GICS sector; they are reported on the asset-class axis (FR-CAT-31). | M |
| FR-CAT-43 | **Funds and ETFs carry no GICS code of their own.** Their sector exposure is computed by look-through: weighted aggregation of the constituents' GICS codes, normalised to the fund's equity portion (FR-CAT-32, FR-ANL-03) | M |
| FR-CAT-44 | Where look-through is unavailable, the fund provider's published sector breakdown is used **only if it is itself GICS-based**; mixing GICS and non-GICS breakdowns in one chart is prohibited, and the result is flagged as estimated | M |
| FR-CAT-45 | Instruments with no GICS classification (unlisted, custom, non-covered) go to an explicit **Not classified** bucket that is always visible — never silently dropped or absorbed into an existing sector | M |
| FR-CAT-46 | **GICS structure is effective-dated.** <cite index="46-1">S&P DJI and MSCI conduct annual reviews to keep the structure current,</cite> and revisions add, split and rename categories. Store the structure version alongside the code. | M |
| FR-CAT-47 | Historical allocation charts must state whether they are shown **as-reported** (classification in force at the time) or **restated** (current classification applied throughout). Default: restated, with the choice disclosed. Silently mixing the two makes historical sector charts incoherent across a revision boundary. | S |
| FR-CAT-48 | A company receives one classification based on its principal activity, so conglomerates land in a single sector. User override is permitted, but the GICS value is retained alongside it (FR-CAT-02, FR-CAT-03) | S |
| FR-CAT-49 | Display the attribution and trademark notice required by the licence terms wherever GICS data appears | M |
| FR-CAT-50 | **Fallback taxonomy behind the canonical layer.** If the licence is unavailable, delayed or withdrawn, sector grouping must degrade to a documented internal taxonomy without schema change or data loss (DM-22). | M |
 
### 5.6 Instrument master data (FR-IMD)
 
A single security master underpins §5.2–5.5. Every classification, valuation and performance figure resolves through it.
 
#### Structural decisions
 
| ID | Decision | Rationale |
|---|---|---|
| DM-25 | **The master is global and shared across all users**, not per portfolio or per user. Per-user data (overrides, notes, custom asset class) lives in a separate table keyed by user + instrument. | If ten users hold the same ETF, that is one master record and one price series, not ten. This is the single largest lever on market-data cost (CON-01). It also creates a tenancy hazard: a user override must never leak into another user's view — enforce at query level, not by convention. |
| DM-26 | **Instrument and Listing are separate entities.** `Instrument` (1) ──< `Listing` (0..n), where a listing is exchange (MIC) + ticker + trading currency. **Prices attach to the Listing, not the Instrument.** | One ISIN is routinely listed on several exchanges in different currencies — an Irish-domiciled ETF quoted on SIX in CHF and XETRA in EUR. A single `exchange` and `price` field on the instrument makes this inexpressible, and users holding the same ISIN bought at two venues will see wrong valuations. |
| DM-27 | **Raw prices are immutable; adjusted series are derived.** Store prices exactly as delivered, plus corporate actions as separate records, and compute split/dividend-adjusted series on read. | Cost basis, tax lots and realised gains need the price actually traded on the day. A provider that silently retro-adjusts a price series corrupts tax figures permanently. |
| DM-28 | **Lazy instantiation with reference counting** — confirmed with stakeholder | See FR-IMD-30..34 |
 
#### Core master record
 
| ID | Requirement | Pri |
|---|---|---|
| FR-IMD-01 | One master record per instrument, primary key **ISIN (ISO 6166)** where one exists; synthetic key for instruments without an ISIN | M |
| FR-IMD-02 | Additional identifiers: **Valorennummer** (CH), **WKN** (DE), CUSIP, SEDOL, FIGI, ticker; **LEI** for the issuer. Valor and WKN are not optional in this market — brokers in CH and DE routinely deliver them instead of ISIN. | M |
| FR-IMD-03 | Legal name, display name, and a **company or fund description** in DE and EN (NFR-USE-02) | M |
| FR-IMD-04 | Instrument type from **CFI (ISO 10962)**, mapped to canonical asset class — weighted one-to-many for funds (FR-CAT-30, DM-24) | M |
| FR-IMD-05 | **Currencies, kept distinct — conflating these is a recurring source of wrong FX attribution:** (a) trading currency, per listing; (b) denomination / NAV currency; (c) underlying currency exposure, derived by look-through for funds; (d) currency-hedged share class flag | M |
| FR-IMD-06 | **Countries, all ISO 3166, stored separately:** (a) **issuer country** — registered office of the issuing entity; (b) **security / domicile country** — where the security or fund is domiciled; (c) **risk country** — country of economic exposure; (d) **withholding-tax country** — the source country for tax purposes | M |
| FR-IMD-07 | FR-IMD-06 must be modelled as four fields, not one. An Irish-domiciled ETF, issued by a Luxembourg entity, holding US equities, has a different value in three of them. The withholding country is retained to explain reduced dividend receipts (FR-TAX-R2), not for reclaim logic. | M |
| FR-IMD-08 | **GICS**: 8-digit sub-industry code, equity-nature instruments only (FR-CAT-40..50) | M |
| FR-IMD-09 | **SNB classification**: issuer institutional sector plus securities category, per the Swiss securities-holdings reporting scheme. Stored **alongside** GICS, never derived from it — GICS classifies a company's industry, SNB classifies the issuer's institutional sector and the instrument category for statistical reporting. Orthogonal axes, both reportable. See D12. | S |
| FR-IMD-10 | Exchange per listing: **MIC (ISO 10383)**, primary-listing flag, trading calendar and timezone | M |
| FR-IMD-11 | Trading calendar is a hard requirement, not metadata: without it, non-trading days cannot be distinguished from missing data, which corrupts the daily valuation series behind TWR (FR-PRF-02) | M |
| FR-IMD-12 | **Type-specific extensions** (per DM-18): *fund* — TER, distributing/accumulating, replication method, share class, benchmark, look-through holdings, *bond* — coupon, maturity, payment frequency, rating, seniority; *equity* — shares outstanding, dividend policy; *crypto*, *derivative* as needed | S |
| FR-IMD-13 | **Field-level provenance**: every field records its source, retrieval timestamp and confidence; multi-source precedence is configurable | M |
| FR-IMD-14 | **User override per field**, never silently overwritten by a later refresh (FR-CAT-03) | M |
| FR-IMD-15 | **Effective-dated master data** — domiciles, names and classifications change; historical reports must remain reproducible (FR-CAT-46) | S |
| FR-IMD-16 | **Successor linkage** for ISIN changes, mergers and spin-offs, so position history is continuous across the event (FR-POR-05) | M |
| FR-IMD-17 | Completeness indicator; records missing fields required for a requested analysis are flagged rather than producing a silently wrong chart (NFR-COR-03) | M |
| FR-IMD-18 | **Identity resolution:** the same instrument arriving from different brokers under different identifiers resolves to one master. ISIN is authoritative; ticker+exchange fallback requires user confirmation, never a silent match. | M |
 
#### Price and rate storage
 
| ID | Requirement | Pri |
|---|---|---|
| FR-IMD-20 | Daily close price per **listing**, with currency, source and as-of timestamp. Intraday optional and out of scope for v1. | M |
| FR-IMD-21 | Store prices **unadjusted as delivered**; derive adjusted series from separately stored corporate actions (DM-27) | M |
| FR-IMD-22 | **Backfill on first reference:** when an instrument enters the system, backfill its price history to the earliest activity date referencing it. Without this, TWR, historical net worth and allocation charts have no data before the user joined. | M |
| FR-IMD-23 | Gap handling: carry forward the last known price across non-trading days; flag as stale beyond a configurable threshold; never interpolate silently | M |
| FR-IMD-24 | **FX rates are a parallel time series** on ISO 4217 pairs, under the same rules. Required for base-currency consolidation (FR-NAV-26) and currency attribution (FR-PRF-07). | M |
| FR-IMD-25 | Delisted, matured and redeemed instruments retained and marked inactive; last price frozen; excluded from refresh and from staleness alerts | M |
| FR-IMD-26 | Price source precedence configurable globally and overridable per instrument (FR-DAT-40) | S |
 
#### Lifecycle — lazy creation (stakeholder-confirmed)
 
| ID | Requirement | Pri |
|---|---|---|
| FR-IMD-30 | **A master record is created only on first reference** by an activity, holding or watchlist entry in some portfolio. No bulk universe load. | M |
| FR-IMD-31 | **The record is retained after the position closes.** Historical activities reference it and past performance must stay reproducible. Deletion only when no activity in any portfolio references it — never merely because nobody currently holds it. | M |
| FR-IMD-32 | Reference counting drives refresh scope: prices refresh only for instruments **currently held or watchlisted**, not for every instrument ever seen. This is the primary control on recurring data cost. | M |
| FR-IMD-33 | Search and instrument lookup at add-time may query the provider's full universe without creating a master record; the record is written only on confirmed selection | M |
| FR-IMD-34 | Master data refresh cadence is configurable per field class — prices daily, classification and reference data far less often | S |
 
### 5.7 Budget & cashflow (FR-CSH)
 
BlueBudget's design thesis, validated: **manual expense entry fails; derive the budget from observed transactions.**
 
| ID | Requirement | Pri |
|---|---|---|
| FR-CSH-01 | Automatic transaction categorisation with a taxonomy the user can edit | M |
| FR-CSH-02 | Merchant-level rules ("always categorise X as Y"), retroactively applicable | M |
| FR-CSH-03 | Transaction renaming/aliasing | S |
| FR-CSH-04 | **Budget proposal generated from the user's actual historical transactions**, not from a blank template | M |
| FR-CSH-05 | Real-time budget tracking with on-track / overspend status per category | M |
| FR-CSH-06 | Money-flow (Sankey) visualisation of income → categories → savings | S |
| FR-CSH-07 | Recurring payment and subscription detection | S |
| FR-CSH-08 | Upcoming-bill reminders, including jurisdiction-specific obligations (CH: Serafe, tax instalments, insurance premiums) | S |
| FR-CSH-09 | Savings rate as a first-class tracked metric, linking cashflow to the investment side | M |
| FR-CSH-10 | **Internal transfers between own accounts excluded from income/expense totals** (see DM-05) | M |
| FR-CSH-11 | Shared/household budgets with per-member visibility control | S |
| FR-CSH-20 | **Credit cards:** individual card transactions are imported and categorised like bank transactions, and the outstanding balance is carried as a liability in net worth | M |
| FR-CSH-21 | **Card settlement must not be double-counted.** The monthly payment from the current account to the card issuer is an *internal transfer*, not an expense; the expenses are the individual card transactions. Auto-detect the settlement payment and match it to the corresponding statement. | M |
| FR-CSH-22 | Statement cycle support: statement period, closing balance, due date, and paid/unpaid status | S |
| FR-CSH-23 | Spending attributed to the **transaction date**, not the settlement date, so a purchase lands in the month it was made | M |
| FR-CSH-24 | Foreign-currency card transactions retain the original amount, currency and the issuer's applied rate; card FX fees identified as fees where the issuer discloses them | S |
| FR-CSH-25 | Configurable alert on card utilisation or an approaching due date | C |
| FR-CSH-12 | Group expense splitting with settlement minimisation (fewest possible payments), invite by link/QR, and support for participants who have not installed the app | C |
 
> FR-CSH-12 is BlueBudget's strongest feature and is genuinely well designed, but it is a **separate social product** with its own network effects and abuse surface. Recommend v2 unless the household use case (P6) makes it strategic.
 
### 5.8 Net worth (FR-NWO)
 
| ID | Requirement | Pri |
|---|---|---|
| FR-NWO-01 | Single consolidated net worth: investments + cash + pension + illiquid assets − liabilities | M |
| FR-NWO-02 | Historical net-worth series with contribution vs performance attribution | M |
| FR-NWO-03 | Liabilities: mortgages, loans, credit, with amortisation schedules and interest | M |
| FR-NWO-04 | Manual/custom assets with user-supplied or scheduled revaluation | M |
| FR-NWO-05 | Liquidity view: what is actually accessible, in what timeframe (relevant because pension assets are locked) | S |
 
### 5.9 Pension & retirement (FR-PEN)
 
Confirmed in scope. Implemented as jurisdiction rule plugins (DM-07).
 
| ID | Requirement | Pri |
|---|---|---|
| FR-PEN-01 | Pension accounts as distinct account types with locked-until / withdrawal-condition semantics | M |
| FR-PEN-02 | **CH Pillar 3a:** track balances across multiple 3a accounts; annual contribution limit tracking with maxed/remaining status; alert before year-end deadline | M |
| ~~FR-PEN-03~~ | ~~CH Pillar 3a staggering advice~~ — **dropped with tax scope.** FR-PEN-02 (contribution-limit and deadline tracking) is retained: it is a savings-goal feature, not a tax feature. | — |
| FR-PEN-04 | **CH Pillar 2** balance tracking incl. voluntary purchase (Einkauf) capacity, manually entered from the pension certificate | S |
| FR-PEN-05 | **DE/AT equivalents:** Riester, Rürup, bAV, Zukunftsvorsorge as configured plugin types | S |
| FR-PEN-06 | Contribution limits, deadlines and tax parameters are **configuration data with an effective-date, not code** | M |
| FR-PEN-07 | Retirement projection: capital at retirement given current assets, contribution rate and assumption set; **user-editable assumptions with the results shown as scenario ranges, not a single number** | S |
| FR-PEN-08 | Pension gap estimate against a target replacement income | S |
| FR-PEN-09 | FIRE calculator: time to financial independence, sustainable withdrawal modelling (Ghostfolio ships this and it is popular with P4) | S |
 
### 5.10 Tax — OUT OF SCOPE (with retained residue)
 
**Tax reporting is out of scope** (stakeholder decision). No tax year reports, no Wertschriftenverzeichnis, no DA-1, no Vorabpauschale calculation, no withholding reclaim support. Removed from all releases.
 
**However, tax cannot be fully removed from the domain model**, because it is a cash event that changes what the user actually received and therefore what their return was. The following is retained *as return mechanics, not as tax functionality*:
 
| ID | Retained | Why it survives the scope cut |
|---|---|---|
| FR-TAX-R1 | `TAX` remains an Activity type; withholding deducted at source is recorded | A gross dividend of 100 with 35% withheld is 65 in the user's pocket. Discarding the deduction makes both the cash balance and the net return wrong. |
| FR-TAX-R2 | Withholding-tax country retained on the instrument master (FR-IMD-06d) | Needed to explain *why* a dividend arrived reduced, and to display gross vs net. No reclaim logic is built. |
| FR-TAX-R3 | Cost-basis method remains configurable (FR-POR-07) | Required for realised gain/loss display and for reconciliation against broker statements — an accounting requirement, not a tax one. FIFO stays the default. |
| FR-TAX-R4 | Realised vs unrealised P&L split (formerly FR-TAX-01) | A core performance figure that users expect regardless of tax |
| FR-TAX-R5 | Income and fee data continue to be captured and reportable as **cashflow**, not as tax categories | Feeds dividend analysis (FR-ANL-06) and fee analysis (FR-ANL-05) |
| FR-TAX-R6 | Data export is complete enough that a user or their adviser can derive tax figures externally (FR-EXP-01) | The chosen substitute for built-in tax reporting — and a reason not to weaken the export requirement |
 
**Design instruction:** do not delete the tax fields from the schema. Removing `TAX` activities or the withholding country would have to be reversed if tax reporting is ever added, and reconstructing historical withholding data after the fact is impossible.
 
### 5.11 Users, sharing, notifications, API
 
| ID | Requirement | Pri |
|---|---|---|
| FR-USR-01 | Email + password with a strong policy; **passkey/WebAuthn support** (BlueBudget's approach, and correct) | M |
| FR-USR-02 | TOTP MFA | M |
| FR-USR-03 | Optional OIDC/SSO (relevant for self-host and B2B; Ghostfolio's precedent) | C |
| FR-USR-04 | App-level PIN/biometric lock on mobile | S |
| FR-USR-05 | Household/shared workspace with roles: owner, member, view-only | S |
| FR-USR-06 | Read-only share link for a portfolio, with configurable masking of absolute values | S |
| FR-NOT-01 | Configurable notifications: daily/weekly summary, budget threshold, dividend received, connector failure, 3a deadline | S |
| FR-NOT-02 | Notifications must be individually disableable; no dark-pattern engagement loops | M |
| FR-API-01 | Documented REST API with token auth covering read of holdings/performance and write of activities (Ghostfolio's model, which is the best in the comparison set) | S |
| FR-API-02 | Third-party integration model so long-tail broker support can be built by the community, as Parqet does via its Connect apps | C |
| FR-EXP-01 | **Complete data export in CSV and JSON, including full activity history — no paywall on export** | M |
 
---
 
## 6. Non-functional requirements
 
| ID | Category | Requirement |
|---|---|---|
| NFR-SEC-01 | Security | Encryption at rest and in transit; provider credentials/tokens in a dedicated secret store, never in the application database |
| NFR-SEC-02 | Security | No storage of broker credentials that permit trading or payment initiation (FR-DAT-10) |
| NFR-SEC-03 | Security | Independent penetration test before public launch |
| NFR-SEC-04 | Security | Ghostfolio holds no SOC 2 / ISO 27001, which is cited as a barrier for institutional use. If S1/S2 personas matter, plan certification early — it is a 12–18 month path. |
| NFR-PRV-01 | Privacy | GDPR (EU) **and** revFADP (CH) compliance; documented lawful basis; subprocessor register |
| NFR-PRV-02 | Privacy | EU/CH data residency; explicit residency choice if serving both |
| NFR-PRV-03 | Privacy | Data minimisation; deletion within a stated SLA; no sale or advertising use of financial data |
| NFR-COR-01 | Correctness | Every derived figure recomputable from the activity log; golden-dataset regression suite covering corporate actions, transfers, multi-currency and partial-history cases |
| NFR-COR-02 | Correctness | Known-answer tests for TWR, MWR and Modified Dietz against published worked examples |
| NFR-COR-03 | Correctness | Data gaps and estimated values are **visibly flagged in the UI**, never silently interpolated |
| NFR-PER-01 | Performance | Dashboard for a 20-account / 20,000-activity portfolio renders in < 2 s (p95) |
| NFR-PER-02 | Performance | Full historical recomputation of a large portfolio completes in < 60 s as a background job |
| NFR-AVL-01 | Availability | 99.5% for the hosted service; degraded price data must not prevent access to holdings |
| NFR-USE-01 | Usability | Time-to-first-insight after signup < 10 minutes for a single-broker user |
| NFR-USE-02 | Usability | **Localisation: English and German at launch.** Full i18n from day one (externalised strings, no concatenated sentences, locale-aware number/date/currency formatting, CHF `1'234.56` vs EUR `1.234,56`). Adding a language must require no code change. |
| NFR-USE-02a | Usability | **Caveat to record, not a v1 requirement:** DE+EN covers Germany fully and German-speaking Switzerland, which is roughly two-thirds of the Swiss market. The Romandie and Ticino are not served; BlueBudget ships DE/EN/FR/IT for this reason. French should be treated as the first post-launch language, and no design decision may foreclose FR/IT. |
| NFR-USE-03 | Usability | Accessibility to WCAG 2.2 AA |
| NFR-POR-01 | Portability | No paywalled lock-in of user data (FR-EXP-01) |
| NFR-MNT-01 | Maintainability | Jurisdiction rules (tax, pension limits, categories) as versioned, effective-dated configuration, deployable without a code release |
| NFR-LEG-01 | Legal | If any AGPL-3.0 code (e.g. Ghostfolio) is used or adapted, the copyleft obligation extends to a network-offered service. **Legal review required before any such reuse.** |
 
---
 
## 7. Recommended MVP cut
 
Scope discipline is the lesson from Ghostfolio, which stays useful and well-regarded on a deliberately narrow feature set.
 
**Release 1 — "Correct consolidated truth" (the only thing that must be excellent)**
- Manual + CSV + template import with dedupe preview (FR-DAT-01..05)
- One PSD2 aggregator for Germany plus 3–5 flagship German broker imports; Swiss coverage via file/document import at launch, bLink in Release 2
- Full domain model incl. FinancialInstitution → Account tree, cash as first-class, corporate actions, multi-currency
- Instrument master with Instrument/Listing separation, lazy creation, price + FX series, backfill (FR-IMD-01..34) — prerequisite for everything else in the release
- Consolidated view across institutions: net worth, allocation, performance, with drill-down (FR-NAV-20..26)
- Credit cards as liability accounts with transaction feed and settlement matching (FR-CSH-20/21/23)
- Reconciliation engine (FR-POR-09)
- TWR + MWR + simple P&L with the "explain this number" view
- Allocation, concentration, dividend calendar
- Net worth incl. liabilities and manual assets
- Full export
- Web + PWA (Ghostfolio's mobile-first PWA is adequate; defer native)
**Release 2 — "Both sides of the balance sheet"**
- Bank transaction categorisation, rules, auto-derived budgets, savings rate
- Swiss bLink connectivity (the long-lead item — start the evaluation during Release 1)
- Pillar 3a tracking and deadline alerts
- Document/PDF import
- Notifications
**Release 3 — "Planning and depth"**
- Retirement/FIRE projections with scenario ranges
- Fund look-through / X-Ray
- Public API
- Household sharing
Deliberately deferred: social/community, group expense splitting, native apps, advisor multi-tenancy, AI agent.
 
---
 
## 8. Key risks
 
| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Connector breakage from unannounced broker changes (observed at Parqet) | High / recurring | Health monitoring, transparent status in-app, always-available manual fallback, prefer regulated APIs over scraping |
| R2 | Wrong performance figures from corporate actions or missing cost basis (observed at getquin) | High — destroys trust irrecoverably | FR-POR-05/06, golden-dataset regression suite, visible flagging of estimated data |
| R3 | Swiss market access has no PSD2 shortcut | High | Early bLink evaluation; consider a bank partnership route as BlueBudget did |
| R4 | Market-data licensing cost exceeds viable per-user price | High | Resolve provider and unit economics before pricing (§3.4) |
| R5 | Scope explosion — the confirmed scope is four products in one | High | Enforce the release cut in §7; treat R1/R2 quality as the differentiator rather than feature count |
| R6 | Data-model migration after launch (observed at getquin) | Medium–High | DM-02, DM-05 resolved in v1; no destructive migration without in-app explanation |
| R7 | AGPL contamination if Ghostfolio code is reused | Medium | Legal review before any reuse (NFR-LEG-01) |
| R8 | **GICS licence cost or terms prove incompatible with the business model** — particularly acute if the product is open-source or self-hostable, since redistributing GICS codes in a downloadable database is unlikely to be permitted | High | Resolve D1 and D10 together; FR-CAT-50 fallback taxonomy keeps the product functional without GICS; scope the licence conversation before committing to sector analytics in marketing |
 
---
 
## 9. Open decisions blocking the next phase
 
| # | Decision | Blocks |
|---|---|---|
| D1 | **Business model** — freemium SaaS / open source / white-label B2B2C. Still open. | Pricing, tenancy architecture, self-host support, certification roadmap, whether S1/S2 personas are in scope |
| D2 | **AISP licence vs. licensed aggregator** for EU account access | Timeline, compliance cost, liability |
| D3 | **Market-data provider** | Unit economics, therefore pricing |
| D4 | **Self-hosting** — required, nice-to-have, or out of scope? P5 depends on it entirely | Deployment model, packaging, support burden |
| D5 | **Native apps vs PWA.** Note: Parqet's on-device Autosync architecture, where credentials never leave the phone, is only possible *because* it is native. If that privacy property matters, PWA is insufficient. | Platform strategy, connector design |
| ~~D6~~ | ~~Jurisdiction sequencing~~ — **CLOSED: Switzerland and Germany.** Two jurisdictions, fully supported, rather than nominal Europe-wide coverage | — |
| D14 | **How is differentiation demonstrated without tax output?** The remaining advantages are architectural and take longer to show in a demo or landing page than a tax export would have. Needs a positioning answer before launch marketing. | Positioning, launch messaging |
| D13 | **Which market leads?** CH and DE need different first connectors, different tax outputs and different launch messaging. Building both fully in parallel doubles the critical path; sequencing one first halves it. | Release plan §7, connector priority |
| D7 | Certification (ISO 27001 / SOC 2) — required or deferred? | Timeline, cost, B2B viability |
| D8 | Team size, budget and target launch date | The entire release plan in §7 |
| ~~D9~~ | ~~Sector classification standard~~ — **CLOSED: GICS selected** (FR-CAT-40..50) | — |
| D10 | **GICS sourcing route:** direct licence via GICS Direct, or obtain GICS codes through a market-data vendor that already redistributes them? The second is usually cheaper for a small user base but couples the classification to the price-data provider, weakening FR-DAT-40 | Vendor selection, unit economics, launch date |
| D12 | **Exact SNB code list to implement** (FR-IMD-09). The SNB securities-holdings survey classifies by issuer sector and securities category; the precise code set and whether ESA-2010-aligned institutional sectors are the intended scheme needs confirming against the current SNB survey documentation before implementation | FR-IMD-09, Swiss reporting outputs |
| D11 | **Constituent-level look-through source for funds** (FR-CAT-43). Look-through is a separate licensed dataset from GICS itself; without it, ETF sector allocation cannot be computed at all — and ETFs are the majority of a typical target user's portfolio | FR-CAT-32/43, FR-ANL-03 |
 
---
 
## 10. Method note
 
This analysis is based on publicly available product documentation, changelogs, app-store listings, vendor sites and independent reviews as of August 2026. Before committing to the specification, I recommend a hands-on teardown: create accounts on Parqet, getquin and BlueBudget, self-host Ghostfolio, and run the **same synthetic portfolio** (deliberately including a stock split, a fund merger, an in-kind custodian transfer, and a USD position held in a CHF base currency) through all four. The divergence in their reported returns will tell you more about where the real requirements lie than any feature list.