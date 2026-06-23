# RFC: Move the cloud SPA to `app.wifihaven.net` (+ marketing site on apex/www)

**Status:** Plan / RFC — no cut-over in this PR.
**Relates to:** [#1832](https://github.com/wifihaven/wifihaven/issues/1832) (umbrella epic).
**Author:** planning pass, 2026-06-22.

> This is a **plan only**. It changes no DNS, no Cloudflare resources, no CORS,
> no JWT, no `render.yaml` env. It enumerates every surface, the concrete change
> for each, the cut-over sequence, the rollback for each step, and the
> dependency-ordered implementation sub-issues.

---

## 0. Goal & current state

**Goal.** Serve the cloud admin SPA from its own host, **`app.wifihaven.net`**,
distinct from a future **marketing/landing site** on the apex
(`wifihaven.net`) and `www`. Today the prod SPA *is* the apex, which conflates
"the app" with "the website" and blocks standing up a public marketing page.

**Current cloud topology** (from `docs/deploy-cloud.md`, `infra/cloudflare/main.tf`,
`render.yaml`):

| Hostname                    | Serves                | Backed by                          |
|-----------------------------|-----------------------|------------------------------------|
| `wifihaven.net` (apex)      | **prod SPA**          | Cloudflare Pages project `wifihaven` |
| `www.wifihaven.net`         | **prod SPA**          | Cloudflare Pages project `wifihaven` |
| `staging.wifihaven.net`     | **staging SPA**       | Cloudflare Pages project `wifihaven-staging` |
| `api.wifihaven.net`         | prod API              | Render `wifihaven-api-prod`        |
| `api-staging.wifihaven.net` | staging API           | Render `wifihaven-api-staging`     |

**Target topology:**

| Hostname                       | Serves                | Backed by                          |
|--------------------------------|-----------------------|------------------------------------|
| `app.wifihaven.net`            | **prod SPA**          | Pages project `wifihaven` (new custom domain) |
| `app-staging.wifihaven.net`    | **staging SPA**       | Pages project `wifihaven-staging` (new custom domain) |
| `wifihaven.net` (apex) + `www` | **marketing site**    | new Pages project `wifihaven-www`  |
| `staging.wifihaven.net`        | (retained, → redirect to `app-staging`) | — |
| `api*.wifihaven.net`           | API (unchanged)       | Render (unchanged)                 |

The app stays on the **same Pages project** (`wifihaven` / `wifihaven-staging`)
— `app.wifihaven.net` is just an additional **custom domain** on it. The SPA
bundle is identical; only the hostname that fronts it changes. This keeps the CI
deploy (`wrangler pages deploy`, project name in `web/wrangler.toml`) unchanged.

---

## 1. The two findings that set the risk profile

Before the surface enumeration, two facts discovered while tracing the code
change what is and isn't risky here. **Read these first** — they invert the
issue's stated worry ("auth origin changes can lock users out").

### 1.1 Auth is a localStorage bearer token — no cookie, no audience, no issuer

`api/src/auth/AuthService.scala:78` builds the JWT claim with **only**
`role`, `subject (sub)`, `issuedAt`, `expiration`:

```scala
claim = JwtClaim(
  content    = s"""{"role":"${UserRole.asString(user.role)}"}""",
  subject    = Some(user.username),
  issuedAt   = Some(now),
  expiration = Some(now + jwtConfig.expiryHours * 3600L),
)
```

There is **no `aud` (audience)** and **no `iss` (issuer)** claim, and `verify`
(`AuthService.scala:120`) checks neither. The web client stores the token in
`localStorage` and sends it as `Authorization: Bearer …`
(`web/src/hooks/useAuth.tsx:48`, `web/src/api/client.ts`). CORS is configured
with `allowCredentials = DoNotAllow` (`api/src/Cors.scala`), i.e. **no cookies
are involved at all**.

**Consequences:**

- There is **no cookie `Domain` attribute** to widen — moving the SPA host does
  not orphan a session cookie.
- There is **no JWT audience/issuer** to add the new origin to — a token minted
  while the SPA was on the apex stays valid on `app.wifihaven.net` with zero
  changes. `localStorage` is per-origin, so users on the *new* host simply log
  in again; existing tokens are not invalidated, just not visible cross-origin
  (expected, harmless).
- The **only** auth-origin surface that must move is the **API CORS allowlist**
  (`WIFIHAVEN_ALLOWED_ORIGINS`). Miss it and the new host's XHRs are blocked by
  the browser; users see a non-functional app but are **not** "locked out" of an
  existing session, and the fix is a one-line additive env change + redeploy.

This is the single biggest de-risking finding: the dangerous class of rename bug
(cookie-domain / OAuth-audience / redirect-URI mismatch that silently
invalidates sessions) **does not exist in this codebase.**

### 1.2 The block-page host is a per-router UCI key that can't be updated over the wire

The router DNATs blocked HTTP/80 to a **block-page host** stored in the UCI key
`block_page_url` (`openwrt/files/usr/sbin/wifihaven-agent:51`), set **once at
install time** (`openwrt/install.sh:242`). For cloud enrollments the installer
defaults it to `https://wifihaven.net` (`openwrt/install.sh:145`):

```sh
case "$API_URL" in
  *api.wifihaven.net*) block_page_default="https://wifihaven.net" ;;
  *)                   block_page_default="$API_URL" ;;
esac
```

Per the architecture rules, this is **not** on the policy snapshot wire and
**must not** be added to it (snapshot is a minimal functional shape; UCI keys are
explicitly *not* part of the wire contract). So **already-enrolled routers keep
`https://wifihaven.net` forever** unless the operator re-runs install or hand-
edits UCI.

**Consequence:** if the apex stops serving the SPA's `/blocked` route (because it
becomes the marketing site), **every existing router's block page breaks** — a
blocked kid gets the marketing landing page instead of the block screen. This is
the real migration hazard, and it dictates the cut-over order: the apex must keep
answering `/blocked*` (via a redirect to `app.wifihaven.net/blocked*`) for the
indefinite lifetime of routers enrolled before the rename. See §4 and §7.

