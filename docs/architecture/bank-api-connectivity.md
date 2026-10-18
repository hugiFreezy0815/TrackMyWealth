# Bank API connectivity — provider feasibility (#263)

Last researched: 2026-10-18. This analysis uses public provider documentation and the
project's existing design only. **No real account, login, PIN, TAN, token, transaction, IBAN,
bank statement, or bank API response was accessed.** The product owner must confirm actual
bank support and consent behavior using their own account locally.

## Decision: keep Enable Banking as a conditional second provider (no production integration yet)

**Conditional GO for the provider abstraction; NO-GO for committing an actual connector before
#259 (FinTS spike), agreement to the restricted production terms, and bank-by-bank checks.**

Enable Banking provides a REST API that can be used by an application rather than connecting
directly to PSD2 bank interfaces as an unlicensed AISP. A production application can be
activated in *restricted mode* by linking the developer's own accounts to it; only those
explicitly linked accounts return data, and "applications can't be made available to the public
before a contract is signed" (provider FAQ). This is a potential fit for a *self-hosted,
own-accounts* installation, but does **not** authorize a publicly hosted multi-user service.
The FAQ does not say whether restricted mode itself needs a contract, nor state usage limits:
the operator must review the current terms before real use. Avoid assuming the restricted tier
remains free indefinitely.

The FinTS-first ADR planned by #259 has not been written or accepted. Do not create a
competing ADR in this spike; the FinTS ADR should record Enable Banking as a possible
optional adapter, with a separate activation decision once account-level tests are known.

### Status of the spike's questions

| Question | Finding without private account data | Status |
|---|---|---|
| DKB availability | Enable Banking's supported-bank listing includes DKB as an institution. This does not verify the availability of a particular member's DKB account, or the actual transaction fields. | **Partially confirmed; account-level unverified** |
| Sparkasse Freiburg availability | The provider offers German Sparkasse connections, but a particular Sparkasse Freiburg ASPSP and its coverage/features must be checked using the current provider directory. | **Unverified for this branch/account** |
| Bank references, ISO bank transaction codes, pending rows | The API's transaction schema has `entry_reference`, `transaction_id`, `bank_transaction_code` (`code`, `sub_code`) and a `status`; `transaction_status` filters the list. Which fields DKB/Sparkasse actually fill, and whether a reference matches the one in their CSV, is **not** demonstrated without a live connection. Never assume `transaction_id` or `entry_reference` equals the bank's `AcctSvcrRef`. Note: the provider defines `PDNG` as "Instant Balance Transaction (ISO20022 Expected)", not as a generic pending booking. | **Unverified** |
| Consent life and unattended sync | `POST /auth` takes `access.valid_until`, capped per ASPSP at now + `maximum_consent_validity` (`GET /aspsps`; the documented example is 180 days). It is not a universal 180-day entitlement; a session can also end as `EXPIRED` or `REVOKED`. Re-consent or SCA may interrupt scheduled sync. | **Partially confirmed; unattended behavior unverified** |
| Restricted tier licensing, personal use, hosting | The provider FAQ describes restricted mode as linking one's own accounts to a production application; public availability needs a contract. Check current terms, permitted hosting, processing, rate limits and data residency before storing real credentials. | **Conditional** |
| Existing import pipeline fit | The project has `CanonicalImportRow` (incl. `externalId` and `iso20022BankTransactionCode`), `ImportStagingService`, `ImportBatchService` and the `import_batch.source_kind` value `API` (V67). A provider adapter can map booked transactions into the existing preview-first workflow without automatically committing. See *Source labelling* below for the `AGGREGATOR` gap. | **Architecturally feasible** |

### Proposed provider contract after #259

One provider-neutral `BankConnectivityProvider` interface should own these **logical**
operations, not leak Enable Banking's JSON types into ledger services:

1. `beginAuthorization(member, institution, redirectUri)` → an opaque, time-limited
   consent attempt and a redirect URL. Validate return state and redirect origins; never
   accept an arbitrary callback URL.
2. `completeAuthorization(state)` → an encrypted provider session/consent reference with
   member/workspace ownership and actual expiry. Do not confuse a short-lived session token
   with a long-lived authorization agreement.
3. `listAccounts(connection)` → only account *proposals*, masked identifiers and capabilities.
   Members must confirm linking; never silently create accounts.
4. `readBookedTransactions(connection, accountRef, from, to)` → a bounded page of provider
   rows mapped to `CanonicalImportRow` with dates, signed decimal amount, currency,
   description, external id and available ISO 20022 transaction code.
5. `readBalance(connection, accountRef)` → a dated, booked balance; create a snapshot only
   when the corresponding import is committed.
6. `refreshOrReauthorize(connection)`, `disconnect(connection)`, and
   `capabilities(connection)` → stable states, no raw bank messages, no stored PIN/TAN.

The `EnableBankingProvider` adapter would call its authorization/session/account/
transaction endpoints over TLS, with a fixed provider base URL and timeouts. Every request
carries a bearer JWT signed `RS256` with the application's private key (`kid` = application
id, at most 24 h lifetime). That private key is the instance's master credential for all
linked accounts: keep it out of the repository, images and PostgreSQL, supply it as
deployment configuration like `JWT_SECRET` and `MFA_ENCRYPTION_KEY`, with no default value
and a startup check that fails without it (as `JwtSecretPolicy` does), and mint short-lived
tokens per call batch rather than one 24 h token. Use the provider's documented
continuation keys; bound total pages and date ranges. Preserve decimal strings as
`BigDecimal`, never JavaScript/Java `double`. Request booked rows only
(`transaction_status=BOOK`): the ledger has no pending state, and a `PDNG` row committed now
would duplicate its later booked version.

