#!/usr/bin/env python3
"""Seed a disposable TrackMyWealth stack through its public API."""

import argparse
import base64
from contextlib import contextmanager
from datetime import date, timedelta
from decimal import Decimal
import fcntl
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import secrets
import struct
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

WORKSPACE = "TrackMyWealth Synthetic Demo"
CSV_TEMPLATE = "8d1b8ef6-882f-46d9-87e1-a17278f8bfa3"
CSV_NAME = "trackmywealth-demo-v1.csv"
SECURITY_NAME = "Alpine Lantern Demo Equity Fund"
FIXTURE = Path(__file__).with_name("fixtures") / "demo-statement.csv"
EMAILS = {
    "admin": "demo-admin@example.invalid",
    "member": "demo-member@example.invalid",
    "partner": "demo-partner@example.invalid",
}


class SeedError(Exception):
    """A safe diagnostic that can be shown without exposing API payloads."""


class ApiError(SeedError):
    def __init__(self, method, path, status):
        self.status = status
        super().__init__(f"{method} {path} returned HTTP {status}.")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def base_url(value, allow_remote):
    try:
        parsed = urlsplit(value)
        host = parsed.hostname
        port = parsed.port
    except ValueError as error:
        raise SeedError("Invalid API base URL.") from error
    if (parsed.scheme not in {"http", "https"} or not host or parsed.username is not None
            or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
        raise SeedError("Use a base URL such as http://127.0.0.1:8080.")
    if host == "localhost":
        host = "127.0.0.1"
    try:
        local = ipaddress.ip_address(host).is_loopback
    except ValueError:
        local = False
    if not local and not allow_remote:
        raise SeedError("A non-loopback URL requires --allow-remote.")
    if not local and parsed.scheme != "https":
        raise SeedError("A remote demo stack requires HTTPS.")
    authority = f"[{host}]" if ":" in host else host
    return f"{parsed.scheme}://{authority}" + (f":{port}" if port is not None else "")


class Api:
    def __init__(self, url, token=None):
        self.url = url
        self.token = token
        self.opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(self, method, path, body=None, *, version=None, multipart=None):
        headers = {"Accept": "application/json"}
        data = None
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if version is not None:
            headers["If-Match"] = f'"{version}"'
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if multipart is not None:
            data, headers["Content-Type"] = multipart
        req = Request(self.url + "/api/v1" + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=45) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except HTTPError as error:
            error.close()
            raise ApiError(method, path, error.code) from None
        except (URLError, TimeoutError):
            raise SeedError(f"{method} {path} could not reach the demo API.") from None

    def pages(self, path):
        page = 0
        while True:
            result = self.request("GET", path + "?" + urlencode({"page": page, "size": 50}))
            yield from result["content"]
            page += 1
            if page >= result["page"]["totalPages"]:
                return


def totp(secret, step):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8), casefold=True)
    digest = hmac.digest(key, struct.pack(">Q", step), hashlib.sha1)
    offset = digest[-1] & 15
    value = int.from_bytes(digest[offset:offset + 4], "big") & 0x7fffffff
    return f"{value % 1_000_000:06d}"


@contextmanager
def local_state(path, url):
    # The sidecar lock stays on the same inode across atomic updates of the credentials file.
    with open(str(path) + ".lock", "a", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SeedError("Another seed run is using these demo credentials.") from None

        def save():
            temporary = path.with_name(f".{path.name}.{secrets.token_hex(8)}")
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as stream:
                    json.dump(state, stream, indent=2)
                    stream.write("\n")
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)

        if path.exists():
            if path.stat().st_mode & 0o077:
                raise SeedError("The credentials file must be private (chmod 600).")
            state = json.loads(path.read_text(encoding="utf-8"))
            if state["url"] != url:
                raise SeedError("Use a separate credentials file for each API base URL.")
        else:
            state = {
                "url": url,
                "users": {
                    key: {"email": email, "password": secrets.token_urlsafe(24)}
                    for key, email in EMAILS.items()
                },
            }
            save()
        yield state, save


def credentials(user):
    return {"email": user["email"], "password": user["password"]}


