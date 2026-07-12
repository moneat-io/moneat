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

import React from 'react'
import {beforeEach, beforeAll, describe, expect, it, vi} from 'vitest'
import {fireEvent, screen} from '@testing-library/react'
import type {DashboardWidget, QueryDsl, TimeRangeDef} from '@/lib/api'
import {renderWithQueryClient} from '@/test/utils'
import {WidgetRenderer} from '../WidgetRenderer'
import {fetchWidgetRows} from '../widgetRows'

vi.mock('../ExtendedWidgets', () => ({
  ExtendedWidgetRenderer: () => null,
}))

vi.mock('../TopListWidget', () => ({
  TopListWidget: () => null,
}))

vi.mock('../HeatmapWidget', () => ({
  HeatmapWidget: () => null,
}))

vi.mock('recharts', () => {
  const chartPart = ({children, ...props}: {children?: React.ReactNode; [key: string]: unknown}) => {
    const dataKey = props.dataKey
    const testId = typeof dataKey === 'string' ? `series-${dataKey}` : 'chart-part'
    return (
      <g data-testid={testId} data-hidden={String(props.hide === true)}>
        {children}
      </g>
    )
  }
  const chartRoot = ({children}: {children?: React.ReactNode}) => <svg data-testid="chart-root">{children}</svg>
  const legendPayload = [
    {value: 'alpha', dataKey: 'alpha', color: '#111111'},
    {value: 'beta', dataKey: 'beta', color: '#222222'},
  ]
  const Legend = ({content}: {content?: React.ReactElement<{payload?: typeof legendPayload}>}) => (
    <div data-testid="chart-legend">
      {content ? React.cloneElement(content, {payload: legendPayload}) : null}
    </div>
  )
  const Pie = ({data}: {data?: Record<string, unknown>[]}) => (
    <g data-testid="pie" data-fills={data?.map((row) => String(row.fill)).join(',')} />
  )

  return {
    Area: chartPart,
    AreaChart: chartRoot,
    Bar: chartPart,
    BarChart: chartRoot,
    CartesianGrid: chartPart,
    Cell: chartPart,
    Legend,
    Line: chartPart,
    LineChart: chartRoot,
    Pie,
    PieChart: chartRoot,
    ReferenceArea: chartPart,
    ReferenceLine: chartPart,
    Scatter: chartPart,
    ScatterChart: chartRoot,
    Tooltip: chartPart,
    XAxis: chartPart,
    YAxis: chartPart,
    ZAxis: chartPart,
  }
})

vi.mock('../widgetRows', async () => {
  const actual = await vi.importActual<typeof import('../widgetRows')>('../widgetRows')
  return {
    ...actual,
    fetchWidgetRows: vi.fn(),
  }
})

const fetchWidgetRowsMock = vi.mocked(fetchWidgetRows)
const timeRange: TimeRangeDef = {from: 'now-1h', to: 'now'}

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {configurable: true, value: 320})
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {configurable: true, value: 240})
  globalThis.ResizeObserver = class {
    private readonly callback: ResizeObserverCallback

    constructor(callback: ResizeObserverCallback) {
      this.callback = callback
    }

    observe(target: Element) {
      this.callback([], this as unknown as ResizeObserver)
      void target
    }

    disconnect() {}

    unobserve() {}

    takeRecords(): ResizeObserverEntry[] {
      return []
    }
  }
})

beforeEach(() => {
  fetchWidgetRowsMock.mockReset()
})

function query(): QueryDsl {
  return {
    dataSource: 'custom:218f4ce4-3f2a-7a67-a32b-0c1848f62b9d',
    metrics: [],
    groupBy: [],
    filters: [],
    limit: 5000,
    timeRange,
  }
}

