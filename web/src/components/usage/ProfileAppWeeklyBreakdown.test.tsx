import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WeeklyBody } from './ProfileAppWeeklyBreakdown'
import type { ProfileAppWeeklyUsage } from '@/types/api'

function fixture(): ProfileAppWeeklyUsage {
  return {
    profileId: 1,
    profileName: 'Kids',
    from: '2025-01-01',
    to: '2025-01-07',
    apps: [
      {
        appId: 10,
        appName: 'YouTube',
        appIcon: '📺',
        appIconType: 'emoji',
        engagedMinutes: 140,
      },
      {
        appId: 11,
        appName: 'Social',
        appIcon: '💬',
        appIconType: 'emoji',
        engagedMinutes: 70,
      },
    ],
  }
}

describe('ProfileAppWeeklyBreakdown — #1089', () => {
  it('renders one row per app with the engaged-minutes total', () => {
    render(<WeeklyBody profileId={1} data={fixture()} />)
    expect(screen.getByTestId('profile-app-weekly-1-app-10')).toHaveTextContent('YouTube')
    expect(screen.getByTestId('profile-app-weekly-1-app-10-mins')).toHaveTextContent('2:20')
    expect(screen.getByTestId('profile-app-weekly-1-app-11')).toHaveTextContent('Social')
    expect(screen.getByTestId('profile-app-weekly-1-app-11-mins')).toHaveTextContent('1:10')
  })

  it('renders the date-range header so it is unambiguous which 7-day window is shown', () => {
    render(<WeeklyBody profileId={1} data={fixture()} />)
    expect(screen.getByTestId('profile-app-weekly-1-range'))
      .toHaveTextContent('2025-01-01 – 2025-01-07')
  })

  it('shows an empty-state when the window has no app usage', () => {
    render(
      <WeeklyBody
        profileId={7}
        data={{
          profileId: 7,
          profileName: 'X',
          from: '2025-01-08',
          to: '2025-01-14',
          apps: [],
        }}
      />,
    )
    const empty = screen.getByTestId('profile-app-weekly-7-empty')
    expect(empty).toBeInTheDocument()
    // Date range is part of the empty-state copy so the operator knows what window came back empty.
    expect(empty).toHaveTextContent('2025-01-08')
    expect(empty).toHaveTextContent('2025-01-14')
  })
})
