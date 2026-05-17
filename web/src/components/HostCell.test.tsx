// Tests for HostCell — the component that renders a HostId tagged union in log rows.
// #458: IP-type hosts (ipv4/ipv6) were rendering without a text separator between
// the IP value and the type tag, producing strings like "239.255.255.250ipv4" when
// the cell text was copied or read by accessibility tools.

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { HostCell } from './HostCell'

describe('HostCell', () => {
  it('renders an fqdn host as plain text with no type tag', () => {
    render(<HostCell host={{ type: 'fqdn', value: 'youtube.com' }} />)
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
    expect(screen.queryByText('fqdn')).not.toBeInTheDocument()
  })

  it('renders an ipv4 host with value and a visible "ipv4" tag', () => {
    render(<HostCell host={{ type: 'ipv4', value: '239.255.255.250' }} />)
    expect(screen.getByText('239.255.255.250')).toBeInTheDocument()
    expect(screen.getByText('ipv4')).toBeInTheDocument()
  })

  it('renders an ipv6 host with value and a visible "ipv6" tag', () => {
    render(<HostCell host={{ type: 'ipv6', value: 'fe80::1' }} />)
    expect(screen.getByText('fe80::1')).toBeInTheDocument()
    expect(screen.getByText('ipv6')).toBeInTheDocument()
  })

  // #458: The fix adds a {' '} text node between the IP value and the type tag so
  // the cell's text content reads "239.255.255.250 ipv4" (with a space), not
  // "239.255.255.250ipv4".  Verify the space is present in the rendered text.
  it('#458: ipv4 cell text content has a space between the value and the type tag', () => {
    const { container } = render(<HostCell host={{ type: 'ipv4', value: '239.255.255.250' }} />)
    const outer = container.firstChild as HTMLElement
    expect(outer.textContent).toContain('239.255.255.250 ipv4')
  })

  it('#458: ipv6 cell text content has a space between the value and the type tag', () => {
    const { container } = render(<HostCell host={{ type: 'ipv6', value: '2001:db8::1' }} />)
    const outer = container.firstChild as HTMLElement
    expect(outer.textContent).toContain('2001:db8::1 ipv6')
  })
})
