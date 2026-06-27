// #1973 (SPA-ws S5, design docs/design/spa-websocket.md §2/§4/§6): the browser ws
// transport client — the connection state machine the dashboard's live surface rides.
// It is deliberately framework-agnostic (no React, no QueryClient): it owns the socket
// lifecycle, the hello→ready handshake, the subscribe/unsubscribe protocol (§1.4), the
// app-level ping/pong heartbeat (§6.2), the set-cookie-then-connect auth dance (§4.2),
// and exponential-backoff-with-jitter reconnect (§6.1). Cache-patching lives one layer
// up in the React hooks (useWs.tsx) — this client just routes a push payload to the
// handler the subscriber registered.
//
// The socket factory + cookie helpers + RNG are injectable so the whole machine is
// unit-testable with a mock socket + fake timers.

import { clearWsAuthCookie, setWsAuthCookie } from '@/api/wsAuth'

export type WsStatus = 'live' | 'reconnecting' | 'offline'

// The server→SPA push topics (design §1.2). A push frame's `op` IS the topic name, so a
// registered subscription's handler is found by op.
export type SpaTopicName =
  | 'trafficUsage'
  | 'now'
  | 'connectionEvents'
  | 'timeStatus'
  | 'appUsage'
  | 'stale'

// The slice of the browser `WebSocket` the client uses — injectable so tests drive a
// mock. The real `WebSocket` satisfies this shape structurally.
export interface WsSocketLike {
  send(data: string): void
  close(code?: number, reason?: string): void
  onopen: ((ev?: unknown) => void) | null
  onclose: ((ev: { code: number; reason?: string }) => void) | null
  onmessage: ((ev: { data: string }) => void) | null
  onerror: ((ev?: unknown) => void) | null
}

export type SocketFactory = (url: string) => WsSocketLike

export interface SpaWsSubscribeOpts {
  /** Invoked with the push payload (an existing REST body, §0.3) each time the topic fires. */
  onPush: (payload: unknown) => void
  /**
   * The React Query key to refetch ONCE on reconnect (§6.1) so a change missed while
   * disconnected converges. Optional — `stale`-style topics need no refetch.
   */
  refetchKey?: unknown
}

interface Subscription extends SpaWsSubscribeOpts {
  params: unknown
  token: number
}

export interface SpaWsClientOptions {
  /** Same env var the REST client keys off (client.ts) — empty = same-origin self-hosted. */
  apiBaseUrl: string
  /** Defaults to `location.origin`; injectable for tests / SSR-less environments. */
  origin?: string
  /** The operator JWT source — same token REST sends as a bearer (SSOT). */
  getToken: () => string | null
  socketFactory?: SocketFactory
  setCookie?: (jwt: string | null | undefined) => void
  clearCookie?: () => void
  /** Refetch a live query once on reconnect (§6.1); wired to `qc.invalidateQueries`. */
  invalidateQuery?: (key: unknown) => void
  /** Called when the server closes with `4401 token-expired` (§4.3) — hand off to /login. */
  onTokenExpired?: () => void
  heartbeatMs?: number
  reconnectBaseMs?: number
  reconnectCapMs?: number
  /** Jitter source, injectable for deterministic backoff tests. */
  random?: () => number
  clientVersion?: string
}

// Derive the ws URL from the same base the REST client targets (§2.1): http→ws,
// same host. Empty base ⇒ same-origin self-hosted build.
export function wsUrl(apiBaseUrl: string, origin: string): string {
  const base = (apiBaseUrl || origin).replace(/^http/, 'ws')
  return `${base}/api/ws`
}

const DEFAULT_HEARTBEAT_MS = 30_000
const DEFAULT_RECONNECT_BASE_MS = 1_000
const DEFAULT_RECONNECT_CAP_MS = 30_000

