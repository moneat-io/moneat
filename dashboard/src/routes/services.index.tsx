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

import {createFileRoute, Link, useNavigate} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useMemo, useState, type ReactNode} from 'react'
import {ArrowDown, ArrowUp, Boxes, List, Network} from 'lucide-react'

import {api, type ApmServiceCatalogRow as CatalogService, type ApmTimeRange} from '@/lib/api'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {StatusDot} from '@/components/ui/status-dot'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {
  ApdexHeat,
  ApmTimeRangeSelect,
  EnvPills,
  MeterBar,
  ServiceStatusBadge,
  Sparkline,
  TypeChip,
} from '@/components/apm/ServiceVisuals'
import {
  formatMs,
  formatRps,
  SERVICE_FACET_SCHEMA,
  statusTone,
} from '@/lib/apm-view'
import type {FacetFilter, FacetRailSection, FacetValue} from '@/lib/filters/types'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/services/')({
  component: ServicesCatalogPage,
})

type SortKey = 'error' | 'rps' | 'p95' | 'p99' | 'apdex' | 'name'
type SortDir = 'asc' | 'desc'

interface SortState {
  key: SortKey
  dir: SortDir
}

const SORT_OPTIONS: ReadonlyArray<{value: SortKey; label: string}> = [
  {value: 'error', label: 'Error rate'},
  {value: 'p95', label: 'p95'},
  {value: 'p99', label: 'p99'},
  {value: 'rps', label: 'Requests/s'},
  {value: 'apdex', label: 'Apdex'},
  {value: 'name', label: 'Name'},
]

