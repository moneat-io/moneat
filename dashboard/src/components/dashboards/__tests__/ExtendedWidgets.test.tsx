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

import {afterEach, describe, expect, it} from 'vitest'
import {cleanup, render, screen} from '@testing-library/react'
import type {DashboardWidget} from '@/lib/api'
import {ExtendedWidgetRenderer} from '../ExtendedWidgets'
import {extendedWidgetTestId, isQueryDrivenExtendedWidget, isExtendedWidgetType} from '../extendedWidgetTypes'

const extendedWidgetTypes = [
  'stream',
  'timeline',
  'geo_map',
  'host_map',
  'topology_map',
  'sankey',
  'treemap',
  'scatter',
  'status',
  'change',
  'custom',
  'flame_graph',
  'cost_summary',
  'iframe',
]

const sampleRows = [
  {
    timestamp: '2026-05-26 12:00:00.000',
    service: 'checkout',
    host: 'web-1',
    message: 'checkout latency',
    status: 'ok',
    source: 'checkout',
    target: 'postgres',
    resource: 'GET /checkout',
    function: 'CheckoutController.render',
    latitude: 40.71,
    longitude: -74.01,
    value: 42,
    latency_ms: 18,
    cost: 12.5,
    depth: 1,
  },
  {
    timestamp: '2026-05-26 12:05:00.000',
    service: 'payments',
    host: 'web-2',
    message: 'payment queue',
    status: 'warning',
    source: 'payments',
    target: 'redis',
    resource: 'POST /pay',
    function: 'PaymentWorker.process',
    latitude: 37.77,
    longitude: -122.42,
    value: 68,
    latency_ms: 31,
    cost: 21.75,
    depth: 2,
  },
]

afterEach(() => {
  cleanup()
})

function renderWidget(type: string, options: {
  data?: Record<string, unknown>[]
  displayConfig?: Record<string, string>
  title?: string | null
} = {}) {
  const displayConfig = {
    unit: 'none',
    iframe_url: type === 'iframe' ? 'https://status.example.com' : '',
    ...options.displayConfig,
  }
  return render(
    <ExtendedWidgetRenderer
      widget={makeWidget(type, displayConfig, options.title)}
      widgetType={type}
      data={options.data ?? sampleRows}
      displayConfig={displayConfig}
    />
  )
}

function makeWidget(
  type: string,
  displayConfig: Record<string, string>,
  title: string | null = `Widget ${type}`
): DashboardWidget {
  return {
    id: 1,
    dashboard_id: 1,
    title,
    widget_type: type,
    grid_x: 0,
    grid_y: 0,
    grid_w: 6,
    grid_h: 4,
    query_configs: [],
    display_config: displayConfig,
    sort_order: 0,
  }
}

describe('extended widget rendering', () => {
  it('recognizes every extended widget type', () => {
    expect(extendedWidgetTypes.every(isExtendedWidgetType)).toBe(true)
    expect(isQueryDrivenExtendedWidget('iframe')).toBe(false)
    expect(isQueryDrivenExtendedWidget('geo_map')).toBe(true)
  })

  it('renders every extended widget type with data', () => {
    for (const type of extendedWidgetTypes) {
      const {unmount} = renderWidget(type)

      expect(screen.getByTestId(extendedWidgetTestId(type))).toBeInTheDocument()
      expect(screen.queryByText(/Unknown widget type/)).not.toBeInTheDocument()

      unmount()
    }
  })

  it('renders empty states for data-driven extended widgets', () => {
    for (const type of extendedWidgetTypes.filter((widgetType) => widgetType !== 'iframe')) {
      const {unmount} = renderWidget(type, {data: [], title: null, displayConfig: {iframe_url: ''}})

      expect(screen.getByTestId(extendedWidgetTestId(type))).toBeInTheDocument()

      unmount()
    }
  })

  it('renders iframe widgets as embedded frames', () => {
    renderWidget('iframe')

    expect(screen.getByTestId('widget-iframe')).toBeInTheDocument()
    expect(screen.getByTitle('Widget iframe')).toHaveAttribute('src', 'https://status.example.com')
  })

  it('extracts iframe URLs from markdown content and rejects unsafe iframe URLs', () => {
    const markdown = renderWidget('iframe', {
      displayConfig: {iframe_url: '', content: '[status](https://status.example.com/embed)'},
    })
    expect(screen.getByTitle('Widget iframe')).toHaveAttribute('src', 'https://status.example.com/embed')
    markdown.unmount()

    renderWidget('iframe', {displayConfig: {iframe_url: 'javascript:alert(1)'}})
    expect(screen.getByText('No data')).toBeInTheDocument()
  })

  it('renders custom widgets as data tables when no content is configured', () => {
    renderWidget('custom', {displayConfig: {content: ''}, title: null})

    expect(screen.getByText('checkout latency')).toBeInTheDocument()
    expect(screen.getByText('payment queue')).toBeInTheDocument()
  })

  it('covers fallback layout branches for map, status, change, and unknown widgets', () => {
    const locationlessRows = sampleRows.map((row) => ({
      timestamp: row.timestamp,
      service: row.service,
      host: row.host,
      message: row.message,
      status: 'critical',
      source: row.source,
      target: row.target,
      resource: row.resource,
      function: row.function,
      value: row.value === 42 ? 90 : 10,
      latency_ms: row.latency_ms,
      cost: row.cost,
      depth: row.depth,
    }))
    const geoMap = renderWidget('geo_map', {data: locationlessRows})
    expect(screen.getByTestId('widget-geo_map')).toBeInTheDocument()
    geoMap.unmount()

    const status = renderWidget('status', {data: locationlessRows})
    expect(screen.getAllByText('critical')).toHaveLength(2)
    status.unmount()

    const change = renderWidget('change', {data: locationlessRows})
    expect(screen.getByText('-80')).toBeInTheDocument()
    change.unmount()

    const zeroChange = renderWidget('change', {
      data: [{value: 0}, {timestamp: 123, value: 5}, {timestamp: 'not-a-date', value: 7}],
      title: null,
    })
    expect(screen.getByText('+5')).toBeInTheDocument()
    zeroChange.unmount()

    const scatter = renderWidget('scatter', {data: [{name: 'alpha'}, {name: 'beta'}]})
    expect(screen.getByTestId('widget-scatter')).toBeInTheDocument()
    scatter.unmount()

    const neutralStatus = renderWidget('status', {
      data: [{host: 'idle', value: 0}, {host: 'upstream', status: 'up', value: 1}],
    })
    expect(screen.getAllByText('idle')).toHaveLength(2)
    expect(screen.getByText('upstream')).toBeInTheDocument()
    neutralStatus.unmount()

    const unsafeIframe = renderWidget('iframe', {displayConfig: {iframe_url: 'not-a-url'}})
    expect(screen.getByText('No data')).toBeInTheDocument()
    unsafeIframe.unmount()

    const emptyIframe = renderWidget('iframe', {displayConfig: {iframe_url: '', content: ''}})
    expect(screen.getByText('No data')).toBeInTheDocument()
    emptyIframe.unmount()

    const unknown = render(
      <ExtendedWidgetRenderer
        widget={makeWidget('unknown', {})}
        widgetType="unknown"
        data={locationlessRows}
        displayConfig={{}}
      />
    )
    expect(unknown.container.firstChild).toBeNull()
  })
})
