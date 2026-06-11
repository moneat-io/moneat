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
import type {CustomDashboard, DashboardTemplateSummary} from '@/lib/api'
import {
  getDashboardSources,
  getDashboardThumb,
  getTemplateThumb,
  normalizeCategory,
} from '../dashboardThumbHelpers'

function makeTemplate(overrides: Partial<DashboardTemplateSummary> = {}): DashboardTemplateSummary {
  return {
    id: 't',
    title: 'T',
    description: 'desc',
    category: 'other',
    tags: [],
    required_sources: [],
    widget_count: 1,
    variable_count: 0,
    resource_path: 'path',
    ...overrides,
  }
}

function makeDashboard(overrides: Partial<CustomDashboard> = {}): CustomDashboard {
  return {
    id: '00000000-0000-0000-0000-000000000001',
    org_id: 1,
    folder_id: null,
    title: 'Board',
    description: null,
    layout_type: 'grid',
    is_default: false,
    is_favorited: false,
    variables: [],
    created_by: 1,
    created_at: '2026-06-10T00:00:00.000Z',
    updated_at: '2026-06-10T00:00:00.000Z',
    widgets: [],
    ...overrides,
  }
}

describe('normalizeCategory', () => {
  it('accepts known categories case-insensitively', () => {
    expect(normalizeCategory('Kubernetes')).toBe('kubernetes')
    expect(normalizeCategory('DATABASES')).toBe('databases')
  })

  it('returns null for unknown categories', () => {
    expect(normalizeCategory('mystery')).toBeNull()
  })
})

describe('getTemplateThumb', () => {
  it('maps known categories to their shape', () => {
    expect(getTemplateThumb(makeTemplate({category: 'kubernetes'}))).toBe('k8s')
    expect(getTemplateThumb(makeTemplate({category: 'databases'}))).toBe('db')
    expect(getTemplateThumb(makeTemplate({category: 'logs'}))).toBe('logs')
    expect(getTemplateThumb(makeTemplate({category: 'applications'}))).toBe('service')
  })

  it('falls back to tags then host for uncategorized templates', () => {
    expect(getTemplateThumb(makeTemplate({category: 'other', tags: ['Kubernetes']}))).toBe('k8s')
    expect(getTemplateThumb(makeTemplate({category: 'other', tags: ['database']}))).toBe('db')
    expect(getTemplateThumb(makeTemplate({category: 'other', tags: ['logs']}))).toBe('logs')
    expect(getTemplateThumb(makeTemplate({category: 'other', tags: ['rum']}))).toBe('vitals')
    expect(getTemplateThumb(makeTemplate({category: 'other', tags: []}))).toBe('host')
  })
})

describe('getDashboardSources', () => {
  it('returns distinct lowercased sources from widget queries', () => {
    const dashboard = makeDashboard({
      widgets: [
        {
          id: '00000000-0000-0000-0000-000000000011',
          dashboard_id: '00000000-0000-0000-0000-000000000001',
          title: 'w',
          widget_type: 'line',
          grid_x: 0,
          grid_y: 0,
          grid_w: 1,
          grid_h: 1,
          query_configs: [
            {dataSource: 'Metrics', metrics: [], groupBy: [], filters: [], limit: 1, timeRange: {from: '', to: ''}},
            {dataSource: 'metrics', metrics: [], groupBy: [], filters: [], limit: 1, timeRange: {from: '', to: ''}},
            {dataSource: 'Traces', metrics: [], groupBy: [], filters: [], limit: 1, timeRange: {from: '', to: ''}},
          ],
          display_config: {},
          sort_order: 0,
        },
      ],
    })
    expect(getDashboardSources(dashboard)).toEqual(['metrics', 'traces'])
  })

  it('returns an empty list when there are no queries', () => {
    expect(getDashboardSources(makeDashboard())).toEqual([])
  })
})

describe('getDashboardThumb', () => {
  it('derives a shape from name, folder and description', () => {
    expect(getDashboardThumb(makeDashboard({title: 'Kubernetes Cluster'}))).toBe('k8s')
    expect(getDashboardThumb(makeDashboard({title: 'PostgreSQL Primary'}))).toBe('db')
    expect(getDashboardThumb(makeDashboard({title: 'Log Error Triage'}))).toBe('logs')
    expect(getDashboardThumb(makeDashboard({title: 'Frontend Web Vitals'}))).toBe('vitals')
    expect(getDashboardThumb(makeDashboard({title: 'Hosts Fleet'}, ))).toBe('host')
    expect(getDashboardThumb(makeDashboard({title: 'Checkout'}))).toBe('service')
  })

  it('uses the folder name as a hint', () => {
    expect(getDashboardThumb(makeDashboard({title: 'Overview'}), 'Databases')).toBe('db')
  })
})
