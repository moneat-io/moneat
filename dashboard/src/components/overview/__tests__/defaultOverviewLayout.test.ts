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
import {OVERVIEW_WIDGET_TYPES} from '../../dashboards/extendedWidgetTypes'
import {buildDefaultOverviewDashboard, buildDefaultOverviewWidgets} from '../defaultOverviewLayout'

describe('default overview layout', () => {
  const widgets = buildDefaultOverviewWidgets()

  it('contains 14 widgets, all of overview types', () => {
    expect(widgets).toHaveLength(14)
    for (const w of widgets) {
      expect(OVERVIEW_WIDGET_TYPES.has(w.widget_type)).toBe(true)
    }
  })

  it('has six KPI widgets with distinct kpiIds', () => {
    const kpis = widgets.filter((w) => w.widget_type === 'kpi')
    expect(kpis).toHaveLength(6)
    const ids = kpis.map((w) => w.display_config.kpiId)
    expect(new Set(ids).size).toBe(6)
    expect(ids).not.toContain(undefined)
  })

  it('uses unique ids and stays within the 12-column grid', () => {
    expect(new Set(widgets.map((w) => w.id)).size).toBe(widgets.length)
    for (const w of widgets) {
      expect(w.grid_x + w.grid_w).toBeLessThanOrEqual(12)
      expect(w.grid_x).toBeGreaterThanOrEqual(0)
    }
  })

  it('builds a default dashboard wrapper', () => {
    const dash = buildDefaultOverviewDashboard()
    expect(dash.title).toBe('Overview')
    expect(dash.is_default).toBe(true)
    expect(dash.widgets).toHaveLength(14)
  })
})
