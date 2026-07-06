# Edge/config globals — the custom-domain pointer

**Status:** v1 documentation-only. No code change ships with this doc.
**Refs:** multi-tenant isolation [#2085](https://github.com/wifihaven/wifihaven/issues/2085),
epic [#622](https://github.com/wifihaven/wifihaven/issues/622), sub-issue **F**
[#2109](https://github.com/wifihaven/wifihaven/issues/2109). Design:
[`multi-tenant-isolation.md`](multi-tenant-isolation.md) §0.2, §2 gap 5, §3.3, §9.

## Why this doc exists

Three server-side config surfaces gate browser access by **origin / hostname**.
Today each is a single **per-deployment** value — one list for the whole API
instance, shared by every household. Under the v1 multi-tenant model that is
correct and needs **no change**: all tenants live under one **shared apex**
(`app.wifihaven.net`), so every household presents the *same* origin, and one
allowlist covers them all.

They become **per-household** only if we ever offer **custom per-household
domains** (e.g. `kids.smith.example` fronting one household). That is an
explicit **non-goal** for v1 ([design §9](multi-tenant-isolation.md#9-non-goals--phasing)).
This doc is the concrete pointer that a future custom-domain epic starts from:
it names exactly which surfaces must move from per-deployment to per-household,
with verified citations, so nothing is missed.

> **This is not a v1 work item.** Nothing here changes until a custom-domain
> epic is filed. When it is, link it from [#2109](https://github.com/wifihaven/wifihaven/issues/2109)
> and from this doc, and close #2109.

## The three surfaces (verified against the tree, 2026-07-06)

| # | Surface | Config type / value | Env var → HOCON key | Consumer (enforcement point) |
|---|---------|---------------------|---------------------|------------------------------|
| 1 | **CORS `allowedOrigins`** | `CorsConfig` ([`Config.scala:119`](../../api/src/Config.scala), field `allowedOrigins` [`:120`](../../api/src/Config.scala), parsed `origins` [`:122`](../../api/src/Config.scala)) | `WIFIHAVEN_ALLOWED_ORIGINS` → `wifihaven.cors.allowedOrigins` | CORS middleware, [`Cors.scala:12`](../../api/src/Cors.scala) (`wrap`) → [`:21`](../../api/src/Cors.scala) (`buildConfig`) |
| 2 | **UI allowed hosts** | `PolicyConfig.uiAllowedHosts` ([`Config.scala:135`](../../api/src/Config.scala), parsed `uiAllowedHostsParsed` [`:149`](../../api/src/Config.scala)) | `WIFIHAVEN_UI_ALLOWED_HOSTS` → `wifihaven.policy.uiAllowedHosts` | unioned into every profile's snapshot `extraAllowed` so a paused member can still reach the admin UI (union at [`PolicyService.scala:201`](../../api/src/policy/PolicyService.scala) `uiGlobalAllow`, threaded from config at [`:892`](../../api/src/policy/PolicyService.scala); #944) |
| 3 | **WS origin gate** | `WsConfig.allowedOrigins` ([`Config.scala:184`](../../api/src/Config.scala), parsed `allowedOriginHosts` [`:191`](../../api/src/Config.scala), match `originAllowed` [`:203`](../../api/src/Config.scala)) | `WIFIHAVEN_WS_ALLOWED_ORIGINS` → `wifihaven.ws.allowedOrigins` | SPA-websocket upgrade check, [`SpaWsRoutes.scala:121`](../../api/src/routes/SpaWsRoutes.scala) (`checkOrigin`); #1969 |

All three parse a comma-separated string, and an **empty value disables the
check entirely** — the self-hosted single-origin path stays header-clean (CORS
middleware skipped, WS same-origin relies on the `SameSite=Strict` `wh_ws`
cookie). `uiAllowedHosts` and `WsConfig.allowedOrigins` default to `""`;
`CorsConfig.allowedOrigins` is a required field (no Scala default) that the
deploy configs supply empty (`render.yaml`, `docker-compose.prod.yml`'s
`${WIFIHAVEN_ALLOWED_ORIGINS:-}`). Cloud/staging populate all three.

Nuance on surface 2: `uiAllowedHosts` is not itself an origin/CORS check — it
lists the deployment's own SPA + API hostnames so a paused household member can
still reach the admin UI to unpause. It is grouped here because it, too, is
keyed on the deployment's public hostnames, so custom per-household domains
would reopen it as per-household config alongside the two origin gates.

### Where these are set per deployment

- [`render.yaml`](../../render.yaml): staging `:114` / `:120` / `:125`,
  prod `:241` / `:247` / `:252` (the three keys in order above).
- [`deploy/docker-compose.prod.yml`](../../deploy/docker-compose.prod.yml):
  `WIFIHAVEN_ALLOWED_ORIGINS` `:51`, `WIFIHAVEN_UI_ALLOWED_HOSTS` `:56`.
- [`deploy/install.sh`](../../deploy/install.sh): `WIFIHAVEN_UI_ALLOWED_HOSTS`
  prompt `:213`.

## What a future custom-domain epic must do

For each of the three surfaces above, move the allowlist from a single
per-deployment value to a per-household lookup keyed on `household_id`
(the tenancy key from sub-issue **A**), resolving the origin/host of an incoming
request to its owning household before the check. The shared-apex default must
keep working unchanged for households that do not opt into a custom domain.

> Line numbers drift. Re-verify every citation in this table against the
> current tree before acting on it — cite from source, never from this doc's
> snapshot (see [`docs/process/verify-and-cite.md`](../process/verify-and-cite.md)).