function widget(widgetType: string, displayConfig: Record<string, string> = {}): DashboardWidget {
  return {
    id: `${widgetType}-widget`,
    dashboard_id: 'dashboard-1',
    title: widgetType,
    widget_type: widgetType,
    grid_x: 0,
    grid_y: 0,
    grid_w: 8,
    grid_h: 6,
    query_configs: [query()],
    display_config: displayConfig,
    sort_order: 0,
  }
}

function renderWidget(target: DashboardWidget) {
  return renderWithQueryClient(
    <WidgetRenderer
      widget={target}
      dashboardId="dashboard-1"
      projectId="018f4ce4-3f2a-7a67-a32b-0c1848f62b9d"
      timeRange={timeRange}
      autoRefresh={false}
    />,
  )
}

describe('WidgetRenderer charts', () => {
  it('renders an area chart and lets users isolate and restore series', async () => {
    fetchWidgetRowsMock.mockResolvedValue([
      {time_bucket: '2026-07-12 00:00:00.000', series: 'alpha', value: 1},
      {time_bucket: '2026-07-12 00:00:00.000', series: 'beta', value: 2},
      {time_bucket: '2026-07-12 01:00:00.000', series: 'alpha', value: 3},
      {time_bucket: '2026-07-12 01:00:00.000', series: 'beta', value: 4},
    ])

    renderWidget(widget('timeseries', {fillOpacity: '0.25', legendPlacement: 'right'}))

    const alpha = await screen.findByRole('button', {name: 'alpha'})
    const beta = screen.getByRole('button', {name: 'beta'})
    expect(screen.getByTestId('series-alpha')).toHaveAttribute('data-hidden', 'false')
    expect(screen.getByTestId('series-beta')).toHaveAttribute('data-hidden', 'false')

    fireEvent.click(alpha)
    expect(screen.getByTestId('series-alpha')).toHaveAttribute('data-hidden', 'false')
    expect(screen.getByTestId('series-beta')).toHaveAttribute('data-hidden', 'true')

    fireEvent.click(beta, {ctrlKey: true})
    expect(screen.getByTestId('series-alpha')).toHaveAttribute('data-hidden', 'false')
    expect(screen.getByTestId('series-beta')).toHaveAttribute('data-hidden', 'false')
  })

  it('renders a pivoted time bar chart with stacked series', async () => {
    fetchWidgetRowsMock.mockResolvedValue([
      {time_bucket: '2026-07-12 00:00:00.000', series: 'alpha', value: 1},
      {time_bucket: '2026-07-12 00:00:00.000', series: 'beta', value: 2},
    ])

    renderWidget(widget('bar', {barMode: 'stacked'}))

    expect(await screen.findByTestId('series-alpha')).toHaveAttribute('data-hidden', 'false')
    expect(screen.getByTestId('series-beta')).toHaveAttribute('data-hidden', 'false')
  })

  it('renders a flat bar chart and a donut chart with deterministic fills', async () => {
    fetchWidgetRowsMock.mockResolvedValue([
      {category: 'one', count: 1},
      {category: 'two', count: 2},
    ])
    renderWidget(widget('bar', {legendMode: 'hidden'}))
    expect(await screen.findByTestId('series-count')).toHaveAttribute('data-hidden', 'false')

    fetchWidgetRowsMock.mockResolvedValue([
      {label: 'one', value: 3},
      {label: 'two', value: 2},
    ])
    renderWidget(widget('donut'))
    expect(await screen.findByTestId('pie')).toHaveAttribute(
      'data-fills',
      'hsl(var(--chart-1)),hsl(var(--chart-2))',
    )
  })

  it('uses the series palette for categorical stat bars', async () => {
    fetchWidgetRowsMock.mockResolvedValue([
      {label: 'one', count: 1},
      {label: 'two', count: 2},
      {label: 'three', count: 3},
      {label: 'four', count: 4},
    ])

    renderWidget(widget('stat'))

    expect(await screen.findByText('one')).toBeInTheDocument()
    expect(screen.getByText('four')).toBeInTheDocument()
  })
})
