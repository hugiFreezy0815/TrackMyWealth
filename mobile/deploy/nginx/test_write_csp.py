"""Unit tests for write-csp.py (#536). Run: python3 -m unittest discover -s deploy/nginx"""

import argparse
import base64
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("write-csp.py")
_spec = importlib.util.spec_from_file_location("write_csp", SCRIPT)
write_csp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(write_csp)


def expected_hash(text):
    return "'sha256-" + base64.b64encode(hashlib.sha256(text.encode()).digest()).decode() + "'"


class ApiOriginTest(unittest.TestCase):
    def test_accepts_literal_https_and_loopback_http_origins(self):
        for origin in [
            "https://api.example.com",
            "https://api.example.com:8443",
            "https://[2001:db8::1]",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
        ]:
            with self.subTest(origin=origin):
                self.assertEqual(write_csp.api_origin(origin), origin)

    def test_rejects_anything_but_a_literal_origin(self):
        for value in [
            "https://api.example.com/",
            "https://api.example.com/api",
            "https://user:secret@api.example.com",
            "https://api.example.com?x=1",
            "https://api.example.com#x",
            "api.example.com",
            "ftp://api.example.com",
            "HTTPS://api.example.com",
            "https://*.example.com",
            "https://api.example.com; script-src *",
            'https://api.example.com" always; add_header X "y',
            "https://$host",
            "https://api.example.com https://evil.example",
            "https://api.example.com\n",
            "",
        ]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                write_csp.api_origin(value)

    def test_rejects_plain_http_off_loopback(self):
        for value in ["http://api.example.com", "http://192.168.1.10:8080", "http://localhost.example.com"]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                write_csp.api_origin(value)

    def test_rejects_invalid_ports(self):
        for value in ["https://api.example.com:0", "https://api.example.com:65536", "https://api.example.com:99999"]:
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                write_csp.api_origin(value)


class InlineContentTest(unittest.TestCase):
    def parse(self, html):
        document = write_csp.InlineContent()
        document.feed(html)
        document.close()
        return document.hashes

    def test_hashes_inline_scripts_styles_and_style_attributes(self):
        hashes = self.parse(
            '<html><head><style id="s">body{margin:0}</style>'
            '<script>window.__HYDRATE__=true;</script>'
            '<script src="/_expo/app.js"></script></head>'
            '<body><div style="display:flex">x</div></body></html>'
        )
        self.assertEqual(hashes["script"], {expected_hash("window.__HYDRATE__=true;")})
        self.assertEqual(hashes["style"], {expected_hash("body{margin:0}")})
        self.assertEqual(hashes["style-attr"], {expected_hash("display:flex")})

    def test_ignores_text_outside_inline_elements(self):
        hashes = self.parse("<script src=/a.js></script><p>text</p><script>a()</script><p>more</p>")
        self.assertEqual(hashes["script"], {expected_hash("a()")})
        self.assertEqual(hashes["style"], set())


class MainTest(unittest.TestCase):
    def run_script(self, export, *args):
        output = Path(export) / "csp.conf"
        result = subprocess.run(
            [sys.executable, "-I", str(SCRIPT), str(export), str(output), *args],
            capture_output=True,
            text=True,
            check=False,
        )
        return result, output

    def test_writes_one_nginx_header_with_hashes_and_sorted_origins(self):
        with tempfile.TemporaryDirectory() as export:
            Path(export, "index.html").write_text(
                '<script>boot()</script><style>a{b:c}</style><p style="x:y">t</p>', encoding="utf-8"
            )
            Path(export, "accounts").mkdir()
            Path(export, "accounts", "[accountId].html").write_text("<script>other()</script>", encoding="utf-8")
            result, output = self.run_script(
                export,
                "--api-origin", "https://b.example.com",
                "--api-origin", "https://a.example.com",
                "--api-origin", "https://b.example.com",
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            conf = output.read_text(encoding="utf-8")

        self.assertTrue(conf.startswith('add_header Content-Security-Policy "'))
        self.assertTrue(conf.endswith('" always;\n'))
        directives = conf.split('"')[1].split("; ")
        self.assertIn("connect-src 'self' https://a.example.com https://b.example.com", directives)
        script = next(d for d in directives if d.startswith("script-src "))
        self.assertIn(expected_hash("boot()"), script)
        self.assertIn(expected_hash("other()"), script)
        self.assertNotIn("'unsafe-inline'", script)
        self.assertIn(f"style-src 'self' {expected_hash('a{b:c}')}", directives)
        self.assertIn(f"style-src-attr 'unsafe-hashes' {expected_hash('x:y')}", directives)
        for required in ["default-src 'self'", "frame-ancestors 'none'", "base-uri 'self'",
                         "form-action 'self'", "object-src 'none'"]:
            self.assertIn(required, directives)

    def test_blocks_style_attributes_when_the_export_has_none(self):
        with tempfile.TemporaryDirectory() as export:
            Path(export, "index.html").write_text("<p>t</p>", encoding="utf-8")
            result, output = self.run_script(export)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("style-src-attr 'none'", output.read_text(encoding="utf-8"))
            self.assertIn("connect-src 'self';", output.read_text(encoding="utf-8"))

    def test_refuses_a_directory_without_an_export(self):
        with tempfile.TemporaryDirectory() as export:
            result, output = self.run_script(export)
            self.assertEqual(result.returncode, 2)
            self.assertIn("index.html", result.stderr)
            self.assertFalse(output.exists())

    def test_refuses_an_unsafe_origin_without_writing(self):
        with tempfile.TemporaryDirectory() as export:
            Path(export, "index.html").write_text("<p>t</p>", encoding="utf-8")
            result, output = self.run_script(export, "--api-origin", "http://api.example.com")
            self.assertEqual(result.returncode, 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
