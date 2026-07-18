import { useState } from 'react'
import { Link } from 'react-router-dom'
import { copyToClipboard } from '@/lib/clipboard'

// #2234 — the real OpenWRT agent install one-liner shown for the operator to run on the
// router. It uses `uclient-fetch` (present on stock OpenWRT) — NOT curl, which the router
// may not have installed yet (install.sh installs curl on demand).
//
// This exact string also appears in the openwrt/install.sh header and docs/install-openwrt.md
// §2; a drift would hand users a broken command. Their equality is enforced MECHANICALLY, not
// by a "keep in sync" comment (an SSOT anti-pattern — docs/process/single-source-of-truth.md):
// openwrt/test/install_spec.sh extracts the canonical line from install.sh and asserts this
// file and the docs contain it verbatim, so any drift fails CI.
export const ROUTER_INSTALL_COMMAND =
  'sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh)"'

// #2234 — a short, opinionated list of known-good OpenWRT hardware, coordinated with the
// in-repo hardware docs (docs/install-flint2.md names the Flint 2 as the reference router).
// Deliberately NOT an exhaustive compatibility matrix. Every entry runs OpenWRT; the GL.iNet
// boxes ship an OpenWRT-based firmware out of the box (lighter flashing), while mainline
// targets need an OpenWRT flash first (see the prerequisite note below).
//
// #2301 — each entry links to Amazon with our Associates tag (wifihaven-20). Every `amazon`
// URL below is a VERIFIED current product listing (dp/<ASIN>) confirmed available on
// amazon.com as of 2026-07-18 — no fabricated ASINs. If a listing later rots, swap it for the
// rot-proof search fallback `https://www.amazon.com/s?k=<model>&tag=wifihaven-20`, which always
// resolves and still carries the tag. The Belkin RT3200 / Linksys E8450 was dropped here: it is
// no longer reliably available on Amazon (discontinued). Its replacement, the Netgear WAX206,
// is the same MediaTek MT7622 mainline-OpenWRT platform as the RT3200 — the same "cheap target
// you flash yourself" role — and is a currently-stocked amazon.com listing.
interface SuggestedRouter {
  name: string
  detail: string
  note?: string
  // Verified amazon.com product URL carrying tag=wifihaven-20 (Amazon Associates).
  amazon: string
}

const SUGGESTED_ROUTERS: SuggestedRouter[] = [
  {
    name: 'GL.iNet Flint 2 (GL-MT6000)',
    detail:
      'Our reference hardware. Quad-core aarch64, dual-band Wi-Fi 6, 2.5G WAN — plenty of headroom for line-rate filtering and accounting at gigabit.',
    note: 'Ships GL.iNet’s OpenWRT-based firmware; well supported in mainline OpenWRT.',
    amazon: 'https://www.amazon.com/dp/B0CP7S3117?tag=wifihaven-20',
  },
  {
    name: 'GL.iNet Flint (GL-AX1800)',
    detail:
      'The smaller sibling — Wi-Fi 6, gigabit Ethernet. A solid, cheaper option for lighter households.',
    note: 'Also ships an OpenWRT-based firmware.',
    amazon: 'https://www.amazon.com/dp/B09HBW45ZJ?tag=wifihaven-20',
  },
  {
    name: 'Netgear WAX206 (AX3200)',
    detail:
      'An inexpensive mainline-OpenWRT target with strong community support (MediaTek MT7622, Wi-Fi 6, 2.5G WAN). Great value if you’re comfortable flashing.',
    note: 'Requires flashing OpenWRT yourself before installing the agent.',
    amazon: 'https://www.amazon.com/dp/B098BRF91P?tag=wifihaven-20',
  },
]

