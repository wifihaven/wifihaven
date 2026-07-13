import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Skeleton } from './Skeleton'

// #2166 — the shared loading skeleton. It must be an accessible, empty
// placeholder — never render a real-looking value (a "0" or "0m") itself.
describe('Skeleton (#2166)', () => {
  it('renders an accessible, empty loading placeholder', () => {
    render(<Skeleton testId="sk" className="h-4 w-16" label="Loading usage…" />)
    const el = screen.getByTestId('sk')
    expect(el).toHaveAttribute('role', 'status')
    expect(el).toHaveAttribute('aria-busy', 'true')
    expect(el).toHaveAttribute('aria-label', 'Loading usage…')
    // It carries the passed sizing + the pulse animation, and no text content
    // that could be mistaken for a real value.
    expect(el.className).toContain('animate-pulse')
    expect(el.className).toContain('h-4')
    expect(el.textContent).toBe('')
  })
})
