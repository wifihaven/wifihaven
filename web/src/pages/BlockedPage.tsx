import { useSearchParams } from 'react-router-dom'

function reasonText(reason: string, until?: string | null): string {
  // MacBlockReason wire-format strings emitted by api/PolicyService and
  // forwarded by the router's block-page handler (#437). Keep in sync with
  // shared/src/Models.scala (MacBlockReason cases) and the inline copy in
  // openwrt/files/usr/lib/lua/wifihaven/block_page.lua.
  if (reason === 'Paused') return 'This profile is paused.'
  if (reason === 'Schedule') return until ? `This is scheduled quiet time until ${until}.` : 'This is scheduled quiet time.'
  if (reason === 'TimeLimit') return 'Daily screen time limit reached.'
  if (reason === 'Manual') return 'This device has been blocked by a parent.'
  if (reason === 'ExtraBlocked') return 'This site is blocked by the household.'
  // Per-flow drop reasons emitted by the router itself (not MacBlockReason).
  if (reason.startsWith('site_time_limit')) return 'Daily time limit for this site reached.'
  if (reason.startsWith('category:')) return `Blocked category: ${reason.slice('category:'.length)}.`
  if (reason === 'extra_blocked') return 'This site is blocked.'
  return 'Access blocked.'
}

export function BlockedPage() {
  const [params] = useSearchParams()
  const host   = params.get('host') ?? ''
  const reason = params.get('reason') ?? ''
  const until  = params.get('until')

  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <div className="w-full max-w-sm space-y-6 text-center">
        <div className="space-y-2">
          <div className="text-4xl font-bold text-red-500">Blocked</div>
          <div className="text-lg font-mono text-white">{host}</div>
          <p className="text-gray-400 text-sm">{reasonText(reason, until)}</p>
        </div>
        <p className="text-gray-500 text-sm">Ask a parent to adjust your settings.</p>
      </div>
    </div>
  )
}
