import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import type {
  Device,
  ProfileDetail,
  TrafficUsageBucket,
  TrafficUsageGroupBy,
  TrafficUsageResponse,
} from '@/types/api'
import { HostCell } from '@/components/HostCell'

// #846 — Traffic Usage page. Bucket selector + group-by selector + raw / aggregated tables.
// Per-app group is reserved for the apps track (#761-#769); per-apex needs PSL.
// Both are disabled here and the server rejects them with a typed error.

interface BucketOption {
  value: TrafficUsageBucket
  label: string
  disabled?: boolean
  disabledReason?: string
}

const BUCKETS: BucketOption[] = [
  { value: 'raw', label: 'Raw (5m)' },
  {
    value: '1m',
    label: '1m',
    disabled: true,
    disabledReason: 'requires faster router upload cadence — not implemented',
  },
  { value: '10m', label: '10m' },
  { value: '1h', label: '1h' },
  { value: '12h', label: '12h' },
  { value: '1d', label: '1d' },
  { value: '1w', label: '1w' },
]

interface GroupByOption {
  value: TrafficUsageGroupBy
  label: string
  disabled?: boolean
  disabledReason?: string
}

const GROUP_BYS: GroupByOption[] = [
  { value: 'domain', label: 'per-domain' },
  {
    value: 'apex',
    label: 'per-apex',
    disabled: true,
    disabledReason: 'public-suffix list not available in API yet',
  },
  {
    value: 'app',
    label: 'per-app',
    disabled: true,
    disabledReason: 'apps track (#761-#769) not implemented',
  },
]

function defaultFromISO(): string {
  const d = new Date(Date.now() - 24 * 60 * 60 * 1000)
  return d.toISOString()
}

function nowISO(): string {
  return new Date().toISOString()
}

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function fmtDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m < 60) return s > 0 ? `${m}m ${s}s` : `${m}m`
  const h = Math.floor(m / 60)
  const mm = m % 60
  return mm > 0 ? `${h}h ${mm}m` : `${h}h`
}

function localTime(iso: string, tz: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, { timeZone: tz })
  } catch {
    return iso
  }
}