---

## 2. Surface enumeration with concrete changes

### 2.1 DNS / Cloudflare (Terraform — `infra/cloudflare/main.tf`)

All Cloudflare state is declarative; CI applies on merge to `main` via
`.github/workflows/master-cloudflare.yml` (state on HCP, local-exec). **Do not
touch the dashboard.**

**Additive (Phase 0 — app host alongside the existing apex SPA):**

```hcl
# Prod app host on the existing prod Pages project.
resource "cloudflare_pages_domain" "app" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.prod.name   # "wifihaven"
  domain       = "app.wifihaven.net"
}

resource "cloudflare_record" "spa_app" {
  zone_id = var.zone_id
  name    = "app"
  type    = "CNAME"
  content = "wifihaven.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven — app host (#1832)"
}

# Staging parity (keeps the smoke/e2e CORS chain coherent). NOTE: app-staging
# is an *added recommendation* for chain coherence, not an operator requirement
# — #1832 names only the prod app.wifihaven.net.
resource "cloudflare_pages_domain" "app_staging" {
  account_id   = var.account_id
  project_name = cloudflare_pages_project.staging.name # "wifihaven-staging"
  domain       = "app-staging.wifihaven.net"
}

resource "cloudflare_record" "spa_app_staging" {
  zone_id = var.zone_id
  name    = "app-staging"
  type    = "CNAME"
  content = "wifihaven-staging.pages.dev"
  proxied = true
  ttl     = 1
  comment = "Cloudflare Pages wifihaven-staging — app host (#1832)"
}
```

After Phase 0 the SPA answers on **both** apex/www **and** app.*, so nothing
breaks while we verify.