def next_code(user, save):
    # The API consumes each TOTP time step, including the confirmation step.
    step = int(time.time()) // 30
    previous = user.get("lastStep", -1)
    if previous >= step:
        delay = (previous + 1) * 30 - time.time()
        if delay > 30:
            raise SeedError("The local clock is behind the saved TOTP step.")
        time.sleep(max(0, delay) + 0.2)
        step = int(time.time()) // 30
    user["lastStep"] = step
    save()
    return totp(user["totpSecret"], step)


def login(public, user, save):
    response = public.request("POST", "/auth/login", credentials(user))
    tokens = response["tokens"]
    if response["mfaRequired"]:
        if "totpSecret" not in user:
            raise SeedError("The demo member's authenticator is missing from the credentials file.")
        tokens = public.request("POST", "/auth/mfa/verify", {
            "challengeToken": response["mfaChallengeToken"],
            "code": next_code(user, save),
        })
    return Api(public.url, tokens["accessToken"]), response["mfaRequired"]


def member(public, admin, user, save, *, enroll=False):
    try:
        admin.request("POST", "/admin/users", {
            **credentials(user), "role": "STANDARD_USER", "language": "EN",
        })
    except ApiError as error:
        if error.status != 409:
            raise
    api, mfa = login(public, user, save)
    if api.request("GET", "/workspace")["name"] != WORKSPACE:
        raise SeedError("The signed-in member is outside the synthetic demo workspace.")
    if enroll and not mfa:
        enrollment = api.request("POST", "/users/me/mfa/enroll", {"password": user["password"]})
        user["totpSecret"] = enrollment["secret"]
        save()
        api.request("POST", "/users/me/mfa/confirm", {"code": next_code(user, save)})
    return api


def find_unique(rows, predicate):
    matches = [row for row in rows if predicate(row)]
    if len(matches) > 1:
        raise SeedError("More than one record matches a demo marker.")
    return matches[0] if matches else None


def ensure_named(api, rows, path, body, *, name_field="name"):
    existing = find_unique(rows, lambda row: row[name_field] == body[name_field])
    return existing or api.request("POST", path, body)


def opening(api, account, day, balance, currency):
    path = f"/accounts/{account}/opening-balance"
    try:
        api.request("GET", path)
    except ApiError as error:
        if error.status != 404:
            raise
        api.request("POST", path, {"date": str(day), "balance": balance, "currency": currency})


def transaction(api, account, ledger, marker, kind, day, amount, description, *, category=None, **fields):
    path = f"/accounts/{account}/transactions"
    external_id = f"tmw-demo-v1-{marker}"
    row = ledger.get(external_id)
    if row is None:
        row = api.request("POST", path, {
            "transactionType": kind, "bookingDate": str(day), "amount": amount,
            "currency": "CHF", "merchantDescription": description, "externalId": external_id,
            **fields,
        })
    if category and row["categoryId"] != category:
        api.request("PUT", f"{path}/{row['id']}/category", {"categoryId": category},
                    version=row["version"])


def import_statement(api, account, day):
    path = f"/accounts/{account}/imports"
    batch = find_unique(api.pages(path), lambda row: row["sourceFileName"] == CSV_NAME)
    if batch is None:
        fixture = FIXTURE.read_text(encoding="utf-8").replace("__DATE__", day.strftime("%d.%m.%Y"))
        boundary = secrets.token_hex(16)
        body = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"templateId\"\r\n\r\n"
            f"{CSV_TEMPLATE}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{CSV_NAME}\""
            f"\r\nContent-Type: text/csv\r\n\r\n"
            + fixture.replace("\n", "\r\n") + f"\r\n--{boundary}--\r\n"
        ).encode("utf-8")
        batch = api.request("POST", path, multipart=(body, f"multipart/form-data; boundary={boundary}"))
    if batch["status"] == "COMMITTED" and batch["counts"]["imported"] == 2:
        return
    if batch["status"] != "PARSED" or batch["counts"]["newRows"] != 2 or batch["counts"]["errors"]:
        raise SeedError("The synthetic CSV did not preview as two new bookings.")
    path += f"/{batch['id']}"
    identification = batch["accountIdentification"]
    if identification and identification["confirmationRequired"] and not identification["confirmed"]:
        batch = api.request("POST", path + "/confirm-account", version=batch["version"])
    committed = api.request("POST", path + "/commit", version=batch["version"])
    if committed["status"] != "COMMITTED" or committed["counts"]["imported"] != 2:
        raise SeedError("The synthetic CSV did not commit two bookings.")


