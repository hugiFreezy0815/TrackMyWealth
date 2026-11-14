"""Safety and rerun behavior of the synthetic demo seed."""

from base64 import b32encode
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import seed_demo


class SeedDemoTest(unittest.TestCase):
    def test_loopback_only_by_default_and_https_for_remote(self):
        self.assertEqual(
            seed_demo.base_url("http://localhost:8080/", False), "http://127.0.0.1:8080"
        )
        self.assertEqual(seed_demo.base_url("http://[::1]", False), "http://[::1]")
        for url in ("https://localhost.example", "http://192.0.2.1",
                    "http://localhost@192.0.2.1", "file:///tmp/demo"):
            with self.subTest(url=url), self.assertRaises(seed_demo.SeedError):
                seed_demo.base_url(url, False)
        with self.assertRaises(seed_demo.SeedError):
            seed_demo.base_url("http://example.test", True)
        self.assertEqual(
            seed_demo.base_url("https://example.test:8443", True), "https://example.test:8443"
        )

    def test_state_preserves_generated_credentials_and_resume_anchor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credentials.json"
            url = "http://127.0.0.1:8080"
            with seed_demo.local_state(path, url) as (state, save):
                state["anchor"] = "2026-11-12"
                save()
                with self.assertRaises(seed_demo.SeedError):
                    with seed_demo.local_state(path, url):
                        self.fail("Opened a second writer")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with seed_demo.local_state(path, url) as (reloaded, _):
                self.assertEqual(reloaded, state)
            with self.assertRaises(seed_demo.SeedError):
                with seed_demo.local_state(path, "http://127.0.0.1:8081"):
                    self.fail("Reused credentials against a different stack")

    def test_totp_rfc_6238_sha1_vector_at_59_seconds(self):
        example_bytes = b"1234567890" * 2  # RFC 6238, Appendix B
        secret = b32encode(example_bytes).decode("ascii")
        self.assertEqual(seed_demo.totp(secret, 59 // 30), "287082")

    def test_existing_committed_import_needs_no_mutation(self):
        api = Mock()
        api.pages.return_value = [{
            "sourceFileName": seed_demo.CSV_NAME,
            "status": "COMMITTED", "counts": {"imported": 2},
        }]
        seed_demo.import_statement(api, "account", None)
        api.request.assert_not_called()

    def test_resume_parsed_import_uses_updated_version_after_confirmation(self):
        api = Mock()
        parsed = {
            "id": "batch", "sourceFileName": seed_demo.CSV_NAME,
            "status": "PARSED", "version": 3, "counts": {"newRows": 2, "errors": 0},
            "accountIdentification": {"confirmationRequired": True, "confirmed": False},
        }
        api.pages.return_value = [parsed]
        api.request.side_effect = [
            {**parsed, "version": 4},
            {"status": "COMMITTED", "counts": {"imported": 2}},
        ]
        seed_demo.import_statement(api, "account", None)
        self.assertEqual(
            [call.kwargs["version"] for call in api.request.call_args_list], [3, 4]
        )


class TotpStepTest(unittest.TestCase):
    SECRET = b32encode(b"1234567890" * 2).decode("ascii")

    def test_a_fresh_step_is_used_and_saved_before_the_code_is_sent(self):
        user, save = {"totpSecret": self.SECRET}, Mock()
        with patch.object(seed_demo.time, "time", return_value=59.0), \
                patch.object(seed_demo.time, "sleep") as sleep:
            self.assertEqual(seed_demo.next_code(user, save), "287082")
        sleep.assert_not_called()
        self.assertEqual(user["lastStep"], 1)
        save.assert_called_once()

    def test_a_used_step_waits_for_the_next_one(self):
        user, save = {"totpSecret": self.SECRET, "lastStep": 1}, Mock()
        clock = iter([50.0, 50.0, 60.5])  # step, remaining wait, step after the wait
        with patch.object(seed_demo.time, "time", side_effect=lambda: next(clock)), \
                patch.object(seed_demo.time, "sleep") as sleep:
            code = seed_demo.next_code(user, save)
        self.assertAlmostEqual(sleep.call_args.args[0], 10.2)
        self.assertEqual(user["lastStep"], 2)
        self.assertEqual(code, seed_demo.totp(self.SECRET, 2))

    def test_a_clock_behind_the_saved_step_refuses_instead_of_sleeping(self):
        user, save = {"totpSecret": self.SECRET, "lastStep": 5}, Mock()
        with patch.object(seed_demo.time, "time", return_value=59.0), \
                patch.object(seed_demo.time, "sleep") as sleep, \
                self.assertRaisesRegex(seed_demo.SeedError, "clock is behind"):
            seed_demo.next_code(user, save)
        sleep.assert_not_called()
        save.assert_not_called()


class AccountValuesTest(unittest.TestCase):
    def setUp(self):
        self.ids = {key: f"id-{key}" for key in (
            "cash", "card", "depot", "eur", "mortgage", "pension", "property", "savings",
        )}
        self.values = {
            f"id-{key}": (seed_demo.EXPECTED_BALANCES.get(key),
                          seed_demo.EXPECTED_STATUSES.get(key, "CURRENT"))
            for key in self.ids
        }

    def check(self, **changes):
        first = dict(self.values)
        for key, value in changes.items():
            first[f"id-{key}"] = value
        return seed_demo.check_account_values(self.ids, first, dict(first))

    def test_every_account_but_the_depot_needs_its_expected_value(self):
        self.assertEqual(self.check(), self.values)
        for key in sorted(set(self.ids) - {"depot"}):
            with self.subTest(key=key), \
                    self.assertRaisesRegex(seed_demo.SeedError, f"demo {key} account"):
                self.check(**{key: (None, self.values[f"id-{key}"][1])})

    def test_the_depot_is_unknown_until_holdings_are_valued(self):
        with self.assertRaisesRegex(seed_demo.SeedError, "depot account did not show UNKNOWN"):
            self.check(depot=("25000", "CURRENT"))

    def test_members_must_see_the_same_values_and_only_the_demo_accounts(self):
        partner = {**self.values, "id-eur": ("2499", "CURRENT")}
        with self.assertRaisesRegex(seed_demo.SeedError, "same demo account values"):
            seed_demo.check_account_values(self.ids, self.values, partner)
        extra = {**self.values, "other": ("1", "CURRENT")}
        with self.assertRaisesRegex(seed_demo.SeedError, "same demo account values"):
            seed_demo.check_account_values(self.ids, extra, dict(extra))


class ValuationFixtureTest(unittest.TestCase):
    def setUp(self):
        self.ids = {key: key for key in (
            "cash", "card", "depot", "eur", "mortgage", "pension", "property", "savings",
        )}
        self.rows = {
            "/accounts/cash/snapshots": [{"id": "cash-manual"}, {"id": "cash-opening"}],
            "/accounts/depot/snapshots": [{
                "id": "depot-manual", "holdings": [{"securityId": "fund"}],
            }],
            "/accounts/eur/snapshots": [{"id": "eur-opening"}],
            "/accounts/pension/snapshots": [{"id": "pension-opening"}],
            "/accounts/savings/snapshots": [{"id": "savings-opening"}],
            "/accounts/property/valuations": [{"id": "property-valuation"}],
        }
        self.api = Mock()
        self.api.pages.side_effect = lambda path: iter(self.rows.get(path, []))
        self.api.request.side_effect = self.read_security

    def read_security(self, method, path):
        self.assertEqual(method, "GET")
        return {"id": path.rsplit("/", 1)[-1], "displayName": seed_demo.SECURITY_NAME}

    def test_collects_stable_ids_independent_of_snapshot_order(self):
        fixture_ids = seed_demo.valuation_fixture_ids(self.api, self.ids)
        self.rows["/accounts/cash/snapshots"].reverse()
        self.assertEqual(seed_demo.valuation_fixture_ids(self.api, self.ids), fixture_ids)
        snapshots, valuations, securities = fixture_ids
        self.assertEqual(snapshots["cash"], ["cash-manual", "cash-opening"])
        self.assertEqual(valuations, ["property-valuation"])
        self.assertEqual(securities, ["fund"])
        self.rows["/accounts/depot/snapshots"][0]["holdings"][0]["securityId"] = "replacement-fund"
        self.assertNotEqual(seed_demo.valuation_fixture_ids(self.api, self.ids), fixture_ids)

    def test_rejects_an_extra_snapshot_or_valuation_without_any_balance_change(self):
        for path, message in (
            ("/accounts/cash/snapshots", "snapshot count"),
            ("/accounts/property/valuations", "exactly one valuation"),
        ):
            with self.subTest(path=path):
                original = self.rows[path]
                self.rows[path] = [*original, {"id": "extra"}]
                with self.assertRaisesRegex(seed_demo.SeedError, message):
                    seed_demo.valuation_fixture_ids(self.api, self.ids)
                self.rows[path] = original
