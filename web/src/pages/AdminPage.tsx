import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { HouseholdSettings, UnmanagedMacPolicy } from '@/types/api'
import { TimezonePicker } from '@/components/TimezonePicker'
import { PageLoader } from './DashboardPage'
import { useDebouncedSave, mergeSaveStatus } from '@/hooks/useDebouncedSave'
import { SaveStatusBadge } from '@/components/SaveStatusBadge'

export function AdminPage() {
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

      {hs && <DailyResetCard value={hs} reload={reload} />}
      {hs && <HeartbeatFilterCard value={hs} reload={reload} />}
      {hs && <UnmanagedMacPolicyCard value={hs} reload={reload} />}
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
