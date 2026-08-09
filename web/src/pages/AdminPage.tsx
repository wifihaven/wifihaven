import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'
import type { EnforcementStatus, HouseholdSettings, UnmanagedMacPolicy } from '@/types/api'
import { TimezonePicker } from '@/components/TimezonePicker'
import { PageLoader } from './DashboardPage'
import { useDebouncedSave, mergeSaveStatus } from '@/hooks/useDebouncedSave'
import { SaveStatusBadge } from '@/components/SaveStatusBadge'

export function AdminPage() {
  // #2522: every card below patches `/api/household/settings` (`requireWriter`), so an adult
  // reaches this page. The escape hatch is the split one: its READ
  // (`GET /api/household/enforcement`) is `requireAuth` — deliberately, so every role can tell
  // that blocking is off — while the WRITE (`PUT`) is still `requireAdmin`. So the card renders
  // for any writer and hands only an admin the toggle.
  const { isAdmin } = useAuth()
  const [hs, setHs] = useState<HouseholdSettings | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.household.get()
      .then(setHs)
      .finally(() => setLoading(false))
  }, [])

  // After any subsection autosave, re-read the server-of-record so a silently
  // dropped field (#571) snaps back to its persisted value rather than showing
  // a false confirmation.
  async function reload() {
    setHs(await api.household.get())
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-brand-ink">Admin</h1>
        <p className="text-sm text-brand-text-muted mt-1">Settings that apply to the whole household.</p>
      </div>

      <DisableEnforcementCard canToggle={isAdmin} />
      {hs && <DailyResetCard value={hs} reload={reload} />}
      {hs && <HeartbeatFilterCard value={hs} reload={reload} />}
      {hs && <NotifyEmailCard value={hs} reload={reload} />}
      {hs && <BlockEncryptedDnsCard value={hs} reload={reload} />}
      {hs && <AmbientGateCard value={hs} reload={reload} />}
      {hs && <UnmanagedMacPolicyCard value={hs} reload={reload} />}
    </div>
  )
}

// #2382 — the server-level per-household "disable enforcement" escape hatch. When on, ALL blocking
// for the household stops (every device passes through). It is the easy dashboard equivalent of the
// on-router escape hatch (#2381) — but because it is server-driven it does NOT work if the API/
// server is unreachable; the copy points the user at the on-router toggle for an outage. Its own
// query (not household settings) since it is backed by the households table, not household_settings.
function DisableEnforcementCard({ canToggle }: { canToggle: boolean }) {
  const [status, setStatus] = useState<EnforcementStatus | null>(null)
  const [disabled, setDisabled] = useState(false)

  useEffect(() => {
    api.household.getEnforcement()
      .then(s => { setStatus(s); setDisabled(s.enforcementDisabled) })
      .catch(() => { /* leave null → the card shows its loading affordance */ })
  }, [])

  const save = useDebouncedSave(
    disabled,
    async (next) => {
      const s = await api.household.setEnforcement(next)
      setStatus(s)
    },
    // This card loads its value asynchronously (it is backed by its own endpoint, not the parent's
    // already-loaded settings), so the initial `disabled=false` is a placeholder, not the saved
    // baseline. Key the save on load state: when the fetch resolves, the baseline resets to the
    // just-loaded value instead of treating "false → loaded true" as a user edit and firing a
    // spurious PUT (+ snapshot invalidation) on every page visit where the hatch is already on.
    { key: status === null ? 'loading' : 'loaded' },
  )

  return (
    <div
      data-testid="disable-enforcement-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Turn off all blocking (escape hatch)</h2>
        {canToggle && (
          <SaveStatusBadge
            testId="disable-enforcement-save-status"
            status={save.status}
            error={save.error}
            onRetry={save.retry}
          />
        )}
      </div>

      <p className="text-xs text-brand-text">
        Flips off <em>all</em> enforcement for the whole household — schedules, time limits, blocked
        sites, and category blocklists — so every device gets the open internet. Use this when a
        setting is blocking something it shouldn&rsquo;t and you just need things working right now,
        then turn it back on. While it&rsquo;s on, nothing on this network is filtered.
      </p>
      <p className="text-xs text-brand-text-muted">
        This works from anywhere but <strong>only while the WifiHaven server is reachable</strong>.
        If the internet is down or the server is unreachable, use the on-router escape hatch instead
        (the &ldquo;Disable enforcement&rdquo; toggle in your router&rsquo;s LuCI admin, or the
        <code className="mx-1">wifihaven-disable</code> command over SSH).
      </p>
      {/* #1098: never conclude a state from an unloaded query — "not disabled" and "still
          loading" are different answers, and the second must not be painted as the first. */}
      {status === null ? (
        <p className="text-sm text-brand-text-muted" data-testid="disable-enforcement-loading">
          Loading&hellip;
        </p>
      ) : canToggle ? (
        <label className="flex items-center gap-2 text-sm text-brand-ink">
          <input
            type="checkbox"
            checked={disabled}
            onChange={e => setDisabled(e.target.checked)}
            data-testid="disable-enforcement-toggle"
            className="h-4 w-4"
          />
          Disable all blocking for this household
        </label>
      ) : (
        /*
          #2522 — an adult may not flip the hatch, but must be able to SEE that it is flipped:
          "nothing is being blocked" is the single most confusing state to debug without being
          told, and the read is `requireAuth` precisely so every role can be told.
        */
        <p className="text-sm text-brand-ink" data-testid="disable-enforcement-status">
          {disabled
            ? 'All blocking is currently OFF for this household. Only the account admin can turn it back on.'
            : 'Blocking is on. Only the account admin can turn it off.'}
        </p>
      )}
    </div>
  )
}

