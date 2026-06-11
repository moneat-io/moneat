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

// ─────────────────────────────────────────────────────────────────────────────
// Resource Catalog — the unified infrastructure inventory page.
// Built on the shared ExplorerShell (dense header + collapsible facet rail +
// scrollable results), the schema-driven SearchFilterBar, and a master/detail
// split into the reusable ResourceDetailPanel. Compact density throughout.
// ─────────────────────────────────────────────────────────────────────────────

import {useMemo, useState, type ComponentType, type KeyboardEvent, type ReactNode} from 'react'
import {Link} from '@tanstack/react-router'
import {
  Activity,
  AlertTriangle,
  ArrowUpDown,
  Boxes,
  Check,
  ChevronRight,
  CircleDollarSign,
  Cloud,
  Globe,
  Layers,
  Loader2,
  Router as RouterIcon,
  Search,
  Server,
  ShieldAlert,
  SlidersHorizontal,
  Users,
} from 'lucide-react'

import {cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle} from '@/components/ui/dialog'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import type {FacetFilter} from '@/lib/filters/types'
import {HealthBadge, KindIcon, Sparkline, TagChip} from './CatalogPrimitives'
import {ResourceDetailPanel} from './ResourceDetailPanel'
import {
  CATALOG_FACET_SCHEMA,
  HEALTH_TONE,
  KIND_META,
  buildCatalogSections,
  formatUsd,
  formatUsdExact,
  matchesFilters,
  relTime,
  findResource,
  sortResources,
  totalVulns,
  useResourceCatalog,
  utilTone,
  type CatalogRailOption,
  type CatalogRailSection,
  type FacetKey,
  type Health,
  type Resource,
  type ResourceKind,
  type SortDir,
  type SortKey,
} from './resourceCatalogData'

const SECTION_ICON: Record<FacetKey, ComponentType<{className?: string}>> = {
  kind: Layers,
  env: Globe,
  health: Activity,
  team: Users,
  cloud: Cloud,
  tag: Layers,
}

// ── Facet rail (icon-rich, controlled by the shared FacetFilter[] model) ──────

function OptionLeading({sectionKey, value}: {readonly sectionKey: FacetKey; readonly value: string}) {
  if (sectionKey === 'kind') {
    const Icon = KIND_META[value as ResourceKind].icon
    return <Icon className="h-3.5 w-3.5 text-muted-foreground" />
  }
  if (sectionKey === 'health') {
    return <StatusDot tone={HEALTH_TONE[value as Health]} size="sm" />
  }
  return null
}

