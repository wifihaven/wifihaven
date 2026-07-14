# gambling / social-media / games pass — 2026-07-14 (#2212)

Non-ads categories touched by the weekly `/blocklist-pass`. Same source + method
as `ads-classification-2212.md` (30-day prod `recent-apexes`, 26 devices, 1,643
distinct apexes; classify by what the apex *is*, skip dual-use).

Cross-checked every candidate against the curated inline list for its category.
`ai` and `adult` produced **no gaps** — the top consumer AI apexes in traffic
(`grok.com`, `openai.com`, `elevenlabs.io`, `x.ai`, `claude.ai`, `anthropic.com`)
and the one adult apex seen (`cam4.com`) are **already curated**.

## gambling (3 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| americascardroom.eu | 5.2M | 10 | America's Cardroom — offshore US-facing online poker room | major real-money poker site; not in `gambling.yml` |
| betrivers.com | 1.6M | 8 | BetRivers (Rush Street Interactive) sportsbook + online casino | licensed US sportsbook/casino; not covered (list had DraftKings/FanDuel/BetMGM but not RSI) |
| polymarket.com | 383K | 5 | Polymarket real-money prediction / event-betting market | real-money wagering on outcomes; fits "casinos, poker, sportsbooks" broadly. Included deliberately — it is a betting platform, not a news site |

Each apex is the product itself (dedicated gambling operator) → low collateral.

## social-media (2 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| nextdoor.com | 1.8M | 89 | Nextdoor neighborhood social network | consumer social network; not in `social-media.yml` |
| vk.com | 452K | 16 | VKontakte — Russian consumer social network | major consumer social network; not covered |

**Skipped (dual-use):** `disqus.com` / `spot.im` / `openweb-ayl.com` /
`viafoura.*` (embedded comment widgets on news sites — blocking breaks the host
page, not a social destination); `whatsapp.com` / `whatsapp.net` (private
messaging utility, not a doomscroll network — left out to match the list's
current scope); `dzen.ru` / `yandex.ru` (news aggregator / search, not social).

## games (2 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| eaglercraft.com | 40 | 1 | Eaglercraft — browser-based Minecraft clone, runs in-tab with no install | classic "unblocked games" / filter-bypass vector (same class as the already-listed `duckmath.org` / `emolingo.games`); observed on a kid iPad |
| eaglercraft.ru | 40 | 1 | Eaglercraft (.ru apex, active) | same |

Traffic is minimal, but the `games` list already curates filter-bypass game
hosts on the **evasion-vector** rationale (#1815 / #1922), not raw volume — an
unblocked-Minecraft host appearing on a kid device at all is the signal.

**Skipped (educational / school-sanctioned, not distraction gaming):**
`prodigygame.com`, `mathplayground.com`, `arcademics.com` (educational math
games used in classrooms — out of scope for a games *block*). `crazygames.com` /
`coolmathgames.com` were candidates but are **already curated**.

**Held (unverified this run):** `lightsgames.com` (110K, 15 hits — identity
unconfirmed), `lax1dude.net` (Eaglercraft developer's domain — believed related
but not confirmed as a game host this run).
