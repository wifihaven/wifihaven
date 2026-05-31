// Tests for AppIcon — the single source of truth for rendering an app's icon.
// #1081: favicon-URL icons were rendering as the raw URL *string* (text) instead
// of an <img> across multiple surfaces (the add-app picker on the profile card,
// the Connection Events table, and the Traffic Usage table). Those table rows
// carry only `appIcon` from the backend — no `appIconType` — so AppIcon must
// infer the type from the string when it is not supplied, while still honouring
// the #1004 https-only safety rule.

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AppIcon } from './AppIcon'

const FAVICON = 'https://icons.duckduckgo.com/ip3/youtube.com.ico'

describe('AppIcon', () => {
  it('renders an explicit url icon as an <img> with the favicon src', () => {
    const { container } = render(<AppIcon icon={FAVICON} iconType="url" />)
    const img = container.querySelector('img')
    expect(img).not.toBeNull()
    expect(img!.getAttribute('src')).toBe(FAVICON)
  })

  it('#1081: infers a favicon image when iconType is omitted (Events / Traffic rows)', () => {
    const { container } = render(<AppIcon icon={FAVICON} />)
    const img = container.querySelector('img')
    expect(img).not.toBeNull()
    expect(img!.getAttribute('src')).toBe(FAVICON)
    // It must NOT have fallen through to printing the raw URL string as text.
    expect(container.textContent).not.toContain(FAVICON)
  })

  it('renders an emoji icon as text, not an image', () => {
    const { container } = render(<AppIcon icon="📺" iconType="emoji" />)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('📺')).toBeInTheDocument()
  })

  it('renders the fallback glyph when no icon is set', () => {
    const { container } = render(<AppIcon icon={null} />)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('◳')).toBeInTheDocument()
  })

  it('respects an explicit emoji type even when the string looks like a URL', () => {
    const { container } = render(<AppIcon icon={FAVICON} iconType="emoji" />)
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain(FAVICON)
  })

  it('#1004: a non-https url falls back to the placeholder, never an <img>', () => {
    const { container } = render(<AppIcon icon="http://evil.example/x.png" iconType="url" />)
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText('◳')).toBeInTheDocument()
  })

  it('#1004: a javascript: scheme string is never rendered as an <img>, even when inferred', () => {
    const { container } = render(<AppIcon icon="javascript:alert(1)" />)
    expect(container.querySelector('img')).toBeNull()
  })
})