export class SpaWsClient {
  private readonly apiBaseUrl: string
  private readonly origin: string
  private readonly getToken: () => string | null
  private readonly socketFactory: SocketFactory
  private readonly setCookie: (jwt: string | null | undefined) => void
  private readonly clearCookie: () => void
  private readonly invalidateQuery?: (key: unknown) => void
  private readonly onTokenExpired?: () => void
  private readonly heartbeatMs: number
  private readonly reconnectBaseMs: number
  private readonly reconnectCapMs: number
  private readonly random: () => number
  private readonly clientVersion: string

  private started = false
  private socket: WsSocketLike | null = null
  private status: WsStatus = 'offline'
  private readonly statusListeners = new Set<() => void>()
  private readonly subs = new Map<SpaTopicName, Subscription>()
  // Per-topic subscription `ack` outcome (§1.4). A topic is only actually *streaming* once
  // the server accepts the subscription (`ok`) — a role-rejected topic (e.g. a Child
  // subscribing `now`) gets `reject` and never receives pushes, so consumers must keep
  // polling for it even while the socket is live. Cleared (→ pending) on (re)subscribe.
  private readonly acks = new Map<SpaTopicName, 'ok' | 'reject'>()
  private subToken = 0
  private attempt = 0
  /** True once a `ready` has been seen — distinguishes the first connect from a reconnect. */
  private hadReady = false
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private lastPongAt = 0

  constructor(opts: SpaWsClientOptions) {
    this.apiBaseUrl = opts.apiBaseUrl
    this.origin = opts.origin ?? (typeof location !== 'undefined' ? location.origin : '')
    this.getToken = opts.getToken
    this.socketFactory =
      opts.socketFactory ?? ((url: string) => new WebSocket(url) as unknown as WsSocketLike)
    this.setCookie = opts.setCookie ?? (jwt => setWsAuthCookie(jwt))
    this.clearCookie = opts.clearCookie ?? (() => clearWsAuthCookie())
    this.invalidateQuery = opts.invalidateQuery
    this.onTokenExpired = opts.onTokenExpired
    this.heartbeatMs = opts.heartbeatMs ?? DEFAULT_HEARTBEAT_MS
    this.reconnectBaseMs = opts.reconnectBaseMs ?? DEFAULT_RECONNECT_BASE_MS
    this.reconnectCapMs = opts.reconnectCapMs ?? DEFAULT_RECONNECT_CAP_MS
    this.random = opts.random ?? Math.random
    this.clientVersion = opts.clientVersion ?? '1'
  }

  // ── status (useSyncExternalStore-compatible; bound for stable identity) ───────────

  getStatus = (): WsStatus => this.status

  subscribeStatus = (listener: () => void): (() => void) => {
    this.statusListeners.add(listener)
    return () => {
      this.statusListeners.delete(listener)
    }
  }

  /**
   * Whether a topic is actually *streaming* right now: the socket is live AND the server
   * accepted the subscription (`ack:ok`). Consumers gate their poll-pause on THIS, not on
   * bare connection liveness — a role-rejected topic (e.g. Child + `now`) is live-socket
   * but never pushed, so its poll must keep running. Notifies via `subscribeStatus`.
   */
  topicActive = (topic: SpaTopicName): boolean =>
    this.status === 'live' && this.acks.get(topic) === 'ok'

  private notify(): void {
    for (const l of this.statusListeners) l()
  }

  private setStatus(next: WsStatus): void {
    if (this.status === next) return
    this.status = next
    this.notify()
  }

  // ── lifecycle ─────────────────────────────────────────────────────────────────────

  start(): void {
    if (this.started) return
    this.started = true
    this.attempt = 0
    this.hadReady = false
    this.openConnection()
  }

  stop(): void {
    this.started = false
    this.clearReconnect()
    this.clearHeartbeat()
    this.closeSocket()
    this.setStatus('offline')
  }

