#!/usr/bin/env python3
"""Generate the nginx CSP include from an Expo static export."""

import argparse
import base64
from hashlib import sha256
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import urlsplit


def csp_hash(source):
    digest = base64.b64encode(sha256(source.encode("utf-8")).digest()).decode("ascii")
    return f"'sha256-{digest}'"


class InlineContent(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hashes = {"script": set(), "style": set(), "style-attr": set()}
        self.inline_tag = None

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "style" or (tag == "script" and "src" not in attributes):
            self.inline_tag = tag
        style = attributes.get("style")
        if style is not None:
            self.hashes["style-attr"].add(csp_hash(style))

    def handle_data(self, data):
        # HTMLParser emits the entire raw text of a script or style as one callback.
        if self.inline_tag is not None:
            self.hashes[self.inline_tag].add(csp_hash(data))

    def handle_endtag(self, tag):
        if tag == self.inline_tag:
            self.inline_tag = None


def api_origin(value):
    # Reject paths and credentials, and permit only literal origins. In particular,
    # an operator's input must never add CSP sources, nginx variables or directives.
    if not re.fullmatch(
        r"https?://(?:[a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\])(?::[0-9]{1,5})?", value
    ):
        raise argparse.ArgumentTypeError("expected an API origin such as https://api.example.com")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as exc:
        raise argparse.ArgumentTypeError("invalid API host or port") from exc
    if port == 0 or (
        parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
    ):
        raise argparse.ArgumentTypeError("API origins must use HTTPS except on loopback")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export", type=Path, help="Expo web export directory")
    parser.add_argument("output", type=Path, help="Path to content-security-policy.conf")
    parser.add_argument("--api-origin", action="append", default=[], type=api_origin)
    args = parser.parse_args()
    if not (args.export / "index.html").is_file():
        parser.error("the Expo export must contain index.html")

    hashes = {"script": set(), "style": set(), "style-attr": set()}
    for path in args.export.rglob("*.html"):
        document = InlineContent()
        document.feed(path.read_text(encoding="utf-8"))
        document.close()
        for kind, values in document.hashes.items():
            hashes[kind].update(values)

    # Hash the export's hydration script and static styles. Runtime style
    # elements also come from Expo Router and animation libraries, including
    # viewport-dependent rules. Limit their exception to style elements.
    style_attributes = ["'unsafe-hashes'", *sorted(hashes["style-attr"])]
    policy = [
        "default-src 'self'",
        "connect-src " + " ".join(["'self'", *sorted(set(args.api_origin))]),
        "script-src " + " ".join(["'self'", *sorted(hashes["script"])]),
        "style-src " + " ".join(["'self'", *sorted(hashes["style"])]),
        "style-src-elem 'self' 'unsafe-inline'",
        "style-src-attr " + (" ".join(style_attributes) if hashes["style-attr"] else "'none'"),
        "img-src 'self' data: blob:",
        "frame-ancestors 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "object-src 'none'",
    ]
    args.output.write_text(
        f'add_header Content-Security-Policy "{"; ".join(policy)}" always;\n',
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
