// #1973 (SPA-ws S5): the transport state machine (design §2/§4/§6), driven by a mock
// socket + fake timers. Proves: hello→ready flips to live, subscriptions replay on
// ready, the cookie is set-before-connect / cleared-after-open, re-subscribe sends
// unsubscribe+subscribe, socket close → backoff → reconnect re-sets cookie + replays +
// refetches once, `4401` stops reconnecting, and the heartbeat reconnects on a dead pong.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { SpaWsClient, wsUrl, type SpaWsClientOptions, type WsSocketLike } from './wsClient'

class MockSocket implements WsSocketLike {
  static instances: MockSocket[] = []
  url: string
  sent: string[] = []
  closed = false
  onopen: ((ev?: unknown) => void) | null = null
  onclose: ((ev: { code: number; reason?: string }) => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onerror: ((ev?: unknown) => void) | null = null

  constructor(url: string) {
    this.url = url
    MockSocket.instances.push(this)
  }
  send(data: string): void {
    this.sent.push(data)
  }
  close(code?: number, reason?: string): void {
    this.closed = true
    this.onclose?.({ code: code ?? 1006, reason })
  }
  // ── test helpers ──
  open(): void {
    this.onopen?.()
  }
  emit(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) })
  }
  frames(): Array<{ op: string; payload?: unknown }> {
    return this.sent.map(s => JSON.parse(s))
  }
  ops(): string[] {
    return this.frames().map(f => f.op)
  }
}

interface Harness {
  client: SpaWsClient
  cookieSet: ReturnType<typeof vi.fn>
  cookieClear: ReturnType<typeof vi.fn>
  invalidate: ReturnType<typeof vi.fn>
  tokenExpired: ReturnType<typeof vi.fn>
}

function makeClient(over: Partial<SpaWsClientOptions> = {}): Harness {
  const cookieSet = vi.fn()
  const cookieClear = vi.fn()
  const invalidate = vi.fn()
  const tokenExpired = vi.fn()
  const client = new SpaWsClient({
    apiBaseUrl: 'https://api.wifihaven.net',
    origin: 'https://app.wifihaven.net',
    getToken: () => 'jwt-abc',
    socketFactory: (url: string) => new MockSocket(url),
    setCookie: cookieSet,
    clearCookie: cookieClear,
    invalidateQuery: invalidate,
    onTokenExpired: tokenExpired,
    heartbeatMs: 1000,
    reconnectBaseMs: 1000,
    reconnectCapMs: 30000,
    random: () => 0, // deterministic backoff: delay = exp/2
    ...over,
  })
  return { client, cookieSet, cookieClear, invalidate, tokenExpired }
}

function last(): MockSocket {
  return MockSocket.instances[MockSocket.instances.length - 1]
}