  private openConnection(): void {
    this.clearReconnect()
    const token = this.getToken()
    if (!token) {
      // No credential — an unauthenticated SPA never opens the socket. Stays offline
      // until start() is called again after login.
      this.setStatus('offline')
      return
    }
    // Set the tightly-scoped wh_ws cookie just before connect (§4.2) — re-set on every
    // (re)connect from the CURRENT token.
    this.setCookie(token)
    this.setStatus('reconnecting')
    let socket: WsSocketLike
    try {
      socket = this.socketFactory(wsUrl(this.apiBaseUrl, this.origin))
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket = socket
    socket.onopen = () => this.handleOpen()
    socket.onmessage = ev => this.handleFrame(ev.data)
    // Guard by socket identity: a late close from a SUPERSEDED socket must not tear down
    // the current one. (Practically unreachable given the ≥1s backoff, but provably safe.)
    socket.onclose = ev => {
      if (this.socket === socket) this.handleClose(ev?.code ?? 1006)
    }
    socket.onerror = () => {
      // Let onclose drive reconnect; closing here makes the error path deterministic.
      try {
        socket.close()
      } catch {
        /* already closing */
      }
    }
  }

  private handleOpen(): void {
    // The cookie's job is done the moment the upgrade completes — clear it so the
    // credential isn't sitting around (§4.2). The short Max-Age is the backstop.
    this.clearCookie()
    this.send({ op: 'hello', payload: { clientVersion: this.clientVersion } })
    // Stay 'reconnecting' until `ready` — a socket that opens but never readys must not
    // read as live (§1.4).
  }

  private handleClose(code: number): void {
    this.clearHeartbeat()
    this.socket = null
    if (!this.started) return
    if (code === 4401) {
      // Token expired mid-connection (§4.3): stop reconnecting, fall back to polling +
      // /login. The next REST call's 401 drives the existing redirect.
      this.started = false
      this.setStatus('offline')
      this.onTokenExpired?.()
      return
    }
    this.scheduleReconnect()
  }

  // ── reconnect / backoff (§6.1) ──────────────────────────────────────────────────────

  private scheduleReconnect(): void {
    this.setStatus('reconnecting')
    const delay = this.backoffDelay(this.attempt)
    this.attempt += 1
    this.clearReconnect()
    this.reconnectTimer = setTimeout(() => this.openConnection(), delay)
  }

  // Exponential backoff capped at reconnectCapMs, with equal jitter so a fleet of tabs
  // doesn't reconnect in lockstep. Reset on `ready` (not `open`) — see handleReady.
  private backoffDelay(attempt: number): number {
    const exp = Math.min(this.reconnectCapMs, this.reconnectBaseMs * 2 ** attempt)
    return exp / 2 + this.random() * (exp / 2)
  }

  private clearReconnect(): void {
    if (this.reconnectTimer != null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  // ── frame handling ──────────────────────────────────────────────────────────────────

  private handleFrame(data: string): void {
    let frame: { op?: unknown; payload?: unknown }
    try {
      frame = JSON.parse(data)
    } catch {
      return // undecodable — ignore (forward-compat, §2.2)
    }
    const op = typeof frame.op === 'string' ? frame.op : ''
    switch (op) {
      case 'ready':
        this.handleReady()
        return
      case 'pong':
        this.lastPongAt = Date.now()
        return
      case 'ping':
        this.send({ op: 'pong', payload: {} })
        return
      case 'ack': {
        // Subscription confirmation (§1.4) — keyed by topic. Record ok/reject so consumers
        // can tell an actually-streaming topic from a role-rejected one (topicActive).
        const ack = frame.payload as { topic?: unknown; status?: unknown }
        const topic = typeof ack?.topic === 'string' ? (ack.topic as SpaTopicName) : undefined
        if (topic && this.subs.has(topic)) {
          this.acks.set(topic, ack.status === 'ok' ? 'ok' : 'reject')
          this.notify()
        }
        return
      }
      default: {
        // A push frame: op === topic. Route to the registered handler, if any.
        const sub = this.subs.get(op as SpaTopicName)
        if (sub) sub.onPush(frame.payload)
        // Unknown op with no subscription → ignore (forward-compat).
        return
      }
    }
  }

  private handleReady(): void {
    const isReconnect = this.hadReady
    this.hadReady = true
    this.attempt = 0 // reset backoff on ready, not merely open (§6.1)
    this.setStatus('live')
    // The server starts each connection subscribed to nothing — (re)send the full
    // subscription set on every ready (§1.4).
    for (const [topic, sub] of this.subs) this.sendSubscribe(topic, sub.params)
    // On RECONNECT only, refetch the live queries once so a change missed while
    // disconnected converges (§6.1); the streams then resume on their next push.
    if (isReconnect && this.invalidateQuery) {
      for (const sub of this.subs.values()) {
        if (sub.refetchKey !== undefined) this.invalidateQuery(sub.refetchKey)
      }
    }
    this.startHeartbeat()
  }

  // ── heartbeat / liveness (§6.2) ─────────────────────────────────────────────────────

  private startHeartbeat(): void {
    this.clearHeartbeat()
    this.lastPongAt = Date.now()
    this.heartbeatTimer = setInterval(() => this.heartbeatTick(), this.heartbeatMs)
  }

  private heartbeatTick(): void {
    // A missed app-pong for 2× the interval ⇒ treat the socket as dead and reconnect
    // (browsers can't send WS control pings, §0.2.2 — this is the app-level liveness).
    if (this.lastPongAt && Date.now() - this.lastPongAt > 2 * this.heartbeatMs) {
      this.closeSocket() // onclose → scheduleReconnect
      return
    }
    this.send({ op: 'ping', payload: {} })
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer != null) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  // ── subscriptions (§1.4) ────────────────────────────────────────────────────────────

  /**
   * Register a subscription and (if live) send the `subscribe` frame. Lifecycle = the
   * mounted UI (§1.4): the returned disposer unsubscribes. Re-subscribe on param change
   * is driven by the caller (a React effect's cleanup→re-run) so the wire sees
   * unsubscribe-then-subscribe — the server replaces that connection's prior
   * subscription for the topic.
   */
  subscribe(topic: SpaTopicName, params: unknown, opts: SpaWsSubscribeOpts): () => void {
    const token = ++this.subToken
    this.subs.set(topic, { params, onPush: opts.onPush, refetchKey: opts.refetchKey, token })
    if (this.status === 'live') this.sendSubscribe(topic, params)
    return () => {
      const cur = this.subs.get(topic)
      // Only tear down if this disposer still owns the current registration — guards the
      // cleanup-then-resubscribe race when params change.
      if (cur && cur.token === token) {
        this.subs.delete(topic)
        this.acks.delete(topic)
        if (this.status === 'live') this.sendUnsubscribe(topic)
      }
    }
  }

  private sendSubscribe(topic: SpaTopicName, params: unknown): void {
    // Pending until the server acks — clear any prior outcome so topicActive reads false
    // until this subscription is confirmed (also resets it across a reconnect replay).
    this.acks.delete(topic)
    const payload =
      params === undefined ? { topic } : { topic, params }
    this.send({ op: 'subscribe', payload })
  }

  private sendUnsubscribe(topic: SpaTopicName): void {
    this.acks.delete(topic)
    this.send({ op: 'unsubscribe', payload: { topic } })
  }

  // ── low-level send / teardown ───────────────────────────────────────────────────────

  private send(frame: { op: string; payload?: unknown }): void {
    if (!this.socket) return
    try {
      this.socket.send(JSON.stringify(frame))
    } catch {
      /* socket racing a close — onclose will drive reconnect */
    }
  }

  // Tear down the current socket WITHOUT going through its onclose (we detach all handlers
  // first, so neither a real socket's async onclose nor a mock's synchronous one re-enters).
  // Used by stop() (started already false → no reconnect) and the heartbeat-dead path
  // (started true → drive handleClose(1006) ourselves to schedule a reconnect). The normal
  // server-initiated close arrives on the live `onclose` handler instead, never here.
  private closeSocket(): void {
    const s = this.socket
    this.socket = null
    if (!s) return
    const wasStarted = this.started
    s.onopen = s.onmessage = s.onerror = s.onclose = null
    try {
      s.close()
    } catch {
      /* already closing */
    }
    if (wasStarted) this.handleClose(1006)
  }
}
