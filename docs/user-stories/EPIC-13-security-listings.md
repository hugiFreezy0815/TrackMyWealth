# EPIC 13 — Security Listings & Exchanges

Covers `listing` (V8). Section 20, FR-LST-*, DM-26.

---

## US-13-01 — A security may have multiple listings across exchanges and currencies

**Actor:** Household member / System
**Objective:** FR-LST-001, DM-26 — one ISIN, several exchange listings in different currencies.
**Story:** As the system, I want a security to support multiple exchange listings, each with its
own MIC, ticker and trading currency, so that an Irish-domiciled ETF quoted on SIX in CHF and on
XETRA in EUR is modelled correctly rather than forcing one currency onto the whole instrument.
**Preconditions:** A `security` row exists.
**Acceptance criteria:**
- Given the same ISIN, when two `listing` rows are created (SIX/CHF and XETRA/EUR), then both
  reference the same `security_id`, each with its own `mic`/`ticker`/`trading_currency`.
- Given one listing is marked `is_primary_listing = true`, when a second listing attempts the
  same flag, then it is rejected by the partial unique index (`uq_listing_one_primary_per_security`
  in V8).
**Applicable business rules:** FR-LST-001/002/003, DM-26.
**Data requirements:** `mic` (ISO 10383) where available, `trading_currency` required.
**Error/edge cases:** A security with zero listings (e.g. an OTC/manual-only instrument) must
still be usable for manual pricing (EPIC 14).
**Authorization/privacy:** N/A (global data).
**Dependencies:** EPIC 12.
**Priority:** MUST.
**Definition of Done:** Integration test creates the SIX/XETRA scenario above.
**Data-quality behaviour:** N/A.

---

## US-13-02 — Valuation listing selection is explicit and inspectable

**Actor:** Household member
**Objective:** FR-LST-004 — the same ETF quoted on two exchanges in two currencies is common in
the target market; the rule for which price values a given position must be explicit.
**Story:** As a household member holding the same ETF bought on two different exchanges in two
depots, I want each position to be valued using the listing it was actually acquired on (falling
back to the primary listing when that is not known), so that my valuation is not silently wrong
because the "other" listing's price was used.
**Preconditions:** Two `listing` rows for one security; two positions in different accounts, each
originally bought on a different listing.
**Acceptance criteria:**
- Given a position acquired via a transaction referencing the XETRA/EUR listing, when the
  position is valued, then the XETRA/EUR price series is used, not the SIX/CHF one.
- Given a position with no recorded acquisition listing (e.g. entered via an opening balance with
  no listing detail), when valued, then the primary listing's price is used, and this fallback is
  visible/inspectable in the "explain this number" view (FR-PERF-016).
**Applicable business rules:** FR-LST-004.
**Data requirements:** A way to record which listing a position/transaction refers to — note:
`transaction.security_id` currently references the security, not a specific listing; document
this as a required refinement for the developer picking up this story (either add a nullable
`listing_id` to `transaction`, or derive "acquisition listing" from the transaction's currency
matching a listing's trading currency) and confirm the approach during sprint planning before
implementation, since it affects the V10 schema.
**Error/edge cases:** A position whose transactions were acquired on two different listings over
time (partial transfer between depots) — the valuation rule must be deterministic and documented,
not silently pick "whichever listing has a price today."
**Authorization/privacy:** N/A.
**Dependencies:** US-13-01, EPIC 07, EPIC 14.
**Priority:** MUST.
**Definition of Done:** This is V-12 in the golden verification dataset (EPIC 27) — "same ETF in
two depots, two listings, two currencies — cross-depot consolidation; listing selection."
**Data-quality behaviour:** A valuation using the fallback (primary-listing) rule rather than the
acquisition listing must be flagged as such in the "explain this number" view.

---

## US-13-03 — Trading calendar distinguishes non-trading days from missing data

**Actor:** System
**Objective:** FR-LST-005 — prerequisite for the daily valuation series behind TWR.
**Story:** As the system, I want each listing to carry a trading calendar (holidays, timezone),
so that a weekend or market holiday is never mistaken for a data gap when building the daily
valuation series.
**Preconditions:** A `trading_calendar` row (V18) and `listing.trading_calendar_code` set.
**Acceptance criteria:**
- Given a listing on the SIX calendar, when the daily valuation job runs on a Swiss public
  holiday, then it correctly carries forward the last known price (per FR-PRC-011) rather than
  flagging the day as a data gap requiring alert.
- Given the same listing on a genuine trading day with no price available (a real gap — e.g. a
  provider outage), when the job runs, then it *is* flagged as a gap/staleness condition,
  distinct from the holiday case above.
**Applicable business rules:** FR-LST-005, FR-PRC-008/011/012.
**Data requirements:** `trading_calendar_holiday` rows for the relevant market(s).
**Error/edge cases:** A listing with no `trading_calendar_code` set — must default to a
conservative "every weekday is a trading day" assumption and flag itself as such, not silently
mis-handle every holiday as a gap.
**Authorization/privacy:** N/A.
**Dependencies:** US-13-01, EPIC 14.
**Priority:** MUST.
**Definition of Done:** This is exercised by V-17 in the golden dataset ("price series with a
market holiday and a trading suspension — carry-forward versus gap; no interpolation").
**Data-quality behaviour:** As above — the whole point of this story is a data-quality
distinction.