def seed(api, anchor):
    portfolios = api.request("GET", "/portfolios")
    portfolio_specs = (
        ("Demo · Cloudberry Bank", "CH", "CHF", "BANK"),
        ("Demo · Paper Moon Bank", "DE", "EUR", "BANK"),
        ("Demo · Alpine Lantern Investments", "CH", "CHF", "BROKER"),
    )
    swiss, german, investing = [
        ensure_named(api, portfolios, "/portfolios", {
            "name": name, "country": country, "portfolioCurrency": currency,
            "institutionType": kind, "identifier": f"tmw-demo-v1-{country}-{kind.lower()}",
        })["id"]
        for name, country, currency, kind in portfolio_specs
    ]
    personal = find_unique(portfolios, lambda row: row["personalAssetsDefault"])
    if personal is None:
        raise SeedError("The demo workspace has no default Personal Assets portfolio.")

    accounts = api.request("GET", "/accounts")
    account_specs = {
        "cash": ("Cloudberry Current", swiss, "CASH", "CHF", {}),
        "savings": ("Cloudberry Savings", swiss, "SAVINGS", "CHF", {}),
        "card": ("Cloudberry Card", swiss, "CREDIT_CARD", "CHF", {}),
        "eur": ("Paper Moon Current", german, "CASH", "EUR", {}),
        "depot": ("Alpine Lantern Depot", investing, "SECURITIES", "CHF", {}),
        "pension": ("Alpine Lantern Pillar 3a", investing, "PENSION", "CHF",
                    {"pensionScheme": "CH_PILLAR_3A"}),
        "property": ("Cloudberry House", personal["id"], "CUSTOM_ASSET", "CHF",
                     {"customAssetType": "REAL_ESTATE"}),
        "mortgage": ("Cloudberry Mortgage", personal["id"], "MORTGAGE", "CHF",
                     {"originalPrincipal": "300000.00", "interestRatePercent": "1.75"}),
    }
    ids = {
        key: ensure_named(api, accounts, "/accounts", {
            "name": "Demo · " + name, "portfolioId": portfolio, "accountType": kind,
            "nativeCurrency": currency, **extra,
        })["id"]
        for key, (name, portfolio, kind, currency, extra) in account_specs.items()
    }
    for key, amount, currency in (
        ("cash", "4000.00", "CHF"), ("savings", "10000.00", "CHF"),
        ("eur", "2500.00", "EUR"), ("pension", "31000.00", "CHF"),
    ):
        opening(api, ids[key], anchor - timedelta(days=185), amount, currency)

    categories = api.request("GET", "/categories")
    salary, groceries = [
        ensure_named(api, categories, "/categories", {"nameEn": en, "nameDe": de},
                     name_field="nameEn")["id"]
        for en, de in (("Demo salary", "Demo-Lohn"), ("Demo groceries", "Demo-Lebensmittel"))
    ]
    ledgers = {
        key: {row["externalId"]: row for row in api.pages(f"/accounts/{ids[key]}/transactions")
              if row["externalId"]}
        for key in ("cash", "card")
    }
    for period in range(6):
        day = anchor - timedelta(days=175 - period * 30)
        transaction(api, ids["cash"], ledgers["cash"], f"salary-{period}", "INCOME",
                    day, "3500.00", "Cloudberry Workshop", category=salary)
        transaction(api, ids["cash"], ledgers["cash"], f"groceries-{period}", "EXPENSE",
                    day + timedelta(days=2), "-125.50", "Paper Moon Market", category=groceries)
        transaction(api, ids["cash"], ledgers["cash"], f"save-{period}", "TRANSFER",
                    day + timedelta(days=4), "-300.00", "Monthly saving",
                    counterpartyAccountId=ids["savings"])
    for index in range(3):
        transaction(api, ids["cash"], ledgers["cash"], f"misc-{index}", "EXPENSE",
                    anchor - timedelta(days=8 + index), "-9.50", f"QZX demo booking {index}")
    transaction(api, ids["card"], ledgers["card"], "card-purchase", "CREDIT_CARD_PURCHASE",
                anchor - timedelta(days=12), "-80.00", "Cloudberry Ticket Office")
    path = f"/accounts/{ids['card']}/settlement-source"
    settlement = api.request("GET", path)
    if settlement["settlementSourceAccountId"] != ids["cash"]:
        api.request("PUT", path, {"settlementSourceAccountId": ids["cash"]},
                    version=settlement["version"])
    for key, amount in (("cash", "-50.00"), ("card", "50.00")):
        transaction(api, ids[key], ledgers[key], f"settlement-{key}", "SETTLEMENT",
                    anchor - timedelta(days=5), amount, "Cloudberry Card Payment")

    import_statement(api, ids["cash"], anchor - timedelta(days=3))
    for key, balance, offset in (("cash", "22000.00", 1), ("depot", "25000.00", 2)):
        day = anchor - timedelta(days=offset)
        path = f"/accounts/{ids[key]}/snapshots"
        if not any(row["snapshotDate"] == str(day) and row["source"] == "MANUAL"
                   for row in api.pages(path)):
            snapshot = {"snapshotDate": str(day), "balance": balance}
            if key == "depot":
                security = api.request("POST", "/securities", {
                    "idempotencyKey": "tmw-demo-v1-alpine-lantern-etf",
                    "displayName": SECURITY_NAME, "denominationCurrency": "CHF",
                    "instrumentType": "ETF", "assetClass": "EQUITY",
                })
                snapshot["holdings"] = [{"securityId": security["id"], "quantity": "20"}]
            api.request("POST", path, snapshot)
    path = f"/accounts/{ids['property']}/valuations"
    if not any(row["valuationDate"] == str(anchor - timedelta(days=60)) for row in api.pages(path)):
        api.request("POST", path, {
            "valuationDate": str(anchor - timedelta(days=60)), "value": "680000.00",
        })
    return ids