**Marketing host (later phase — §2.6):** a new
`cloudflare_pages_project "marketing"` (name e.g. `wifihaven-www`) plus repointing
the existing `cloudflare_pages_domain.apex` / `.www` to it, and the redirect
rules in §2.4. This is its own sub-issue and does **not** block the app-host
move.

> Note: `cloudflare_record.spa_apex` / `spa_www` currently CNAME the apex/www to
> `wifihaven.pages.dev` (the app project). When marketing lands they retarget to
> the marketing project's `.pages.dev`. Until then, leave them untouched.

### 2.2 OAuth / JWT / CORS / cookies

Per §1.1, there is **no OAuth, no audience/issuer, no cookie domain, no redirect
URI** anywhere. The complete list of origin-coupled surfaces:

| Surface | File:line | Current | Change |
|---|---|---|---|
| Prod CORS allowlist | `render.yaml:204` (`WIFIHAVEN_ALLOWED_ORIGINS`) | `https://wifihaven.net,https://www.wifihaven.net` | **Add** `https://app.wifihaven.net` (additive — keep old origins through transition) |
| Staging CORS allowlist | `render.yaml:101` | `https://staging.wifihaven.net` | **Add** `https://app-staging.wifihaven.net` |
| Prod UI-allow hosts (#944) | `render.yaml:209` (`WIFIHAVEN_UI_ALLOWED_HOSTS`) | `wifihaven.net,www.wifihaven.net,api.wifihaven.net` | **Add** `app.wifihaven.net` |
| Staging UI-allow hosts (#944) | `render.yaml:106` | `staging.wifihaven.net,api-staging.wifihaven.net` | **Add** `app-staging.wifihaven.net` |
| CORS middleware code | `api/src/Cors.scala` | exact-match origin from config | **No change** (config-driven) |
| JWT mint/verify | `api/src/auth/AuthService.scala:78,120` | no aud/iss | **No change** |
| Web token storage / send | `web/src/hooks/useAuth.tsx`, `web/src/api/client.ts` | localStorage + Bearer | **No change** |

`WIFIHAVEN_UI_ALLOWED_HOSTS` (#944) is **security-relevant and easy to forget**:
it is unioned into *every* profile's snapshot `extraAllowed` so a paused/blocked
device can still reach the admin UI **and the block page**. If `app.wifihaven.net`
is the new SPA/block-page host but isn't in this list, a blocked device cannot
load the block page or the unpause UI — carve-out fails closed. It must be added
**before** the app host becomes primary, and (per the in-line comment) it must
**not** mix prod and staging hosts.

> CORS is **additive and reversible at every step**: the allowlist is a set;
> adding `app.*` cannot break the apex, and removing it later cannot break
> `app.*` until `app.*` is actually the origin. There is never a window where
> both old and new are simultaneously required to be absent.

### 2.3 SPA config

| Concern | Where | Change |
|---|---|---|
| API base URL | `web/src/api/client.ts:21` via `VITE_API_BASE_URL` (baked at build, `master-api-ui.yml:370,693`) | **No change** — the app still talks to `api.wifihaven.net`. The *SPA* host moving does not change the *API* host. |
| Absolute asset / callback URLs | `web/index.html` | **No change** — favicons/OG images are root-relative (`/brand/...`); no hardcoded host. |
| PWA manifest | — | **None exists** (no `.webmanifest`). Nothing to update. (If a marketing-vs-app brand split later wants distinct titles, that's a follow-up, not a rename blocker.) |
| `<title>` / meta | `web/index.html` (`<title>wifihaven</title>`) | **No change** required; optional cosmetic later. |
| Wrangler project name | `web/wrangler.toml`, `web/wrangler.staging.toml` | **No change** — `app.*` is a custom domain on the same project; CI deploy is untouched. |
| SPA routing catch-all | `web/public/_redirects` (`/* /index.html 200`) | **No change** — ships per-project, applies to every custom domain on it. |

Net: **the SPA bundle needs no code change** to live at `app.wifihaven.net`.

### 2.4 Redirects (old host → app, and the block-page compatibility shim)

> **Outcome (2026-06): neither redirect shipped.** The `/blocked` router-compat
> shim was dropped because existing routers were re-pointed to
> `app.wifihaven.net` directly (so no router DNATs its block page at the apex),
> and the `www → app` / `staging → app-staging` redirects were dropped because a
> Cloudflare dynamic-redirect ruleset needs a token scope the deploy token
> doesn't carry and the redirects are low-value. Apex and `www` simply serve the
> marketing site; `staging` keeps serving the staging SPA. The plan below is
> retained as the original design record.

Two distinct redirect needs, with different lifetimes:

1. **`www.wifihaven.net` → `app.wifihaven.net` (human bookmarks):** once apex/www
   become the marketing site, `www`'s old role (SPA) should 301 to the app for
   anyone with an old bookmark. **Permanent (301).** Implement via the marketing
   Pages project's `_redirects` (`https://www.wifihaven.net/*  https://app.wifihaven.net/:splat  301`)
   or a Cloudflare Redirect Rule in TF. Marketing's own homepage can live on the
   apex root.

2. **`wifihaven.net/blocked*` → `app.wifihaven.net/blocked*` (router compat,
   §1.2):** this is **not optional and not removable** on any near horizon —
   it's the only thing keeping pre-rename routers' block pages working. Must
   **preserve the query string** (`?mac=&host=`), because the block page reads
   them. Implement as a high-priority redirect rule that the marketing site's
   catch-all cannot shadow:
   `https://wifihaven.net/blocked*  https://app.wifihaven.net/blocked:splat?:query  302`.
   Recommend **302 (temporary)**, not 301: a 301 is cached hard by browsers, so a
   302 keeps the shim **retargetable** (we can repoint it without fighting stale
   browser caches). This is a choice about retargetability, **not** about expected
   lifetime — per §1.2 the shim must live for the *indefinite* lifetime of
   pre-rename routers, so do not read "temporary" as "short-lived."

   > This shim is *required because* `block_page_url` can't be pushed over the
   > wire (§1.2). The alternative — re-running install / hand-editing UCI on
   > every deployed router — is not operable. The redirect is the correct fix.

3. **`staging.wifihaven.net` → `app-staging.wifihaven.net`:** mirror of (1) for
   staging, lower stakes; 301 once staging cuts over.

### 2.5 Docs / links sweep

| File | Reference | Change |
|---|---|---|
| `openwrt/install.sh:145` | block-page default `https://wifihaven.net` | → `https://app.wifihaven.net` (new installs get the app host directly; existing routers covered by the §2.4(2) shim) |
| `docs/deploy-cloud.md` | custom-domain table (§intro), §1 DNS table, §3, §5 verify, §9 first-login URL | add `app.*` rows; update verify curls; note marketing split |
| `.github/workflows/master-api-ui.yml` | smoke (`:439–467`, Origin/SPA bundle checks against `staging.wifihaven.net`), `E2E_SPA_URL` (`:514`), header comment (`:16–17`) | retarget to `app-staging.wifihaven.net` when staging cuts over |
| `web/src/pages/BlockedPage.tsx:8` | comment "router DNATs … to wifihaven.net" | update comment to `app.wifihaven.net` (+ note the apex `/blocked` compat shim) |
| `docs/architecture.md`, `docs/install-openwrt.md`, `docs/install-flint2.md`, `docs/design/*` | host mentions | sweep + update where they describe the SPA/UI host (grep `wifihaven.net` in `docs/`) |
| 1Password notes (`docs/deploy-cloud.md` §9) | "open `https://wifihaven.net/`" first-login | → `https://app.wifihaven.net/` |

`web/src/api/client.ts` comments mention `api.wifihaven.net` (the API host) —
**leave as-is**, that host is unchanged.

### 2.6 Marketing site (separate sub-track)

**Recommendation — host:** a **new Cloudflare Pages project `wifihaven-www`**,
fronted by apex + `www`. Keeps everything in the one Cloudflare account/zone, one
deploy mechanism (Wrangler), free tier, DNS-01 certs — no new vendor, no new CI
shape. The repo can hold it under `web-marketing/` (or a separate repo if we want
fully independent cadence; in-repo is simpler to start).

**Recommendation — stack:** **Astro** (static output) for a content site — fast,
SEO-friendly, trivial on Pages — or, for an MVP, a single hand-written static
`index.html` + Tailwind to avoid a second toolchain. Start with the static MVP;
adopt Astro only if the content grows.

**MVP scope (one issue):** landing hero (what WifiHaven is — connection-layer
parental controls, *not* DNS blocking), a short "how it works" section, a
self-host vs cloud note, and a prominent **"Open the app" → `app.wifihaven.net`**
CTA. No auth, no dynamic content. Explicitly out of scope for the rename's
critical path — the app can live at `app.wifihaven.net` with apex still serving
the old SPA until marketing is ready.

---

## 3. Cut-over sequence

Ordered so that **DNS + CORS + UI-allow-hosts land and are verified BEFORE** the
app host is anyone's primary, and the apex SPA keeps working until marketing is
ready. Each phase is independently shippable and reversible.

**Phase 0 — Stand up `app.*` alongside the apex (zero user-visible change).**
1. TF: add `app.wifihaven.net` + `app-staging.wifihaven.net` Pages custom domains
   + DNS CNAMEs (§2.1). Merge → `master-cloudflare.yml` applies.
2. `render.yaml`: add `https://app.wifihaven.net` / `https://app-staging.wifihaven.net`
   to CORS allowlist **and** `app.*` to `WIFIHAVEN_UI_ALLOWED_HOSTS` (§2.2).
   Redeploy API (staging then prod).
3. **Verify** (both must pass before Phase 1):
   - `curl -I https://app-staging.wifihaven.net/` → 200, serves the SPA.
   - CORS preflight: `curl -H 'Origin: https://app-staging.wifihaven.net' -I
     https://api-staging.wifihaven.net/api/health` → `access-control-allow-origin:
     https://app-staging.wifihaven.net`.
   - Log in end-to-end on `app-staging.wifihaven.net`; confirm XHRs succeed.
   - Same trio on prod `app.wifihaven.net`.
   - Confirm `app.*` appears in a fresh policy snapshot's `extraAllowed`
     (proves #944 carve-out covers the new block-page/UI host).

**Phase 1 — Make `app.*` the canonical app host for new installs + tooling.**
4. `openwrt/install.sh`: default block-page URL → `https://app.wifihaven.net`.
5. CI: retarget `master-api-ui.yml` smoke + `E2E_SPA_URL` to `app-staging`.
6. Docs sweep (§2.5). `BlockedPage.tsx` comment.
7. **Verify:** a fresh router install points its block page at `app.*`; staging
   smoke/e2e green against `app-staging`.

**Phase 2 — Marketing site on apex/www + redirects.**
8. New `wifihaven-www` Pages project (§2.6); CI deploy job for it.
9. TF: repoint apex/www Pages domains + DNS to `wifihaven-www`.
10. Add redirect rules (§2.4): `www → app` (301), `staging → app-staging` (301),
    and the **`wifihaven.net/blocked* → app.wifihaven.net/blocked*` (302,
    query-preserving) compat shim** — verify it outranks the marketing catch-all.
11. **Verify (critical):** simulate a pre-rename router — hit
    `https://wifihaven.net/blocked?mac=AA:BB:CC:DD:EE:FF&host=example.com` and
    confirm it lands on the app block page with the reason rendered. Confirm apex
    root now serves marketing.

**Phase 3 — Cleanup (after a soak window).**
12. Once apex/www are marketing-only and no SPA traffic hits them, **remove**
    `https://wifihaven.net` / `https://www.wifihaven.net` from `WIFIHAVEN_ALLOWED_ORIGINS`
    and `wifihaven.net,www.wifihaven.net` from `WIFIHAVEN_UI_ALLOWED_HOSTS`
    (keep `api.wifihaven.net`). The `/blocked` compat shim **stays** until
    telemetry proves no pre-rename routers remain.

---

## 4. Rollback per step

Every step is additive and/or TF/Render-reversible; nothing requires a coordinated
two-sided flip.

| Step | Rollback |
|---|---|
| Phase 0.1 (TF app domains/DNS) | Revert the `infra/cloudflare` commit; `master-cloudflare.yml` reapplies, removing the `app.*` domain+record. Apex SPA untouched throughout. |
| Phase 0.2 (CORS / UI-allow-hosts) | Revert the `render.yaml` env change + redeploy. Additive set — removal cannot break the still-canonical apex. |
| Phase 1.4 (install.sh default) | Revert; only affects *new* installs (no fleet impact). Already-installed routers unaffected either way. |
| Phase 1.5–1.6 (CI/docs) | Revert commit. |
| Phase 2.8–2.9 (marketing project + apex repoint) | Revert TF → apex/www CNAME back to `wifihaven.pages.dev` (app project); apex serves the SPA again exactly as today. |
| Phase 2.10 (redirects) | Remove the redirect rules in TF; apex stops redirecting. **Caveat:** if apex has already been repointed to marketing, removing the `/blocked` shim re-breaks existing-router block pages — so roll back 2.10 and 2.9 together. |
| Phase 3.12 (cleanup) | Re-add the apex/www origins+hosts to `render.yaml`; additive, instant on redeploy. |

SPA-content rollback at any time: Cloudflare Pages → Deployments → Rollback (per
`docs/deploy-cloud.md §7`), independent of all the above.

---

## 5. Implementation sub-issues (dependency-ordered)

Each is independently shippable and reversible. File under epic #1832; assignee
`sameerparekh`, label `in-progress`.

1. **TF: add `app.wifihaven.net` + `app-staging.wifihaven.net` Pages custom
   domains + DNS CNAMEs (additive).** `infra/cloudflare/main.tf` per §2.1.
   No user-visible change; apex SPA keeps serving. *Foundation — blocks 3.*
2. **API: add `app.*` origins to CORS allowlist + `WIFIHAVEN_UI_ALLOWED_HOSTS`
   (additive).** `render.yaml` per §2.2 (staging + prod). *Can land in parallel
   with #1; both must be verified before #3.*
3. **Cut over the canonical app host: `install.sh` block-page default → `app.*`,
   CI smoke/`E2E_SPA_URL` → `app-staging`, docs sweep + `BlockedPage.tsx`
   comment.** §2.3/§2.5. *Depends on #1 + #2 verified.*
4. **Marketing site MVP on apex/www: new `wifihaven-www` Pages project + CI
   deploy + apex/www repoint + redirects (incl. the query-preserving
   `/blocked` router-compat shim).** §2.4/§2.6. *Depends on #1–#3; the
   `/blocked` shim is merge-gating for this issue — verify a simulated
   pre-rename router before close.*
5. **Cleanup: drop apex/www from CORS allowlist + UI-allow-hosts after soak.**
   §3 Phase 3. *Last; depends on #4 + a soak window. Keep the `/blocked` shim.*

---

## 6. Explicit non-goals / guardrails

- **No wire/snapshot change.** `block_page_url` stays a UCI key; the
  router-compat problem is solved by an edge redirect, not by shipping the host
  on the policy snapshot (which would violate the minimal-functional-shape rule).
- **No cookie/JWT-audience work** — none exists (§1.1); do not "add an audience
  for the new origin." There is nothing to add.
- **No `VITE_API_BASE_URL` change** — the API host is unchanged.
- **No dashboard clicking** — all Cloudflare/Render changes are declarative
  (`infra/cloudflare`, `render.yaml`) and CI-applied.
- This is distinct from the old `familydns → wifihaven` package renames
  (#363/#560/#561) — this is purely the SPA *host* + the marketing site.