function ServicesCatalogPage() {
  const navigate = useNavigate()
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<SortState>({key: 'error', dir: 'desc'})
  const [view, setView] = useState<'list' | 'map'>('list')
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('1h')

  const catalogQuery = useQuery({
    queryKey: ['apm-services', timeRange],
    queryFn: () => api.getApmServices({timeRange, limit: 200}),
  })
  const services = catalogQuery.data?.services ?? []
  const facetSections = useMemo(() => buildFacetSections(services), [services])
  const rows = useMemo(() => sortServices(filterServices(services, facetFilters, query), sort), [facetFilters, query, services, sort])
  const summary = catalogQuery.data?.summary ?? {total: 0, alerting: 0, degraded: 0}

  const setSortKey = (key: SortKey) =>
    setSort((current) =>
      current.key === key
        ? {key, dir: current.dir === 'desc' ? 'asc' : 'desc'}
        : {key, dir: key === 'name' ? 'asc' : 'desc'},
    )

  return (
    <ExplorerShell
      className="min-h-[calc(100vh-3.5rem)]"
      title="Services"
      icon={<Boxes className="h-4 w-4 text-muted-foreground" />}
      searchBar={
        <SearchFilterBar
          query={query}
          onQueryChange={setQuery}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          schema={SERVICE_FACET_SCHEMA}
          placeholder="Filter by service, tag, or facet…"
          trailing={<ApmTimeRangeSelect value={timeRange} onChange={setTimeRange} />}
        />
      }
      rail={
        <FacetRail
          sections={facetSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          title="Service facets"
        />
      }
      toolbar={
        <div className="flex flex-1 items-center gap-2">
          <Badge variant="neutral" className="font-normal">
            {rows.length} services
          </Badge>
          <span className="mx-1 h-4 w-px bg-border" />
          <div className="inline-flex overflow-hidden rounded-md border">
            <SegmentButton active={view === 'list'} onClick={() => setView('list')}>
              <List className="h-3.5 w-3.5" /> List
            </SegmentButton>
            <SegmentButton active={view === 'map'} onClick={() => setView('map')}>
              <Network className="h-3.5 w-3.5" /> Map
            </SegmentButton>
          </div>
          <div className="ml-auto flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Sort</span>
            <Select value={sort.key} onValueChange={(value) => setSort({key: value as SortKey, dir: value === 'name' ? 'asc' : 'desc'})}>
              <SelectTrigger className="h-7 w-[132px] text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {SORT_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
      }
    >
      <div className="min-w-0 px-3 py-3 sm:px-4">
        <div className="mb-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
          <span>APM service catalog</span>
          <span className="text-border">·</span>
          <span>
            <strong className="font-semibold text-foreground tabular-nums">{summary.total}</strong> services
          </span>
          <span className="text-border">·</span>
          <span className="inline-flex items-center gap-1.5">
            <StatusDot tone="danger" /> {summary.alerting} alerting
          </span>
          <span className="inline-flex items-center gap-1.5">
            <StatusDot tone="warning" /> {summary.degraded} degraded
          </span>
        </div>

        {catalogQuery.isError && (
          <div className="mb-3 rounded-md border border-danger-border bg-danger-bg/40 px-3 py-2 text-sm text-danger-fg">
            Service telemetry is unavailable.
          </div>
        )}

        {view === 'map' ? (
          <MapPlaceholder />
        ) : (
          <div className="overflow-hidden rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="pl-3">Service</TableHead>
                  <TableHead>Env</TableHead>
                  <SortableHead label="Requests/s" sortKey="rps" sort={sort} onSort={setSortKey} />
                  <SortableHead label="p95" sortKey="p95" sort={sort} onSort={setSortKey} align="right" />
                  <SortableHead label="p99" sortKey="p99" sort={sort} onSort={setSortKey} align="right" />
                  <SortableHead label="Error rate" sortKey="error" sort={sort} onSort={setSortKey} align="right" />
                  <SortableHead label="Apdex" sortKey="apdex" sort={sort} onSort={setSortKey} align="right" />
                  <TableHead>Last deploy</TableHead>
                  <TableHead>Team</TableHead>
                  <TableHead className="pr-3">Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((service) => (
                  <TableRow
                    key={service.name}
                    className="cursor-pointer"
                    onClick={() => navigate({to: '/services/$service', params: {service: service.name}})}
                  >
                    <TableCell className="pl-3">
                      <div className="flex items-center gap-2">
                        <StatusDot tone={statusTone(service.status)} pulse={service.status === 'alerting'} />
                        <Link
                          to="/services/$service"
                          params={{service: service.name}}
                          onClick={(event) => event.stopPropagation()}
                          className="font-semibold text-foreground hover:text-primary hover:underline"
                        >
                          {service.name}
                        </Link>
                        <TypeChip type={service.type} />
                      </div>
                    </TableCell>
                    <TableCell>
                      <EnvPills pills={service.env} />
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Sparkline values={service.spark} />
                        <span className="font-mono text-xs tabular-nums">{formatRps(service.rps)}</span>
                      </div>
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(service.p95Ms)}</TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{formatMs(service.p99Ms)}</TableCell>
                    <TableCell>
                      <MeterBar pct={service.errorBarPct} level={service.errorLevel} value={service.errorRateLabel} />
                    </TableCell>
                    <TableCell className="text-right">
                      <ApdexHeat value={service.apdex} tone={service.apdexTone} />
                    </TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{service.lastDeploy}</TableCell>
                    <TableCell className="text-muted-foreground">{service.team}</TableCell>
                    <TableCell className="pr-3">
                      <ServiceStatusBadge status={service.status} />
                    </TableCell>
                  </TableRow>
                ))}
                {rows.length === 0 && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={10} className="py-10 text-center text-sm text-muted-foreground">
                      {catalogQuery.isLoading ? 'Loading services...' : 'No services match the current filters.'}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>
        )}
      </div>
    </ExplorerShell>
  )
}

function SegmentButton({
  active,
  onClick,
  children,
}: Readonly<{active: boolean; onClick: () => void; children: ReactNode}>) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium transition-colors',
        active ? 'bg-muted text-foreground' : 'text-muted-foreground hover:bg-muted/50',
      )}
    >
      {children}
    </button>
  )
}

