import { usePressMessages } from '@/api/queries'
import type { PressMessage } from '@/types/api'

/**
 * #2296 (press correspondence log, epic #2197/#2203): the OPERATOR view of the autonomous press
 * channel — every inbound press email and the AI reply emailed back.
 *
 * Visible only to a household-1 admin — the route/nav is gated on the `isOperator` API signal
 * (mirrors the beta-request queue), and the API independently 404s any other household (press is a
 * company-global channel, not tenant-owned). Read-only: press replies send autonomously; this is
 * the audit surface, not a compose box.
 *
 * The API returns a flat, newest-first list of both directions; we pair each inbound inquiry with
 * the outbound reply that answers it (via `inReplyTo`). Follows docs/process/loading-states.md —
 * loading (spinner), error (affordance), loaded (real data, may legitimately be empty) are
 * distinct; a genuine empty log is never rendered until the query resolves.
 */
export function PressPage() {
  const { data, isPending, isError } = usePressMessages()

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-brand-ink">Press</h1>
        <p className="text-brand-text-muted text-sm mt-1">
          Every inbound press inquiry and the reply our assistant emailed back, newest first. Replies
          send automatically — this is a read-only record.
        </p>
      </div>

      {isPending ? (
        <div className="text-brand-text-muted text-sm py-12 text-center" role="status">
          Loading press correspondence…
        </div>
      ) : isError ? (
        <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
          Couldn't load the press log. Refresh to try again.
        </div>
      ) : (
        <PressThreads messages={data} />
      )}
    </div>
  )
}

function PressThreads({ messages }: { messages: PressMessage[] }) {
  const inbound = messages.filter(m => m.direction === 'inbound')
  const repliesFor = (inboundId: number) =>
    messages.filter(m => m.direction === 'outbound' && m.inReplyTo === inboundId)
  // Outbound rows with no resolvable inbound (a fail-open recording gap, or a reply predating this
  // log) still deserve to be seen rather than silently dropped.
  const orphanOutbound = messages.filter(
    m => m.direction === 'outbound' && (m.inReplyTo == null || !inbound.some(i => i.id === m.inReplyTo)),
  )

  if (messages.length === 0) {
    return (
      <div className="text-brand-text-muted text-sm py-12 text-center">
        No press correspondence yet.
      </div>
    )
  }

  return (
    <ul className="space-y-4">
      {inbound.map(inq => (
        <li key={inq.id} className="bg-white border border-brand-border rounded-xl p-4">
          <InboundHeader msg={inq} />
          <div className="mt-2 text-sm text-brand-text whitespace-pre-wrap break-words">
            {inq.body}
          </div>
          {repliesFor(inq.id).map(reply => (
            <ReplyBlock key={reply.id} msg={reply} />
          ))}
        </li>
      ))}
      {orphanOutbound.map(reply => (
        <li key={`orphan-${reply.id}`} className="bg-white border border-brand-border rounded-xl p-4">
          <ReplyBlock msg={reply} standalone />
        </li>
      ))}
    </ul>
  )
}

function InboundHeader({ msg }: { msg: PressMessage }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <div className="font-medium text-brand-ink truncate">{msg.subject || '(no subject)'}</div>
        <div className="text-sm text-brand-text-muted truncate">from {msg.peerEmail}</div>
      </div>
      <div className="text-xs text-brand-text-muted shrink-0">
        {new Date(msg.createdAt).toLocaleString()}
      </div>
    </div>
  )
}

function ReplyBlock({ msg, standalone = false }: { msg: PressMessage; standalone?: boolean }) {
  return (
    <div className={`mt-3 border-l-2 border-brand-accent/40 pl-3 ${standalone ? '' : 'ml-1'}`}>
      <div className="flex items-center justify-between gap-2">
        <div className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
          {standalone ? `Reply to ${msg.peerEmail}` : 'AI reply'}
          {msg.outcome === 'failed' && (
            <span className="ml-2 normal-case text-red-600 font-normal">send failed</span>
          )}
        </div>
        <div className="text-xs text-brand-text-muted shrink-0">
          {new Date(msg.createdAt).toLocaleString()}
        </div>
      </div>
      <div className="mt-1 text-sm text-brand-text whitespace-pre-wrap break-words">{msg.body}</div>
    </div>
  )
}