def valuation_fixture_ids(api, ids):
    # Opening balances are snapshots too. The CSV fixture contains bookings only.
    expected_snapshots = {"cash": 2, "savings": 1, "eur": 1, "pension": 1, "depot": 1}
    snapshot_ids = {}
    depot_holdings = []
    for key, account_id in sorted(ids.items()):
        snapshots = list(api.pages(f"/accounts/{account_id}/snapshots"))
        if len(snapshots) != expected_snapshots.get(key, 0):
            raise SeedError(f"The demo {key} account did not have the expected snapshot count.")
        snapshot_ids[key] = sorted(row["id"] for row in snapshots)
        if key == "depot":
            depot_holdings = snapshots[0]["holdings"]
    if len(depot_holdings) != 1:
        raise SeedError("The demo depot did not have exactly one seeded holding.")
    security_id = depot_holdings[0]["securityId"]

    valuations = list(api.pages(f"/accounts/{ids['property']}/valuations"))
    if len(valuations) != 1:
        raise SeedError("The demo property did not have exactly one valuation.")
    security = api.request("GET", f"/securities/{security_id}")
    if security["id"] != security_id or security["displayName"] != SECURITY_NAME:
        raise SeedError("The demo depot did not hold the seeded security.")
    return snapshot_ids, [valuations[0]["id"]], [security_id]


EXPECTED_STATUSES = {
    "cash": "UNRECONCILED", "property": "STALE", "mortgage": "ESTIMATED",
    # An account that holds positions stays unknown until holdings are valued (EPIC 15), and the
    # public API offers no other value source for it.
    "depot": "UNKNOWN",
}
# Opening balance plus the seeded ledger (cash: 4000 + 6 x (3500 - 125.50 - 300) - 28.50 - 50
# - 38.25 imported); the manual cash snapshot of 22000 is the open reconciliation difference.
EXPECTED_BALANCES = {
    "card": "-30", "savings": "11800", "eur": "2500", "cash": "22330.25",
    "pension": "31000", "property": "680000", "mortgage": "-300000",
}


def account_values(client):
    # Native balance and status only: the workspace-currency figure moves when a fresh stack's
    # first exchange rates arrive, which may happen between the two members' reads.
    return {row["id"]: (row["balance"], row["valueStatus"])
            for row in client.request("GET", "/accounts")}


