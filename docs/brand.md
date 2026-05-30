# wifihaven brand

> A haven for the household network.

This document covers colors, type, the mark/mascot/wordmark system, lockup rules,
and the file inventory under `web/public/brand/`. Closes the design half of
issue #405.

## Palette

| Token        | Hex       | Role                                                 |
|--------------|-----------|------------------------------------------------------|
| Terracotta   | `#C2693A` | The mascot, accent dot under the roof, brand pop.    |
| Tail-shadow  | `#A8582E` | Darker terracotta for the mascot's tail (separation).|
| Belly        | `#E89A6A` | Lighter terracotta — mascot's belly highlight only.  |
| Slate        | `#3A5A6C` | Wordmark, roof mark, acorn cap, UI primary text.     |
| Cream        | `#F2E8D5` | Hero/OG/social card surface, acorn nut, warmth fill. |
| Off-white    | `#FAFAF7` | Page canvas. Default brand background.               |
| Ink          | `#1F1B16` | Body text, fine lines, eye and stem detail.          |

The five "main" colors are terracotta, slate, cream, off-white, ink. Tail-shadow
and belly are *internal* mascot colors — they are not part of the
brand-at-large palette and should not appear elsewhere in product UI.

Avoid: bright primaries (kid-toy red, web-1.0 blue), pure black, pure white.

## Typography

The wordmark is set in **Inter** — all lowercase, weight 500, with mild
negative letter-spacing (`-0.025em`, or `-2` to `-5` units depending on
absolute size). Inter is loaded from Google Fonts in production; the SVG
sources also list Nunito, Poppins, SF Pro Rounded, and the system
`ui-rounded` stack as graceful fallbacks.

The mascot pairs equally well with any humanist rounded sans — if Inter
becomes unavailable, Nunito or Manrope are the closest substitutes. Do not
swap in a geometric grotesque (Helvetica, Arial); the wordmark needs the
slight warmth of a rounded x-height to sit next to a mascot without
feeling clinical.

## The three marks

The brand has three visual primitives. They are not interchangeable.

1. **Mascot** — the terracotta squirrel with acorn. Used in lockups, the OG
   card, the hero, and the empty-state illustration. The mascot is a
   *companion to the wordmark*, never a stand-in for the brand at small
   sizes.
2. **Wordmark** — the word `wifihaven` set in Inter, slate. Used wherever
   text identification of the product is needed.
3. **Mark** (the haven) — a filled gabled-roof silhouette in slate with a
   terracotta device-dot sheltered beneath it. Used wherever the squirrel
   would be visual noise: collapsed sidebar, monochrome contexts, anywhere
   the available pixels are too few for the mascot's silhouette to read.

The acorn is a secondary brand element. It doubles as the "device" /
"wifi-node" dot in the brand language and forms the favicon at 16px.

### Mascot anatomy

The mascot is built from layered solid fills, drawn in this order:

1. Tail (darker terracotta `#A8582E`) — the silhouette priority.
2. Body (terracotta `#C2693A`).
3. Belly highlight (lighter terracotta `#E89A6A`) — gives the body depth
   *without* breaking the single-ink monochrome version.
4. Head (terracotta).
5. Ear (terracotta, with optional inner-ear shadow).
6. Eye (ink), eye-highlight (off-white), nose (ink).
7. Acorn nut (cream with ink stroke), cap (slate), stem (ink).

If you remove any of layers 5–7, the squirrel still reads as a squirrel.
If you remove the tail, it doesn't. Protect the tail.

## Lockups

### Horizontal lockup (default)

Mascot on the left, wordmark on the right, baselines aligned to the
mascot's vertical center. Minimum clear space = half the mascot's width on
every side.

### Stacked lockup

Mascot above wordmark, both centered horizontally. Use only when the slot
is tall/square (mobile splash, app-icon-shaped slots, print collateral).

### Lockup do/don'ts

- **Do** use the horizontal lockup as the default everywhere except where a
  stacked one is required by aspect ratio.
- **Do** scale the lockup uniformly. The mascot must scale with the
  wordmark — never resize one without the other.
- **Don't** rotate, skew, or recolor the mascot.
- **Don't** apply gradients, drop shadows, or outer glows.
- **Don't** place the lockup on a busy or photographic background. If
  you need to, the off-white card (`#FAFAF7`) provides the breathing room.
- **Don't** swap the wordmark colors. The wordmark is always slate
  (`#3A5A6C`) on cream/off-white, or off-white on dark backgrounds.
- **Don't** capitalize the wordmark. `wifihaven` is always lowercase.

## Admin UI usage

- **Header**: `header-mark.svg` (or `header-mark-32.png` / `-64` / `-96`).
  The simplified mascot drops the belly highlight, eye highlight, and
  acorn cap stripe — those details would smear at 32px. This is
  intentional; do not swap in the full mascot at this size.
- **Sidebar collapsed (24×24)**: `sidebar-collapsed.svg`. This uses the
  *roof mark*, not the mascot. The mascot's silhouette is dominated by
  the tail, and at 24px the tail's curl flattens into an ambiguous
  paisley. The roof reads unambiguously at any size and pairs visually
  with the favicon (both are minimalist brand glyphs).
- **Empty states**: `empty-state.svg`. A curled, sleeping mascot for "no
  devices yet," "no events recorded," and similar zero-data screens. The
  tone is warm and reassuring, not alerting — empty isn't bad, it's just
  quiet.

## Favicon

