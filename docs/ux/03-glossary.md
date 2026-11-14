# UX-04 · Sprint 7 glossary and copy

Use these terms on the [Sprint 7 screens](screens/README.md). German addresses the member with **du**. The German column applies to both markets unless the Swiss column gives a different term.

| EN | DE-DE | DE-CH where different | Definition |
|---|---|---|---|
| Portfolio | Portfolio | | A group of accounts named by the member, optionally linked to an institution. |
| Institution | Institut | | The actual bank or broker behind a portfolio, used for branding and import formats. |
| Depot | Depot | | An account that holds securities positions. |
| Account | Konto | | A record that holds a balance, positions, an asset or a liability. |
| Current account | Girokonto | Privatkonto | An account for everyday payments and deposits. |
| Savings account | Sparkonto | | An account for savings that can also be linked as a savings pocket. |
| Credit card | Kreditkarte | | A card account whose purchases and settlements affect its balance. |
| Pension / Pillar 3a | Vorsorge / Säule 3a | | Retirement assets. Pillar 3a is Swiss tied private pension provision. |
| Property / Mortgage | Immobilie / Hypothek | | Real estate and the loan secured by it, recorded as separate asset and liability accounts. |
| Transaction | Transaktion | | A dated ledger entry. Statement import uses “Buchung” for an entry read from the statement. |
| Booking date / Value date | Buchungsdatum / Valutadatum | | The date a transaction is booked and the date it takes effect for value or interest. |
| Category / Uncategorised | Kategorie / Nicht kategorisiert | | A classification for a transaction, or the state of a transaction without one. |
| Counter-account / Counterparty | Gegenkonto / Gegenpartei | | The account on the other side of a transfer, or the person or organisation on the other side of a transaction. |
| Transfer / Card settlement | Umbuchung / Kreditkartenabrechnung | | Money moved between accounts, or a payment applied to a card balance. |
| Security | Wertpapier | | An identifiable investment instrument, such as a share or fund. |
| Position / Units | Position / Stück | | The holding of one security in a depot, expressed as a quantity. |
| Asset / Liability | Vermögenswert / Verbindlichkeit | | Something owned or a financial obligation owed. |
| Net worth | Nettovermögen | | The server-reported total of assets less liabilities. |
| Balance / Market value | Saldo / Marktwert | | A monetary account balance, or the value of a holding at the applicable market price. |
| Opening balance / Valuation | Anfangssaldo / Bewertung | | A starting balance on a date, or a dated assessment of an asset's or liability's value. |
| Statement / Holdings statement | Kontoauszug / Depotauszug | | A bank or broker record of balances and transactions, or of depot positions. |
| Statement closing balance | Schlusssaldo des Auszugs | | The balance the institution reports at the end of the statement period. |
| Import format / Import template | Importformat / Importvorlage | | A supported kind of statement file, and the mapping used to read it. |
| Import / Import batch | Import / Import | | One statement upload and its review, including the rows saved together on confirmation. |
| Duplicate / Skipped row | Duplikat / Übersprungene Zeile | | A possible repeat of a recorded transaction, or a row excluded from the import. |
| Snapshot | Stand | | A dated record of a statement balance or depot positions. |
| Reconciliation / Reconciled | Abgleich / Abgeglichen | | Comparing the recorded balance with a statement and determining whether they agree. |
| Estimated / Price from {date} | Geschätzt / Kurs vom {date} | | A value based on an approximation, or one based on an older source. |
| Differs from statement / Value unknown | Weicht vom Auszug ab / Wert unbekannt | | A recorded value that disagrees with the statement, or a value the server cannot supply. |
| Exchange rate / Original amount | Wechselkurs / Originalbetrag | | The rate used for a currency conversion, or the amount before conversion. |
| Change history | Änderungsverlauf | | A dated record of changes to a transaction. |
| Workspace (internal) | — | | The tenant that contains the member's portfolios. It has no UI term. |

The first Overview tab uses the member's exact name when set. Its fallback and the name field's placeholder are **All portfolios** / **Alle Portfolios** (`overview.tab.allPortfolios`). Settings uses **Name of your overview** / **Name deiner Übersicht**. Other tabs use the portfolio's own name, followed by **Add portfolio** / **Portfolio hinzufügen**.

## Screen copy

The app catalogues contain the complete [English](../../mobile/src/i18n/en.json) and [German](../../mobile/src/i18n/de.json) messages and the [Swiss](../../mobile/src/i18n/de-CH.json) and [German](../../mobile/src/i18n/de-DE.json) overrides. Use the relevant screen spec for when a message appears.

| Screen | Catalogue keys |
|---|---|
| S1 Overview and name setup | `overview.*`, `setup.overviewName.*`, `settings.overviewName` and its action keys |
| S2 Portfolio View | `portfolio.*`, `section.*` |
| S3 Account detail | `account.*` |
| S4 Transaction detail | `txn.*`, `transactionType.*` |
| S5 Add portfolio | `addPortfolio.*` |
| S6 Add account | `addAccount.*` |
| S7 Import statement | `statement.*` |
| Shared empty states, value statuses and conflicts | `ui.empty`, `valueStatus.*`, `total.incomplete`, `conflict.*` |

Use one ICU message for each sentence or label, with named parameters such as `{date}`, `{amount}` and `{name}`. Use an ICU plural for counts, as in `overview.dataStatus.unreconciled`. Keep date and amount formatting in the existing formatters and pass the formatted values into the message. Keep parameter names stable so translators can reorder them or supply new catalogues without changing screens.

The [API error catalogue](../api/error-codes.md) defines the codes and reactions. Shared app errors use `problem.<CODE>` for known codes and `problem.reaction.<REACTION>` for the status-based fallback. Screen-specific copy takes precedence where the next action needs more context, such as `statement.error.*` for import failures. Import row warnings and errors use the API's localised `message` field. Keep the server's diagnostic problem detail out of user-visible copy.

To add French or Italian, put `fr.json` or `it.json` in `mobile/src/i18n/` with the same message IDs and ICU arguments. Metro discovers the files. A regional catalogue such as `fr-CH.json` can override selected keys. Selection uses the member’s language when set, otherwise the device locale. Copy falls back from regional to base-language messages and then to English.
