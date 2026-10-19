# Import source analysis (spike #238)

Which of the product owner's real bank documents can create or identify the container
(institution and account), and which can import balances, transactions or positions. Written
before #229/#230 fix their template scope (FR-IMP-020..025, FR-INS-010, FR-REC-002/007,
INPUT-001..005).

**Data protection.** The samples are real data and stay on the product owner's machine
(`docs/import-samples/`, git-ignored). This document describes structure only. Documents are
referred to by an id, never by file name (some file names contain an IBAN). Every example value
is invented and every identifier masked (`DE00 •••• 0000`). The samples were read locally in a
container without network access; nothing was uploaded to any tool or service.

Analysed on 2026-10-04: 20 documents from 5 institutions.

## 1. Inventory

| Id | Institution | Document | Format | Kind |
|---|---|---|---|---|
| DKB-1 | DKB | Transaction list, current account (Girokonto) | CSV, UTF-8 with BOM, `;` | Transaction list |
| DKB-2 | DKB | Current-account statement with card payments, added 2026-10-18 | PDF, text | Account statement |
| PF-1 | PostFinance | Account movements, private account | CSV, UTF-8 with BOM, `;`, CRLF | Transaction list |
| PF-2 | PostFinance | Account movements, savings account | CSV, same format as PF-1 | Transaction list |
| PF-3 | PostFinance | E-Trading transactions | CSV, windows-1252, `;` | Securities and cash transactions |
| PF-4 | PostFinance | E-Trading list of assets (cash and positions per currency) | XLS (BIFF) | Balance / asset statement |
| PF-5 | PostFinance | E-Trading positions | XLSX | Holdings statement |
| PF-6 | PostFinance | E-Trading account overview | PDF, text | Balance and holdings statement |
| PF-7 | PostFinance | E-Trading portfolio performance report | PDF, text | Holdings and performance statement |
| PF-8 | PostFinance | Pillar 3a account, E-Finance browser print | PDF, text (printed web page) | Balance and holdings statement |
| PF-9 | PostFinance | Private-account statement, added 2026-10-18 | PDF, text | Account statement |
| PF-10 | PostFinance | Savings-account statement with interest statement, added 2026-10-18 | PDF, text | Account statement |
| PF-11 | PostFinance | E-Trading account statement (buys, sells, dividends), added 2026-10-18 | PDF, text | Account statement |
| PF-12 | PostFinance | E-Trading portfolio valuation, added 2026-10-18 | PDF, text | Holdings statement |
| PF-13 | PostFinance | Single E-Trading notices: trade confirmation (stamp duty, fees), dividend, cash movement, added 2026-10-18 | PDF, text, one page each | Transaction notice |
| PF-14 | PostFinance | Pillar 3a notices: fund-unit statement, income booking, added 2026-10-18 | PDF, text, one page each | Transaction notice |
| PF-15 | PostFinance | Private-account single-booking notice, added 2026-10-18 | PDF, text, one page | Transaction notice |
| SPK-1 | Sparkasse Freiburg | Transaction list, current account (18-column CSV with `Kategorie`) | CSV, windows-1252, `;` | Transaction list |
| SPK-2 | Sparkasse Freiburg | Transaction list, savings-type account (same 18-column format) | CSV, windows-1252, `;` | Transaction list |
| SPK-3 | Sparkasse Freiburg | Transaction list, loan account, "CSV-CAMT V8" (17 columns) | CSV, windows-1252, `;` | Transaction list |
| SPK-4 | Sparkasse Freiburg | Transaction list, loan account, "CSV-MT940" (11 columns) | CSV, windows-1252, `;` | Transaction list (same bookings as SPK-3) |
| SPK-5 | Sparkasse Freiburg | Credit card transactions | CSV, windows-1252, `;` | Card statement |
| SPK-6 | Sparkasse Freiburg | Annual loan account statement (Jahreskontoauszug) | PDF, text | Account statement |
| SPK-7 | LBS Süd (via Sparkasse) | Annual building-savings statement (Bausparvertrag) | PDF, text | Account statement |
| SPK-8 | Sparkasse Freiburg | Monthly credit-card statement (Mastercard), added 2026-10-16 (#339) | PDF, text | Card statement |
| SPK-9 | Sparkasse Freiburg | Current-account turnover as `camt.052.001.02`, one report per day, added 2026-10-18 | ZIP of XML files, or a single XML | Account report |
| SPK-10 | Sparkasse Freiburg | The same turnover as `camt.052.001.08`, added 2026-10-18 | ZIP of XML files, or a single XML | Account report |
| SPK-11 | Sparkasse Freiburg | The same turnover as MT940, added 2026-10-18 | TXT (SWIFT), CRLF | Account statement |
| SPK-12 | Sparkasse Freiburg | Intraday report as MT942, added 2026-10-18 | TXT (SWIFT), CRLF | Account report |
| TW-1 | True Wealth | Contract bundle: asset management mandate and pillar 3a agreement | PDF, text | Contract (other) |
| YUH-1 | Yuh | Account statement, multi-currency, with savings pocket | PDF, text | Account statement |
| YUH-2 | Yuh | Portfolio performance report | PDF, text | Holdings and performance statement |
| YUH-3 | Yuh | Swiss tax statement (Steuerauszug), eCH-0196 v2 (6.5.1) | PDF, text + Code 128 per page + PDF417 barcode pages | Tax statement |
| YUH-4 | Yuh | Single-booking notices (debit, credit), added 2026-10-18 | PDF, text, one page each | Transaction notice |
| YUH-5 | Yuh | Securities buy confirmation (stamp duty, commission), added 2026-10-18 | PDF, text, one page | Transaction notice |

No document is scanned: every PDF has a text layer, so none needs OCR. SPK-3 is the Sparkasse's
CSV rendering of camt.052 (17 columns, see 3.4); the XML itself (SPK-9, SPK-10) was added on
2026-10-18 with the download check of spike #277 (6.5.1). The same check added newer exports in
the DKB-1, SPK-1, SPK-4 and PF-1 formats (unchanged headers) and copies of YUH-1 and YUH-3,
which need no id of their own. The PDF notices' small Data Matrix codes hold a short
document id (no XML), not import data.
TrueWealth and Yuh, listed as empty in the issue, now have samples, but neither has a
transaction export (see section 5).

## 2. Capability matrix

`yes` / `partial` / `no`, each with its reason. "Institution" means the document names the
institution well enough to pick it from `institution_catalogue`; "account" means it carries an
identifier that could match or create an `account`.

| Id | Institution | Account | Opening balance | Closing balance | Cash transactions | Securities transactions | Holdings | API alternative |
|---|---|---|---|---|---|---|---|---|
| DKB-1 | partial: only implied by the format | yes: IBAN and account kind in the preamble | no | yes: "Kontostand vom <date>" in the preamble | yes | no | no | FinTS (#259) |
| PF-1, PF-2 | partial: implied by the format | yes: IBAN and currency in the preamble | no | no: no balance anywhere | yes | no | no | none |
| PF-3 | partial: implied | no: no account number in the file | no | partial: running balance per currency, not a statement balance | yes: deposits, withdrawals, interest, fees, FX | yes: buy, sell, dividend, corporate actions | no | none |
| PF-4 | no | no | no | yes: cash per currency, date only in the file name | no | no | partial: position value per currency, no securities | none |
| PF-5 | no | no | no | no | no | no | partial: ticker only, no ISIN; date only in the file name | none |
| PF-6 | yes | yes: account number | no | yes: cash per currency, dated | no | no | partial: ticker only | none |
| PF-7 | yes | yes: account number and holder | no | yes: cash per currency with FX rate, dated | no | no | yes: ISIN, quantity, average price, market price, price date | none |
| PF-8 | yes | yes: pillar 3a account number | no | yes: account balance, dated | no | no | yes: fund ISIN, units, price, date | none |
| SPK-1, SPK-2 | yes: BIC per counterparty, own BLZ implied | yes: own IBAN in every row (`Auftragskonto`) | no | no | yes | no | no | FinTS (#259) |
| SPK-3 | yes | yes: own IBAN in every row | no | no | yes | no | no | FinTS (#259) |
| SPK-4 | yes | yes: own IBAN in every row | no | no | yes, but duplicates SPK-3 | no | no | FinTS (#259) |
| SPK-5 | partial | partial: masked card number in every row (last four digits only) | no | no | yes: with original amount, currency and FX rate | no | no | FinTS for the card is bank-specific (#259) |
| SPK-6 | yes: name, BIC, BLZ | yes: loan IBAN, product, approved amount, rate, fixed term | yes: "Kontostand am <start of year>" | yes: balance at the end of the period | yes: instalments, interest | no | no | FinTS (#259) |
| SPK-7 | yes: LBS Süd | yes: contract number, tariff, contract sum, start date | yes: "Saldovortrag" | yes: "Ihr Bausparguthaben am <date>" | yes: deposits, interest | no | no | none known |
| SPK-8 | partial | partial: masked card number on the title line | yes: dated "Saldo ... Abrechnung" line | yes: "Neuer Saldo", dated by the period's end | yes: purchases, the payment received (sign after the amount) | no | no | FinTS for the card is bank-specific (#259) |
| TW-1 | yes: True Wealth, custodian bank, pension foundation | partial: customer number and product, no account number | no | no | no | no | no | none |
| YUH-1 | yes: Swissquote as the bank, SWIFT | yes: IBAN, customer number | yes: "Anfangssaldo" / "Saldo per <start>" | yes: "Endsaldo" / "Saldo per <end>", per currency | yes: with value date and running balance | partial: buy/sell/dividend as cash bookings only | no | none |
| YUH-2 | yes | yes: account number and IBAN | no | yes: liquidity per date | no | no | yes: shares, funds, crypto | none |
| YUH-3 | yes | partial: customer number | no | partial: tax values at year end | no | no | partial: tax value per position at year end; machine-readable through the eCH-0196 barcode (confirmed, 6.5.1) | none |

## 3. Formats in detail

All structure below is as observed; values in the examples are invented.

### 3.1 DKB-1: transaction list (current account)

- UTF-8 with BOM, LF, `;`, every cell quoted.
- Four physical preamble lines, then the header:
  1. the account kind (`Girokonto`) and the IBAN,
  2. empty,
  3. `Kontostand vom <dd.MM.yyyy>:` and the balance with a `€` suffix,
  4. empty.
- 12 columns: `Buchungsdatum`, `Wertstellung`, `Status`, `Zahlungspflichtige*r`,
  `Zahlungsempfänger*in`, `Verwendungszweck`, `Umsatztyp`, `IBAN`, `Betrag (€)`, `Gläubiger-ID`,
  `Mandatsreferenz`, `Kundenreferenz`.
- Dates `dd.MM.yy` (two-digit year). One signed amount column, decimal `,`, trailing zeros
  dropped (`-7,5`, `-89`). `Umsatztyp` is `Eingang`/`Ausgang` (redundant with the sign).
- `Status` was `Gebucht` on every row of the sample. The column exists because the export can
  also list pending bookings (expected value `Vorgemerkt`, not in the sample; see section 5, answer 3).
  Those must not be imported, because they come back booked later (duplicate risk, see 6.1).
- `Kundenreferenz` is a numeric reference on most rows and empty on some: usable as `externalId`
  where present, not unique enough to be the only duplicate key.
- No trailer rows.

```csv
"Girokonto";"DE00 0000 0000 0000 0000 00"
""
"Kontostand vom 31.01.2031:";"1.111,11 €"
""
"Buchungsdatum";"Wertstellung";"Status";"Zahlungspflichtige*r";"Zahlungsempfänger*in";"Verwendungszweck";"Umsatztyp";"IBAN";"Betrag (€)";"Gläubiger-ID";"Mandatsreferenz";"Kundenreferenz"
"28.02.31";"28.02.31";"Gebucht";"Erika Beispiel";"Muster Markt GmbH";"Einkauf 0815";"Ausgang";"DE00 1111 1111 1111 1111 11";"-17,9";"";"";"100000000000001"
"29.01.31";"29.01.31";"Gebucht";"Beispiel AG";"Erika Beispiel";"Lohn September";"Eingang";"DE00 2222 2222 2222 2222 22";"2.987";"";"";""
```

### 3.2 PF-1 / PF-2: PostFinance account movements

- UTF-8 with BOM, CRLF, `;`.
- Six physical preamble lines, values written in Excel's `="…"` form:
  `Datum von:`, `Datum bis:`, `Kategorie:` (`="Alle"`), `Konto:` (`="<IBAN>"`), `Währung:`
  (`="CHF"`), empty.
- Header, then one empty record, then the data.
- 7 columns: `Datum`, `Bewegungstyp` (`Buchung`), `Avisierungstext`, `Gutschrift in CHF`,
  `Lastschrift in CHF`, `Label`, `Kategorie`. The currency is part of the column name.
- Dates `dd.MM.yyyy`. Separate credit and debit columns; the debit is **already negative**.
  Decimal `.`, no thousands separator, trailing zeros dropped.
- `Kategorie` is PostFinance's own categorization (a candidate input for US-08-01).
- Trailer: two records, `Disclaimer:` and one line of text.
- No balance, no reference id: duplicate detection must hash date + amount + text.

```csv
Datum von:;="01.10.2030"
Datum bis:;="31.01.2031"
Kategorie:;="Alle"
Konto:;="CH00 0000 0000 0000 0000 0"
Währung:;="CHF"

Datum;Bewegungstyp;Avisierungstext;Gutschrift in CHF;Lastschrift in CHF;Label;Kategorie

28.02.2031;Buchung;"Einkauf Musterladen Beispielstadt";;-17.9;;Lebensmittel
27.01.2031;Buchung;"Gutschrift Beispiel AG Lohn";2987;;;Einkommen

Disclaimer:
"Dieser Auszug dient nur zur Information."
```

### 3.3 PF-3: PostFinance E-Trading transactions

- windows-1252, CRLF, `;`, header in row 0, no preamble, no trailer.
- 13 columns: `Datum` (`dd-MM-yyyy HH:mm:ss`), `Auftrag #`, `Transaktionen`, `Symbol`, `Name`,
  `ISIN`, `Anzahl`, `Stückpreis`, `Kosten`, `Aufgelaufene Zinsen`, `Nettobetrag`, `Saldo`,
  `Währung`.
- Multi-currency (CHF, EUR, USD in one file): `Saldo` is the running cash balance **of that
  currency**; `Nettobetrag` is `-` on non-cash rows (e.g. a split).
- 22 transaction types: `Kauf`, `Verkauf`, `Dividende`, `Capital Gain`, `Einzahlung`,
  `Auszahlung`, `Depotgebühr`, `Zinsen auf Belastungen`, `Forex-Gutschrift`/`-Belastung`,
  `Fx-Gutschrift Comp.`/`Fx-Belastung Comp.`, `Berichtigung Börsengeb.`, `Corporate Action`,
  `Reverse Split`, `Spin Off`, `Ausgabe von Anrechten`, `Ausübung von Anrechten`,
  `Titelumbuchung`, `Interne Titelumbuchung`, `Rückzahlung`, `Wertlose Ausbuchung`.
- No account number. Quantity, price, cost and ISIN per row: everything a
  `SECURITIES_TRANSACTIONS` template needs, but its import depends on EPIC 15 (lots, corporate
  actions) and is not a sprint 5 topic.

### 3.4 SPK-1..4: Sparkasse transaction lists

- windows-1252, LF, `;`, every cell quoted, header in row 0, no preamble, no trailer.
- Three column sets of the same export family. The header is ASCII-transliterated
  (`Waehrung`, `Glaeubiger ID`):
  - 18 columns (SPK-1/2): `Auftragskonto`, `Buchungstag`, `Valutadatum`, `Buchungstext`,
    `Verwendungszweck`, `Glaeubiger ID`, `Mandatsreferenz`, `Kundenreferenz (End-to-End)`,
    `Sammlerreferenz`, `Lastschrift Ursprungsbetrag`, `Auslagenersatz Ruecklastschrift`,
    `Beguenstigter/Zahlungspflichtiger`, `Kontonummer/IBAN`, `BIC (SWIFT-Code)`, `Betrag`,
    `Waehrung`, `Info`, `Kategorie`;
  - 17 columns (SPK-3, "CSV-CAMT V8"): the same without `Kategorie`;
  - 11 columns (SPK-4, "CSV-MT940"): `Auftragskonto`, `Buchungstag`, `Valutadatum`,
    `Buchungstext`, `Verwendungszweck`, `Beguenstigter/Zahlungspflichtiger`, `Kontonummer`,
    `BLZ`, `Betrag`, `Waehrung`, `Info`.
- Dates `dd.MM.yy`. One signed amount, decimal `,`. Currency per row (`Waehrung`, `EUR`).
- `Auftragskonto` is the **own account's IBAN on every row**: the file identifies its account
  (strategy `COLUMN`).
- `Buchungstext` is a short booking-type code (`GUTSCHRIFT`, `DARLEHENSZINSEN`, `ABSCHLUSS`,
  standing-order and transfer texts...): input for `type_mapping`.
- `Kundenreferenz (End-to-End)` is almost always empty; `Mandatsreferenz` and `Sammlerreferenz`
  are filled only for direct debits and batch bookings. There is no stable per-booking id.
- SPK-3 and SPK-4 are the same loan account and hold exactly the same bookings in two formats.

```csv
"Auftragskonto";"Buchungstag";"Valutadatum";"Buchungstext";"Verwendungszweck";"Glaeubiger ID";"Mandatsreferenz";"Kundenreferenz (End-to-End)";"Sammlerreferenz";"Lastschrift Ursprungsbetrag";"Auslagenersatz Ruecklastschrift";"Beguenstigter/Zahlungspflichtiger";"Kontonummer/IBAN";"BIC (SWIFT-Code)";"Betrag";"Waehrung";"Info"
"DE00 0000 0000 0000 0000 00";"28.02.31";"28.02.31";"FOLGELASTSCHRIFT";"Beitrag 01/2031";"DE00ZZZ00000000000";"M-0001";"";"";"";"";"Beispiel Versicherung AG";"DE00 3333 3333 3333 3333 33";"XXXXDEXXXXX";"-19,87";"EUR";"Umsatz gebucht"
"DE00 0000 0000 0000 0000 00";"28.02.31";"28.02.31";"DARLEHENSZINSEN";"Zinsen 01/2031";"";"";"";"";"";"";"";"0000000000";"00000000";"-98,76";"EUR";"Umsatz gebucht"
```

### 3.5 SPK-5: Sparkasse credit card

- windows-1252, `;`, header in row 0.
- 16 columns: `Umsatz getätigt von` (the card number, masked: only some digits visible),
  `Belegdatum`, `Buchungsdatum`, `Originalbetrag`, `Originalwährung`, `Umrechnungskurs`,
  `Buchungsbetrag`, `Buchungswährung`, `Transaktionsbeschreibung`,
  `Transaktionsbeschreibung Zusatz`, `Buchungsreferenz`, `Gebührenschlüssel`,
  `Länderkennzeichen`, `BAR-Entgelt+Buchungsreferenz`, `AEE+Buchungsreferenz`,
  `Abrechnungskennzeichen`.
- Dates `dd.MM.yy`; `Buchungsbetrag` signed, decimal `,`; the booking currency per row.
- Original amount, original currency and FX rate are separate columns. Since the template's
  version 2 (V91, #327) `Originalbetrag` and `Originalwährung` are the original amount and
  currency (`0,00` with an empty currency is none); `Umrechnungskurs` stays in `raw_source_data`,
  as the ledger derives the rate from the billed amount.
- `Buchungsreferenz` is not unique (a small running number).

```csv
"Umsatz getätigt von";"Belegdatum";"Buchungsdatum";"Originalbetrag";"Originalwährung";"Umrechnungskurs";"Buchungsbetrag";"Buchungswährung";"Transaktionsbeschreibung";"Transaktionsbeschreibung Zusatz";"Buchungsreferenz";"Gebührenschlüssel";"Länderkennzeichen";"BAR-Entgelt+Buchungsreferenz";"AEE+Buchungsreferenz";"Abrechnungskennzeichen"
"0000 00XX XXXX 0000";"27.01.31";"29.01.31";"37,00";"CZK";"0,04";"-1,49";"EUR";"Muster Online Shop";"Beispielstadt";"1";"0000";"";"";"";""
```

### 3.6 Spreadsheets (PF-4, PF-5)

- PF-4 (XLS, one sheet, 7 columns): `Währung`, `Kurs`, `Kontosaldo`, `Positionswert`,
  `Totalwert`, `Bewertung CHF`, `Portfolio %`; one row per currency plus a total row. No date,
  no account number inside the file; the file name holds only the date.
- PF-5 (XLSX, one sheet, 13 columns): an unnamed group column, `Symbol`, `Anzahl`,
  `Einstandskurs`, `Totalwert`, `Tagesveränderung`, `Diff. Vortag %`, `Preis`, `Währung`,
  `G&V Nominalwert CHF`, `G&V % CHF`, `Totalwert CHF`, `Positionen %`. Group rows (`Aktien`,
  `ETFs`) and a total row are interleaved with the positions. **No ISIN**: the security can only
  be resolved through the ticker, which PF-3 maps to an ISIN.
- Both carry their date only in the file name; PF-5's file name also holds the account number.

### 3.7 PDFs

All PDFs have a text layer and stable label texts, but tables come out as text lines, not cells:
a PDF template is a set of label anchors and line patterns, not a column mapping.

- **PF-6 / PF-7 / YUH-2** share one Swissquote layout (PostFinance E-Trading and Yuh are both
  operated by Swissquote): account number, valuation date, cash per currency with FX rate,
  positions by asset class. PF-7 and YUH-2 list ISIN, quantity, average and market price, price
  date and value; PF-6 lists tickers only. One template would cover both institutions.
- **PF-8** is a browser print of an E-Finance page (`Direktausdruck aus E-Finance`): its layout
  follows the web page and can change without notice. Not a stable import source.
- **SPK-6** (annual loan statement): IBAN and loan terms in the header (product, approved amount,
  instalment, interest rate and fixed term), `Kontostand am <date>` at the start and at the end,
  bookings as `<date> <text> [/ Wert: <date>] <amount>` lines with debit/credit columns, and a
  yearly summary (interest and other costs).
- **SPK-7** (LBS building savings): contract number, tariff, contract sum, start date,
  `Saldovortrag`, bookings (`Einzahlung`, interest) with booking and value date printed without
  a space between them, and `Ihr Bausparguthaben am <date>`. Pages 2-3 are explanatory text.
- **YUH-1** (account statement): summary page (opening and closing balance, balance per currency
  and in the savings pocket, IBAN, customer number, SWIFT), then one section per currency with
  `DATUM | INFORMATION | REFERENZ | BELASTUNG | GUTSCHRIFT | VALUTA-DATUM | SALDO`. Booking types
  seen: payment, savings deposit, automated saving, buy, sell, dividend, opening and closing
  entries. Securities trades appear only as their cash leg.
- **YUH-3** (Swiss tax statement): tax values and income per position at year end, summary for
  the tax return forms. Every page carries one tall, narrow image, most likely the barcode of the
  Swiss e-tax statement standard eCH-0196 (structured XML). Not verified: there is no barcode
  decoder in this analysis.
- **TW-1** is a contract bundle (asset management mandate, pillar 3a agreement, signature page):
  it proves the relationship (customer number, custodian bank, product) but holds no balance,
  transaction or position.

## 4. Recommended primary source per institution

| Institution | Container details | Cash transactions | Balances (snapshot) | Positions | Ignore |
|---|---|---|---|---|---|
| DKB | DKB-1 preamble (IBAN, account kind) | DKB-1 CSV now, FinTS later (#259-#262) | DKB-1 preamble `Kontostand vom` | none (no depot sample) | - |
| PostFinance, accounts | PF-1/PF-2 preamble (IBAN, currency) | PF-1/PF-2 CSV | none in the CSV; PF-9/PF-10 statement PDFs once a PDF template exists, manual entry until then | - | PF-9/PF-10 bookings (same as the CSV), PF-15 |
| PostFinance, E-Trading | PF-7 (account number, holder) or manual | PF-3 cash rows (later, with the securities import) | PF-4 (cash per currency) | PF-7 (has ISIN); PF-5 only with ticker resolution | PF-6 (duplicates PF-4/PF-5) |
| PostFinance, pillar 3a | PF-8 or manual | none | PF-8, manual entry recommended (unstable print layout) | PF-8 (fund units) | - |
| Sparkasse, accounts | `Auftragskonto` column of SPK-1/SPK-2 | SPK-1/SPK-2 CSV, FinTS later | none in the CSV; FinTS later | - | - |
| Sparkasse, loan | SPK-6 header (IBAN, loan terms) | SPK-3 (CSV-CAMT V8) | SPK-6: opening and closing balance once a year | - | SPK-4 (same bookings as SPK-3, older format) |
| Sparkasse, credit card | `Umsatz getätigt von` of SPK-5 or the title line of SPK-8, confirmed by the member | SPK-5 CSV or SPK-8 PDF (#339; domestic purchases only) | SPK-8: previous and new balance each month | - | SPK-5 and SPK-8 hold the same bookings where their periods overlap; both book on `Buchungsdatum`, so the duplicate rule (date, amount, currency) pairs them |
| LBS Süd | SPK-7 header | SPK-7 (PDF, once a year): manual entry recommended | SPK-7 opening and closing balance | - | - |
| True Wealth | TW-1, manual creation (mandate + pillar 3a) | none available: manual | manual snapshots | none available | TW-1 for anything but the container |
| Yuh | YUH-1 summary (IBAN, customer number) | YUH-1 (PDF): no CSV export exists | YUH-1 opening and closing balance per currency | YUH-2 (or YUH-3 at year end) | - |

## 5. Answers from the sample set

The product owner answered the open questions with the sample folder itself: what it holds is
what is available today (checked 2026-10-04, no files beyond the 20 above).

1. **True Wealth:** only the contract bundle (TW-1), no statement or export. True Wealth accounts
   are created manually and valued by manual snapshots until an export is supplied.
2. **Yuh:** PDF only (YUH-1..3), no CSV export. The PDF statement (YUH-1) is Yuh's only
   transaction source, which is why the PDF import moved into sprint 5 (6.8).
3. **DKB:** one export without pending bookings. The row filter (6.1) stays a proposal; its test
   uses a synthetic pending row until a real one is available.
4. **Sparkasse:** the files show three own accounts, related by their transfers (counted, not
   read): SPK-1 is the **current account** (most booking types; it pays to and receives from every
   other Sparkasse account), SPK-2 a **savings-type account** fed by a standing order from SPK-1
   with periodic closing entries, and SPK-3/SPK-4 the **loan**, whose instalments come from SPK-1.
   No CAMT XML was supplied: the CSV-CAMT rendering (SPK-3 format) stays the source; a CAMT XML
   importer stays a spike (story 8).
5. **PostFinance pillar 3a:** only the browser print (PF-8): manual snapshots. **DKB depot:** none.

The transfers between SPK-1 and SPK-2/the loan are internal transfers: imported from both sides,
they must be matched as one transfer (FR-CF-005), not counted as income and expense.

## 6. Gap analysis against the schema and sprint 5

### 6.1 Sprint 5 (#229/#230): CSV fits with one gap; PDF is added

Every cash transaction CSV above can be described by the #229 template as it is:

| Document | Template settings |
|---|---|
| DKB-1 | UTF-8, `;`, preamble 4, header 0, `dd.MM.yy`, `SINGLE_SIGNED_COLUMN`, decimal `,` / thousands `.`, `FIXED` EUR |
| PF-1/PF-2 | UTF-8, `;`, preamble 6, header 0, trailing 2, `dd.MM.yyyy`, `SEPARATE_DEBIT_CREDIT` (debit already negative: the parser takes the column's sign), decimal `.`, `FIXED` CHF |
| SPK-1/2/3 | windows-1252, `;`, header 0, `dd.MM.yy`, `SINGLE_SIGNED_COLUMN`, decimal `,` / thousands `.`, `PER_ROW` (`Waehrung`) |
| SPK-5 | windows-1252, `;`, header 0, `dd.MM.yy`, `SINGLE_SIGNED_COLUMN` (`Buchungsbetrag`), `PER_ROW` (`Buchungswährung`) |

The gap: **a template cannot skip rows by a column value.** DKB pending bookings
(`Status = Vorgemerkt`) must be skipped, or the same booking is imported twice (pending, then
booked with a different text). Proposal: an optional `rowFilter` (column + accepted values) in
the template, as a small follow-up story, not a sprint 5 change. Until then the member exports
booked transactions only.

**Product owner decision (2026-10-04): the import must also read PDF files.** Three samples are
the only transaction source of their account (YUH-1, SPK-6, SPK-7; Yuh has no CSV export at all),
so a CSV-only import leaves them unsupported. Sprint 5 therefore keeps `CASH_TRANSACTIONS` +
`USER_SELECTED` and adds **text-layer PDF** next to CSV (6.8). Every other finding below is a new
story.

### 6.2 Account identification

`account.identifier_masked` is display-only, so nothing can match a file to an account today. The
documents offer:

| Strategy | Documents | Identifier |
|---|---|---|
| `PREAMBLE_LINE` | DKB-1, PF-1/PF-2 | IBAN in a labelled preamble line |
| `COLUMN` | SPK-1..4 (`Auftragskonto`), SPK-5 (`Umsatz getätigt von`) | IBAN, or a masked card number |
| `FILENAME` | DKB-1 (IBAN), PF-5 (account number) | Fragile; only as a fallback |
| none | PF-3, PF-5 | the member selects the account (`USER_SELECTED`) |

Proposal: store an `account_identifier_hash` per account: HMAC-SHA256 with a per-workspace key
over the normalized identifier (IBAN without spaces, upper case; for cards the visible digits
and their positions, since exports only ever show a masked number). It is unique per workspace,
the plain number is never stored, and an import looks it up after normalizing the file's
identifier the same way. A card match on masked digits must be confirmed by the member, because
two cards can share them.

### 6.3 Creating institution and account from a file

Recommendation: **only as a pre-filled proposal that the member confirms**. A file can propose
the institution (catalogue entry by template), the account type (from the template, e.g.
`CASH`, `CREDIT_CARD`, `LOAN`), the currency and the masked identifier, and SPK-6 adds loan terms.
It never knows the holder relationship or ownership shares (C2), so creating accounts silently
would produce incomplete containers.

### 6.4 Balances from files

- A balance read from a file (DKB-1 preamble, SPK-6, SPK-7, YUH-1, PF-4) is an
  `account_snapshot` with `source = 'DOCUMENT'`; no new value is needed (CSV and PDF are both
  documents). It needs **traceability**, though: add `import_batch_id` to `account_snapshot` so
  a snapshot can be traced to, and removed with, the import that wrote it.
- The snapshot date is the statement date in the file (`Kontostand vom`, `Saldo per`), not the
  import date. `UNIQUE (account_id, snapshot_date, source)` already prevents the same statement
  being imported twice.
- Fed into #234: an imported closing balance is a regular snapshot, so the reconciliation engine
  compares it with the ledger as it does a manual one. An opening balance (SPK-6, SPK-7, YUH-1)
  maps to #232's `is_opening_balance` only when the member confirms it, because a later statement
  also starts with an "opening" balance.

### 6.5 Formats beyond CSV

- **XLS/XLSX** (PF-4, PF-5): Apache POI reads both; the template model stays a column mapping
  with a sheet index. Only PostFinance E-Trading needs it, and only for holdings: low priority.
- **PDF**: cash transaction statements are in sprint 5 (6.8). Holdings PDFs (the shared Swissquote
  layout of PF-7 and YUH-2) follow with the holdings import (6.6).
- **OCR**: no sample is scanned (every page of every PDF has a text layer), so nothing justifies
  OCR today. It stays out of scope until a scanned statement turns up.
- **CAMT XML**: Sparkasse `camt.052` supplied (SPK-9, SPK-10; 6.5.1). A generic CAMT importer (one for all banks, no template) is
  preferable to per-bank CSV renderings: stable schema, account IBAN and statement balances
  included. `AcctSvcrRef` is a bank reference but not guaranteed present or unique (6.5.1).
- **eCH-0196 tax statements** (YUH-3): the barcode is confirmed as eCH-0196 v2, a standardized,
  machine-readable year-end source for every Swiss bank that issues it (6.5.1).

#### 6.5.1 Standards feasibility decision (spike #277, 2026-10-18)

**Decision: build two format-driven importers instead of more per-bank templates.**

1. **ISO 20022 CAMT XML** for cash statements: one parser for every bank that offers the
   download, Swiss or German, starting with `camt.053` and with `camt.052` in the same story.
2. **eCH-0196 e-tax statements** for Swiss year-end holdings and bank accounts: one decoder
   for every Swiss bank that prints the standard's barcodes. Confirmed on YUH-3; the posting
   path waits for the securities master (EPIC 15) and the holdings import (#273).

The owner's download check (2026-10-18) found `camt.052` at Sparkasse Freiburg only. DKB,
PostFinance and Yuh offered no CAMT file for the owner's accounts: CSV and PDF only
(follow-up 2 below).

##### Where the standards are used

**Switzerland.** The Swiss Payment Standards (SIX) replaced MT940/MT942 with ISO 20022 in the
payments harmonisation: `camt.053` is the end-of-day statement, `camt.052` the intraday report
and `camt.054` the debit/credit notification (it also resolves collective bookings). Swiss
banks offer these in e-banking as file downloads, so a `camt.053` importer is generic across
Swiss banks in the same way the eCH-0196 decoder is. The Swiss guidelines follow message
versions `.001.04` and, in newer releases, `.001.08`; support both.

**Germany.** Yes, `camt.053` is used: the Deutsche Kreditwirtschaft's DFÜ agreement
(Anlage 3) defines `camt.052` (intraday/turnover report), `camt.053` (end-of-day statement,
the MT940 successor) and `camt.054` (notification, collective-booking resolution). The
delivery channel differs from Switzerland, though: `camt.053` mainly reaches business
customers over **EBICS**, while retail tools reading over **FinTS** get `camt.052`, and an
online-banking download, where offered, is often a ZIP of several XML files. The Sparkasse
CSV the project already imports (SPK-3, "CSV-CAMT") is a CSV rendering of `camt.052`, not of
`camt.053`. For the owner's German banks, `camt.052` is therefore the more likely file, which
is why it is not deferred.

| Institution (inventory) | CAMT XML download | eCH-0196 tax statement |
|---|---|---|
| PostFinance (PF-1..15) | **No** among the owner's e-finance downloads: CSV (PF-1 format) and PDF statements and notices only | Not among the downloads (issued once a year); to confirm |
| Yuh (YUH-1..5) | **No**: PDF statements and notices only, no CSV | **Confirmed** on YUH-3: eCH-0196 v2.22 (below) |
| True Wealth (TW-1) | Not applicable (asset manager, no cash account export seen) | Not among the samples; to confirm |
| Sparkasse Freiburg (SPK-1..12) | **`camt.052` yes** (`.001.02` and `.001.08`, ZIP of daily reports or single XML; SPK-9, SPK-10); **`camt.053` not offered**; also MT940/MT942 | Not applicable (German bank) |
| DKB (DKB-1, DKB-2) | **No**: online banking offers the CSV (DKB-1) and PDF statements (DKB-2) only | Not applicable (German bank) |
| Any other Swiss bank | Generic `camt.053`/`camt.052` parser applies when it offers the file | Generic decoder applies when it prints the barcodes |

A bank advertising *ISO 20022 payment files* (`pain.001`) does not prove a statement download.
Do **not** label an institution supported until the owner has checked the actual download
formats locally, without sharing files.

**Public references** (verify exact versions before implementation):

- ISO 20022 message catalogue (cash management, `camt`):
  https://www.iso20022.org/iso-20022-message-definitions
- SIX Swiss Payment Standards, ISO 20022 implementation guidelines incl. cash management:
  https://www.six-group.com/en/products-services/banking-services/payment-standardization/standards/iso-20022.html
- Deutsche Kreditwirtschaft, DFÜ-Abkommen Anlage 3 (data formats): https://www.ebics.de/
- eCH-0196 e-tax statement, standard and version documents:
  https://www.ech.ch/de/ech/ech-0196

##### What the Sparkasse files show (2026-10-18)

Read locally in a container without network access; structure and counts only. Both ZIPs hold
40 daily reports with 95 booked entries in total, plus pending entries in the single files.

| | `camt.052.001.02` (SPK-9) | `camt.052.001.08` (SPK-10) |
|---|---|---|
| Entry status | `Sts` code: `BOOK`, `PDNG` | `Sts/Cd`: `BOOK`, `PDNG` |
| Balances per report | `PRCD` (previous closing) and `CLBD` | `OPBD` and `CLBD` |
| `Ntry/AcctSvcrRef` | Present on every entry, but **shared by all entries of a report** (24 of the 40 reports have more than one entry) | Distinct per entry |
| Bank transaction code | Proprietary only (issuer `DK`) | Proprietary (`DK`) and ISO `Domn` |
| Other | `EndToEndId` and `MndtId` where the payment had them; one `TxDtls` per entry; EUR only; no `AmtDtls`, no reversals | same |

Consequences: `AcctSvcrRef` alone can never identify a Sparkasse booking in `.001.02`, which
confirms the scoped external id (reference plus date, amount and currency). Each daily report
ends in a closed booked balance (`CLBD`), not an intraday one. **Product owner decision
(2026-10-18):** a `camt.052` import stores no balance; its `CLBD` may later serve as a
reconciliation check if one is needed, never as a `DOCUMENT` snapshot.

**Local check of #352 against these files** (counts only): the `.001.02` ZIP imports 95 of 95
booked entries with no errors, although its entries share references; the `.001.08` ZIP of the
same days then finds 95 of 95 duplicates and posts nothing, and the reverse order gives the same.
Pending entries in the single files are skipped, never imported. The 18-column CSV export of the
same account after the CAMT import is 95 duplicates and 1 filtered pending row. With the IBAN on
the account, the account is identified without confirmation. No balance is stored.
MT940/MT942 (SPK-11, SPK-12) carry the same bookings in an older format; with `camt.052`
available they need no importer.

##### Generic CAMT XML architecture

A **format-driven** parser, not an institution template: detect the message by its XML
namespace (`urn:iso:std:iso:20022:tech:xsd:camt.05x.001.nn`), accept an explicit list of
supported message/version pairs, and reject everything else with an import error. Start with
`camt.053` and `camt.052` in versions `.001.02`, `.001.04` and `.001.08`. For `camt.052`,
import booked entries only and never store an intraday balance as a closing balance.
`camt.054` stays out until a bank is found that delivers collective bookings only that way.

| Source field / XML concept | Project destination | Rule / caveat |
|---|---|---|
| `Stmt/Acct/Id/IBAN` (`Rpt/...` in `camt.052`) | Account identifier matching (`IBAN`) | Check digits; the existing wrong-account confirmation applies when it does not match |
| `Ntry/Sts` | Preview inclusion | A code (`BOOK`) up to `.001.04`; a choice `Sts/Cd` from `.001.06`. Booked only; pending (`PDNG`) and information entries never commit |
| `Ntry/BookgDt/Dt` (or `DtTm`) | `CanonicalImportRow.bookingDate` | Convert `DtTm` explicitly to the account's zone |
| `Ntry/ValDt/Dt` | `valueDate` | Optional |
| `Ntry/Amt` + `CdtDbtInd` | Signed `amount` | Debit negative, credit positive; the XML amount itself is unsigned |
| `Ntry/RvslInd` | Transaction type / notes | A reversal; the sign still follows `CdtDbtInd`, mark the row so the preview shows it |
| `Ntry/Amt/@Ccy` | `currency` | Keep the stated currency |
| `TxDtls/AmtDtls/InstdAmt`, `TxAmt`, `CcyXchg` | `originalAmount`, `originalCurrency`, `bankExchangeRate` | Feeds duplicate rule 3 (#341) for foreign-currency card purchases |
| `Ntry/AcctSvcrRef`, `TxDtls/Refs/AcctSvcrRef`, `EndToEndId` | `externalId` | Neither present nor unique at every bank; scope by account and source, keep entry- and detail-level references apart, ignore `NOTPROVIDED` |
| `Ntry/BkTxCd` (or `TxDtls/BkTxCd`) | `iso20022BankTransactionCode` | Domain/family/sub-family, or the proprietary code with its issuer |
| `TxDtls/RltdPties/Cdtr` / `Dbtr` | `counterpartyName` | Direction-dependent: the creditor for a debit, the debtor for a credit |
| `TxDtls/RmtInf/Ustrd` / `Strd` | `description` | Bounded concatenation; never log the full text |
| `Stmt/Bal` (type, date, currency) | `account_snapshot`, source `DOCUMENT`, at commit | Closing booked `CLBD` only, never a sum of balances; verify against the ledger |

**Grouping is essential:** one `Ntry` can hold several `TxDtls` (a collective booking) with
their own amounts and references. Emit one row per entry when there is a single detail; split
into one row per detail only when the detail amounts reconcile to the entry; otherwise show a
structured preview error. Duplicates use the existing `ImportDuplicateService`: (1) the
source-qualified external id, (2) booking date, amount and currency against any source, and
(3) foreign purchases against rows in the account's currency (#341). A missing `AcctSvcrRef` is
not an id. Re-importing the same file or the same bookings as CSV must not double-post.

**Security and operations:** a streaming StAX parser with `SUPPORT_DTD` and
`IS_SUPPORTING_EXTERNAL_ENTITIES` off and `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA` empty;
XSDs, if validated against, bundled locally with secure processing on. Bound the upload size,
nesting depth, entry count and text lengths. **ZIP containers** (German downloads) need the same
care: a bound on entry count, per-entry and total inflated size and compression ratio, no path
handling of entry names (each entry is parsed in memory), and XML entries only. Malformed input
becomes an import error, not an uncaught exception. The upload goes through the existing
`import_file`/batch lifecycle, workspace RLS and access checks, preview first, explicit commit,
**never auto-post**. A synthetic fixture covers: two plain entries, a collective entry with
reconciling and with non-reconciling details, a pending entry, a reversal, a foreign-currency
entry, an absent reference, two balance types, a ZIP with two statements, and hostile XML
(XXE, entity expansion, deep nesting). No bank data is needed for any of these tests.

**Value vs effort:** one parser can replace per-bank CSV templates for every Swiss bank and
every German bank that offers the file, and brings IBAN, transaction codes and balances with
it. Version differences, the grouping rules, reference semantics and ZIP handling make it
**M–L**. The shipped CSV and PDF templates stay as the fallback.

##### eCH-0196 e-tax statement architecture

eCH-0196 is the Swiss standard for electronic tax statements (*E-Steuerauszug*): the bank
prints the statement as a PDF and embeds the same data as barcodes, which cantonal tax software
reads. It is a Swiss standard, not a Yuh format, so one decoder serves every Swiss bank that
issues it; banks are not obliged to, so availability is per bank.

**Confirmed on YUH-3** (decoded locally in a container without network access; only structure
was printed):

- Every page carries a **Code 128** barcode of 16 digits starting with `196`, which identifies
  the document and page.
- The data sits in **PDF417** symbols on dedicated barcode pages at the end (9 symbols on
  2 pages, 300 dpi rendering suffices). There is no per-symbol header: the symbols are
  consecutive slices of one **zlib** stream, in page order and, within a page, column by column
  from right to left.
- The inflated payload (about 33 KB) is an XML `taxStatement` in namespace
  `http://www.ech.ch/xmlns/eCH-0196/2`, `minorVersion` 22, with tax period, canton and totals
  (tax value, gross revenue A/B, withholding-tax claim) as attributes.
- It contains `listOfBankAccounts` (4 accounts with tax values and payments), `listOfSecurities`
  with one `depot` (13 securities and their `stock` entries: positions and quantity changes
  over the year), `listOfLiabilities` and `listOfExpenses`, plus issuer-specific elements
  outside the eCH namespace (they hold personal data and must be ignored).

Design: render the barcode pages (PDFBox), decode PDF417 and Code 128 (ZXing), order the
symbols by the Code 128 page number and the standard's symbol order, check the result with
the zlib checksum instead of guessing an order, then inflate with a size bound and validate
against the bundled eCH-0196 v2 XSD. The result is a **tax/holdings snapshot**, not a cash
history; it never feeds `CASH_TRANSACTIONS`. It maps to a `SNAPSHOT` import at the period end:
bank account balances (tax value at year end, matched by IBAN) and depot positions (ISIN,
quantity, value, currency), confirmed by the member. An unknown security becomes a review item,
never an auto-created holding. The `stock` quantity changes could later seed securities
transactions; that is out of scope here.

Threat model: bound image size, symbol count, inflated size (a zlib bomb) and XML depth;
reject unknown major versions, XXE and entity expansion. Never commit, log or attach a real
barcode, decoded payload or tax value; fixtures are synthetic eCH-0196 v2 XML, compressed and
encoded into PDF417 by the test itself.

##### Follow-up and exit criteria

1. **CAMT import story #350 (SHOULD, M–L):** `camt.053` and `camt.052`, the supported versions
   above, ZIP containers, the mapping table, typed statement balances, synthetic fixtures,
   preview/commit and duplicate tests.
2. **CAMT availability (product owner, local only):** Sparkasse Freiburg `camt.052` yes
   (`.001.02`/`.001.08`, ZIP or single XML, online-banking download), `camt.053` no; DKB no CAMT
   (checked 2026-10-18). PostFinance and Yuh: no CAMT among the downloads (checked
   2026-10-18). Re-check only if a bank announces a CAMT download.
3. **eCH-0196 decoder story (SHOULD, M):** generic Swiss e-tax statement import into a
   holdings/bank-account snapshot, blocked on EPIC 15 and #273. Open it when those are planned.
4. **Spike #277 closure:** both questions are answered. Q1: only Sparkasse Freiburg offers CAMT
   (`camt.052`), so #350 serves it first and every other bank that offers the file later.
   Q2: YUH-3 is eCH-0196 v2. The other new documents (PF-9..15, YUH-4/5, DKB-2) are PDF
   statements and notices for future PDF templates and the securities import (EPIC 15).

### 6.6 Holdings and securities

- `template_class SNAPSHOT` (PF-4/5/7/8, YUH-2/3): done for PF-4, PF-5, PF-7 and YUH-2 (#273,
  6.6.1), resolving positions by ISIN in the security master or by a ticker the workspace mapped;
  `snapshot_holding` keeps the reported price, value and currency (V98). PF-8 (an unstable browser
  print) and YUH-3 (a tax statement, eCH-0196) are not imported as snapshots.
- `template_class SECURITIES_TRANSACTIONS` (PF-3) needs quantity, price, fees, accrued interest
  and corporate-action handling in the canonical row: also EPIC 15.

### 6.6.1 Holdings statements as snapshots (#273)

A holdings statement is imported through the import batch like a cash statement, by a template of
class `SNAPSHOT` (see database-schema.md, "Holdings statements"). V100 ships three:

| Template | Sources | Reads |
|---|---|---|
| Swissquote Portfolio-Wertentwicklung PDF | PF-7, YUH-2 (one layout for PostFinance E-Trading and Yuh) | date from `Portfolio-Wertentwicklung per <date>`; positions with ISIN, quantity, market price and value in the currency of their section (the first `Bewertung in <currency>` of the section's table header); Yuh's crypto lines by symbol; one `Kontokorrent` cash line per currency |
| PostFinance E-Trading Positionen XLSX | PF-5 | positions by ticker (`Symbol`, `Anzahl`, `Preis`, `Totalwert`, `Währung`); group headings and subtotals passed over; date entered at upload |
| PostFinance E-Trading Liquidität XLS | PF-4 | cash per currency (`Währung`, `Kontosaldo`); the `Gesamt CHF` total passed over; date entered at upload |

Checked locally against the product owner's PF-4, PF-5, PF-7 and YUH-2 by a probe that printed
counts only: every ISIN line of PF-7 and YUH-2 became a position, Yuh's crypto lines positions by
symbol, every cash line a balance, with no unreadable line and no value mismatch; PF-4 and PF-5
read every currency and position row. The committed fixtures (`ShippedTemplateFixtures`) are
invented, with ISINs of the non-country `ZZ` and dates in 2031.

Only the cash in the account's own currency becomes the snapshot's balance; the statement's other
currencies are shown in the preview. PF-5 lists no cash and PF-4 no positions: whichever of the
two comes second for an account and date completes the snapshot the first one recorded (#366,
database-schema.md "Holdings statements"), and PF-7 after either adds what it lacks. A statement
that adds nothing is refused as a duplicate, one that differs as a conflict - nothing is
overwritten.

The source documents remain private and local; never add real holdings or extracted PDF text to
CI, source control, bug reports, or logs.

### 6.7 Duplicates across documents

| Overlap | Rule |
|---|---|
| SPK-3 and SPK-4 (same bookings, two formats) | One template per account; recommend CSV-CAMT V8. Batch dedup (#230) on date + amount + normalized text catches a second import. |
| PF-6 vs PF-4/PF-5, PF-7 vs PF-4/PF-5 | One source per snapshot date; the `UNIQUE (account, date, source)` constraint enforces it. |
| SPK-6/SPK-7/YUH-1 (PDF) vs a CSV or FinTS of the same account | Use the PDF only for balances when the transactions come from elsewhere. |
| YUH-1 trade rows vs a later securities import | The cash leg of a trade must be linked to, not duplicated by, the securities transaction (EPIC 15). |
| DKB pending vs booked rows | Row filter (6.1). |

### 6.8 PDF cash statements: what the template must express

Measured on the three transaction PDFs (word positions counted locally, no value read out):

| Need | YUH-1 | SPK-6 | SPK-7 |
|---|---|---|---|
| Booking line starts with | a date `dd.MM.yyyy` | a date glued to the booking text (`dd.MM.yyyy<text>`) | two dates glued together (booking and value date) |
| Sign of the amount | **by column only**: amounts are unsigned; 27 lines sit under `BELASTUNG`, 6 under `GUTSCHRIFT` | signed in the text (debits carry `-`) | by column (`Belastungen` / `Gutschriften`); all sample lines are credits |
| Amounts per line | two (amount and running balance `SALDO`) on most lines | one | one |
| Continuation lines (no date) | many: counterparty, IBAN, reference | yes: name lines | few |
| Sections | one per currency (`Kontoauszug in <CCY>`, four in the sample) | one | one |
| Repeated page header and footer | yes | yes | yes |
| Balance lines | `Saldo per <date>` at each section's start and end | `Kontostand am <date>` at start and end | `Saldovortrag`, `Ihr Bausparguthaben am <date>` |

So a CSV column mapping cannot describe a PDF. A `PDF_TEXT` template needs, beyond the shared
settings (date format, decimal and thousands separators, `type_mapping`, currency mode):

1. **Text with positions**: lines rebuilt from the words' coordinates (PDFBox `TextPosition`),
   not plain text, because the sign of an unsigned amount depends on its column.
2. **Booking line**: a regular expression for the line start (date pattern, optionally a second
   glued date for the value date) and where the description starts.
3. **Amount columns**: either `SIGNED` (SPK-6), or debit and credit columns located by their
   **header labels** (`BELASTUNG`/`GUTSCHRIFT`), not by absolute coordinates, since layouts move
   between pages and versions. An optional **balance column** lets the parser cross-check each
   sign with the running balance (previous balance + amount = this balance), which turns a column
   misreading into a row error instead of a wrong sign.
4. **Continuation lines**: lines without a date or amount are appended to the previous booking's
   description (counterparty, reference).
5. **Sections**: a marker such as `Kontoauszug in (?<currency>[A-Z]{3})` that starts a section and
   sets the currency of its rows; text outside sections (summary pages) is ignored.
6. **Page furniture**: lines that repeat on every page (header, footer, page numbers) are dropped.
7. **Balance markers** (`Saldo per`, `Kontostand am`): read as balances, not bookings. They feed the
   balance story (6.4) and, until then, the per-section cross-check.

Header fingerprint (FR-IMP-022) for detection: the normalized labels of the booking table's header
line (e.g. `DATUM INFORMATION REFERENZ BELASTUNG GUTSCHRIFT VALUTA-DATUM SALDO`) play the role of
the CSV header row.

Row errors and the `ParsedImportRow`/`CanonicalImportRow` contract stay the same; `rawData` holds
the reconstructed line and its continuation lines. Fixtures are synthetic PDFs generated in the
tests (PDFBox), reproducing the three layouts above with invented values; a real statement is never
committed.

SPK-8 (#339) has no continuation lines: on the three samples (2026-10-16, counts only) no line
stands between two booking lines, and `Neuer Saldo` follows the last one directly. Its template
(V93) therefore has no continuation column: a merchant text that wrapped onto a second line would
keep only its first line as the description (the amount is unaffected). A continuation column
without header labels would take every line below a booking, so on a statement longer than one
page the next page's title and table header would be appended to that page's last booking; add one
only together with a `continuationEndPattern` for those lines, once a sample shows a wrapped line.

Synthetic structure of a YUH-1-like statement section (invented values, columns aligned as on
the page):

```text
Kontoauszug in CHF
Saldo per 01.01.2031                                                              263.40 CHF
DATUM       INFORMATION           REFERENZ     BELASTUNG  GUTSCHRIFT  VALUTA-DATUM   SALDO (CHF)
01.01.2031  Anfangsbestand                                                             263.40
03.01.2031  Zahlung von           0000000001               1'000.00   03.01.2031     1'263.40
            Erika Beispiel
            CH00 0000 0000 0000 0000 0
05.01.2031  Spareinlage           0000000002     17.35                05.01.2031     1'246.05
Saldo per 31.01.2031                                                            1'246.05 CHF
```

## 7. Backlog consequences

- **#229/#230:** scope amended by the product owner: `CASH_TRANSACTIONS`, `USER_SELECTED`, and
  the file formats **CSV and text-layer PDF** (6.8). OCR stays out (no scanned sample). Synthetic
  fixture blueprints: 3.1, 3.2, 3.4, 3.5 (CSV) and 6.8 (PDF).
- **New stories** (no sprint label; the product owner decides):
  1. Skip rows by a column value in an import template (DKB pending bookings).
  2. Identify the account from an import file (`PREAMBLE_LINE`, `COLUMN`, keyed identifier hash).
  3. Propose institution and account from an import file (member confirms).
  4. Import a statement's balance as an `account_snapshot` (`DOCUMENT`, with `import_batch_id`).
  5. Holdings snapshot import (Swissquote PDF layout, PostFinance XLSX), depends on EPIC 15.
  6. Securities transaction import (PostFinance E-Trading CSV), depends on EPIC 15.
  7. Spike: CAMT XML import and eCH-0196 tax statement barcodes (#277, done: 6.5.1; CAMT
     import #350, eCH-0196 decoder after EPIC 15).
  8. Shipped templates per institution: DKB, PostFinance, Sparkasse account and card, Yuh
     statement, Sparkasse loan statement, LBS (one story each, after #230).
  9. Import from scanned PDFs (OCR), only once a scanned statement is supplied.