Duplicates across CSV, FinTS and aggregator sources must continue to pass through
`ImportDuplicateService`. Its **reference** match is scoped to `(account, source,
external_id)`, like the unique index `uq_transaction_external_id` (V69), so an aggregator
`transaction_id` never matches a CSV or FinTS reference. Cross-source duplicates are found
only by the fallback key (booking date, amount, currency); the live check must confirm that
the provider's booking dates match the CSV's, so an overlapping CSV import of the same
period is caught.

**Source labelling.** `transaction.source` already accepts `AGGREGATOR` (V10), as #263
question 4 asks. The gap is upstream: commit writes the batch's `import_batch.source_kind`
as the transaction's source, and `chk_import_batch_source_kind` (V67) allows only `CSV`,
`DOCUMENT` and `API`. The follow-up story must choose explicitly between adding
`AGGREGATOR` to that constraint (one forward migration, plus the source used by the
reference duplicate match) or recording aggregator bookings as `API` with the provider in
the connection metadata. Never label aggregator bookings as FinTS.

### Consent, privacy and scheduled-sync rules

- Use state/nonce validation and explicit allowlisted redirects for consent callback
  protection. Session IDs and tokens are secrets and cannot appear in logs, analytics,
  crash reports or plain API responses.
- Encrypt persisted provider session ids at rest with AES-256-GCM and a fresh IV per value,
  the key outside PostgreSQL, as `MfaEncryptionService` does for TOTP secrets. That service
  has a single key; rotation (key id stored with the ciphertext) is new work for this story.
  Delete the stored session and revoke it at the provider
  (`DELETE /sessions/{session_id}`) on disconnect, member removal, expired consent or
  account unlink.
- Isolate every connection and callback to the initiating member and workspace. A new
  connection table carries `workspace_id` under `ENABLE` + `FORCE ROW LEVEL SECURITY`
  (`SchemaConventionsTest`), and a connection id of another workspace is denied through
  `AuthorizationDenialAuditService`, the one audited generic 404, never a raw `NOT_FOUND`.
- Store in `raw_source_data` only the mapped provider fields needed for evidence, never
  session ids, JWTs or the provider's full response.
- Schedule only within an actively consented period. Detect expiration and surface
  `SCA_REQUIRED` / `RECONSENT_REQUIRED`; **never claim unattended synchronization
  will continue without SCA**.
- A scheduled fetch leaves an import batch in `PARSED` for member review; **no automatic
  commit**. Today an account may hold several open batches up to `maxOpenBatches`
  (`IMPORT_TOO_MANY_OPEN_BATCHES`); the follow-up should allow at most one open *sync* batch
  per account, so a later fetch cannot pile up beside a batch still under review.
- Restrict to read-only endpoints and minimal scopes. Do not implement transfers or payments
  through the aggregator. Retain CSV/PDF import as the fallback.

### One-day follow-up checklist (local-only)

The operator checks, on the provider's current directory and own account, **without
posting account data to issues or CI**:

- Is DKB listed and functional for this particular login? Is Sparkasse Freiburg
  (exact institution) present with account-information access?
- Which account types are returned, and are date range, balance and booked/pending
  transaction APIs supported?
- Are `AcctSvcrRef`, ISO 20022 bank transaction codes, merchant/reference data, and
  stable transaction identifiers available? Report only **yes/no/partial** per field;
  no raw values.
- What are the actual `valid_until`/expiry and reauthorization requirements for
  these two connections? Can any booked-transaction sync succeed after the member
  has left the app, without a fresh SCA challenge?
- Confirm permitted self-hosting and restricted-tier limits with the provider's
  current terms; hosted/multi-user use needs independent legal/product approval.

**Recommendation:** do not start #260/#261/#264 as an Enable Banking implementation
solely from this public research. Complete #259, then confirm the checklist. If supported,
implement a thin provider adapter behind the common interface, use synthetic JSON fixtures
for tests, and add a time-boxed live smoke test that reports only counts and booleans.

### References

- Restricted mode, linked accounts and contract (provider FAQ):
  https://enablebanking.com/docs/faq/
- Linking own accounts for restricted use: https://enablebanking.com/docs/api/linked-accounts/
- Enable Banking API documentation (JWT authentication, `POST /auth`, transactions,
  `TransactionStatus`): https://enablebanking.com/docs/api/reference/
- Supported institutions and `maximum_consent_validity` (live provider catalogue):
  https://enablebanking.com/docs/api/reference/#get-aspsps
- Account and authorization lifecycle: https://enablebanking.com/docs/
- Existing internal dependencies: #259 (FinTS ADR), #260 (bank connection), #261
  (import preview), #263 (this assessment), #264 (user-selected import mode), #265 (mobile UX).

Research is limited by the absence of live provider credentials and private samples;
**these are explicit pending acceptance checks, not implicit successes**.