// #961 — admin control for how the household treats MACs that have appeared
// on the network but are not enrolled into any profile. Router-side
// enforcement of `block` is deferred behind Gate 2 (#654); for v1 this just
// persists the policy + drives the in-SPA "Unmanaged Devices" copy.
function UnmanagedMacPolicyCard({
  value, reload,
}: {
  value: HouseholdSettings
  reload: () => Promise<void>
}) {
  const [policy, setPolicy] = useState<UnmanagedMacPolicy['policy']>(value.unmanagedMacPolicy.policy)
  const [blockPage, setBlockPage] = useState(value.unmanagedMacPolicy.blockPage)
  useEffect(() => { setPolicy(value.unmanagedMacPolicy.policy) }, [value.unmanagedMacPolicy.policy])
  useEffect(() => { setBlockPage(value.unmanagedMacPolicy.blockPage) }, [value.unmanagedMacPolicy.blockPage])

  const policySave = useDebouncedSave(
    policy,
    async (next) => {
      await api.household.patch({ unmanagedMacPolicy: { policy: next } })
      await reload()
    },
  )
  const blockPageSave = useDebouncedSave(
    blockPage,
    async (next) => {
      await api.household.patch({ unmanagedMacPolicy: { blockPage: next } })
      await reload()
    },
  )
  const merged = mergeSaveStatus([policySave, blockPageSave])

  return (
    <div
      data-testid="unmanaged-mac-policy-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Unmanaged devices</h2>
        <SaveStatusBadge
          testId="unmanaged-mac-policy-save-status"
          status={merged.status}
          error={merged.error}
          onRetry={merged.retry}
        />
      </div>

      <p className="text-xs text-brand-text">
        Applied to any MAC that connects to the network without being assigned to a profile.
        When set to "block" the API marks the device manual-blocked in the next router
        snapshot, so the existing per-MAC enforcement path applies — no router code change
        needed.
      </p>
      <div className="space-y-2">
        <label className="flex items-center gap-2 text-sm text-brand-ink">
          <input
            type="radio"
            name="unmanaged-mac-policy"
            value="allow"
            checked={policy === 'allow'}
            onChange={() => setPolicy('allow')}
            data-testid="unmanaged-mac-policy-allow"
          />
          Allow unmanaged MACs (default; alert-only)
        </label>
        <label className="flex items-center gap-2 text-sm text-brand-ink">
          <input
            type="radio"
            name="unmanaged-mac-policy"
            value="block"
            checked={policy === 'block'}
            onChange={() => setPolicy('block')}
            data-testid="unmanaged-mac-policy-block"
          />
          Block unmanaged MACs
        </label>
      </div>
      <label className="flex items-center gap-2 text-sm text-brand-ink">
        <input
          type="checkbox"
          checked={blockPage}
          disabled={policy !== 'block'}
          onChange={e => setBlockPage(e.target.checked)}
          data-testid="unmanaged-mac-policy-block-page"
          className="h-4 w-4"
        />
        Show block page (HTTP/80 DNATs to the "device not enrolled" page)
      </label>
    </div>
  )
}

