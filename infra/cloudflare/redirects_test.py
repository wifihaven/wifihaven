#!/usr/bin/env python3
"""Assertion test for the redirect ruleset in main.tf (#1842).

`terraform validate` proves the ruleset is schema-valid, but not that the
*semantics* are right. The marketing split sends the old SPA hosts to the
canonical app host so existing bookmarks keep working; the property worth
pinning is that each redirect targets the right host **and preserves the query
string** (a bookmark with `?…` should survive the hop).

There is intentionally NO apex `/blocked` router-compat shim (dropped 2026-06 —
no pre-rename routers still DNAT their block page at the apex), so this test
also asserts that rule is absent, to catch an accidental re-introduction.

We can't curl the live edge from CI (nothing is deployed at PR time), so this
test pins the ruleset config instead — a plain-text parse of the HCL (stdlib
only).

Run: python3 infra/cloudflare/redirects_test.py
"""

import re
import sys
from pathlib import Path

MAIN_TF = Path(__file__).with_name("main.tf")

failures: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        failures.append(msg)


def _balanced_from(text: str, open_idx: int) -> str:
    """Return text[open_idx .. matching close brace], inclusive."""
    depth = 0
    for j in range(open_idx, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx : j + 1]
    return ""


def extract_block(text: str, header_re: str) -> str:
    """Return the brace-balanced body of the first block matching header_re."""
    m = re.search(header_re, text)
    if not m:
        return ""
    return _balanced_from(text, text.index("{", m.start()))


def extract_rule_by_ref(ruleset: str, ref: str) -> str:
    """Return the whole `rules { … }` block whose body contains `ref = "<ref>"`."""
    for m in re.finditer(r"\brules\s*{", ruleset):
        block = _balanced_from(ruleset, ruleset.index("{", m.start()))
        if re.search(rf'ref\s*=\s*"{re.escape(ref)}"', block):
            return block
    return ""


def main() -> int:
    text = MAIN_TF.read_text()

    ruleset = extract_block(text, r'resource\s+"cloudflare_ruleset"\s+"redirects"')
    check(bool(ruleset), "cloudflare_ruleset.redirects not found in main.tf")
    if not ruleset:
        _report()
        return 1

    check(
        re.search(r'phase\s*=\s*"http_request_dynamic_redirect"', ruleset) is not None,
        "ruleset phase must be http_request_dynamic_redirect (edge redirect, fires before Pages)",
    )

    refs = re.findall(r'ref\s*=\s*"([^"]+)"', ruleset)
    for expected in ("www_to_app", "staging_to_app_staging"):
        check(expected in refs, f"missing redirect rule ref {expected!r}")

    # The apex /blocked router-compat shim was deliberately removed — assert it
    # has not crept back in.
    check(
        "blocked_compat_shim" not in refs,
        "blocked_compat_shim rule should NOT exist — the apex /blocked shim was dropped (#1842)",
    )

    # Each old-host redirect must hit the right app host AND preserve the query
    # string. Match the exact concat() target as a regex anchored on the opening
    # quote (not a substring `in`, which would also accept e.g.
    # app.wifihaven.net.evil.com and which CodeQL flags as incomplete-URL-sanitization).
    for ref, host, code in (
        ("www_to_app", "app.wifihaven.net", 301),
        ("staging_to_app_staging", "app-staging.wifihaven.net", 301),
    ):
        block = extract_rule_by_ref(ruleset, ref).replace('\\"', '"')
        check(
            re.search(rf'concat\(\s*"https://{re.escape(host)}"\s*,\s*http\.request\.uri\.path', block)
            is not None,
            f"{ref} must redirect to https://{host} + the request path",
        )
        check(
            re.search(rf"status_code\s*=\s*{code}", block) is not None,
            f"{ref} must be a {code}",
        )
        check(
            re.search(r"preserve_query_string\s*=\s*true", block) is not None,
            f"{ref} must preserve the query string",
        )

    _report()
    return 1 if failures else 0


def _report() -> None:
    if failures:
        print("redirects_test: FAIL")
        for f in failures:
            print(f"  ✗ {f}")
    else:
        print(
            "redirects_test: OK — www → app and staging → app-staging are 301, "
            "query-preserving; no apex /blocked shim"
        )


if __name__ == "__main__":
    sys.exit(main())
