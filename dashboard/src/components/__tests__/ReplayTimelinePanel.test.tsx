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

import {describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen} from '@testing-library/react'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {ReplayTimelinePanel} from '@/components/ReplayTimelinePanel'
import type {ReplayTimelineItem} from '@/lib/api'

vi.mock('@/hooks/useTimezone', () => ({useTimezone: () => ({timezone: 'UTC'})}))

vi.mock('@tanstack/react-router', () => ({
  Link: ({children}: {children: React.ReactNode}) => <a>{children}</a>,
}))

const items: ReplayTimelineItem[] = [
  {id: 'n1', type: 'span', timestamp: '2026-06-01T00:00:00.000Z', offsetMs: 0, title: 'Direct → /products', category: 'navigation'},
  {id: 'u1', type: 'span', timestamp: '2026-06-01T00:00:03.000Z', offsetMs: 3_000, title: 'click "Place order"', category: 'ui.click', description: 'rage click ×3', rage: true},
  {id: 'h1', type: 'span', timestamp: '2026-06-01T00:00:06.000Z', offsetMs: 6_000, title: 'POST /api/checkout → 500', category: 'http'},
  {id: 'e1', type: 'error', timestamp: '2026-06-01T00:00:09.000Z', offsetMs: 9_000, title: "TypeError: cannot read 'total' of undefined", category: 'exception'},
  {id: 'lc', type: 'span', timestamp: '2026-06-01T00:00:12.000Z', offsetMs: 12_000, title: 'Logcat', category: 'Logcat', description: 'No auth token available'},
]

function setup() {
  const onSeek = vi.fn()
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ReplayTimelinePanel items={items} currentOffsetMs={0} projectId="svc-1" onSeek={onSeek} />
    </QueryClientProvider>
  )
  return {onSeek}
}

describe('ReplayTimelinePanel (target-state rail)', () => {
  it('renders the All / Errors / Network / User filters and the total count', () => {
    setup()
    for (const label of ['All', 'Errors', 'Network', 'User']) {
      expect(screen.getByRole('button', {name: label})).toBeInTheDocument()
    }
    expect(screen.getByText('5')).toBeInTheDocument()
  })

  it('collapses a title that just echoes its category onto the detail line', () => {
    setup()
    // The detail becomes the primary line...
    expect(screen.getByText('No auth token available')).toBeInTheDocument()
    // ...and "Logcat" shows once (the category label), not also as a redundant title.
    expect(screen.getAllByText('Logcat')).toHaveLength(1)
  })

  it('renders an HTTP status badge and a rage badge from target-state fields', () => {
    setup()
    expect(screen.getByText('500')).toBeInTheDocument()
    expect(screen.getByText('rage')).toBeInTheDocument()
  })

  it('filters to network events when Network is selected', () => {
    setup()
    fireEvent.click(screen.getByRole('button', {name: 'Network'}))
    expect(screen.getByText(/POST \/api\/checkout/)).toBeInTheDocument()
    expect(screen.queryByText(/Direct →/)).not.toBeInTheDocument()
  })

  it('seeks to the event offset when a row is clicked', () => {
    const {onSeek} = setup()
    fireEvent.click(screen.getByText(/click "Place order"/))
    expect(onSeek).toHaveBeenCalledWith(3_000)
  })
})
