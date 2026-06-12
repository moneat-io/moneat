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
import {useMemo} from 'react'
import {
  Activity,
  AlertTriangle,
  ArrowLeft,
  Bell,
  ChevronRight,
  Clock,
  Pencil,
  Pin,
  Play,
  Server,
  Sliders,
  Zap,
} from 'lucide-react'
import {LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer} from 'recharts'

import {api, type SyntheticResultResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {UptimeStrip} from '@/components/synthetics/SyntheticsViz'
import {locationMeta} from '@/components/synthetics/syntheticsHelpers'

export const Route = createFileRoute('/synthetics/$testId/')({
  component: SyntheticTestDetail,
})

function testStatusVariant(isFailing: boolean, active: boolean): 'danger' | 'success' | 'neutral' {
  if (!active) return 'neutral'
  if (isFailing) return 'danger'
  return 'success'
}

function testStatusLabel(isFailing: boolean, active: boolean): string {
  if (!active) return 'Paused'
  if (isFailing) return 'Failing'
  return 'Passing'
}

function recipientKey(target: string, type: string, index: number): string {
  return `${type}-${target || 'blank'}-${index}`
}

function StatBlock({
  label,
  value,
  unit,
  sub,
  valueClass,
}: Readonly<{label: string; value: string; unit?: string; sub?: React.ReactNode; valueClass?: string}>) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-card px-3 py-2.5">
      <div className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className={cn('flex items-baseline gap-1 text-2xl font-bold leading-none tabular-nums', valueClass)}>
        {value}
        {unit && <span className="text-sm font-semibold text-muted-foreground">{unit}</span>}
      </div>
      {sub && <div className="text-xs text-muted-foreground">{sub}</div>}
    </div>
  )
}