export function TrafficUsagePage() {
  const [bucket, setBucket] = useState<TrafficUsageBucket>('raw')
  const [groupBy, setGroupBy] = useState<TrafficUsageGroupBy>('domain')
  const [from, setFrom] = useState<string>(defaultFromISO())
  const [to, setTo] = useState<string>(nowISO())
  const [mac, setMac] = useState<string>('')
  const [profileId, setProfileId] = useState<string>('')
  const [devices, setDevices] = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [data, setData] = useState<TrafficUsageResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const tz = useMemo(
    () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    [],
  )

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    api.usage
      .traffic({
        mac: mac || undefined,
        profileId: profileId ? Number(profileId) : undefined,
        from,
        to,
        bucket,
        groupBy: bucket === 'raw' ? undefined : groupBy,
        tz,
      })
      .then(d => {
        if (!cancelled) setData(d)
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setError(e.message || 'failed to load')
          setData(null)
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [bucket, groupBy, from, to, mac, profileId, tz])

  return (
    <div className="space-y-4" data-testid="traffic-usage-page">
      <header>
        <h1 className="text-2xl font-bold text-gray-100">Traffic Usage</h1>
        <p className="text-sm text-gray-500">
          Raw <code className="text-emerald-400">traffic_reports</code> rows plus on-the-fly
          aggregation by domain. Times shown in {tz}.
        </p>
      </header>

      {/* Bucket selector */}
      <div className="flex flex-wrap gap-2" role="group" aria-label="bucket-selector">
        {BUCKETS.map(b => (
          <button
            key={b.value}
            type="button"
            disabled={b.disabled}
            data-testid={`bucket-${b.value}`}
            onClick={() => {
              if (!b.disabled) setBucket(b.value)
            }}
            title={b.disabledReason ?? ''}
            className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
              b.disabled
                ? 'bg-gray-900 text-gray-600 cursor-not-allowed'
                : bucket === b.value
                ? 'bg-emerald-600 text-white'
                : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
            }`}
          >
            {b.label}
          </button>
        ))}
      </div>

      {/* Group-by selector — only for aggregated views */}
      {bucket !== 'raw' && (
        <div className="flex flex-wrap gap-2" role="group" aria-label="groupby-selector">
          <span className="text-sm text-gray-500 self-center">group by:</span>
          {GROUP_BYS.map(g => (
            <button
              key={g.value}
              type="button"
              disabled={g.disabled}
              data-testid={`groupby-${g.value}`}
              onClick={() => {
                if (!g.disabled) setGroupBy(g.value)
              }}
              title={g.disabledReason ?? ''}
              className={`px-2.5 py-1 rounded text-xs font-medium transition-colors ${
                g.disabled
                  ? 'bg-gray-900 text-gray-600 cursor-not-allowed'
                  : groupBy === g.value
                  ? 'bg-emerald-600 text-white'
                  : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
              }`}
            >
              {g.label}
            </button>
          ))}
        </div>
      )}

      {/* Filters: device / profile / time range */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3 bg-gray-900/40 rounded p-3 border border-gray-800">
        <label className="text-xs text-gray-400">
          Device
          <select
            data-testid="device-filter"
            value={mac}
            onChange={e => {
              setMac(e.target.value)
              if (e.target.value) setProfileId('')
            }}
            className="block w-full mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm"
          >
            <option value="">all</option>
            {devices.map(d => (
              <option key={d.mac} value={d.mac}>
                {d.name} ({d.mac})
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-gray-400">
          Profile
          <select
            data-testid="profile-filter"
            value={profileId}
            onChange={e => {
              setProfileId(e.target.value)
              if (e.target.value) setMac('')
            }}
            className="block w-full mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm"
          >
            <option value="">all</option>
            {profiles.map(p => (
              <option key={p.profile.id} value={p.profile.id}>
                {p.profile.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-gray-400">
          From (ISO)
          <input
            data-testid="from-input"
            value={from}
            onChange={e => setFrom(e.target.value)}
            className="block w-full mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm font-mono"
          />
        </label>
        <label className="text-xs text-gray-400">
          To (ISO)
          <input
            data-testid="to-input"
            value={to}
            onChange={e => setTo(e.target.value)}
            className="block w-full mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm font-mono"
          />
        </label>
      </div>

      {error && (
        <div data-testid="error" className="text-red-400 text-sm">
          {error}
        </div>
      )}
      {loading && <div className="text-gray-500 text-sm">Loading…</div>}

      {data && bucket === 'raw' && (
        <RawTable data={data} tz={tz} />
      )}
      {data && bucket !== 'raw' && (
        <AggregateTable data={data} tz={tz} />
      )}
    </div>
  )
}

function RawTable({ data, tz }: { data: TrafficUsageResponse; tz: string }) {
  return (
    <div className="overflow-x-auto" data-testid="raw-table">
      <table className="min-w-full text-sm">
        <thead className="text-gray-500 text-xs uppercase">
          <tr>
            <th className="text-left px-2 py-1">Period start</th>
            <th className="text-left px-2 py-1">Device</th>
            <th className="text-left px-2 py-1">Profile</th>
            <th className="text-left px-2 py-1">Host</th>
            <th className="text-right px-2 py-1">Bytes in</th>
            <th className="text-right px-2 py-1">Bytes out</th>
            <th className="text-right px-2 py-1">Seconds</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {data.rawRows.length === 0 && (
            <tr>
              <td colSpan={7} className="text-center text-gray-500 py-4">
                No rows in window.
              </td>
            </tr>
          )}
          {data.rawRows.map((r, i) => (
            <tr key={i} className="border-t border-gray-800">
              <td className="px-2 py-1 font-mono text-xs">{localTime(r.periodStart, tz)}</td>
              <td className="px-2 py-1">{r.deviceName ?? r.mac}</td>
              <td className="px-2 py-1">{r.profileName ?? '-'}</td>
              <td className="px-2 py-1"><HostCell host={r.host} /></td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.bytesIn)}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.bytesOut)}</td>
              <td className="px-2 py-1 text-right font-mono">{r.activeSeconds}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function AggregateTable({ data, tz }: { data: TrafficUsageResponse; tz: string }) {
  return (
    <div className="overflow-x-auto" data-testid="aggregate-table">
      <table className="min-w-full text-sm">
        <thead className="text-gray-500 text-xs uppercase">
          <tr>
            <th className="text-left px-2 py-1">Window start</th>
            <th className="text-left px-2 py-1">Group</th>
            <th className="text-right px-2 py-1">Bytes in</th>
            <th className="text-right px-2 py-1">Bytes out</th>
            <th className="text-right px-2 py-1">Total seconds</th>
            <th className="text-right px-2 py-1">Devices</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {data.aggregateRows.length === 0 && (
            <tr>
              <td colSpan={6} className="text-center text-gray-500 py-4">
                No rows in window.
              </td>
            </tr>
          )}
          {data.aggregateRows.map((r, i) => (
            <tr key={i} className="border-t border-gray-800">
              <td className="px-2 py-1 font-mono text-xs">{localTime(r.windowStart, tz)}</td>
              <td className="px-2 py-1">{r.group}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.totalBytesIn)}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.totalBytesOut)}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtDuration(r.totalSeconds)}</td>
              <td className="px-2 py-1 text-right font-mono">{r.distinctDevices}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
