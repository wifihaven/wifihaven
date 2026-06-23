#!/usr/bin/env python3
"""Assertion test for the redirect ruleset in main.tf (#1842).

`terraform validate` proves the ruleset is schema-valid, but not that the
*semantics* are right. The merge-gating property here is router back-compat:
pre-rename routers DNAT blocked HTTP/80 to the apex, so wifihaven.net/blocked*
MUST keep working by redirecting to the app host **while preserving the
?mac=&host= query string** the block page reads — and that shim must out-rank
the marketing catch-all (i.e. be the first rule).

We can't curl the live edge from CI (nothing is deployed at PR time), so this
test pins the ruleset config instead. It is a plain-text parse of the HCL
(stdlib only) — deliberately strict about the few properties that, if wrong,
silently break every deployed router's block page.

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

    ruleset = extract_block(
        text, r'resource\s+"cloudflare_ruleset"\s+"redirects"'
    )
    check(bool(ruleset), "cloudflare_ruleset.redirects not found in main.tf")
    if not ruleset:
        _report()
        return 1

    check(
        'phase       = "http_request_dynamic_redirect"' in ruleset
        or 'phase = "http_request_dynamic_redirect"' in ruleset
        or re.search(r'phase\s*=\s*"http_request_dynamic_redirect"', ruleset) is not None,
        "ruleset phase must be http_request_dynamic_redirect (edge redirect, fires before Pages)",
    )

    # Order matters: the first match wins, so the /blocked shim must precede the
    # other host redirects (and, at the edge, the marketing catch-all entirely).
    refs = re.findall(r'ref\s*=\s*"([^"]+)"', ruleset)
    check(
        refs[:1] == ["blocked_compat_shim"],
        f"blocked_compat_shim must be the FIRST rule; got order {refs}",
    )
    for expected in ("blocked_compat_shim", "www_to_app", "staging_to_app_staging"):
        check(expected in refs, f"missing redirect rule ref {expected!r}")

    # The /blocked shim itself — the back-compat net. Pull just its rule block.
    # Normalize HCL string escapes (\" → ") so the substring checks read cleanly.
    shim = extract_rule_by_ref(ruleset, "blocked_compat_shim").replace('\\"', '"')
    check(bool(shim), "could not isolate the blocked_compat_shim rule block")

    # Matches the apex host + /blocked path prefix (so /blocked, /blocked?…,
    # /blocked/anything all hit it).
    check(
        'http.host eq "wifihaven.net"' in shim,
        "/blocked shim must match host wifihaven.net (the pre-rename apex)",
    )
    check(
        'starts_with(http.request.uri.path, "/blocked")' in shim,
        "/blocked shim must match the /blocked path prefix",
    )
    # 302 (retargetable), not 301 (hard-cached).
    check(
        re.search(r"status_code\s*=\s*302", shim) is not None,
        "/blocked shim must be a 302 (retargetable), not 301",
    )
    # CRITICAL: preserve the ?mac=&host= query string the block page reads.
    check(
        re.search(r"preserve_query_string\s*=\s*true", shim) is not None,
        "/blocked shim MUST preserve the query string (?mac=&host=) — the block "
        "page can't render its reason without it",
    )
    # Targets the app host's /blocked, carrying the original path through.
    check(
        "https://app.wifihaven.net" in shim
        and "http.request.uri.path" in shim,
        "/blocked shim must redirect to https://app.wifihaven.net + the request path",
    )

    # www / staging redirects also preserve the query string (lower stakes, but
    # a bookmark with a query should survive the hop).
    for ref, target in (
        ("www_to_app", "https://app.wifihaven.net"),
        ("staging_to_app_staging", "https://app-staging.wifihaven.net"),
    ):
        block = extract_rule_by_ref(ruleset, ref)
        check(target in block, f"{ref} must target {target}")
        check(
            re.search(r"preserve_query_string\s*=\s*true", block) is not None,
            f"{ref} should preserve the query string",
        )

    _report()
    return 1 if failures else 0


def _report() -> None:
    if failures:
        print("redirects_test: FAIL")
        for f in failures:
            print(f"  ✗ {f}")
    else:
        print("redirects_test: OK — /blocked shim is first, 302, query-preserving, "
              "→ app.wifihaven.net; www/staging redirects present")


if __name__ == "__main__":
    sys.exit(main())
