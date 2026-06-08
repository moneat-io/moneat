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

import {createFileRoute, Link} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type SyntheticResultResponse} from '@/lib/api'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {PageHeader} from '@/components/ui/page-header'
import {cn} from '@/lib/utils'
import {ArrowLeft, Play, Clock, Activity, AlertTriangle, CheckCircle2, FlaskConical, Settings, History, LineChart as LineChartIcon} from 'lucide-react'
import {useToast} from '@/hooks/useToast'
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'

export const Route = createFileRoute('/synthetics/$testId')({
  component: SyntheticTestDetail,
})

// Pass/fail/skip mapped onto the shared status language.
function statusVariant(status?: string | null): BadgeProps['variant'] {
  switch ((status ?? '').toLowerCase()) {
    case 'passed':
      return 'success'
    case 'failed':
      return 'danger'
    default:
      return 'neutral'
  }
}

function UptimeBar({results}: {results: SyntheticResultResponse[]}) {
  const recent = results.slice(0, 90).reverse()
  if (recent.length === 0) {
    return <div className="text-xs text-muted-foreground">No data yet</div>
  }
  return (
    <div className="flex gap-px items-end h-6">
      {recent.map((r, i) => (
        <div
          key={i}
          className={cn(
            'flex-1 min-w-[3px] max-w-[8px] rounded-sm h-full',
            r.status === 'passed' ? 'bg-success-solid' : 'bg-danger-solid'
          )}
          title={`${r.status} — ${r.durationMs}ms — ${r.timestamp}`}
        />
      ))}
    </div>
  )
}