function SortableHead({
  label,
  sortKey,
  sort,
  onSort,
  align = 'left',
}: Readonly<{
  label: string
  sortKey: SortKey
  sort: SortState
  onSort: (key: SortKey) => void
  align?: 'left' | 'right'
}>) {
  const active = sort.key === sortKey
  return (
    <TableHead className={cn(align === 'right' && 'text-right')}>
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className={cn(
          'inline-flex items-center gap-1 hover:text-foreground',
          align === 'right' && 'flex-row-reverse',
          active && 'text-foreground',
        )}
      >
        {label}
        {active &&
          (sort.dir === 'desc' ? <ArrowDown className="h-3 w-3" /> : <ArrowUp className="h-3 w-3" />)}
      </button>
    </TableHead>
  )
}

function MapPlaceholder() {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed py-16 text-center">
      <div className="flex h-10 w-10 items-center justify-center rounded-full bg-muted">
        <Network className="h-5 w-5 text-muted-foreground" />
      </div>
      <div>
        <p className="text-sm font-medium">Service map</p>
        <p className="mt-0.5 text-sm text-muted-foreground">
          Topology of these services and their dependencies.
        </p>
      </div>
      <Button asChild variant="outline" size="sm">
        <Link to="/monitoring/service-map">Open service map</Link>
      </Button>
    </div>
  )
}

/** Derive the rail's selectable values + counts from the loaded services. */
function buildFacetSections(services: readonly CatalogService[]): FacetRailSection[] {
  const tally = (key: string): FacetValue[] => {
    const counts = new Map<string, number>()
    for (const service of services) {
      const value = serviceFacetValue(service, key)
      if (value) counts.set(value, (counts.get(value) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .map(([value, count]) => ({value, count}))
  }
  return [
    {key: 'env', label: 'Environment', color: 'bg-chart-1', singleSelect: true, options: tally('env')},
    {key: 'type', label: 'Service type', color: 'bg-chart-2', options: tally('type')},
    {key: 'source', label: 'Telemetry source', color: 'bg-chart-6', options: tally('source')},
  ]
}

function filterServices(
  services: readonly CatalogService[],
  filters: readonly FacetFilter[],
  query: string,
): CatalogService[] {
  const text = query.trim().toLowerCase()
  const byKey = new Map<string, {include: Set<string>; exclude: Set<string>}>()
  for (const filter of filters) {
    const entry = byKey.get(filter.key) ?? {include: new Set<string>(), exclude: new Set<string>()}
    ;(filter.exclude ? entry.exclude : entry.include).add(filter.value)
    byKey.set(filter.key, entry)
  }

  return services.filter((service) => {
    if (text && !service.name.toLowerCase().includes(text) && !service.team.toLowerCase().includes(text)) {
      return false
    }
    for (const [key, sets] of byKey) {
      const value = serviceFacetValue(service, key)
      // Unknown facet keys (no value on the row) are treated as no-ops.
      if (value === undefined) continue
      if (sets.include.size > 0 && !sets.include.has(value)) return false
      if (sets.exclude.has(value)) return false
    }
    return true
  })
}

function serviceFacetValue(service: CatalogService, key: string): string | undefined {
  switch (key) {
    case 'type':
      return service.type
    case 'env':
      return service.env[0]?.label === 'prod' ? 'production' : service.env[0]?.label
    case 'source':
      return service.sources[0]
    default:
      return undefined
  }
}

function sortServices(services: CatalogService[], sort: SortState): CatalogService[] {
  const factor = sort.dir === 'desc' ? -1 : 1
  return [...services].sort((a, b) => {
    switch (sort.key) {
      case 'name':
        return factor * a.name.localeCompare(b.name)
      case 'rps':
        return factor * (a.rps - b.rps)
      case 'p95':
        return factor * (a.p95Ms - b.p95Ms)
      case 'p99':
        return factor * (a.p99Ms - b.p99Ms)
      case 'apdex':
        return factor * (Number.parseFloat(a.apdex) - Number.parseFloat(b.apdex))
      default:
        return factor * (a.errorBarPct - b.errorBarPct)
    }
  })
}
