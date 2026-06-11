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

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {useMemo, useState} from 'react'
import {
  Activity,
  AlertTriangle,
  Bell,
  CheckCircle2,
  FlaskConical,
  Globe,
  Layers,
  Monitor,
  Pause,
  Pin,
  Play,
  Plus,
  RefreshCw,
  Shield,
  XCircle,
  Zap,
} from 'lucide-react'

import {api, type SyntheticResultResponse, type SyntheticTestResponse} from '@/lib/api'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {FacetRail} from '@/components/filters/FacetRail'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import type {FacetFilter, FacetRailSection, FacetSchema} from '@/lib/filters/types'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Checkbox} from '@/components/ui/checkbox'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {LocationPins, UptimeStrip} from '@/components/synthetics/SyntheticsViz'
import {p95} from '@/components/synthetics/syntheticsHelpers'

export const Route = createFileRoute('/synthetics/')({
  component: SyntheticsOverview,
})

type DerivedStatus = 'passing' | 'failing' | 'degraded' | 'paused'

const TYPE_ICON: Record<string, React.ComponentType<{className?: string}>> = {
  api: FlaskConical,
  multistep: Layers,
  browser: Monitor,
  ssl: Shield,
  dns: Globe,
  tcp: Activity,
  ping: Activity,
}

const TYPE_BADGE: Record<string, 'accent' | 'info' | 'neutral' | 'warning'> = {
  api: 'accent',
  browser: 'info',
  multistep: 'neutral',
  ssl: 'warning',
  dns: 'neutral',
  tcp: 'neutral',
  ping: 'neutral',
}

const SYNTH_FACET_SCHEMA = [
  {key: 'service', label: 'Service', color: 'bg-chart-1', allowExclude: true},
  {key: 'type', label: 'Type', color: 'bg-chart-2', allowExclude: true},
  {key: 'location', label: 'Location', color: 'bg-chart-3', allowExclude: true},
  {key: 'tag', label: 'Tag', color: 'bg-chart-5', allowExclude: true},
] satisfies FacetSchema

const STATUS_TABS: ReadonlyArray<{value: 'all' | 'failing' | 'degraded' | 'paused'; label: string}> = [
  {value: 'all', label: 'All'},
  {value: 'failing', label: 'Failing'},
  {value: 'degraded', label: 'Degraded'},
  {value: 'paused', label: 'Paused'},
]

function facetValuesFor(test: SyntheticTestResponse, key: string): string[] {
  switch (key) {
    case 'service':
      return test.service ? [test.service] : []
    case 'type':
      return [test.testType]
    case 'location':
      return test.locations ?? []
    case 'tag':
      return test.tags ?? []
    default:
      return []
  }
}

function matchesFacets(test: SyntheticTestResponse, filters: FacetFilter[]): boolean {
  const byKey = new Map<string, {include: string[]; exclude: string[]}>()
  for (const f of filters) {
    const entry = byKey.get(f.key) ?? {include: [], exclude: []}
    if (f.exclude) entry.exclude.push(f.value)
    else entry.include.push(f.value)
    byKey.set(f.key, entry)
  }
  for (const [key, {include, exclude}] of byKey) {
    const values = facetValuesFor(test, key)
    if (exclude.some((v) => values.includes(v))) return false
    if (include.length > 0 && !include.some((v) => values.includes(v))) return false
  }
  return true
}

function buildRailSections(
  tests: readonly SyntheticTestResponse[]
): FacetRailSection[] {
  const counts = (extract: (t: SyntheticTestResponse) => string[]) => {
    const map = new Map<string, number>()
    for (const t of tests) for (const v of extract(t)) map.set(v, (map.get(v) ?? 0) + 1)
    return [...map.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([value, count]) => ({value, count}))
  }
  return [
    {key: 'service', label: 'Service', color: 'bg-chart-1', options: counts((t) => (t.service ? [t.service] : []))},
    {key: 'type', label: 'Type', color: 'bg-chart-2', options: counts((t) => [t.testType])},
    {key: 'location', label: 'Location', color: 'bg-chart-3', options: counts((t) => t.locations ?? [])},
    {key: 'tag', label: 'Tag', color: 'bg-chart-5', options: counts((t) => t.tags ?? [])},
  ]
}