beforeEach(() => {
  MockSocket.instances = []
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('wsUrl (§2.1)', () => {
  it('derives ws:// from the http base, same host', () => {
    expect(wsUrl('https://api.wifihaven.net', 'https://app.x')).toBe('wss://api.wifihaven.net/api/ws')
    expect(wsUrl('', 'http://localhost:5173')).toBe('ws://localhost:5173/api/ws')
  })
})

describe('connect + handshake (§4.2/§1.4)', () => {
  it('sets the cookie before connect, sends hello on open, clears the cookie after open', () => {
    const h = makeClient()
    h.client.start()
    expect(h.cookieSet).toHaveBeenCalledWith('jwt-abc')
    expect(MockSocket.instances).toHaveLength(1)
    expect(last().url).toBe('wss://api.wifihaven.net/api/ws')
    expect(h.cookieClear).not.toHaveBeenCalled()

    last().open()
    expect(h.cookieClear).toHaveBeenCalledTimes(1)
    expect(last().ops()).toEqual(['hello'])
  })

  it('ready flips the status to live and notifies listeners', () => {
    const h = makeClient()
    const seen: string[] = []
    h.client.subscribeStatus(() => seen.push(h.client.getStatus()))
    h.client.start()
    expect(h.client.getStatus()).toBe('reconnecting')
    last().open()
    last().emit({ op: 'ready', payload: { role: 'admin', serverTime: 't' } })
    expect(h.client.getStatus()).toBe('live')
    expect(seen).toContain('live')
  })

  it('replays the subscription set on ready (server starts subscribed to nothing)', () => {
    const h = makeClient()
    h.client.start()
    h.client.subscribe('now', undefined, { onPush: () => {} })
    h.client.subscribe('trafficUsage', { groupBy: ['profile'], bucket: '1m' }, { onPush: () => {} })
    // not live yet → no subscribe frames on the wire
    expect(last().ops().filter(o => o === 'subscribe')).toHaveLength(0)
    last().open()
    last().emit({ op: 'ready', payload: {} })
    const subs = last().frames().filter(f => f.op === 'subscribe')
    expect(subs).toHaveLength(2)
    expect(subs.map(f => (f.payload as { topic: string }).topic).sort()).toEqual(['now', 'trafficUsage'])
  })

  it('routes a push to the registered handler by topic', () => {
    const h = makeClient()
    const onNow = vi.fn()
    h.client.start()
    h.client.subscribe('now', undefined, { onPush: onNow })
    last().open()
    last().emit({ op: 'ready', payload: {} })
    last().emit({ op: 'now', payload: { asOf: 'x', profiles: [] } })
    expect(onNow).toHaveBeenCalledWith({ asOf: 'x', profiles: [] })
  })

  it('replies pong to a server ping', () => {
    const h = makeClient()
    h.client.start()
    last().open()
    last().emit({ op: 'ready', payload: {} })
    const before = last().ops().length
    last().emit({ op: 'ping', payload: {} })
    expect(last().ops().slice(before)).toContain('pong')
  })
})

describe('re-subscribe on param change (#747)', () => {
  it('sends unsubscribe then subscribe when the bucket changes', () => {
    const h = makeClient()
    h.client.start()
    last().open()
    last().emit({ op: 'ready', payload: {} })
    const dispose = h.client.subscribe('trafficUsage', { groupBy: ['profile'], bucket: '1m' }, { onPush: () => {} })
    // simulate the React effect cleanup→re-run on bucket change
    dispose()
    h.client.subscribe('trafficUsage', { groupBy: ['profile'], bucket: '10m' }, { onPush: () => {} })
    const ops = last().frames()
    const idxSub1 = ops.findIndex(f => f.op === 'subscribe' && (f.payload as { params?: { bucket?: string } }).params?.bucket === '1m')
    const idxUnsub = ops.findIndex(f => f.op === 'unsubscribe')
    const idxSub2 = ops.findIndex(f => f.op === 'subscribe' && (f.payload as { params?: { bucket?: string } }).params?.bucket === '10m')
    expect(idxSub1).toBeGreaterThanOrEqual(0)
    expect(idxUnsub).toBeGreaterThan(idxSub1)
    expect(idxSub2).toBeGreaterThan(idxUnsub)
  })
})

describe('reconnect / backoff (§6.1)', () => {
  it('on socket close: backs off, reconnects, re-sets cookie, replays subs, refetches once', () => {
    const h = makeClient()
    h.client.start()
    h.client.subscribe('now', undefined, { onPush: () => {}, refetchKey: ['dashboard', 'now'] })
    last().open()
    last().emit({ op: 'ready', payload: {} })
    expect(h.client.getStatus()).toBe('live')
    h.cookieSet.mockClear()
    h.invalidate.mockClear()

    // server drops the connection
    last().onclose?.({ code: 1006 })
    expect(h.client.getStatus()).toBe('reconnecting')
    expect(MockSocket.instances).toHaveLength(1) // not yet — backing off

    // backoff delay = exp/2 = 1000/2 = 500ms (random()=0)
    vi.advanceTimersByTime(500)
    expect(MockSocket.instances).toHaveLength(2)
    expect(h.cookieSet).toHaveBeenCalledWith('jwt-abc') // cookie re-set before reconnect

    last().open()
    last().emit({ op: 'ready', payload: {} })
    // subscriptions replayed on the new connection
    expect(last().frames().some(f => f.op === 'subscribe' && (f.payload as { topic: string }).topic === 'now')).toBe(true)
    // live queries refetched ONCE on reconnect
    expect(h.invalidate).toHaveBeenCalledWith(['dashboard', 'now'])
    expect(h.invalidate).toHaveBeenCalledTimes(1)
  })

  it('does NOT refetch on the FIRST connect (only on reconnect)', () => {
    const h = makeClient()
    h.client.start()
    h.client.subscribe('now', undefined, { onPush: () => {}, refetchKey: ['dashboard', 'now'] })
    last().open()
    last().emit({ op: 'ready', payload: {} })
    expect(h.invalidate).not.toHaveBeenCalled()
  })

  it('4401 token-expired stops reconnecting and hands off to /login', () => {
    const h = makeClient()
    h.client.start()
    last().open()
    last().emit({ op: 'ready', payload: {} })
    last().onclose?.({ code: 4401, reason: 'token-expired' })
    expect(h.client.getStatus()).toBe('offline')
    expect(h.tokenExpired).toHaveBeenCalledTimes(1)
    // no reconnect, ever
    vi.advanceTimersByTime(60_000)
    expect(MockSocket.instances).toHaveLength(1)
  })
})

describe('heartbeat (§6.2)', () => {
  it('sends a ping each interval and reconnects on a dead pong', () => {
    const h = makeClient()
    h.client.start()
    last().open()
    last().emit({ op: 'ready', payload: {} })
    const sock = last()

    // one interval → ping sent (pong fresh from ready, so not dead yet)
    vi.advanceTimersByTime(1000)
    expect(sock.ops()).toContain('ping')

    // no pong arrives; > 2× interval since last pong → treated dead → socket closed → reconnect
    vi.advanceTimersByTime(2500)
    expect(sock.closed).toBe(true)
    expect(h.client.getStatus()).toBe('reconnecting')
  })

  it('a pong keeps the connection alive', () => {
    const h = makeClient()
    h.client.start()
    last().open()
    last().emit({ op: 'ready', payload: {} })
    const sock = last()
    vi.advanceTimersByTime(1000)
    sock.emit({ op: 'pong', payload: {} })
    vi.advanceTimersByTime(1500)
    expect(sock.closed).toBe(false)
    expect(h.client.getStatus()).toBe('live')
  })
})

describe('stop()', () => {
  it('closes the socket, goes offline, and never reconnects', () => {
    const h = makeClient()
    h.client.start()
    last().open()
    last().emit({ op: 'ready', payload: {} })
    h.client.stop()
    expect(h.client.getStatus()).toBe('offline')
    vi.advanceTimersByTime(60_000)
    expect(MockSocket.instances).toHaveLength(1)
  })
})

describe('unauthenticated', () => {
  it('does not open a socket without a token', () => {
    const h = makeClient({ getToken: () => null })
    h.client.start()
    expect(MockSocket.instances).toHaveLength(0)
    expect(h.client.getStatus()).toBe('offline')
  })
})