// #1913 / #1909 — network-wide "block encrypted DNS & relays" toggle. Forces
// every device onto the LAN resolver (turns off iCloud Private Relay + public
// DoH/DoT, drops hardcoded resolver IPs) so WifiHaven's filtering and
// hostname attribution actually see the traffic. Household-wide, not
// per-profile (the clean disable signal — NXDOMAIN — is network-wide only).
function BlockEncryptedDnsCard({
  value, reload,
}: {
  value: HouseholdSettings
  reload: () => Promise<void>
}) {
  const [enabled, setEnabled] = useState(value.blockEncryptedDns)
  useEffect(() => { setEnabled(value.blockEncryptedDns) }, [value.blockEncryptedDns])

  const save = useDebouncedSave(
    enabled,
    async (next) => {
      await api.household.patch({ blockEncryptedDns: next })
      await reload()
    },
  )

  return (
    <div
      data-testid="block-encrypted-dns-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Block encrypted DNS &amp; relays</h2>
        <SaveStatusBadge
          testId="block-encrypted-dns-save-status"
          status={save.status}
          error={save.error}
          onRetry={save.retry}
        />
      </div>

      <p className="text-xs text-brand-text">
        Forces every device onto this network's local DNS resolver so WifiHaven can filter
        traffic and attribute it to the right site. Turns off iCloud Private Relay and public
        encrypted DNS (DoH/DoT) — without this, a device can tunnel around all filtering and
        time limits. Applies to the whole household, not a single profile.
      </p>
      {/* #2643: new households start with this ON, so the surface has to say so and say what
          turning it off costs — a default that changes network behaviour is only an improvement
          over the old silent-off if the operator can see it. Shown in both states: when on it
          explains why it is already on, when off it names what is currently unenforced. */}
      <p
        data-testid="block-encrypted-dns-default-note"
        className="text-xs text-brand-text"
      >
        {enabled
          ? 'On by default for new networks. Turn it off only if a device here needs its own encrypted DNS — while it is off, that device can bypass all filtering and time limits, and its traffic shows up as raw IP addresses instead of site names.'
          : 'This is off. Any device on this network can currently use iCloud Private Relay or its own encrypted DNS to bypass all filtering and time limits, and its traffic will show as raw IP addresses instead of site names. New networks start with this on.'}
      </p>
      <label className="flex items-center gap-2 text-sm text-brand-ink">
        <input
          type="checkbox"
          checked={enabled}
          onChange={e => setEnabled(e.target.checked)}
          data-testid="block-encrypted-dns-enabled"
          className="h-4 w-4"
        />
        Block encrypted DNS &amp; relays
      </label>
    </div>
  )
}

