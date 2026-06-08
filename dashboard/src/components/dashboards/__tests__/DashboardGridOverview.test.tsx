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

import type {ReactElement, ReactNode} from 'react'
import {render, screen} from '@testing-library/react'
import {beforeAll, describe, expect, it, vi} from 'vitest'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {DashboardGrid} from '../DashboardGrid'
import type {DashboardWidget} from '@/lib/api'
import {OverviewDataProvider} from '@/components/overview/OverviewDataProvider'
import {overviewTestData} from '@/components/overview/__tests__/overviewTestData'

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

function kpiWidget(overrides: Partial<DashboardWidget> = {}): DashboardWidget {
  return {
    id: -1,
    dashboard_id: 0,
    title: 'KPI Title Should Be Hidden',
    widget_type: 'kpi',
    grid_x: 0,
    grid_y: 0,
    grid_w: 2,
    grid_h: 3,
    query_configs: [],
    display_config: {kpiId: 'errors'},
    sort_order: 0,
    ...overrides,
  }
}

const baseProps = {
  dashboardId: 0,
  timeRange: {from: 'now-24h', to: 'now'},
  autoRefresh: false,
  onLayoutChange: vi.fn(),
  onWidgetClick: vi.fn(),
  onWidgetDelete: vi.fn(),
}

function renderGrid(ui: ReactElement) {
  const queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}})
  return render(
    <QueryClientProvider client={queryClient}>
      <OverviewDataProvider data={overviewTestData}>
        {ui}
      </OverviewDataProvider>
    </QueryClientProvider>,
  )
}

describe('DashboardGrid overview widgets', () => {
  it('renders overview widgets chromeless (no widget-card header) in view mode', () => {
    renderGrid(<DashboardGrid widgets={[kpiWidget()]} isEditing={false} {...baseProps} />)
    expect(screen.getByTestId('widget-kpi')).toBeInTheDocument()
    expect(screen.getByText('Errors · 24h')).toBeInTheDocument()
    expect(screen.queryByText('KPI Title Should Be Hidden')).not.toBeInTheDocument()
  })

  it('overlays a drag handle in edit mode', () => {
    const {container} = renderGrid(<DashboardGrid widgets={[kpiWidget()]} isEditing {...baseProps} />)
    expect(container.querySelector('.drag-handle')).not.toBeNull()
  })
})