def check_account_values(ids, first, second):
    if first != second or set(ids.values()) != first.keys():
        raise SeedError("The two demo members could not see the same demo account values.")
    for key, account_id in ids.items():
        balance, status = first[account_id]
        expected = EXPECTED_STATUSES.get(key)
        if expected is not None and status != expected:
            raise SeedError(f"The demo {key} account did not show {expected}.")
        if key == "depot":
            continue
        if balance is None or Decimal(balance) != Decimal(EXPECTED_BALANCES[key]):
            raise SeedError(f"The demo {key} account did not have the expected balance.")
    return first


def verify(api, partner, ids):
    first = check_account_values(ids, account_values(api), account_values(partner))

    cash_path = f"/accounts/{ids['cash']}"
    cash_rows = list(api.pages(cash_path + "/transactions"))
    card_rows = list(api.pages(f"/accounts/{ids['card']}/transactions"))
    savings_rows = list(api.pages(f"/accounts/{ids['savings']}/transactions"))
    imports = list(api.pages(cash_path + "/imports"))
    if (len(cash_rows), len(card_rows), len(savings_rows), len(imports)) != (24, 2, 6, 1):
        raise SeedError("The demo ledgers or import history did not have the expected counts.")
    categories = api.request("GET", "/categories")
    uncategorized = find_unique(categories, lambda row: row["code"] == "UNCATEGORIZED")
    if uncategorized is None or sum(
        row["categoryId"] == uncategorized["id"] for row in cash_rows
    ) < 3:
        raise SeedError("The demo ledger did not contain three uncategorised bookings.")
    portfolios = api.request("GET", "/portfolios")
    if len(portfolios) != 4 or not any(
        row["name"] == "Demo · Alpine Lantern Investments" and row["portfolioType"] == "MIXED"
        for row in portfolios
    ):
        raise SeedError("The demo portfolios did not include the mixed investment portfolio.")
    # IDs and native balances let CI check reruns without depending on daily exchange rates.
    resources = (
        sorted((account_id, balance) for account_id, (balance, _) in first.items()),
        sorted(row["id"] for row in cash_rows + card_rows + savings_rows + imports + portfolios),
        valuation_fixture_ids(api, ids),
    )
    return hashlib.sha256(json.dumps(resources).encode()).hexdigest()[:16]


def run(url, state, save):
    public = Api(url)
    admin_user = state["users"]["admin"]
    try:
        tokens = public.request("POST", "/setup/administrator", {
            **credentials(admin_user), "workspaceName": WORKSPACE, "currencyCode": "CHF",
        })
        admin = Api(url, tokens["accessToken"])
    except ApiError as error:
        if error.status != 409:
            raise
        try:
            admin, _ = login(public, admin_user, save)
        except ApiError as login_error:
            if login_error.status == 401:
                raise SeedError("Use a fresh disposable stack or its original demo credentials file.") from None
            raise

    api = member(public, admin, state["users"]["member"], save, enroll=True)
    partner = member(public, admin, state["users"]["partner"], save)
    if "anchor" not in state:
        # Fix dates before the first financial write so retries work on later calendar days.
        state["anchor"] = str(date.fromisoformat(api.request("GET", "/net-worth")["asOf"])
                              - timedelta(days=1))
        save()
    ids = seed(api, date.fromisoformat(state["anchor"]))
    fingerprint = verify(api, partner, ids)
    for client in (api, partner, admin):
        session = client.request("GET", "/users/me")["sessionId"]
        client.request("POST", f"/sessions/{session}/revoke")
    return fingerprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--allow-remote", action="store_true", help="Allow a disposable HTTPS stack")
    parser.add_argument("--credentials-file", type=Path, default=Path(".demo-credentials.json"))
    args = parser.parse_args()
    try:
        url = base_url(args.base_url, args.allow_remote)
        with local_state(args.credentials_file, url) as (state, save):
            fingerprint = run(url, state, save)
    except SeedError as error:
        parser.exit(1, f"{error}\n")
    except (OSError, ValueError, KeyError, TypeError, ArithmeticError):
        parser.exit(1, "Could not read or write demo state, or the API returned an unexpected response.\n")
    print(f"Verified synthetic demo accounts ({fingerprint}). Login details are in {args.credentials_file}.")


if __name__ == "__main__":
    main()
