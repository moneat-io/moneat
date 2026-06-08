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

import type {ReactNode} from 'react'
import {fireEvent, render, screen} from '@testing-library/react'
import {afterEach, beforeAll, beforeEach, describe, expect, it, vi} from 'vitest'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {OverviewDashboard} from '../OverviewDashboard'
import {overviewTestData} from './overviewTestData'

const LAYOUT_STORAGE_KEY = 'moneat.overview.layout.v3'

vi.mock('@/lib/api', () => ({
  api: {
    getOverview: vi.fn(),
  },
}))

vi.mock('react-grid-layout/legacy', () => ({
  Responsive: ({children, className}: {children: ReactNode; className?: string}) => (
    <div data-testid="responsive-grid-layout" className={className}>
      {children}
    </div>
  ),
}))

vi.mock('@tanstack/react-router', () => ({
  Link: ({children, to}: {children: ReactNode; to?: string}) => <a href={to}>{children}</a>,
}))

beforeAll(() => {
  globalThis.ResizeObserver = class {
    disconnect() {}
    observe() {}
    unobserve() {}
  }
})

function renderDashboard() {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>
      <OverviewDashboard />
    </QueryClientProvider>,
  )
}

describe('OverviewDashboard', () => {
  beforeEach(() => {
    globalThis.localStorage.clear()
    vi.mocked(api.getOverview).mockResolvedValue(overviewTestData)
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders the header and all default widgets', async () => {
    renderDashboard()

    expect(screen.getByText('Overview')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: /Customize overview/})).toBeInTheDocument()
    expect(await screen.findByText('Action needed')).toBeInTheDocument()
    expect(screen.getByText('Service health')).toBeInTheDocument()
  })

  it('enters edit mode and shows edit controls', () => {
    renderDashboard()

    fireEvent.click(screen.getByRole('button', {name: /Customize overview/}))
    expect(screen.getByText('Done')).toBeInTheDocument()
    expect(screen.getByText('Reset')).toBeInTheDocument()
    expect(screen.getByText('Add widget')).toBeInTheDocument()
  })

  it('opens the add widget dialog', () => {
    renderDashboard()

    fireEvent.click(screen.getByRole('button', {name: /Customize overview/}))
    fireEvent.click(screen.getByRole('button', {name: /Add widget/}))
    expect(screen.getByText('Choose a widget to add to your overview.')).toBeInTheDocument()
  })

  it('deletes a widget and resets to defaults', async () => {
    renderDashboard()

    fireEvent.click(screen.getByRole('button', {name: /Customize overview/}))
    expect(await screen.findByText('Action needed')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Reset'))
    expect(screen.getByText('Action needed')).toBeInTheDocument()
  })

  it('persists layout to localStorage', () => {
    renderDashboard()

    fireEvent.click(screen.getByRole('button', {name: /Customize overview/}))
    fireEvent.click(screen.getByText('Reset'))

    const stored = globalThis.localStorage.getItem(LAYOUT_STORAGE_KEY)
    expect(stored).not.toBeNull()
    const parsed = JSON.parse(stored!)
    expect(parsed).toHaveLength(14)
  })

  it('loads layout from localStorage on mount', async () => {
    const singleWidget = [{
      id: -1,
      dashboard_id: 0,
      title: 'Activity',
      widget_type: 'activity',
      grid_x: 0,
      grid_y: 0,
      grid_w: 12,
      grid_h: 3,
      query_configs: [],
      display_config: {},
      sort_order: 0,
    }]
    globalThis.localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(singleWidget))

    renderDashboard()

    expect(await screen.findByTestId('widget-activity')).toBeInTheDocument()
    expect(screen.queryByText('Service health')).not.toBeInTheDocument()
  })
})
