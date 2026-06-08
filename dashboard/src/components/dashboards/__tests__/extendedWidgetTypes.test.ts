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
import {
  EXTENDED_WIDGET_TYPES,
  OVERVIEW_WIDGET_TYPES,
  isExtendedWidgetType,
  isOverviewWidgetType,
  isQueryDrivenExtendedWidget,
} from '../extendedWidgetTypes'

const OVERVIEW_IDS = [
  'system_status',
  'kpi',
  'service_health',
  'telemetry',
  'triage',
  'infra_summary',
  'uptime_summary',
  'deploys',
  'activity',
]

describe('overview widget types', () => {
  it('are extended, non-query, and flagged as overview', () => {
    for (const id of OVERVIEW_IDS) {
      expect(isExtendedWidgetType(id)).toBe(true)
      expect(OVERVIEW_WIDGET_TYPES.has(id)).toBe(true)
      expect(isOverviewWidgetType(id)).toBe(true)
      expect(isQueryDrivenExtendedWidget(id)).toBe(false)
    }
  })

  it('keeps existing extended types query-driven, except iframe', () => {
    expect(EXTENDED_WIDGET_TYPES.has('host_map')).toBe(true)
    expect(isQueryDrivenExtendedWidget('host_map')).toBe(true)
    expect(isQueryDrivenExtendedWidget('iframe')).toBe(false)
    expect(isOverviewWidgetType('host_map')).toBe(false)
  })
})