function SyntheticTestDetail() {
  const {testId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const {data: test, isLoading} = useQuery({queryKey: ['synthetic-test', testId], queryFn: () => api.getSyntheticTest(testId)})
  const {data: summary} = useQuery({queryKey: ['synthetic-test-summary', testId], queryFn: () => api.getSyntheticTestSummary(testId)})
  const {data: resultsData} = useQuery({queryKey: ['synthetic-test-results', testId], queryFn: () => api.getSyntheticTestResults(testId, 200)})
  const {data: locSummaries} = useQuery({queryKey: ['synthetic-test-locsummary', testId], queryFn: () => api.getSyntheticLocationSummaries(testId)})
  const {data: locationsData} = useQuery({queryKey: ['synthetic-locations'], queryFn: () => api.listSyntheticLocations()})

  const results = useMemo(() => resultsData?.results ?? [], [resultsData])
  const locations = locationsData ?? []

  const runMutation = useMutation({
    mutationFn: () => api.runSyntheticTest(testId),
    onSuccess: () => {
      toast({title: 'Test run triggered'})
      queryClient.invalidateQueries({queryKey: ['synthetic-test-results', testId]})
      queryClient.invalidateQueries({queryKey: ['synthetic-test-summary', testId]})
    },
    onError: (e: Error) => toast({title: 'Failed to run test', description: e.message, variant: 'destructive'}),
  })

  const byLocation = useMemo(() => {
    const map = new Map<string, SyntheticResultResponse[]>()
    for (const r of results) {
      const loc = r.locationCode || r.probeDc || 'moneat'
      const arr = map.get(loc) ?? []
      arr.push(r)
      map.set(loc, arr)
    }
    return map
  }, [results])

  const chartData = useMemo(
    () =>
      results
        .slice(0, 100)
        .reverse()
        .map((r) => ({
          time: new Date(r.timestamp).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'}),
          duration: r.durationMs,
        })),
    [results]
  )

  const failingFromBanner = useMemo(() => {
    const latestByLoc = new Map<string, SyntheticResultResponse>()
    for (const r of results) {
      const loc = r.locationCode || r.probeDc || 'moneat'
      if (!latestByLoc.has(loc)) latestByLoc.set(loc, r)
    }
    const failing = [...latestByLoc.entries()].filter(([, r]) => r.status === 'failed')
    return failing
  }, [results])

  if (isLoading) {
    return <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">Loading…</div>
  }
  if (!test) {
    return (
      <div className="flex flex-col items-center gap-3 py-12 text-center">
        <p className="text-sm font-medium">Test not found</p>
        <Button variant="outline" size="sm" onClick={() => navigate({to: '/synthetics'})}>
          Back to tests
        </Button>
      </div>
    )
  }

  const isFailing = test.lastStatus === 'failed'
  const recipients = test.alertRecipients ?? []
  const cfg = test.alertConfig

  return (
    <div className="px-5 py-4">
      {/* Header */}
      <div className="mb-4 flex flex-wrap items-start gap-3">
        <Button size="icon" variant="outline" className="mt-0.5 h-7 w-7" onClick={() => navigate({to: '/synthetics'})}>
          <ArrowLeft className="h-3.5 w-3.5" />
        </Button>
          <div className="min-w-0">
            <h1 className="flex items-center gap-2 text-xl font-bold tracking-tight">
              {test.name}
            <Badge variant={testStatusVariant(isFailing, test.active)} size="sm">
              {testStatusLabel(isFailing, test.active)}
            </Badge>
          </h1>
          <div className="mt-1 flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
            <Badge variant="accent" size="sm" className="uppercase">
              {test.testType}
            </Badge>
            <span>{[test.service, test.environment].filter(Boolean).join(' · ')}</span>
            {(test.tags ?? []).map((t) => (
              <span key={t} className="rounded border bg-muted/50 px-1.5 py-0.5 text-[11px]">
                {t}
              </span>
            ))}
          </div>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <Button size="sm" variant="outline" className="h-7 gap-1.5" disabled={runMutation.isPending} onClick={() => runMutation.mutate()}>
            <Play className="h-3.5 w-3.5" />
            Run now
          </Button>
          <Button size="sm" variant="outline" className="h-7 gap-1.5" onClick={() => navigate({to: '/synthetics/$testId/edit', params: {testId}})}>
            <Pencil className="h-3.5 w-3.5" />
            Edit
          </Button>
        </div>
      </div>

      {/* Alert banner */}
      {isFailing && failingFromBanner.length > 0 && (
        <div className="mb-4 flex items-center gap-3 rounded-lg border border-danger-border bg-danger-bg px-3.5 py-2.5">
          <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-danger-solid text-white">
            <AlertTriangle className="h-3.5 w-3.5" />
          </span>
          <div className="min-w-0 flex-1 text-sm">
            <b>Failing from {failingFromBanner.map(([loc]) => locationMeta(loc, locations).name).join(', ')}</b>
            {failingFromBanner[0][1].errorMessage && (
              <span className="text-muted-foreground"> — {failingFromBanner[0][1].errorMessage}</span>
            )}
          </div>
          <Button
            size="sm"
            variant="destructive"
            className="h-7 gap-1"
            onClick={() => navigate({to: '/synthetics/$testId/results/$resultId', params: {testId, resultId: failingFromBanner[0][1].resultId}})}
          >
            View failing run
            <ChevronRight className="h-3 w-3" />
          </Button>
        </div>
      )}

      {/* Stat band */}
      <div className="grid grid-cols-2 gap-2.5 md:grid-cols-3 xl:grid-cols-6">
        <StatBlock
          label="Uptime · 30d"
          value={summary ? summary.uptimePercent.toFixed(1) : '—'}
          unit={summary ? '%' : undefined}
          valueClass={summary && summary.uptimePercent < 99 ? 'text-danger-fg' : undefined}
          sub="all locations"
        />
        <StatBlock label="Avg latency" value={summary ? String(Math.round(summary.avgResponseMs)) : '—'} unit="ms" sub={`${byLocation.size} locations`} />
        <StatBlock label="p95 latency" value={summary ? String(Math.round(summary.p95ResponseMs)) : '—'} unit="ms" />
        <StatBlock label="Total runs" value={summary ? String(summary.totalRuns) : '—'} sub="last 30 days" />
        <StatBlock label="Failures" value={summary ? String(summary.failureCount) : '—'} valueClass={summary && summary.failureCount > 0 ? 'text-danger-fg' : undefined} sub="last 30 days" />
        <StatBlock label="Locations" value={String(test.locations?.length ?? 0)} sub="configured" />
      </div>

      <div className="mt-4 grid grid-cols-1 gap-3.5 lg:grid-cols-[1fr_330px]">
        {/* Main column */}
        <div className="flex min-w-0 flex-col gap-3.5">
          {/* Availability by location */}
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Pin className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Availability by location</h3>
              <span className="ml-auto text-xs text-muted-foreground">last 24h</span>
            </div>
            <div className="p-3.5">
              {(locSummaries ?? []).length === 0 && <div className="py-4 text-center text-xs text-muted-foreground">No location data yet.</div>}
              {(locSummaries ?? []).map((ls) => {
                const meta = locationMeta(ls.locationCode, locations)
                const recent = (byLocation.get(ls.locationCode) ?? []).slice(0, 24).reverse().map((r) => r.status)
                const bad = ls.uptimePercent < 99
                return (
                  <div key={ls.locationCode} className="grid grid-cols-[150px_1fr_56px_56px] items-center gap-3.5 border-b border-border/40 py-2 last:border-b-0">
                    <div className="flex items-center gap-2 text-sm font-medium">
                      <span className="grid h-4.5 w-4.5 shrink-0 place-items-center rounded-full text-[8px] font-bold text-white" style={{backgroundColor: meta.color}}>
                        {meta.isPrivate ? <Server className="h-2.5 w-2.5" /> : meta.abbr}
                      </span>
                      <span className="truncate">{meta.name}</span>
                    </div>
                    <UptimeStrip statuses={recent} className="h-5" />
                    <span className={cn('text-right font-bold tabular-nums', bad ? 'text-danger-fg' : '')}>{ls.uptimePercent.toFixed(1)}%</span>
                    <span className="text-right text-xs tabular-nums text-muted-foreground">{Math.round(ls.p95ResponseMs)}ms</span>
                  </div>
                )
              })}
            </div>
          </div>

          {/* Response time chart */}
          {chartData.length > 0 && (
            <div className="rounded-lg border bg-card">
              <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
                <Activity className="h-3.5 w-3.5 text-muted-foreground" />
                <h3 className="text-sm font-semibold">Response time</h3>
                <span className="ml-auto text-xs text-muted-foreground">aggregated · all locations</span>
              </div>
              <div className="p-3.5">
                <div className="h-44">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={chartData}>
                      <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                      <XAxis dataKey="time" tick={{fontSize: 11}} className="text-muted-foreground" interval="preserveStartEnd" />
                      <YAxis tick={{fontSize: 11}} className="text-muted-foreground" tickFormatter={(v) => `${v}ms`} />
                      <Tooltip
                        contentStyle={{backgroundColor: 'hsl(var(--card))', border: '1px solid hsl(var(--border))', borderRadius: '8px', fontSize: 12}}
                        formatter={(value: unknown) => [`${Number(value) || 0}ms`, 'Duration']}
                      />
                      <Line type="monotone" dataKey="duration" stroke="hsl(var(--primary))" strokeWidth={2} dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </div>
          )}

          {/* Recent runs */}
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Clock className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Recent runs</h3>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/40 text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                    <th className="px-3.5 py-2 font-medium">Status</th>
                    <th className="px-3.5 py-2 font-medium">Location</th>
                    <th className="px-3.5 py-2 font-medium">Started</th>
                    <th className="px-3.5 py-2 text-right font-medium">Duration</th>
                    <th className="px-3.5 py-2 font-medium">Assertions</th>
                    <th className="w-8 px-3.5 py-2" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/40">
                  {results.slice(0, 30).map((r) => {
                    const meta = locationMeta(r.locationCode || r.probeDc || 'moneat', locations)
                    const passed = r.status === 'passed'
                    return (
                      <tr
                        key={r.resultId}
                        role="link"
                        tabIndex={0}
                        className="cursor-pointer transition-colors hover:bg-muted/40"
                        onClick={() => navigate({to: '/synthetics/$testId/results/$resultId', params: {testId, resultId: r.resultId}})}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            navigate({to: '/synthetics/$testId/results/$resultId', params: {testId, resultId: r.resultId}})
                          }
                        }}
                      >
                        <td className="px-3.5 py-1.5">
                          <span className={cn('inline-flex items-center gap-1.5 text-xs font-semibold', passed ? 'text-success-fg' : 'text-danger-fg')}>
                            <span className={cn('h-2 w-2 rounded-full', passed ? 'bg-success-solid' : 'bg-danger-solid')} />
                            {passed ? 'Passed' : 'Failed'}
                          </span>
                        </td>
                        <td className="px-3.5 py-1.5">
                          <span className="inline-flex items-center gap-1.5">
                            <span className="grid h-4 w-4 place-items-center rounded-full text-[8px] font-bold text-white" style={{backgroundColor: meta.color}}>
                              {meta.isPrivate ? <Server className="h-2 w-2" /> : meta.abbr}
                            </span>
                            {meta.name}
                          </span>
                        </td>
                        <td className="px-3.5 py-1.5 text-muted-foreground">{new Date(r.timestamp).toLocaleString()}</td>
                        <td className="px-3.5 py-1.5 text-right font-mono tabular-nums">{r.durationMs ? `${r.durationMs}ms` : '—'}</td>
                        <td className="px-3.5 py-1.5">
                          {r.assertionsTotal ? (
                            <Badge variant={r.assertionsFailed ? 'danger' : 'success'} size="sm">
                              {r.assertionsFailed ? `${r.assertionsFailed} / ${r.assertionsTotal} failed` : `${r.assertionsTotal} / ${r.assertionsTotal}`}
                            </Badge>
                          ) : (
                            <span className="text-xs text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="px-3.5 py-1.5">
                          <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
              {results.length === 0 && <div className="py-6 text-center text-xs text-muted-foreground">No runs yet.</div>}
            </div>
          </div>
        </div>

        {/* Side column */}
        <div className="flex flex-col gap-3.5">
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Sliders className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Configuration</h3>
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-3.5 gap-y-2 p-3.5 text-sm">
              {test.url && (
                <>
                  <dt className="text-muted-foreground">Endpoint</dt>
                  <dd className="truncate text-right font-mono text-xs">{test.method} {test.url}</dd>
                </>
              )}
              <dt className="text-muted-foreground">Interval</dt>
              <dd className="text-right">Every {Math.round(test.intervalSeconds / 60) || test.intervalSeconds / 60} min</dd>
              <dt className="text-muted-foreground">Timeout</dt>
              <dd className="text-right">{test.timeoutSeconds}s</dd>
              <dt className="text-muted-foreground">Retries</dt>
              <dd className="text-right">{test.retryCount ?? 0}×</dd>
              <dt className="text-muted-foreground">Assertions</dt>
              <dd className="text-right">{test.assertions?.length ?? 0}</dd>
              <dt className="text-muted-foreground">Locations</dt>
              <dd className="text-right">{test.locations?.length ?? 0}</dd>
            </dl>
          </div>

          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Bell className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Alerting</h3>
              {isFailing && (
                <Badge variant="danger" size="sm" className="ml-auto">
                  Triggered
                </Badge>
              )}
            </div>
            <div className="p-3.5">
              {cfg ? (
                <p className="text-sm leading-relaxed">
                  Alert when <b className="text-accent-subtle-fg">failing</b> for <b className="text-accent-subtle-fg">{cfg.consecutiveChecks}</b> checks from{' '}
                  <b className="text-accent-subtle-fg">{cfg.minLocations} of {cfg.totalLocations}</b> locations.
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">No alert condition configured.</p>
              )}
              {recipients.length > 0 && (
                <div className="mt-3 flex flex-col gap-1.5">
                  {recipients.map((r, i) => (
                    <div key={recipientKey(r.target, r.type, i)} className="flex items-center gap-2 border-b border-border/40 py-1.5 text-sm last:border-b-0">
                      <Bell className="h-3.5 w-3.5 text-muted-foreground" />
                      <span className="flex-1 font-medium">{r.target}</span>
                      <Badge variant="neutral" size="sm" className="capitalize">
                        {r.type}
                      </Badge>
                    </div>
                  ))}
                </div>
              )}
              <Button size="sm" variant="outline" className="mt-3 w-full gap-1.5" onClick={() => navigate({to: '/synthetics/$testId/edit', params: {testId}})}>
                <Zap className="h-3.5 w-3.5" />
                Edit alert conditions
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
