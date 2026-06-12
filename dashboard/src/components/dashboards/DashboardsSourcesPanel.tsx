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

import {Database, LayoutDashboard, MoreHorizontal, Plus} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {StatusDot} from '@/components/ui/status-dot'
import type {StatusTone} from '@/components/ui/status-dot'
import type {CustomDashboard, CustomDataSourceResponse} from '@/lib/api'
import {DATA_SOURCE_TYPES} from '@/components/dashboards/DataSourceTypes'
import {getDashboardSources} from './dashboardThumbHelpers'

// Data sources tab of the Dashboards hub. Folds the standalone data-sources
// route in next to the dashboards it powers: a dense table of every connected
// source, led by Moneat's built-in native telemetry. Add / edit / delete still
// live on the full /dashboards/datasources form, which this links to.

type DashboardsSourcesPanelProps = Readonly<{
  dashboards: readonly CustomDashboard[]
  dataSources: readonly CustomDataSourceResponse[]
  isLoading: boolean
  searchQuery: string
  onAdd: () => void
  onManage: (id: string) => void
}>

type SourceRowModel = Readonly<{
  key: string
  name: string
  subtitle: string
  type: string
  statusLabel: string
  statusTone: Extract<StatusTone, 'success' | 'warning' | 'neutral'>
  icon: React.ReactNode
  usedByDashboardCount: number
  updatedLabel: string
  onManage?: () => void
}>

const STATUS_TEXT: Record<SourceRowModel['statusTone'], string> = {
  success: 'text-success-fg',
  warning: 'text-warning-fg',
  neutral: 'text-muted-foreground',
}

export function DashboardsSourcesPanel({
  dashboards,
  dataSources,
  isLoading,
  searchQuery,
  onAdd,
  onManage,
}: DashboardsSourcesPanelProps) {
  const query = searchQuery.trim().toLowerCase()

  const nativeRow: SourceRowModel = {
    key: 'native',
    name: 'Moneat telemetry',
    subtitle: 'native · traces · metrics · logs',
    type: 'Native',
    statusLabel: 'Connected',
    statusTone: 'success',
    icon: <LayoutDashboard className="h-4 w-4 text-primary" />,
    usedByDashboardCount: nativeDashboardUsageCount(dashboards),
    updatedLabel: 'Built in',
  }

  const customRows: SourceRowModel[] = dataSources.map((source) => ({
    key: `ds-${source.id}`,
    name: source.name,
    subtitle: buildSubtitle(source),
    type: typeLabel(source.source_type),
    statusLabel: source.enabled ? 'Connected' : 'Disabled',
    statusTone: source.enabled ? 'success' : 'neutral',
    icon: DATA_SOURCE_TYPES.find((type) => type.value === source.source_type)?.logo ?? (
      <Database className="h-4 w-4" />
    ),
    usedByDashboardCount: source.used_by_dashboard_count ?? 0,
    updatedLabel: formatUpdated(source.updated_at),
    onManage: () => onManage(source.id),
  }))

  const rows = [nativeRow, ...customRows].filter((row) => {
    if (!query) return true
    return `${row.name} ${row.subtitle} ${row.type}`.toLowerCase().includes(query)
  })

  const connectedCount = rows.filter((row) => row.statusTone === 'success').length

  return (
    <section>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-xs text-muted-foreground">
          <b className="text-foreground">{rows.length}</b> data source{rows.length === 1 ? '' : 's'}
          {' · '}
          {connectedCount} connected
        </span>
        <div className="ml-auto">
          <Button size="sm" variant="outline" onClick={onAdd} className="h-[30px] gap-1.5">
            <Plus className="h-4 w-4" />
            Add data source
          </Button>
        </div>
      </div>

      <div className="overflow-hidden rounded-lg border bg-card text-sm">
        <div className="grid grid-cols-[1fr_140px_130px_84px_110px_36px] items-center gap-3 border-b bg-muted/50 px-3 py-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          <span>Source</span>
          <span>Type</span>
          <span>Status</span>
          <span className="text-right">Used by</span>
          <span className="text-right">Updated</span>
          <span />
        </div>

        {isLoading ? (
          <div className="px-3 py-10 text-center text-sm text-muted-foreground">
            Loading data sources…
          </div>
        ) : (
          rows.map((row) => (
            <div
              key={row.key}
              className="grid grid-cols-[1fr_140px_130px_84px_110px_36px] items-center gap-3 border-b border-border/60 px-3 py-2.5 last:border-b-0 hover:bg-muted/40"
            >
              <span className="flex min-w-0 items-center gap-2.5">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md border bg-muted text-muted-foreground [&_svg]:h-4 [&_svg]:w-4">
                  {row.icon}
                </span>
                <span className="min-w-0">
                  <span className="block truncate font-semibold text-foreground">{row.name}</span>
                  <span className="block truncate font-mono text-[10px] text-muted-foreground/80">
                    {row.subtitle}
                  </span>
                </span>
              </span>
              <span className="truncate text-xs text-muted-foreground">{row.type}</span>
              <span className="inline-flex items-center gap-2 text-xs">
                <StatusDot tone={row.statusTone} size="sm" />
                <span className={STATUS_TEXT[row.statusTone]}>{row.statusLabel}</span>
              </span>
              <span className="text-right font-mono text-xs text-muted-foreground">
                {row.usedByDashboardCount}
              </span>
              <span className="text-right text-xs text-muted-foreground">{row.updatedLabel}</span>
              {row.onManage ? (
                <button
                  type="button"
                  onClick={row.onManage}
                  aria-label={`Manage ${row.name}`}
                  title={`Manage ${row.name}`}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
                >
                  <MoreHorizontal className="h-4 w-4" />
                </button>
              ) : (
                <span />
              )}
            </div>
          ))
        )}
      </div>
    </section>
  )
}

function buildSubtitle(source: CustomDataSourceResponse): string {
  const host = source.port ? `${source.host}:${source.port}` : source.host
  return source.database_name ? `${host} / ${source.database_name}` : host
}

function typeLabel(sourceType: string): string {
  return DATA_SOURCE_TYPES.find((type) => type.value === sourceType)?.label ?? sourceType
}

function nativeDashboardUsageCount(dashboards: readonly CustomDashboard[]): number {
  return dashboards.filter((dashboard) =>
    getDashboardSources(dashboard).some((source) => NATIVE_DATA_SOURCES.has(source)),
  ).length
}

function formatUpdated(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleDateString(undefined, {month: 'short', day: 'numeric'})
}

const NATIVE_DATA_SOURCES = new Set(['events', 'logs', 'metrics', 'traces', 'spans', 'sessions'])