`favicon.svg` is the acorn, sized for 16×16 legibility. The slate cap and
terracotta nut give the mark two-color contrast that survives aggressive
downsampling. The stem disappears below ~20px, which is fine — the cap +
nut still read as an acorn.

The acorn was chosen over the mascot face for the favicon because:

1. It scales cleaner. The squirrel's silhouette is the tail; at 16px the
   tail flattens, and the mascot stops looking like a squirrel.
2. It carries brand meaning. The acorn is the "device protected by the
   haven" — the same dot used in the roof mark. Using the acorn here ties
   the favicon, the roof, and the mascot into one consistent metaphor.

If the team later wants a face-style favicon, swap `favicon.svg` for one
based on the squirrel's head silhouette; the surrounding HTML
(`apple-touch-icon`, `manifest.json`) won't need to change.

### `<head>` reference snippet

```html
<link rel="icon" type="image/svg+xml" href="/brand/favicon.svg" />
<link rel="icon" type="image/png" sizes="16x16"  href="/brand/favicon-16.png" />
<link rel="icon" type="image/png" sizes="32x32"  href="/brand/favicon-32.png" />
<link rel="icon" type="image/png" sizes="48x48"  href="/brand/favicon-48.png" />
<link rel="apple-touch-icon"      sizes="180x180" href="/brand/apple-touch-icon-180.png" />
<link rel="icon" type="image/png" sizes="192x192" href="/brand/android-chrome-192.png" />
<link rel="icon" type="image/png" sizes="512x512" href="/brand/android-chrome-512.png" />
<link rel="shortcut icon"         href="/brand/favicon.ico" />
```

## Mascot name

Working name in copy and conversation: **Tuck**. Brief leaves the final
name to a parallel decision, so the SVG files refer to the squirrel as
"the mascot" or `mascot` — no name appears in any filename or in this
document's prose beyond this section. The file references will not need
to change when the name is decided.

(`Nutkin` was considered and rejected. `Squirrel Nutkin` is a Frederick
Warne / Penguin Random House trademark, actively policed; the underlying
Beatrix Potter story is public-domain but the name is not, and any
adjacent commercial use would generate a polite cease-and-desist.)

## Single-ink rendering

Every mark in this brand is required to work in a single ink color (per
issue #405). To produce a monochrome variant:

1. Take any source SVG.
2. Replace every `fill="…"` and `stroke="…"` value with a single color
   (typically `#1F1B16` for light backgrounds, `#FAFAF7` for dark).
3. Skip the belly highlight layer (it disappears into the body anyway).
4. Verify the silhouette still reads.

There is no separately-shipped monochrome SVG — the substitution is
trivial and the team is expected to perform it at the call site. If
demand emerges, generate `mascot-mono.svg`, `mark-mono.svg`, etc.

## File inventory

All under `web/public/brand/` unless noted otherwise.

### Source SVGs

| File                       | Purpose                                        |
|----------------------------|------------------------------------------------|
| `mascot.svg`               | The squirrel mascot alone (no wordmark).       |
| `mark.svg`                 | The roof + device mark.                        |
| `wordmark.svg`             | `wifihaven` text only.                         |
| `lockup-horizontal.svg`    | Default lockup (mascot + wordmark, horizontal).|
| `lockup-stacked.svg`       | Stacked lockup (mascot above wordmark).        |
| `header-mark.svg`          | Lockup optimized for 32px admin header.        |
| `sidebar-collapsed.svg`    | 24×24 roof mark for collapsed admin sidebar.   |
| `empty-state.svg`          | Curled sleeping mascot for empty-state slots.  |
| `favicon.svg`              | Two-color acorn for browser tabs.              |
| `og-card.svg`              | 1200×630 social card (Open Graph).             |
| `hero.svg`                 | 2400×800 web hero.                             |

### Rendered PNG / ICO exports

| File                         | Size      | Use                                |
|------------------------------|-----------|------------------------------------|
| `hero-2400x800.png`          | 2400×800  | Web hero, README banner.           |
| `og-card-1200x630.png`       | 1200×630  | GitHub social preview, OG meta tag.|
| `header-mark-32.png`         | -×32      | Admin header, 1x.                  |
| `header-mark-64.png`         | -×64      | Admin header, 2x (retina).         |
| `header-mark-96.png`         | -×96      | Admin header, 3x.                  |
| `sidebar-collapsed-24.png`   | 24×24     | Collapsed sidebar, 1x.             |
| `sidebar-collapsed-48.png`   | 48×48     | Collapsed sidebar, 2x.             |
| `sidebar-collapsed-72.png`   | 72×72     | Collapsed sidebar, 3x.             |
| `favicon-16.png`             | 16×16     | Browser tab fallback.              |
| `favicon-32.png`             | 32×32     | Browser tab.                       |
| `favicon-48.png`             | 48×48     | Browser tab, hi-DPI.               |
| `apple-touch-icon-180.png`   | 180×180   | iOS home-screen icon.              |
| `android-chrome-192.png`     | 192×192   | Android home-screen.               |
| `android-chrome-512.png`     | 512×512   | PWA install / splash.              |
| `favicon.ico`                | multi-res | Legacy `.ico` (16/32/48 bundled).  |
| `empty-state.png`            | 600×-     | PNG fallback for empty-state slot. |

### Plain-text

| File              | Purpose                                              |
|-------------------|------------------------------------------------------|
| `cli-banner.txt`  | ASCII wifihaven banner for `install.sh` / boot logs. |

## License

CC-BY-4.0. Shipped under `web/public/brand/` in this repo. Attribution
notice: "wifihaven brand mark, CC-BY-4.0."
