// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {fireEvent, render, screen, within} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'

import {
  LevelChips,
  LevelSelect,
  LogVolumeBars,
} from '@/components/logs/LogManagementControls'

describe('LogManagementControls', () => {
  it('collapses empty and full level selections to all levels', () => {
    const {rerender} = render(<LevelChips levels={[]} />)

    expect(screen.getByText('All levels')).toBeInTheDocument()

    rerender(<LevelChips levels={['trace', 'debug', 'info', 'warn', 'error', 'fatal']} />)
    expect(screen.getByText('All levels')).toBeInTheDocument()
  })

  it('renders unique selected level chips with their canonical labels', () => {
    render(<LevelChips levels={[' error ', 'error', 'warn']} />)

    expect(screen.getByText('error')).toBeInTheDocument()
    expect(screen.getByText('warn')).toBeInTheDocument()
    expect(screen.queryByText('All levels')).toBeNull()
  })

  it('toggles editable level filters in known-level order', () => {
    const onChange = vi.fn()
    render(<LevelSelect levels={['error']} onChange={onChange} />)

    expect(screen.getByRole('button', {name: 'error'})).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('1 selected')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'warn'}))
    expect(onChange).toHaveBeenLastCalledWith(['warn', 'error'])

    fireEvent.click(screen.getByRole('button', {name: 'error'}))
    expect(onChange).toHaveBeenLastCalledWith([])
  })

  it('shows loading and empty states for volume previews', () => {
    const {rerender} = render(<LogVolumeBars buckets={[]} isFetching />)

    expect(screen.getByText(/Estimating volume/)).toBeInTheDocument()

    rerender(<LogVolumeBars buckets={[]} emptyLabel="Nothing matched." />)
    expect(screen.getByText('Nothing matched.')).toBeInTheDocument()
  })

  it('renders the latest volume buckets with proportional bars', () => {
    render(
      <LogVolumeBars
        limit={2}
        buckets={[
          {timestamp: '2026-06-01T00:00:00.000Z', count: 1, groups: {}},
          {timestamp: '2026-06-01T00:05:00.000Z', count: 5, groups: {}},
          {timestamp: '2026-06-01T00:10:00.000Z', count: 10, groups: {}},
        ]}
      />
    )

    expect(screen.queryByText('2026-06-01T00:00:00.000Z')).toBeNull()
    const fiveBucket = screen.getByText('2026-06-01T00:05:00.000Z').parentElement
    const tenBucket = screen.getByText('2026-06-01T00:10:00.000Z').parentElement
    expect(fiveBucket).not.toBeNull()
    expect(tenBucket).not.toBeNull()
    expect(within(fiveBucket as HTMLElement).getByText('5')).toBeInTheDocument()
    expect(within(tenBucket as HTMLElement).getByText('10')).toBeInTheDocument()

    const fiveBar = fiveBucket?.querySelector('.bg-primary\\/70') as HTMLElement | null
    const tenBar = tenBucket?.querySelector('.bg-primary\\/70') as HTMLElement | null
    expect(fiveBar).not.toBeNull()
    expect(tenBar).not.toBeNull()
    expect(fiveBar).toHaveStyle({width: '50%'})
    expect(tenBar).toHaveStyle({width: '100%'})
  })
})
