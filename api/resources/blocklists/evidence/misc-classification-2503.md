# adult / ai / gambling / games / social-media pass — 2026-07-28 (#2503)

Non-ads categories touched by the weekly `/blocklist-pass`. Same source +
method as `ads-classification-2503.md` (30-day prod `recent-apexes`, 26
devices, 1,636 distinct apexes; classify by what the apex *is*, skip
dual-use).

## social-media (1 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| redditmedia.com | 8.4M | 6 | Reddit's own image/video/thumbnail CDN — sibling apex to the already-curated `redd.it`/`redditstatic.com` | different apex from `reddit.com`, not suffix-matched by the existing entry; confirmed Reddit-only infra (not a shared/dual-use CDN), same pattern as the existing `redd.it`/`redditstatic.com` companions |

**Skipped (dual-use SDK / support-widget infra):** `snapkit.com` — Snap Inc's
own developer platform (Login Kit / Creative Kit / Story Kit) embedded by
many unrelated third-party apps to integrate "Login with Snapchat" / share
buttons; blocking it breaks those integrations elsewhere, not just Snapchat
itself. `freshchat.com`, `gorgias.chat`, `sierra.chat` — customer-support
chat widgets (Freshworks, Gorgias, Sierra AI) embedded broadly on business
websites, not social networks.

## adult, ai, gambling, games — no gaps this pass

- **adult**: zero adult-shaped apexes appeared in this week's traffic at all
  (no hits on any porn/adult keyword sweep).
- **gambling**: zero gambling-shaped apexes appeared in this week's traffic
  (previously-added `acebet.cc` didn't even show up this window). Two
  `bet`-substring matches turned out to be false positives / already handled:
  `betweendigital.com` is ad-tech (already curated in `ads.yml`, see ads
  evidence doc), `betrad.com` is held out unverified (ambiguous, carried
  forward from #2348 — see ads evidence doc's held-out list).
- **games**: `prodigygame.com` (educational/classroom game — explicitly out
  of scope per the #2212 learning), `steamboat.com` / `steamboatpilot.com`
  (Steamboat Pilot, a Colorado newspaper — false positive from the "steam"
  substring match) were the only candidates; both rejected. `saygames.io`
  and `eaglercraft.com`/`.ru` are already curated.
- **ai**: `claude.com`, `copilot.money`, `copilotmoney.app` were the only
  candidates. `claude.com` is Anthropic's own corporate/support domain (the
  chat product itself lives at the already-curated `claude.ai`; per the
  ai.yml host-scoping convention we list product subdomains, not bare vendor
  apexes — and per #2348's prior note, Anthropic's own infra is out of scope
  for WifiHaven regardless of traffic volume, since the API server itself
  depends on it). `copilot.money`/`copilotmoney.app` are Copilot Money, a
  personal-finance/budgeting app — a false positive from the `copilot`
  substring, wrong category entirely (not an AI chatbot).
