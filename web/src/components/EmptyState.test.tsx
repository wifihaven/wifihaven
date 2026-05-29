// Tests for EmptyState — the shared placeholder for "nothing here yet" / "no
// data" surfaces across the SPA (#828). Centralizes the scattered ad-hoc
// `<p>No X yet</p>` strings so empty states look and read consistently.

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EmptyState } from './EmptyState'

describe('EmptyState', () => {
  it('renders the title', () => {
    render(<EmptyState title="No devices yet." />)
    expect(screen.getByText('No devices yet.')).toBeInTheDocument()
  })

  it('renders an optional hint/subtitle', () => {
    render(<EmptyState title="No apps yet." hint="Create one to start grouping hosts." />)
    expect(screen.getByText('Create one to start grouping hosts.')).toBeInTheDocument()
  })

  it('does not render a hint when none is given', () => {
    const { container } = render(<EmptyState title="No users yet." />)
    // only the title paragraph, no second line
    expect(container.querySelectorAll('p')).toHaveLength(1)
  })

  it('renders an optional action / CTA', () => {
    render(<EmptyState title="No routers enrolled yet." action={<button>Enroll</button>} />)
    expect(screen.getByRole('button', { name: 'Enroll' })).toBeInTheDocument()
  })

  it('exposes a status role for assistive tech', () => {
    render(<EmptyState title="No blocked queries yet" />)
    expect(screen.getByRole('status')).toHaveTextContent('No blocked queries yet')
  })

  it('forwards a data-testid', () => {
    render(<EmptyState title="No profiles configured yet." data-testid="now-empty" />)
    expect(screen.getByTestId('now-empty')).toBeInTheDocument()
  })

  it('supports an inline variant for sub-sections', () => {
    render(<EmptyState title="No activity in the last 5 minutes" variant="inline" />)
    expect(screen.getByText('No activity in the last 5 minutes')).toBeInTheDocument()
  })
})
