import { describe, it, expect } from 'vitest'
import indexHtml from '../index.html?raw'

// #2923: a whole-MAC blocked device (checked out, paused, ...) can reach only the
// WifiHaven hosts; every other host, fonts.googleapis.com included, is silently
// dropped. A <link rel="stylesheet"> in the document blocks first render until it
// loads or fails, so the SPA a child needs to check in stayed blank until the TCP
// connect timed out. The page may only block on same-origin stylesheets.
const stylesheetHrefs = [...indexHtml.matchAll(/<link\b[^>]*>/g)]
  .map((m) => m[0])
  .filter((tag) => /\brel=["']stylesheet["']/.test(tag))
  .map((tag) => /\bhref=["']([^"']+)["']/.exec(tag)?.[1] ?? '')

describe('index.html', () => {
  it('parses (liveness anchor for the absence check below)', () => {
    expect(indexHtml).toContain('<div id="root"></div>')
    expect(indexHtml).toContain('<link rel="icon"')
  })

  it('has no render-blocking cross-origin stylesheet', () => {
    expect(stylesheetHrefs.filter((href) => /^(https?:)?\/\//.test(href))).toEqual([])
  })
})
