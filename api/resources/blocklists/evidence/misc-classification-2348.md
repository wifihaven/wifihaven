# gambling / games / ai pass — 2026-07-21 (#2348)

Non-ads categories touched by the weekly `/blocklist-pass`. Same source + method
as `ads-classification-2348.md` (30-day prod `recent-apexes`, 26 devices, 1,712
distinct apexes; classify by what the apex *is*, skip dual-use).

Cross-checked every candidate against the curated inline list for its category.
`adult` and `social-media` produced **no gaps** this week — the one adult apex
seen (`cam4.com`) and every social apex seen (`facebook.com`, `instagram.com`,
`tiktok.com`, `twitter.com`, `reddit.com`, `snapchat.com`, `pinterest.com`,
`whatsapp.net`, `linkedin.com`, `nextdoor.com`) are already curated.

## gambling (1 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| acebet.cc | 5.9M | 29 | Acebet.cc — US sweepstakes social casino (Trey Mark Services Limited), 2000+ game titles from Betsoft/AvatarUX/BGaming/ShadyLady | dedicated real-money-adjacent (sweeps-coin) casino; not in `gambling.yml` |

**Skipped (false positive / ambiguous):** `betweendigital.com` (name matches
the `bet` substring but is Between Digital, a Moscow SSP/RTB ad-tech company —
moved to `ads.yml` instead); `betrad.com` (ambiguous gambling-adjacent
tracking infra, held out — see ads evidence doc).

## games (1 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| saygames.io | 59K | 3 | SayGames — Cyprus-based hyper-casual mobile game publisher, 200+ titles, 8B+ downloads | dedicated game-publisher apex; not in `games.yml` |

**Skipped (dual-use SDK/engine infra):** `unity3d.com` / `unity3dusercontent.com`
— Unity is a game ENGINE/SDK used by thousands of unrelated legitimate games;
blocking it is infrastructure-level collateral, not a block on a specific game
or platform (same reasoning that keeps `jwplayer.com` off the ads list).

**Held (unverified this run):** `viddea.com` (178K, 36 hits — no identifiable
owner found).

## ai (1 added)

| apex | bytes | hits | what it is | why a gap |
|------|------:|-----:|------------|-----------|
| gemini.google | 139K | 8 | Google Gemini's share-link feature (`share.gemini.google`) | rides the bare `.google` gTLD, a *different* apex from the already-curated `gemini.google.com` — the existing entry does not suffix-match it |

Everything else AI-shaped in traffic this week (`anthropic.com`, `claude.ai`,
`claudeusercontent.com`, `claudemcpcontent.com`, `claude.com`) is **our own
vendor's infrastructure** — WifiHaven itself depends on Anthropic's API, so
these are never block candidates regardless of traffic volume; not a
"dual-use" judgment call, just self-evidently out of scope. `openai.com`,
`grok.com`, `elevenlabs.io`, `x.ai` were also observed but are already
curated. `learnings.ai`, `axon.ai`, `ihawk.ai` matched the `.ai` TLD but are
not consumer AI chatbot/generator products (Axon is a policing/body-cam
company) — a TLD match is not a category signal.