function availabilityBuckets(
  results: readonly SyntheticResultResponse[],
  buckets = 48,
  windowMs = 24 * 3600 * 1000
): number[] {
  const now = Date.now()
  const start = now - windowMs
  const acc = Array.from({length: buckets}, () => ({pass: 0, total: 0}))
  for (const r of results) {
    const t = new Date(r.timestamp).getTime()
    if (Number.isNaN(t) || t < start) continue
    const idx = Math.min(buckets - 1, Math.max(0, Math.floor(((t - start) / windowMs) * buckets)))
    acc[idx].total += 1
    if (r.status === 'passed') acc[idx].pass += 1
  }
  return acc.map((b) => (b.total === 0 ? -1 : (b.pass / b.total) * 100))
}

interface Derived {
  status: DerivedStatus
  uptimeStrip: string[]
  p95Label: string
  failingLocations: number
}

function deriveTest(test: SyntheticTestResponse, results: SyntheticResultResponse[]): Derived {
  const recent = results.slice(0, 30)
  const hasFailure = recent.some((r) => r.status === 'failed')
  let status: DerivedStatus
  if (test.lastStatus === 'failed') status = 'failing'
  else if (!test.active) status = 'paused'
  else if (hasFailure) status = 'degraded'
  else status = 'passing'

  const durations = results.filter((r) => r.durationMs > 0).map((r) => r.durationMs)
  const p95Ms = p95(durations)
  const p95Label = test.testType === 'browser'
    ? p95Ms > 0 ? `${(p95Ms / 1000).toFixed(1)}s` : '—'
    : p95Ms > 0 ? `${Math.round(p95Ms)}ms` : '—'

  // Most-recent failing locations: latest result per location that failed.
  const latestByLoc = new Map<string, string>()
  for (const r of results) {
    const loc = r.locationCode || r.probeDc || 'moneat'
    if (!latestByLoc.has(loc)) latestByLoc.set(loc, r.status)
  }
  const failingLocations = [...latestByLoc.values()].filter((s) => s === 'failed').length

  return {status, uptimeStrip: recent.slice(0, 24).reverse().map((r) => r.status), p95Label, failingLocations}
}

function formatRelative(timestamp: number | null | undefined): string {
  if (!timestamp) return 'never'
  const diff = Date.now() - timestamp
  if (diff < 60_000) return `${Math.max(1, Math.round(diff / 1000))}s ago`
  if (diff < 3_600_000) return `${Math.round(diff / 60_000)}m ago`
  if (diff < 86_400_000) return `${Math.round(diff / 3_600_000)}h ago`
  return `${Math.round(diff / 86_400_000)}d ago`
}

