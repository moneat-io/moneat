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

import {describe, it, expect, vi, beforeEach} from 'vitest'
import {renderHook, act} from '@testing-library/react'
import {useWidgetClipboard} from '../useWidgetClipboard'
import type {DashboardWidget} from '@/lib/api'

const makeWidget = (overrides: Partial<DashboardWidget> = {}): DashboardWidget => ({
  id: 1,
  dashboard_id: 10,
  title: 'Widget A',
  widget_type: 'timeseries',
  grid_x: 0,
  grid_y: 0,
  grid_w: 6,
  grid_h: 4,
  query_config: {
    dataSource: 'events',
    metrics: [{function: 'count', alias: 'count'}],
    groupBy: [],
    filters: [],
    limit: 100,
    timeRange: {from: 'now-24h', to: 'now'},
  },
  display_config: {},
  sort_order: 0,
  ...overrides,
})

// Mock clipboard API
const mockClipboard = {
  writeText: vi.fn().mockResolvedValue(undefined),
  readText: vi.fn().mockResolvedValue(''),
}

Object.defineProperty(navigator, 'clipboard', {
  value: mockClipboard,
  writable: true,
})

describe('useWidgetClipboard', () => {
  const onPasteWidget = vi.fn()
  const onDatasourceMapping = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    mockClipboard.readText.mockResolvedValue('')
  })

  const defaultOpts = {
    isEditing: true,
    widgets: [makeWidget()],
    selectedWidgetId: 1,
    onPasteWidget,
    onDatasourceMapping,
  }

  it('copies selected widget to clipboard on Ctrl+C', () => {
    renderHook(() => useWidgetClipboard(defaultOpts))

    act(() => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'c', ctrlKey: true, bubbles: true})
      )
    })

    expect(mockClipboard.writeText).toHaveBeenCalledWith(
      expect.stringContaining('"_moneat_widget":true')
    )
  })

  it('does not copy when not editing', () => {
    renderHook(() => useWidgetClipboard({...defaultOpts, isEditing: false}))

    act(() => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'c', ctrlKey: true, bubbles: true})
      )
    })

    expect(mockClipboard.writeText).not.toHaveBeenCalled()
  })

  it('does not copy when no widget selected', () => {
    renderHook(() => useWidgetClipboard({...defaultOpts, selectedWidgetId: null}))

    act(() => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'c', ctrlKey: true, bubbles: true})
      )
    })

    expect(mockClipboard.writeText).not.toHaveBeenCalled()
  })

  it('pastes duplicated widget on Ctrl+V after copying', async () => {
    mockClipboard.readText.mockRejectedValue(new Error('denied'))

    renderHook(() => useWidgetClipboard(defaultOpts))

    // Copy first
    act(() => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'c', ctrlKey: true, bubbles: true})
      )
    })

    // Paste
    await act(async () => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'v', ctrlKey: true, bubbles: true})
      )
      // Allow microtasks to settle
      await new Promise((r) => setTimeout(r, 10))
    })

    expect(onPasteWidget).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Widget A (copy)',
        widget_type: 'timeseries',
        grid_y: 4, // next y position
      })
    )
  })

  it('detects and converts Grafana panel JSON from clipboard', async () => {
    const grafanaPanel = JSON.stringify({
      type: 'timeseries',
      title: 'Grafana Panel',
      gridPos: {x: 0, y: 0, w: 12, h: 8},
      targets: [{expr: 'up{job="api"}'}],
      datasource: {type: 'prometheus', uid: 'abc'},
    })
    mockClipboard.readText.mockResolvedValue(grafanaPanel)

    renderHook(() => useWidgetClipboard(defaultOpts))

    await act(async () => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'v', ctrlKey: true, bubbles: true})
      )
      await new Promise((r) => setTimeout(r, 10))
    })

    // Prometheus maps to system_metrics (known source)
    expect(onPasteWidget).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Grafana Panel',
        widget_type: 'timeseries',
        grid_w: 6, // 12/2
        grid_h: 8,
      })
    )
  })

  it('triggers datasource mapping for unknown Grafana datasources', async () => {
    const grafanaPanel = JSON.stringify({
      type: 'stat',
      title: 'Custom DS Panel',
      gridPos: {x: 0, y: 0, w: 8, h: 4},
      targets: [{expr: 'custom_metric', datasource: {type: 'mysql', uid: 'xyz'}}],
    })
    mockClipboard.readText.mockResolvedValue(grafanaPanel)

    renderHook(() => useWidgetClipboard(defaultOpts))

    await act(async () => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'v', ctrlKey: true, bubbles: true})
      )
      await new Promise((r) => setTimeout(r, 10))
    })

    expect(onDatasourceMapping).toHaveBeenCalledWith(
      expect.objectContaining({
        widget_type: 'stat',
        query_config: expect.objectContaining({
          dataSource: '__unmapped:mysql',
        }),
      }),
      ['mysql']
    )
  })

  it('pastes Moneat widget JSON from clipboard', async () => {
    const moneatWidget = JSON.stringify({
      _moneat_widget: true,
      widget_type: 'bar',
      title: 'From Other Tab',
      grid_x: 2,
      grid_y: 0,
      grid_w: 4,
      grid_h: 3,
      query_config: {dataSource: 'logs', metrics: [], groupBy: [], filters: [], limit: 50, timeRange: {from: 'now-1h', to: 'now'}},
      display_config: {},
    })
    mockClipboard.readText.mockResolvedValue(moneatWidget)

    renderHook(() => useWidgetClipboard(defaultOpts))

    await act(async () => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'v', ctrlKey: true, bubbles: true})
      )
      await new Promise((r) => setTimeout(r, 10))
    })

    expect(onPasteWidget).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'From Other Tab (copy)',
        widget_type: 'bar',
        grid_w: 4,
        grid_h: 3,
      })
    )
  })

  it('supports Cmd+C/V on macOS', () => {
    renderHook(() => useWidgetClipboard(defaultOpts))

    act(() => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'c', metaKey: true, bubbles: true})
      )
    })

    expect(mockClipboard.writeText).toHaveBeenCalled()
  })

  it('does not trigger paste when not editing', async () => {
    renderHook(() => useWidgetClipboard({...defaultOpts, isEditing: false}))

    await act(async () => {
      document.dispatchEvent(
        new KeyboardEvent('keydown', {key: 'v', ctrlKey: true, bubbles: true})
      )
      await new Promise((r) => setTimeout(r, 10))
    })

    expect(onPasteWidget).not.toHaveBeenCalled()
    expect(onDatasourceMapping).not.toHaveBeenCalled()
  })
})