// #578 — where to email when a kid raises an access request from the block page
// (extension / exemption / unpause). Empty clears the recipient. The API only
// actually sends when its email transport (Resend) is configured; otherwise this
// address is recorded but notifications fall back to a server log line.
function NotifyEmailCard({
  value, reload,
}: {
  value: HouseholdSettings
  reload: () => Promise<void>
}) {
  const [email, setEmail] = useState(value.notifyEmail ?? '')
  useEffect(() => { setEmail(value.notifyEmail ?? '') }, [value.notifyEmail])

  const save = useDebouncedSave(
    email,
    async (next) => {
      await api.household.patch({ notifyEmail: next.trim() })
      await reload()
    },
  )

  return (
    <div
      data-testid="notify-email-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Request notifications</h2>
        <SaveStatusBadge
          testId="notify-email-save-status"
          status={save.status}
          error={save.error}
          onRetry={save.retry}
        />
      </div>

      <p className="text-xs text-brand-text">
        When a child taps &ldquo;Ask a parent for access&rdquo; on the block page, WifiHaven emails
        this address so you can approve or deny from the dashboard. Leave blank to turn email
        notifications off &mdash; pending requests still appear in the dashboard either way.
      </p>
      <input
        type="email"
        value={email}
        onChange={e => setEmail(e.target.value)}
        placeholder="parent@example.com"
        data-testid="notify-email-input"
        className="w-full rounded-lg border border-brand-border px-3 py-2 text-sm text-brand-ink"
      />
    </div>
  )
}

// #2077 — the ambient anchor gate: screen time only counts when a device shows an
// engagement signature (an assigned app's traffic, or a host outside the learned
// ambient baseline). The learner runs regardless of the toggle so the would-be
// ambient set is inspectable (GET /api/presence/ambient-hosts) before enabling.
function AmbientGateCard({
  value, reload,
}: {
  value: HouseholdSettings
  reload: () => Promise<void>
}) {
  const [enabled, setEnabled] = useState(value.ambientGateEnabled)
  useEffect(() => { setEnabled(value.ambientGateEnabled) }, [value.ambientGateEnabled])

  const save = useDebouncedSave(
    enabled,
    async (next) => {
      await api.household.patch({ ambientGateEnabled: next })
      await reload()
    },
  )

  return (
    <div
      data-testid="ambient-gate-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Ignore idle background traffic</h2>
        <SaveStatusBadge
          testId="ambient-gate-save-status"
          status={save.status}
          error={save.error}
          onRetry={save.retry}
        />
      </div>

      <p className="text-xs text-brand-text">
        Devices sitting unused still phone home (iCloud sync, photo uploads, OS updates,
        widget refreshes) and that background chatter can read as screen time. When enabled,
        time only counts while a device shows real engagement — traffic from an assigned app,
        or a host outside its learned idle baseline. The baseline learns automatically from
        traffic that habitually appears on its own.
      </p>
      <label className="flex items-center gap-2 text-sm text-brand-ink">
        <input
          type="checkbox"
          checked={enabled}
          onChange={e => setEnabled(e.target.checked)}
          data-testid="ambient-gate-enabled"
          className="h-4 w-4"
        />
        Ignore idle background traffic
      </label>
    </div>
  )
}