function formatInterval(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`
  return `${Math.round(seconds / 3600)}h`
}

function StatBlock({
  icon: Icon,
  label,
  value,
  unit,
  sub,
  valueClass,
}: Readonly<{
  icon: React.ComponentType<{className?: string}>
  label: string
  value: string
  unit?: string
  sub?: React.ReactNode
  valueClass?: string
}>) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5">
      <div className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
        <Icon className="h-3 w-3 text-muted-foreground/70" />
        {label}
      </div>
      <div className={cn('flex items-baseline gap-1 text-2xl font-bold leading-none tabular-nums', valueClass)}>
        {value}
        {unit && <span className="text-sm font-semibold text-muted-foreground">{unit}</span>}
      </div>
      {sub && <div className="text-xs text-muted-foreground">{sub}</div>}
    </div>
  )
}

function SyntheticsOverview() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [searchQuery, setSearchQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const [statusTab, setStatusTab] = useState<DerivedStatus | 'all'>('all')
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const {data: testsData, isLoading} = useQuery({
    queryKey: ['synthetic-tests'],
    queryFn: () => api.listSyntheticTests(),
  })
  const {data: resultsData} = useQuery({
    queryKey: ['synthetic-results'],
    queryFn: () => api.listSyntheticResults(500),
  })
  const {data: locationsData} = useQuery({
    queryKey: ['synthetic-locations'],
    queryFn: () => api.listSyntheticLocations(),
  })

  const tests = useMemo(() => testsData ?? [], [testsData])
  const results = useMemo(() => resultsData?.results ?? [], [resultsData])
  const locations = locationsData ?? []

  const resultsByTest = useMemo(() => {
    const map = new Map<string, SyntheticResultResponse[]>()
    for (const r of results) {
      const arr = map.get(r.testId) ?? []
      arr.push(r)
      map.set(r.testId, arr)
    }
    return map
  }, [results])

  const derived = useMemo(() => {
    const map = new Map<string, Derived>()
    for (const t of tests) map.set(t.id, deriveTest(t, resultsByTest.get(t.id) ?? []))
    return map
  }, [tests, resultsByTest])

  const invalidate = () => {
    queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    queryClient.invalidateQueries({queryKey: ['synthetic-results']})
  }

  const runMutation = useMutation({
    mutationFn: (testId: string) => api.runSyntheticTest(testId),
    onSuccess: () => {
      toast({title: 'Test run triggered'})
      invalidate()
    },
    onError: (e: Error) => toast({title: 'Failed to run test', description: e.message, variant: 'destructive'}),
  })
  const pauseMutation = useMutation({
    mutationFn: ({testId, active}: {testId: string; active: boolean}) =>
      api.updateSyntheticTest(testId, {active}),
    onSuccess: () => invalidate(),
    onError: (e: Error) => toast({title: 'Failed to update test', description: e.message, variant: 'destructive'}),
  })

  const filteredTests = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    return tests.filter((t) => {
      if (q && !t.name.toLowerCase().includes(q) && !(t.service ?? '').toLowerCase().includes(q)) return false
      if (!matchesFacets(t, facetFilters)) return false
      if (statusTab !== 'all' && derived.get(t.id)?.status !== statusTab) return false
      return true
    })
  }, [tests, searchQuery, facetFilters, statusTab, derived])

  const counts = useMemo(() => {
    let failing = 0
    let degraded = 0
    let paused = 0
    for (const t of tests) {
      const s = derived.get(t.id)?.status
      if (s === 'failing') failing += 1
      else if (s === 'degraded') degraded += 1
      else if (s === 'paused') paused += 1
    }
    return {all: tests.length, failing, degraded, paused}
  }, [tests, derived])

  const stats = useMemo(() => {
    const active = tests.filter((t) => t.active).length
    const durations = results.filter((r) => r.durationMs > 0).map((r) => r.durationMs)
    const passed = results.filter((r) => r.status === 'passed').length
    const uptime = results.length ? (passed / results.length) * 100 : 100
    const sorted = [...durations].sort((a, b) => a - b)
    const p50 = sorted.length ? sorted[Math.floor(sorted.length / 2)] : 0
    const managed = locations.filter((l) => l.type === 'managed').length
    const priv = locations.filter((l) => l.type === 'private').length
    return {
      active,
      paused: tests.length - active,
      uptime,
      p50,
      p95: p95(durations),
      managed,
      priv,
      runs: results.length,
    }
  }, [tests, results, locations])

  const buckets = useMemo(() => availabilityBuckets(results), [results])

  const attention = useMemo(
    () =>
      tests
        .map((t) => ({test: t, d: derived.get(t.id)}))
        .filter((x) => x.d && (x.d.status === 'failing' || x.d.status === 'degraded'))
        .sort((a, b) => (a.d?.status === 'failing' ? -1 : 1) - (b.d?.status === 'failing' ? -1 : 1))
        .slice(0, 4),
    [tests, derived]
  )

  const railSections = useMemo(() => buildRailSections(tests), [tests])

  const toggleSelect = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }
  const toggleAll = () => {
    setSelected((prev) => (prev.size === filteredTests.length ? new Set() : new Set(filteredTests.map((t) => t.id))))
  }

  return (
    <ExplorerShell
      title="Synthetic Monitoring"
      icon={<FlaskConical className="h-4 w-4 text-muted-foreground" />}
      searchBar={
        <SearchFilterBar
          query={searchQuery}
          onQueryChange={setSearchQuery}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          schema={SYNTH_FACET_SCHEMA}
          placeholder="Search tests — or filter service:, type:, location:, tag:"
        />
      }
      actions={
        <Button size="sm" className="h-7 gap-1.5" onClick={() => navigate({to: '/synthetics/new'})}>
          <Plus className="h-3.5 w-3.5" />
          New test
        </Button>
      }
      rail={
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={setFacetFilters}
          title="Synthetics"
        />
      }
    >
      <div className="flex flex-col gap-3 p-3">
        {/* Stat band */}
        <div className="grid grid-cols-2 gap-2.5 md:grid-cols-3 xl:grid-cols-6">
          <StatBlock icon={FlaskConical} label="Tests" value={String(tests.length)} sub={`${stats.active} active · ${stats.paused} paused`} />
          <StatBlock icon={CheckCircle2} label="Uptime · recent" value={stats.uptime.toFixed(2)} unit="%" sub="across all runs" />
          <StatBlock
            icon={AlertTriangle}
            label="Failing now"
            value={String(counts.failing)}
            valueClass={counts.failing > 0 ? 'text-danger-fg' : undefined}
            sub={counts.degraded > 0 ? <span className="text-warning-fg">{counts.degraded} degraded</span> : 'all healthy'}
          />
          <StatBlock icon={Zap} label="Latency p95" value={stats.p95 ? String(Math.round(stats.p95)) : '—'} unit={stats.p95 ? 'ms' : undefined} sub={`p50 ${Math.round(stats.p50)}ms`} />
          <StatBlock icon={Pin} label="Locations" value={String(stats.managed + stats.priv)} sub={`${stats.managed} managed · ${stats.priv} private`} />
          <StatBlock icon={RefreshCw} label="Runs · recent" value={String(stats.runs)} sub="recorded results" />
        </div>

        {/* Availability + needs attention */}
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-[1.4fr_1fr]">
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Activity className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Global availability</h3>
              <span className="ml-auto text-xs text-muted-foreground">last 24h</span>
            </div>
            <div className="p-3.5">
              <div className="mb-2 flex items-baseline gap-2.5">
                <span className="text-2xl font-bold tabular-nums">{stats.uptime.toFixed(2)}%</span>
                <span className="text-xs text-muted-foreground">across all tests &amp; locations · target 99.9%</span>
              </div>
              <div className="flex h-11 items-stretch gap-px">
                {buckets.map((v, i) => (
                  <span
                    key={i}
                    title={v < 0 ? 'No data' : `${v.toFixed(0)}% passing`}
                    className={cn(
                      'min-w-[2px] flex-1 rounded-[1px]',
                      v < 0 ? 'bg-muted' : v >= 100 ? 'bg-success-solid/90' : v === 0 ? 'bg-danger-solid' : 'bg-warning-solid'
                    )}
                  />
                ))}
              </div>
              <div className="mt-1.5 flex justify-between text-[11px] text-muted-foreground">
                <span>24h ago</span>
                <span>now</span>
              </div>
            </div>
          </div>

          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <AlertTriangle className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Needs attention</h3>
              {attention.length > 0 && (
                <Badge variant="danger" size="sm" className="ml-auto">
                  {attention.length}
                </Badge>
              )}
            </div>
            <div className="divide-y">
              {attention.length === 0 && (
                <div className="px-3.5 py-6 text-center text-xs text-muted-foreground">Everything is passing.</div>
              )}
              {attention.map(({test, d}) => (
                <button
                  key={test.id}
                  type="button"
                  onClick={() => navigate({to: '/synthetics/$testId', params: {testId: test.id}})}
                  className="flex w-full items-center gap-3 px-3.5 py-2.5 text-left transition-colors hover:bg-muted/50"
                >
                  <span
                    className={cn(
                      'grid h-7 w-7 shrink-0 place-items-center rounded-md border',
                      d?.status === 'failing'
                        ? 'border-danger-border bg-danger-bg text-danger-fg'
                        : 'border-warning-border bg-warning-bg text-warning-fg'
                    )}
                  >
                    {d?.status === 'failing' ? <XCircle className="h-3.5 w-3.5" /> : <Activity className="h-3.5 w-3.5" />}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-semibold">{test.name}</span>
                    <span className="block truncate text-xs text-muted-foreground">
                      {d?.status === 'failing'
                        ? `Failing from ${d.failingLocations || 1} location${(d.failingLocations || 1) > 1 ? 's' : ''}`
                        : 'Recent failures — degraded'}
                    </span>
                  </span>
                  <Badge variant={d?.status === 'failing' ? 'danger' : 'warning'} size="sm">
                    {d?.status === 'failing' ? 'Failing' : 'Degraded'}
                  </Badge>
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Tests table */}
        <div className="rounded-lg border bg-card">
          <div className="flex flex-wrap items-center gap-2 border-b px-3 py-2">
            <div className="flex items-center gap-1">
              {STATUS_TABS.map((tab) => {
                const count = tab.value === 'all' ? counts.all : counts[tab.value]
                return (
                  <button
                    key={tab.value}
                    type="button"
                    onClick={() => setStatusTab(tab.value)}
                    className={cn(
                      'flex h-7 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium transition-colors',
                      statusTab === tab.value
                        ? 'bg-accent text-accent-foreground'
                        : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground'
                    )}
                  >
                    {tab.label}
                    <span className="tabular-nums text-muted-foreground/70">{count}</span>
                  </button>
                )
              })}
            </div>
            <span className="ml-auto text-xs text-muted-foreground">
              {filteredTests.length} of {tests.length} tests
            </span>
          </div>

          {selected.size > 0 && (
            <div className="flex items-center gap-2 border-b bg-accent/40 px-3 py-2 text-sm">
              <b>{selected.size}</b> selected
              <Button
                size="sm"
                variant="outline"
                className="h-7"
                onClick={() => {
                  selected.forEach((id) => runMutation.mutate(id))
                  setSelected(new Set())
                }}
              >
                <Play className="mr-1 h-3 w-3" />
                Run
              </Button>
              <Button
                size="sm"
                variant="outline"
                className="h-7"
                onClick={() => {
                  selected.forEach((id) => pauseMutation.mutate({testId: id, active: false}))
                  setSelected(new Set())
                }}
              >
                <Pause className="mr-1 h-3 w-3" />
                Pause
              </Button>
            </div>
          )}

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-muted/40 text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="w-8 px-2 py-2">
                    <Checkbox
                      checked={selected.size === filteredTests.length && filteredTests.length > 0}
                      onCheckedChange={toggleAll}
                      aria-label="Select all"
                    />
                  </th>
                  <th className="px-2 py-2 font-medium">Name</th>
                  <th className="px-2 py-2 font-medium">Type</th>
                  <th className="px-2 py-2 font-medium">Status</th>
                  <th className="px-2 py-2 font-medium">Uptime 24h</th>
                  <th className="px-2 py-2 text-right font-medium">p95</th>
                  <th className="px-2 py-2 font-medium">Locations</th>
                  <th className="px-2 py-2 font-medium">Last run</th>
                  <th className="px-2 py-2 font-medium">Interval</th>
                  <th className="w-16 px-2 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {filteredTests.map((t) => {
                  const d = derived.get(t.id)
                  const Icon = TYPE_ICON[t.testType] ?? FlaskConical
                  return (
                    <tr
                      key={t.id}
                      className="group cursor-pointer transition-colors hover:bg-muted/40"
                      onClick={() => navigate({to: '/synthetics/$testId', params: {testId: t.id}})}
                    >
                      <td className="px-2 py-1.5" onClick={(e) => e.stopPropagation()}>
                        <Checkbox checked={selected.has(t.id)} onCheckedChange={() => toggleSelect(t.id)} aria-label={t.name} />
                      </td>
                      <td className="px-2 py-1.5">
                        <div className="flex items-center gap-2">
                          <span className="grid h-6 w-6 shrink-0 place-items-center rounded border bg-muted/50 text-muted-foreground">
                            <Icon className="h-3.5 w-3.5" />
                          </span>
                          <span className="min-w-0">
                            <span className="flex items-center gap-1 font-semibold text-foreground">
                              <span className="truncate">{t.name}</span>
                              {(t.alertConfig || t.alertOnFailure) && <Bell className="h-3 w-3 text-muted-foreground" />}
                            </span>
                            <span className="block truncate text-[11px] text-muted-foreground">
                              {[t.service, t.environment].filter(Boolean).join(' · ') || t.testType}
                            </span>
                          </span>
                        </div>
                      </td>
                      <td className="px-2 py-1.5">
                        <Badge variant={TYPE_BADGE[t.testType] ?? 'neutral'} size="sm" className="uppercase">
                          {t.testType}
                        </Badge>
                      </td>
                      <td className="px-2 py-1.5">
                        <span
                          className={cn(
                            'inline-flex items-center gap-1.5 text-xs font-semibold',
                            d?.status === 'failing' && 'text-danger-fg',
                            d?.status === 'degraded' && 'text-warning-fg',
                            d?.status === 'passing' && 'text-success-fg',
                            d?.status === 'paused' && 'text-muted-foreground'
                          )}
                        >
                          <span
                            className={cn(
                              'h-2 w-2 rounded-full',
                              d?.status === 'failing' && 'bg-danger-solid',
                              d?.status === 'degraded' && 'bg-warning-solid',
                              d?.status === 'passing' && 'bg-success-solid',
                              d?.status === 'paused' && 'bg-muted-foreground/50'
                            )}
                          />
                          {d?.status === 'failing'
                            ? 'Failing'
                            : d?.status === 'degraded'
                              ? 'Degraded'
                              : d?.status === 'paused'
                                ? 'Paused'
                                : 'Passing'}
                        </span>
                      </td>
                      <td className="px-2 py-1.5">
                        <div className="w-[104px]">
                          <UptimeStrip statuses={d?.uptimeStrip ?? []} />
                        </div>
                      </td>
                      <td className="px-2 py-1.5 text-right font-mono text-xs tabular-nums">{d?.p95Label}</td>
                      <td className="px-2 py-1.5">
                        <LocationPins codes={t.locations ?? []} locations={locations} />
                      </td>
                      <td className="px-2 py-1.5 text-muted-foreground">{formatRelative(t.lastRunAt)}</td>
                      <td className="px-2 py-1.5 font-mono text-xs text-muted-foreground">{formatInterval(t.intervalSeconds)}</td>
                      <td className="px-2 py-1.5" onClick={(e) => e.stopPropagation()}>
                        <div className="flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                          <Button size="icon" variant="ghost" className="h-6 w-6" title="Run now" onClick={() => runMutation.mutate(t.id)}>
                            <Play className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-6 w-6"
                            title={t.active ? 'Pause' : 'Resume'}
                            onClick={() => pauseMutation.mutate({testId: t.id, active: !t.active})}
                          >
                            <Pause className={cn('h-3.5 w-3.5', !t.active && 'text-warning-fg')} />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {!isLoading && filteredTests.length === 0 && (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <FlaskConical className="h-6 w-6 text-muted-foreground" />
                <p className="text-sm font-medium">{tests.length === 0 ? 'No synthetic tests yet' : 'No tests match your filters'}</p>
                {tests.length === 0 && (
                  <Button size="sm" className="mt-1 gap-1.5" onClick={() => navigate({to: '/synthetics/new'})}>
                    <Plus className="h-3.5 w-3.5" />
                    Create a test
                  </Button>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </ExplorerShell>
  )
}
