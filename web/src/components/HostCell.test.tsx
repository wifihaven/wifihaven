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

  // #826: standardize bare-IP-row presentation across surfaces. Every surface
  // that renders a HostId in a ranked/log list previously hand-rolled its own
  // italic/muted/tag markup, drifting on the muted color (text-gray-400 vs
  // text-gray-500) and the title tooltip. HostCell is now the single source of
  // truth: monospace value, full-value title for truncated cells, and one
  // canonical unresolved treatment (italic + text-gray-400 + type tag).
  describe('#826: standardized presentation', () => {
    it('renders the value in monospace for both fqdn and IP rows', () => {
      const { container: fqdn } = render(<HostCell host={{ type: 'fqdn', value: 'youtube.com' }} />)
      const { container: ip } = render(<HostCell host={{ type: 'ipv4', value: '10.0.0.5' }} />)
      expect((fqdn.firstChild as HTMLElement).className).toContain('font-mono')
      expect((ip.firstChild as HTMLElement).className).toContain('font-mono')
    })

    it('gives bare-IP rows the standardized unresolved treatment (italic + muted gray-400)', () => {
      const { container } = render(<HostCell host={{ type: 'ipv4', value: '10.0.0.5' }} />)
      const outer = container.firstChild as HTMLElement
      expect(outer.className).toContain('italic')
      expect(outer.className).toContain('text-gray-400')
      // The drifted surfaces used text-gray-500; the standardized treatment must not.
      expect(outer.className).not.toContain('text-gray-500')
    })

    it('does not italicize resolved fqdn rows', () => {
      const { container } = render(<HostCell host={{ type: 'fqdn', value: 'youtube.com' }} />)
      expect((container.firstChild as HTMLElement).className).not.toContain('italic')
    })

    it('sets a title tooltip with the full value so truncated cells stay inspectable', () => {
      const { container: fqdn } = render(<HostCell host={{ type: 'fqdn', value: 'a.very.long.subdomain.example.com' }} />)
      const { container: ip } = render(<HostCell host={{ type: 'ipv6', value: '2001:db8::dead:beef' }} />)
      expect((fqdn.firstChild as HTMLElement).getAttribute('title')).toBe('a.very.long.subdomain.example.com')
      expect((ip.firstChild as HTMLElement).getAttribute('title')).toBe('2001:db8::dead:beef')
    })

    it('merges a caller-supplied className onto the outer span (structural classes from the surface)', () => {
      const { container: fqdn } = render(<HostCell host={{ type: 'fqdn', value: 'youtube.com' }} className="truncate min-w-0" />)
      const { container: ip } = render(<HostCell host={{ type: 'ipv4', value: '10.0.0.5' }} className="truncate min-w-0" />)
      expect((fqdn.firstChild as HTMLElement).className).toContain('truncate')
      expect((fqdn.firstChild as HTMLElement).className).toContain('min-w-0')
      expect((ip.firstChild as HTMLElement).className).toContain('truncate')
      expect((ip.firstChild as HTMLElement).className).toContain('italic')
    })
  })
})
