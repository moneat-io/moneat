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

import {describe, expect, it} from 'vitest'
import {screen} from '@testing-library/react'
import {renderWithQueryClient} from '@/test/utils'
import type {DashboardWidget} from '@/lib/api'
import {WidgetRenderer} from '../WidgetRenderer'

function overviewWidget(widgetType: string): DashboardWidget {
  return {
    id: -1,
    dashboard_id: 0,
    title: widgetType,
    widget_type: widgetType,
    grid_x: 0,
    grid_y: 0,
    grid_w: 8,
    grid_h: 10,
    query_configs: [],
    display_config: {},
    sort_order: 0,
  }
}

describe('WidgetRenderer overview dispatch', () => {
  it('renders a native overview widget without running a query', () => {
    renderWithQueryClient(
      <WidgetRenderer
        widget={overviewWidget('service_health')}
        dashboardId={0}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={false}
      />,
    )
    expect(screen.getByTestId('widget-service_health')).toBeInTheDocument()
    expect(screen.getByText('checkout-api')).toBeInTheDocument()
  })

  it('renders the KPI overview widget using display_config', () => {
    const widget = {...overviewWidget('kpi'), display_config: {kpiId: 'uptime'}}
    renderWithQueryClient(
      <WidgetRenderer
        widget={widget}
        dashboardId={0}
        timeRange={{from: 'now-24h', to: 'now'}}
        autoRefresh={false}
      />,
    )
    expect(screen.getByTestId('widget-kpi')).toBeInTheDocument()
    expect(screen.getByText('Uptime · 24h')).toBeInTheDocument()
  })
})