export function RouterInstallPage() {
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')

  async function copyCommand() {
    // #2234: reuse the shared helper (secure-context Clipboard API + plain-http
    // execCommand fallback) so self-hosted installs served over plain http still copy.
    setCopyState((await copyToClipboard(ROUTER_INSTALL_COMMAND)) ? 'copied' : 'failed')
    setTimeout(() => setCopyState('idle'), 2000)
  }

  return (
    <div className="space-y-8 max-w-3xl">
      <div>
        <h1 className="text-xl font-bold text-brand-ink">Set up your router</h1>
        <p className="text-sm text-brand-text mt-2">
          WifiHaven runs as a small agent on an OpenWRT router — that router becomes your
          household gateway and enforces every profile, schedule and blocklist. Three steps:
          pick hardware, run the install command on it, then paste the enrollment token you
          mint on the Routers tab.
        </p>
      </div>

      {/* Step 1 — hardware */}
      <section className="space-y-4">
        <h2 className="text-base font-semibold text-brand-ink">1. Suggested hardware</h2>
        <div className="bg-amber-500/10 border border-amber-500/30 text-amber-700 text-sm rounded-xl px-4 py-3">
          <strong>Prerequisite:</strong> the router must run <strong>OpenWRT</strong> (23.05.x
          with <code className="font-mono">opkg</code>, or 24.10+/25.12.x with{' '}
          <code className="font-mono">apk</code>). GL.iNet devices ship an OpenWRT-based firmware
          out of the box; other hardware needs an OpenWRT flash first.
        </div>
        <ul className="space-y-3">
          {SUGGESTED_ROUTERS.map(r => (
            <li
              key={r.name}
              className="bg-white rounded-2xl border border-brand-border px-5 py-4"
            >
              <p className="font-medium text-brand-ink">{r.name}</p>
              <p className="text-sm text-brand-text mt-1">{r.detail}</p>
              {r.note && (
                <p className="text-xs text-brand-text-muted mt-1.5">{r.note}</p>
              )}
              {/* #2301 — affiliate link. rel="sponsored" flags the paid relationship (Amazon
                  Associates / SEO), noopener hardens the new tab. */}
              <a
                href={r.amazon}
                target="_blank"
                rel="sponsored noopener"
                className="inline-flex items-center gap-1 text-xs font-semibold text-brand-accent hover:text-brand-accent-dark mt-2.5"
              >
                View on Amazon
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
                  <polyline points="15 3 21 3 21 9" />
                  <line x1="10" y1="14" x2="21" y2="3" />
                </svg>
              </a>
            </li>
          ))}
        </ul>
        {/* #2301 — Amazon Associates requires a visible affiliate disclosure near the links. */}
        <p className="text-xs text-brand-text-muted">
          We may earn a small commission from these links, at no cost to you.
        </p>
      </section>

      {/* Step 2 — install command */}
      <section className="space-y-3">
        <h2 className="text-base font-semibold text-brand-ink">2. Run the install command</h2>
        <p className="text-sm text-brand-text">
          SSH into the router as <code className="font-mono">root</code> and run:
        </p>
        {/* #2234 — copy affordance mirrors the RoutersPage token pattern (#2252): a small icon
            button in the corner of the field, swapping to a check on success / warning on failure. */}
        <div className="relative">
          <pre className="bg-brand-surface border border-brand-border-strong rounded-xl pl-4 pr-12 py-3 text-brand-accent font-mono text-xs overflow-x-auto">
            <code>{ROUTER_INSTALL_COMMAND}</code>
          </pre>
          <CopyCommandIcon state={copyState} onCopy={copyCommand} />
        </div>
        {copyState === 'failed' && (
          <p role="alert" className="text-xs text-red-700">
            Copy failed — select the command above and copy manually.
          </p>
        )}
        <p className="text-sm text-brand-text">
          The script installs the agent, then prompts for the API URL, the enrollment token,
          and your LAN prefix. Accept the defaults unless you’re self-hosting.
        </p>
      </section>

      {/* Step 3 — token */}
      <section className="space-y-3">
        <h2 className="text-base font-semibold text-brand-ink">3. Get your enrollment token</h2>
        <p className="text-sm text-brand-text">
          The install script asks for a one-time enrollment token. Create a router on the
          Routers tab to mint one, then paste it into the script when prompted.
        </p>
        {/* #2235: deep link to the open enroll dialog (?add=1) — no extra clicks to reach
            "Generate Token". */}
        <Link
          to="/routers?add=1"
          className="inline-block bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-lg transition-colors"
        >
          Get an enrollment token →
        </Link>
      </section>
    </div>
  )
}

// #2234 / #2252 — the inline copy icon that sits in the corner of the command block.
// A real <button> (Enter/Space work), aria-labelled, with a focus-visible ring; the icon
// swaps to a check on success and a warning on failure, and the label announces the state.
function CopyCommandIcon({
  state,
  onCopy,
}: {
  state: 'idle' | 'copied' | 'failed'
  onCopy: () => void
}) {
  const label =
    state === 'copied' ? 'Install command copied' :
    state === 'failed' ? 'Copy failed' :
    'Copy install command to clipboard'
  return (
    <button
      type="button"
      data-testid="copy-install-command"
      onClick={onCopy}
      aria-label={label}
      title={label}
      className={`absolute right-1.5 top-1.5 p-2 rounded-lg transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-accent ${
        state === 'copied'
          ? 'text-brand-accent'
          : state === 'failed'
          ? 'text-red-700'
          : 'text-brand-text-muted hover:text-brand-ink hover:bg-brand-alt'
      }`}
    >
      {state === 'copied' ? (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <polyline points="20 6 9 17 4 12" />
        </svg>
      ) : state === 'failed' ? (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
      ) : (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
        </svg>
      )}
    </button>
  )
}