function HeartbeatFilterCard({
  value, reload,
}: {
  value: HouseholdSettings
  reload: () => Promise<void>
}) {
  const [enabled, setEnabled] = useState(value.heartbeatFilter.enabled)
  const [bytes, setBytes] = useState(value.heartbeatFilter.bytesThreshold)
  useEffect(() => { setEnabled(value.heartbeatFilter.enabled) }, [value.heartbeatFilter.enabled])
  useEffect(() => { setBytes(value.heartbeatFilter.bytesThreshold) }, [value.heartbeatFilter.bytesThreshold])

  const bytesValid = Number.isFinite(bytes) && bytes >= 0

  const enabledSave = useDebouncedSave(
    enabled,
    async (next) => {
      await api.household.patch({ heartbeatFilter: { enabled: next } })
      await reload()
    },
  )
  const bytesSave = useDebouncedSave(
    bytes,
    async (next) => {
      if (!Number.isFinite(next) || next < 0) throw new Error('Bytes threshold must be ≥ 0.')
      await api.household.patch({ heartbeatFilter: { bytesThreshold: Math.trunc(next) } })
      await reload()
    },
  )
  // #1525: the per-install heartbeat host allowlist editor was removed. Host-identity suppression
  // now lives in the server-side canonical InfraHosts list; this card only tunes the byte floor.
  const merged = mergeSaveStatus([enabledSave, bytesSave])

  return (
    <div
      data-testid="heartbeat-filter-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Heartbeat filter</h2>
        <SaveStatusBadge
          testId="heartbeat-filter-save-status"
          status={merged.status}
          error={merged.error}
          onRetry={merged.retry}
        />
      </div>

      <p className="text-xs text-brand-text">
        Excludes low-traffic background "heartbeat" rows from device/profile screen-time
        totals. A row is classified as a heartbeat when its bytes/minute is below the
        configured floor. Known device-level infrastructure (OS/telemetry/cert/safe-browsing
        hosts) is excluded automatically and is no longer configured here.
      </p>
      {!bytesValid && (
        <div
          data-testid="heartbeat-filter-validation"
          className="bg-amber-500/10 border border-amber-500/30 text-amber-700 text-sm rounded-xl px-4 py-2"
        >
          Bytes threshold must be ≥ 0.
        </div>
      )}
      <label className="flex items-center gap-2 text-sm text-brand-ink">
        <input
          type="checkbox"
          checked={enabled}
          onChange={e => setEnabled(e.target.checked)}
          data-testid="heartbeat-filter-enabled"
          className="h-4 w-4"
        />
        Filter enabled
      </label>
      <div className="flex flex-wrap gap-3 items-end">
        <div>
          <label className="block text-xs text-brand-text-muted mb-1">Bytes/min threshold</label>
          <input
            type="number"
            min={0}
            step={1}
            value={bytes}
            onChange={e => setBytes(Number(e.target.value))}
            data-testid="heartbeat-filter-bytes"
            className="bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink text-sm w-32"
          />
        </div>
      </div>
    </div>
  )
}

function DailyResetCard({
  value, reload,
}: {
  value: HouseholdSettings
  reload: () => Promise<void>
}) {
  const [time, setTime] = useState(value.dailyResetTime)
  const [tz, setTz] = useState(value.dailyResetTz)
  useEffect(() => { setTime(value.dailyResetTime) }, [value.dailyResetTime])
  useEffect(() => { setTz(value.dailyResetTz) }, [value.dailyResetTz])

  const timeSave = useDebouncedSave(
    time,
    async (next) => {
      await api.household.patch({ dailyResetTime: next })
      await reload()
    },
  )
  const tzSave = useDebouncedSave(
    tz,
    async (next) => {
      await api.household.patch({ dailyResetTz: next })
      await reload()
    },
  )
  const merged = mergeSaveStatus([timeSave, tzSave])

  return (
    <div
      data-testid="household-settings-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-brand-ink">Daily reset</h2>
        <SaveStatusBadge
          testId="household-save-status"
          status={merged.status}
          error={merged.error}
          onRetry={merged.retry}
        />
      </div>

      <p className="text-xs text-brand-text">
        The wall-clock time at which daily usage limits reset. Both the reset time and
        timezone are stored together — the reset always fires at the configured local clock
        time, even across DST.
      </p>
      <div className="flex flex-wrap gap-3 items-end">
        <div>
          <label className="block text-xs text-brand-text-muted mb-1">Reset time</label>
          <input
            type="time"
            value={time}
            onChange={e => setTime(e.target.value)}
            data-testid="household-reset-time"
            className="bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink text-sm"
          />
        </div>
        <div className="flex-1 min-w-[14rem]">
          <label className="block text-xs text-brand-text-muted mb-1">Timezone</label>
          <TimezonePicker
            value={tz}
            onChange={setTz}
            testId="household-reset-tz"
          />
        </div>
      </div>
    </div>
  )
}