function RailSection({
  section,
  facetFilters,
  onToggle,
}: {
  readonly section: CatalogRailSection
  readonly facetFilters: readonly FacetFilter[]
  readonly onToggle: (key: FacetKey, value: string) => void
}) {
  const [open, setOpen] = useState(true)
  const Icon = SECTION_ICON[section.key]
  const activeCount = facetFilters.filter((f) => f.key === section.key && !f.exclude).length
  return (
    <div className="border-b border-border/50 last:border-b-0">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left text-[11px] font-semibold uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
      >
        <ChevronRight className={cn('h-3.5 w-3.5 shrink-0 transition-transform', open && 'rotate-90')} />
        <Icon className="h-3.5 w-3.5 shrink-0" />
        <span className="flex-1 truncate">{section.label}</span>
        <span className="font-normal tabular-nums text-muted-foreground/70">
          {activeCount > 0 ? `${activeCount}/${section.options.length}` : section.options.length}
        </span>
      </button>
      {open && (
        <div className="space-y-0.5 px-2 pb-2">
          {section.options.map((opt: CatalogRailOption) => {
            const active = facetFilters.some((f) => f.key === section.key && f.value === opt.value && !f.exclude)
            return (
              <button
                key={opt.value}
                type="button"
                onClick={() => onToggle(section.key, opt.value)}
                className={cn(
                  'flex w-full items-center gap-2 rounded-md px-2 py-1 text-xs transition-colors hover:bg-accent/50',
                  active ? 'text-foreground' : 'text-muted-foreground',
                )}
              >
                <span
                  className={cn(
                    'flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded border',
                    active ? 'border-primary bg-primary text-primary-foreground' : 'border-border',
                  )}
                >
                  {active && <Check className="h-2.5 w-2.5" />}
                </span>
                <OptionLeading sectionKey={section.key} value={opt.value} />
                <span className="min-w-0 flex-1 truncate text-left">{opt.label}</span>
                <span className="shrink-0 tabular-nums text-[11px] text-muted-foreground/70">{opt.count}</span>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

function CatalogFacetRail({
  sections,
  facetFilters,
  onFacetFiltersChange,
}: {
  readonly sections: readonly CatalogRailSection[]
  readonly facetFilters: FacetFilter[]
  readonly onFacetFiltersChange: (filters: FacetFilter[]) => void
}) {
  const toggle = (key: FacetKey, value: string) => {
    const active = facetFilters.some((f) => f.key === key && f.value === value && !f.exclude)
    if (active) {
      onFacetFiltersChange(facetFilters.filter((f) => !(f.key === key && f.value === value && !f.exclude)))
    } else {
      onFacetFiltersChange([...facetFilters, {key, value, exclude: false}])
    }
  }
  return (
    <div className="flex h-full flex-col overflow-hidden bg-card/50">
      <div className="flex h-9 shrink-0 items-center gap-1.5 border-b px-2">
        <Layers className="h-3.5 w-3.5 text-muted-foreground" />
        <span className="text-xs font-semibold uppercase tracking-wider">Filters</span>
        {facetFilters.length > 0 && (
          <button
            type="button"
            onClick={() => onFacetFiltersChange([])}
            className="ml-auto text-[11px] text-muted-foreground transition-colors hover:text-foreground"
          >
            Clear all
          </button>
        )}
      </div>
      <div className="flex-1 overflow-y-auto">
        {sections.map((section) => (
          <RailSection key={section.key} section={section} facetFilters={facetFilters} onToggle={toggle} />
        ))}
      </div>
    </div>
  )
}

// ── Summary toolbar ──────────────────────────────────────────────────────────

function SummaryStat({
  icon: Icon,
  label,
  value,
  tone,
}: {
  readonly icon: ComponentType<{className?: string}>
  readonly label: string
  readonly value: string
  readonly tone: string
}) {
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap">
      <Icon className={cn('h-3.5 w-3.5', tone)} />
      <span className="font-semibold tabular-nums text-foreground">{value}</span>
      <span className="hidden text-muted-foreground sm:inline">{label}</span>
    </span>
  )
}

// ── Configure (agent / integration setup) ────────────────────────────────────

const SETUP_ROW_CLASS = 'flex items-center gap-3 rounded-lg border p-2.5 text-left transition-colors hover:bg-accent/50'
const SETUP_ICON_CLASS = 'flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground'

const SETUP_OPTIONS: {
  readonly icon: ComponentType<{className?: string}>
  readonly title: string
  readonly desc: string
  readonly tab: 'infrastructure' | 'cloud'
}[] = [
  {icon: Server, title: 'Hosts & VMs', desc: 'Install the agent on a Linux host or VM.', tab: 'infrastructure'},
  {icon: Boxes, title: 'Containers & Kubernetes', desc: 'Run the agent in Docker, Compose, or a cluster.', tab: 'infrastructure'},
  {icon: RouterIcon, title: 'Network devices', desc: 'Collect SNMP metrics and traps through the agent.', tab: 'infrastructure'},
  {icon: Cloud, title: 'Cloud accounts', desc: 'Connect AWS, Google Cloud, or Azure agentlessly.', tab: 'cloud'},
]

/** Onboarding entry point: routes to the guided setup on the Configuration page. */
function ConfigureDialog() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button type="button" variant="outline" size="sm" className="h-7 gap-1.5 text-xs" onClick={() => setOpen(true)}>
        <SlidersHorizontal className="h-3.5 w-3.5" /> Configure
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Set up monitoring</DialogTitle>
            <DialogDescription>
              Pick what to connect — each opens the guided setup on the Configuration page.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5">
            {SETUP_OPTIONS.map((option) => {
              const Icon = option.icon
              return (
                <Link
                  key={option.title}
                  to="/configuration"
                  search={{tab: option.tab}}
                  onClick={() => setOpen(false)}
                  className={SETUP_ROW_CLASS}
                >
                  <span className={SETUP_ICON_CLASS}>
                    <Icon className="h-4 w-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium">{option.title}</div>
                    <div className="text-xs text-muted-foreground">{option.desc}</div>
                  </div>
                  <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                </Link>
              )
            })}
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}

// ── Results table ────────────────────────────────────────────────────────────

function SortHeader({
  label,
  sortKey,
  active,
  dir,
  onSort,
  className,
}: {
  readonly label: string
  readonly sortKey: SortKey
  readonly active: boolean
  readonly dir: SortDir
  readonly onSort: (key: SortKey) => void
  readonly className?: string
}) {
  return (
    <TableHead className={className}>
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className={cn('inline-flex items-center gap-1 hover:text-foreground', active && 'text-foreground')}
      >
        {label}
        <ArrowUpDown className={cn('h-3 w-3', active ? 'opacity-100' : 'opacity-40')} />
        {active && <span className="text-[10px] text-muted-foreground">{dir === 'asc' ? '↑' : '↓'}</span>}
      </button>
    </TableHead>
  )
}

function handleResourceRowKeyDown(
  event: KeyboardEvent<HTMLTableRowElement>,
  resourceId: string,
  onSelect: (id: string) => void,
) {
  if (event.key !== 'Enter' && event.key !== ' ') return
  event.preventDefault()
  onSelect(resourceId)
}

function ResourceTable({
  resources,
  sortKey,
  sortDir,
  onSort,
  onSelect,
}: {
  readonly resources: readonly Resource[]
  readonly sortKey: SortKey
  readonly sortDir: SortDir
  readonly onSort: (key: SortKey) => void
  readonly onSelect: (id: string) => void
}) {
  return (
    <div className="h-full overflow-auto">
      <Table>
        <TableHeader className="sticky top-0 z-10 bg-card">
          <TableRow className="hover:bg-transparent">
            <SortHeader label="Resource" sortKey="name" active={sortKey === 'name'} dir={sortDir} onSort={onSort} />
            <TableHead>Type</TableHead>
            <SortHeader label="Health" sortKey="health" active={sortKey === 'health'} dir={sortDir} onSort={onSort} />
            <TableHead>Owner</TableHead>
            <TableHead className="hidden xl:table-cell">Tags</TableHead>
            <TableHead className="hidden lg:table-cell">CPU</TableHead>
            <SortHeader label="Vulns" sortKey="vulns" active={sortKey === 'vulns'} dir={sortDir} onSort={onSort} />
            <SortHeader label="Cost/mo" sortKey="cost" active={sortKey === 'cost'} dir={sortDir} onSort={onSort} className="text-right" />
            <SortHeader
              label="Last change"
              sortKey="lastChange"
              active={sortKey === 'lastChange'}
              dir={sortDir}
              onSort={onSort}
              className="hidden md:table-cell"
            />
          </TableRow>
        </TableHeader>
        <TableBody>
          {resources.map((r) => {
            const total = totalVulns(r.vulns)
            return (
              <TableRow
                key={r.id}
                className="cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                role="button"
                tabIndex={0}
                aria-label={`Open ${r.name}`}
                onClick={() => onSelect(r.id)}
                onKeyDown={(event) => handleResourceRowKeyDown(event, r.id, onSelect)}
              >
                <TableCell className="max-w-[260px]">
                  <div className="flex items-center gap-2">
                    <KindIcon kind={r.kind} className="h-4 w-4 shrink-0" />
                    <span className="truncate font-medium">{r.name}</span>
                  </div>
                </TableCell>
                <TableCell className="text-xs text-muted-foreground">{KIND_META[r.kind].label}</TableCell>
                <TableCell>
                  <HealthBadge health={r.health} />
                </TableCell>
                <TableCell className="text-xs">
                  {r.owner ? r.owner.team : <span className="text-warning-fg">Unowned</span>}
                </TableCell>
                <TableCell className="hidden xl:table-cell">
                  <div className="flex max-w-[200px] flex-wrap gap-1">
                    {r.tags.slice(0, 2).map((tag) => (
                      <TagChip key={tag} label={tag} />
                    ))}
                    {r.tags.length > 2 && <span className="text-[10px] text-muted-foreground">+{r.tags.length - 2}</span>}
                  </div>
                </TableCell>
                <TableCell className="hidden lg:table-cell">
                  {r.telemetry.cpuPct == null ? (
                    <span className="text-xs text-muted-foreground">—</span>
                  ) : (
                    <div className="flex items-center gap-2">
                      <Sparkline seed={`${r.id}-cpu`} tone={utilTone(r.telemetry.cpuPct)} className="h-5 w-14" />
                      <span className="text-xs tabular-nums text-muted-foreground">{r.telemetry.cpuPct}%</span>
                    </div>
                  )}
                </TableCell>
                <TableCell>
                  {total === 0 ? (
                    <span className="text-xs text-muted-foreground">0</span>
                  ) : (
                    <div className="flex items-center gap-1.5">
                      {r.vulns.critical > 0 && (
                        <Badge variant="dangerSolid" className="px-1.5 py-0 text-[10px] leading-4">
                          {r.vulns.critical}C
                        </Badge>
                      )}
                      <span className="text-xs tabular-nums text-muted-foreground">{total}</span>
                    </div>
                  )}
                </TableCell>
                <TableCell className="text-right text-xs tabular-nums">{r.monthlyUsd > 0 ? formatUsd(r.monthlyUsd) : '—'}</TableCell>
                <TableCell className="hidden text-xs text-muted-foreground md:table-cell">{relTime(r.lastChange)}</TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}

function CompactList({
  resources,
  selectedId,
  onSelect,
}: {
  readonly resources: readonly Resource[]
  readonly selectedId: string | null
  readonly onSelect: (id: string) => void
}) {
  return (
    <div className="hidden w-[280px] shrink-0 overflow-y-auto rounded-lg border bg-card md:block">
      {resources.map((r) => (
        <button
          key={r.id}
          type="button"
          onClick={() => onSelect(r.id)}
          className={cn(
            'flex w-full items-center gap-2 border-b border-border/60 px-3 py-2 text-left',
            r.id === selectedId ? 'bg-muted' : 'hover:bg-muted/50',
          )}
        >
          <KindIcon kind={r.kind} className="h-4 w-4 shrink-0" />
          <div className="min-w-0 flex-1">
            <div className="truncate text-xs font-medium">{r.name}</div>
            <div className="truncate text-[11px] text-muted-foreground">
              {KIND_META[r.kind].label} · {r.region}
            </div>
          </div>
          <StatusDot tone={HEALTH_TONE[r.health]} size="sm" />
        </button>
      ))}
    </div>
  )
}

// ── Page ─────────────────────────────────────────────────────────────────────

export function ResourceCatalog() {
  const {data: resources = [], isLoading} = useResourceCatalog()
  const [query, setQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [sortKey, setSortKey] = useState<SortKey>('health')
  const [sortDir, setSortDir] = useState<SortDir>('desc')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const sections = useMemo(() => buildCatalogSections(resources), [resources])
  const filtered = useMemo(
    () => resources.filter((r) => matchesFilters(r, query, facetFilters)),
    [resources, query, facetFilters],
  )
  const sorted = useMemo(() => sortResources(filtered, sortKey, sortDir), [filtered, sortKey, sortDir])
  const selected = findResource(sorted, selectedId)

  const stats = useMemo(() => {
    const unhealthy = filtered.filter((r) => r.health === 'critical' || r.health === 'warn').length
    const unowned = filtered.filter((r) => r.owner === null).length
    const withVulns = filtered.filter((r) => totalVulns(r.vulns) > 0).length
    const cost = filtered.reduce((sum, r) => sum + r.monthlyUsd, 0)
    return {total: filtered.length, unhealthy, unowned, withVulns, cost}
  }, [filtered])

  function handleSort(key: SortKey) {
    if (key === sortKey) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortKey(key)
      setSortDir(key === 'name' ? 'asc' : 'desc')
    }
  }

  const toolbar = (
    <div className="flex min-w-0 flex-1 items-center gap-3 overflow-x-auto px-1 text-xs text-muted-foreground">
      <SummaryStat icon={Boxes} label="resources" value={String(stats.total)} tone="text-primary" />
      <SummaryStat icon={AlertTriangle} label="unhealthy" value={String(stats.unhealthy)} tone="text-danger-fg" />
      <SummaryStat icon={Users} label="unowned" value={String(stats.unowned)} tone="text-warning-fg" />
      <SummaryStat icon={ShieldAlert} label="with vulns" value={String(stats.withVulns)} tone="text-danger-fg" />
      <SummaryStat icon={CircleDollarSign} label="/mo" value={formatUsdExact(stats.cost)} tone="text-primary" />
      <span className="ml-auto shrink-0 text-[11px] text-muted-foreground/70">sample data</span>
    </div>
  )

  let catalogContent: ReactNode
  if (isLoading) {
    catalogContent = (
      <div className="flex h-full items-center justify-center text-muted-foreground">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" /> Loading resources…
      </div>
    )
  } else if (sorted.length === 0) {
    catalogContent = (
      <div className="p-3">
        <EmptyState
          icon={Search}
          title="No resources match"
          description="Try removing a filter or broadening your search."
          action={
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                setQuery('')
                setFacetFilters([])
              }}
            >
              Clear filters
            </Button>
          }
        />
      </div>
    )
  } else if (selected) {
    catalogContent = (
      <div className="flex h-full min-h-0 gap-3 p-3">
        <CompactList resources={sorted} selectedId={selectedId} onSelect={setSelectedId} />
        <ResourceDetailPanel resource={selected} onSelect={setSelectedId} onClose={() => setSelectedId(null)} />
      </div>
    )
  } else {
    catalogContent = (
      <ResourceTable resources={sorted} sortKey={sortKey} sortDir={sortDir} onSort={handleSort} onSelect={setSelectedId} />
    )
  }

  return (
    <div className="h-[calc(100vh-var(--header-height,0px)-43px)] min-h-[520px]">
      <ExplorerShell
        icon={<Boxes className="h-4 w-4 text-primary" />}
        title="Resource Catalog"
        defaultRailOpen
        searchBar={
          <SearchFilterBar
            query={query}
            onQueryChange={setQuery}
            facetFilters={facetFilters}
            onFacetFiltersChange={setFacetFilters}
            schema={CATALOG_FACET_SCHEMA}
            placeholder="Search resources — try kind:host, env:prod, -team:Unowned"
          />
        }
        actions={<ConfigureDialog />}
        rail={<CatalogFacetRail sections={sections} facetFilters={facetFilters} onFacetFiltersChange={setFacetFilters} />}
        toolbar={toolbar}
      >
        {catalogContent}
      </ExplorerShell>
    </div>
  )
}
