import { describe, it, expect, vi } from 'vitest'
import { assetFingerprint, createBuildChecker, type BuildCheckLog } from './buildCheck'

// #2903: a tab opened before a deploy keeps running the old bundle. The checker compares the
// running bundle's hashed /assets/ URLs against the ones in the freshly served index.html.

const OLD_HTML = `<!doctype html><html><head>
  <script type="module" crossorigin src="/assets/index-OLD111.js"></script>
  <link rel="stylesheet" crossorigin href="/assets/index-OLDCSS.css">
  <link rel="icon" href="/brand/favicon.svg">
</head><body><div id="root"></div></body></html>`

const NEW_HTML = OLD_HTML.replace('index-OLD111.js', 'index-NEW222.js')

function htmlResponse(body: string, status = 200): Response {
  return new Response(body, { status, headers: { 'Content-Type': 'text/html' } })
}

function quietLog(): BuildCheckLog & { info: ReturnType<typeof vi.fn>; warn: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> } {
  return { info: vi.fn(), warn: vi.fn(), error: vi.fn() }
}

describe('assetFingerprint', () => {
  it('collects the hashed /assets/ script and stylesheet URLs, ignoring everything else', () => {
    expect(assetFingerprint(OLD_HTML)).toBe('/assets/index-OLD111.js /assets/index-OLDCSS.css')
  })

  it('is null when the page references no /assets/ files (dev server, or not our index.html)', () => {
    expect(assetFingerprint('<html><body><script type="module" src="/src/main.tsx"></script></body></html>')).toBeNull()
  })

  it('does not depend on tag order', () => {
    const reordered = `<html><head>
      <link rel="stylesheet" href="/assets/index-OLDCSS.css">
      <script type="module" src="/assets/index-OLD111.js"></script>
    </head></html>`
    expect(assetFingerprint(reordered)).toBe(assetFingerprint(OLD_HTML))
  })

  it('reads absolute URLs by path, so a same-origin absolute src still matches', () => {
    const abs = OLD_HTML.replace('src="/assets/', 'src="https://app.example.test/assets/')
    expect(assetFingerprint(abs)).toBe(assetFingerprint(OLD_HTML))
  })
})

describe('createBuildChecker', () => {
  it('stays current when the served index.html references the same bundle', async () => {
    const log = quietLog()
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex: async () => htmlResponse(OLD_HTML),
      log,
    })
    await checker.check()
    expect(checker.snapshot().status).toBe('current')
    expect(log.error).not.toHaveBeenCalled()
    expect(log.warn).not.toHaveBeenCalled()
  })

  it('goes stale when the served index.html references a different bundle, and stops checking', async () => {
    const log = quietLog()
    const fetchIndex = vi.fn(async () => htmlResponse(NEW_HTML))
    let t = 0
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex,
      log,
      now: () => t,
      minIntervalMs: 1000,
    })
    await checker.check()
    expect(checker.snapshot().status).toBe('stale')
    expect(log.info).toHaveBeenCalledWith(expect.stringContaining('[build-check]'))

    t += 10_000
    await checker.check()
    expect(fetchIndex).toHaveBeenCalledTimes(1)
  })

  it('notifies subscribers on the transition to stale', async () => {
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex: async () => htmlResponse(NEW_HTML),
      log: quietLog(),
    })
    const listener = vi.fn()
    checker.subscribe(listener)
    await checker.check()
    expect(listener).toHaveBeenCalled()
  })

  it('treats a 200 index.html with no /assets/ references as a broken check: status error + console.error', async () => {
    const log = quietLog()
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex: async () => htmlResponse('<html><body>Maintenance</body></html>'),
      log,
    })
    await checker.check()
    const snap = checker.snapshot()
    expect(snap.status).toBe('error')
    expect(snap.status === 'error' && snap.reason).toMatch(/no \/assets\//)
    expect(log.error).toHaveBeenCalledWith(expect.stringContaining('[build-check]'))
  })

  it('recovers from error to current once the served page is readable again', async () => {
    let html = '<html></html>'
    let t = 0
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex: async () => htmlResponse(html),
      log: quietLog(),
      now: () => t,
      minIntervalMs: 1000,
    })
    await checker.check()
    expect(checker.snapshot().status).toBe('error')
    html = OLD_HTML
    t += 5000
    await checker.check()
    expect(checker.snapshot().status).toBe('current')
  })

  it('logs a non-2xx response as a warning, and escalates to error after repeated failures', async () => {
    const log = quietLog()
    let t = 0
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex: async () => htmlResponse('nope', 503),
      log,
      now: () => t,
      minIntervalMs: 1000,
    })
    await checker.check()
    expect(log.warn).toHaveBeenCalledWith(expect.stringContaining('503'))
    expect(log.error).not.toHaveBeenCalled()
    expect(checker.snapshot().status).toBe('current')

    for (let i = 0; i < 2; i++) {
      t += 5000
      await checker.check()
    }
    expect(log.error).toHaveBeenCalledTimes(1)
    expect(checker.snapshot().status).toBe('error')
  })

  it('logs a network failure as a warning rather than swallowing it', async () => {
    const log = quietLog()
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex: async () => { throw new TypeError('Failed to fetch') },
      log,
    })
    await checker.check()
    expect(log.warn).toHaveBeenCalledWith(expect.stringContaining('Failed to fetch'))
  })

  it('is loud, not silent, when the running bundle itself has no fingerprint', async () => {
    const log = quietLog()
    const fetchIndex = vi.fn(async () => htmlResponse(OLD_HTML))
    const checker = createBuildChecker({ running: null, fetchIndex, log })
    expect(checker.snapshot().status).toBe('error')
    expect(log.error).toHaveBeenCalledWith(expect.stringContaining('[build-check]'))
    await checker.check()
    expect(fetchIndex).not.toHaveBeenCalled()
  })

  it('throttles unforced checks to one per interval, and dedupes concurrent ones', async () => {
    let t = 0
    const fetchIndex = vi.fn(async () => htmlResponse(OLD_HTML))
    const checker = createBuildChecker({
      running: assetFingerprint(OLD_HTML),
      fetchIndex,
      log: quietLog(),
      now: () => t,
      minIntervalMs: 60_000,
    })
    await Promise.all([checker.check(), checker.check()])
    expect(fetchIndex).toHaveBeenCalledTimes(1)
    t += 30_000
    await checker.check()
    expect(fetchIndex).toHaveBeenCalledTimes(1)
    t += 31_000
    await checker.check()
    expect(fetchIndex).toHaveBeenCalledTimes(2)
  })
})