function SyntheticTestDetail() {
  const {testId} = Route.useParams()
  const {toast} = useToast()
  const queryClient = useQueryClient()

  const {data: test, isLoading: testLoading} = useQuery({
    queryKey: ['synthetic-test', testId],
    queryFn: () => api.getSyntheticTest(testId),
  })

  const {data: summary} = useQuery({
    queryKey: ['synthetic-test-summary', testId],
    queryFn: () => api.getSyntheticTestSummary(testId),
  })

  const {data: resultsData, isLoading: resultsLoading} = useQuery({
    queryKey: ['synthetic-test-results', testId],
    queryFn: () => api.getSyntheticTestResults(testId, 200),
  })

  const runMutation = useMutation({
    mutationFn: () => api.runSyntheticTest(testId),
    onSuccess: () => {
      toast({title: 'Test run triggered'})
      queryClient.invalidateQueries({queryKey: ['synthetic-test', testId]})
      queryClient.invalidateQueries({queryKey: ['synthetic-test-results', testId]})
      queryClient.invalidateQueries({queryKey: ['synthetic-test-summary', testId]})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to run test', description: error.message, variant: 'destructive'})
    },
  })

  const results = resultsData?.results ?? []

  const chartData = results
    .slice(0, 100)
    .reverse()
    .map((r) => ({
      time: new Date(r.timestamp).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'}),
      duration: r.durationMs,
      status: r.status,
    }))

  if (testLoading || resultsLoading) {
    return (
      <div className="flex justify-center py-8">
        <div className="animate-spin rounded-full h-6 w-6 border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!test) {
    return (
      <EmptyState
        icon={FlaskConical}
        title="Test not found"
        description="This synthetic test may have been removed."
        action={
          <Button asChild variant="outline" size="sm">
            <Link to="/synthetics">Back to tests</Link>
          </Button>
        }
      />
    )
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <PageHeader
        title={
          <span className="flex items-center gap-2">
            <Button asChild variant="ghost" size="icon" className="h-7 w-7 -ml-1">
              <Link to="/synthetics">
                <ArrowLeft className="h-3.5 w-3.5" />
              </Link>
            </Button>
            {test.name}
          </span>
        }
        description={
          <span className="flex flex-wrap items-center gap-1.5">
            <Badge variant="neutral" size="sm" className="uppercase">{test.testType}</Badge>
            <Badge variant={statusVariant(test.lastStatus)} size="sm">
              {test.lastStatus || 'pending'}
            </Badge>
            {test.tags && test.tags.length > 0 && test.tags.map((tag) => (
              <Badge key={tag} variant="neutral" size="sm">{tag}</Badge>
            ))}
          </span>
        }
        actions={
          <Button size="sm" onClick={() => runMutation.mutate()} disabled={runMutation.isPending}>
            <Play className="h-4 w-4 mr-1" />Run now
          </Button>
        }
      />

      {/* Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard
          label="Uptime"
          value={summary ? `${summary.uptimePercent.toFixed(1)}%` : '—'}
          icon={CheckCircle2}
          tone="success"
        />
        <StatCard
          label="Avg response"
          value={summary ? `${Math.round(summary.avgResponseMs)}` : '—'}
          unit={summary ? 'ms' : undefined}
          icon={Clock}
          tone="info"
        />
        <StatCard
          label="P95 response"
          value={summary ? `${Math.round(summary.p95ResponseMs)}` : '—'}
          unit={summary ? 'ms' : undefined}
          icon={Activity}
          tone="accent"
        />
        <StatCard
          label="Failures"
          value={summary ? summary.failureCount : '—'}
          icon={AlertTriangle}
          tone={summary && summary.failureCount > 0 ? 'danger' : 'neutral'}
        />
      </div>

      {/* Uptime Bar */}
      <SectionCard title="Uptime history" icon={Activity} iconTone="muted">
          <UptimeBar results={results} />
          <div className="flex justify-between text-[11px] text-muted-foreground mt-1.5">
            <span>Older</span>
            <span>Recent</span>
          </div>
      </SectionCard>

      {/* Response Time Chart */}
      {chartData.length > 0 && (
        <SectionCard title="Response time" icon={LineChartIcon} iconTone="accent">
            <div className="h-48">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                  <XAxis
                    dataKey="time"
                    tick={{fontSize: 11}}
                    className="text-muted-foreground"
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{fontSize: 11}}
                    className="text-muted-foreground"
                    tickFormatter={(v) => `${v}ms`}
                  />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: 'hsl(var(--card))',
                      border: '1px solid hsl(var(--border))',
                      borderRadius: '8px',
                      fontSize: 12,
                    }}
                    formatter={(value: unknown) => [`${Number(value) || 0}ms`, 'Duration']}
                  />
                  <Line
                    type="monotone"
                    dataKey="duration"
                    stroke="hsl(var(--primary))"
                    strokeWidth={2}
                    dot={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
        </SectionCard>
      )}

      {/* Test Configuration */}
      <SectionCard title="Configuration" icon={Settings} iconTone="muted">
          <div className="grid grid-cols-2 gap-2 text-sm">
            {test.url && (
              <div>
                <span className="text-muted-foreground">URL</span>
                <p className="font-mono text-xs mt-0.5 break-all">{test.method} {test.url}</p>
              </div>
            )}
            <div>
              <span className="text-muted-foreground">Interval</span>
              <p className="mt-0.5">Every {Math.round(test.intervalSeconds / 60)} min</p>
            </div>
            <div>
              <span className="text-muted-foreground">Timeout</span>
              <p className="mt-0.5">{test.timeoutSeconds}s</p>
            </div>
            {(test.retryCount ?? 0) > 0 && (
              <div>
                <span className="text-muted-foreground">Retries</span>
                <p className="mt-0.5">
                  {test.retryCount}x{test.retryIntervalMs != null ? ` every ${test.retryIntervalMs}ms` : ''}
                </p>
              </div>
            )}
            {test.alertOnFailure && (
              <div>
                <span className="text-muted-foreground">Alerts</span>
                <p className="mt-0.5">Enabled on failure</p>
              </div>
            )}
          </div>
      </SectionCard>

      {/* Results History */}
      <SectionCard title="Results history" icon={History} iconTone="muted" flushBody>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b bg-muted/40 text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-4 py-2 font-medium">Status</th>
                  <th className="px-4 py-2 font-medium">Duration</th>
                  <th className="px-4 py-2 font-medium">Error</th>
                  <th className="px-4 py-2 font-medium">Time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/40">
                {results.slice(0, 50).map((r) => (
                  <tr key={r.resultId} className="transition-colors hover:bg-accent/40">
                    <td className="px-4 py-1.5">
                      <Badge variant={statusVariant(r.status)} size="sm">
                        {r.status}
                      </Badge>
                    </td>
                    <td className="px-4 py-1.5 tabular-nums">{r.durationMs}ms</td>
                    <td className="px-4 py-1.5 text-muted-foreground max-w-xs truncate">
                      {r.errorMessage || '—'}
                    </td>
                    <td className="px-4 py-1.5 tabular-nums text-muted-foreground">
                      {new Date(r.timestamp).toLocaleString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {results.length === 0 && (
              <div className="py-4">
                <EmptyState
                  icon={History}
                  title="No results yet"
                  description="Run the test to see results here."
                />
              </div>
            )}
          </div>
      </SectionCard>
    </div>
  )
}
