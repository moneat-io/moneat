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
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import {ArrowLeft, Play, Clock, Activity, AlertTriangle, CheckCircle2} from 'lucide-react'
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

const statusColors: Record<string, string> = {
  passed: 'bg-green-500/15 text-green-500 border-green-500/30',
  failed: 'bg-red-500/15 text-red-500 border-red-500/30',
  skipped: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
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
            r.status === 'passed' ? 'bg-green-500' : 'bg-red-500'
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
      <div className="text-center py-8">
        <p className="text-muted-foreground">Test not found</p>
        <Link to="/synthetics" className="text-primary hover:underline mt-2 inline-block">
          Back to tests
        </Link>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Link to="/synthetics">
            <Button variant="ghost" size="icon" className="h-7 w-7">
              <ArrowLeft className="h-3.5 w-3.5" />
            </Button>
          </Link>
          <div>
            <h2 className="text-lg font-bold">{test.name}</h2>
            <div className="flex items-center gap-1.5 mt-0.5">
              <Badge variant="outline" className="text-[10px]">{test.testType}</Badge>
              <Badge
                variant="outline"
                className={cn('text-[10px]', test.lastStatus === 'passed'
                  ? 'bg-green-500/15 text-green-500' : test.lastStatus === 'failed'
                    ? 'bg-red-500/15 text-red-500' : 'bg-slate-500/15 text-slate-400')}
              >
                {test.lastStatus || 'pending'}
              </Badge>
              {test.tags && test.tags.length > 0 && test.tags.map((tag) => (
                <Badge key={tag} variant="secondary" className="text-[10px]">{tag}</Badge>
              ))}
            </div>
          </div>
        </div>
        <Button size="sm" className="h-7 text-xs" onClick={() => runMutation.mutate()} disabled={runMutation.isPending}>
          <Play className="h-3 w-3 mr-1" />Run Now
        </Button>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
        <Card>
          <CardContent className="pt-3 pb-2 px-3">
            <div className="flex items-center gap-1.5 text-muted-foreground text-[11px] mb-0.5">
              <CheckCircle2 className="h-3 w-3" />Uptime
            </div>
            <div className="text-lg font-bold">
              {summary ? `${summary.uptimePercent.toFixed(1)}%` : '—'}
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-3 pb-2 px-3">
            <div className="flex items-center gap-1.5 text-muted-foreground text-[11px] mb-0.5">
              <Clock className="h-3 w-3" />Avg Response
            </div>
            <div className="text-lg font-bold">
              {summary ? `${Math.round(summary.avgResponseMs)}ms` : '—'}
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-3 pb-2 px-3">
            <div className="flex items-center gap-1.5 text-muted-foreground text-[11px] mb-0.5">
              <Activity className="h-3 w-3" />P95 Response
            </div>
            <div className="text-lg font-bold">
              {summary ? `${Math.round(summary.p95ResponseMs)}ms` : '—'}
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-3 pb-2 px-3">
            <div className="flex items-center gap-1.5 text-muted-foreground text-[11px] mb-0.5">
              <AlertTriangle className="h-3 w-3" />Failures
            </div>
            <div className="text-lg font-bold">
              {summary ? summary.failureCount : '—'}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Uptime Bar */}
      <Card>
        <CardHeader className="py-2 px-3"><CardTitle className="text-xs">Uptime History</CardTitle></CardHeader>
        <CardContent className="px-3 pb-3 pt-0">
          <UptimeBar results={results} />
          <div className="flex justify-between text-[11px] text-muted-foreground mt-1.5">
            <span>Older</span>
            <span>Recent</span>
          </div>
        </CardContent>
      </Card>

      {/* Response Time Chart */}
      {chartData.length > 0 && (
        <Card>
          <CardHeader className="py-2 px-3"><CardTitle className="text-xs">Response Time</CardTitle></CardHeader>
          <CardContent className="px-3 pb-3 pt-0">
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
                    formatter={(value: number) => [`${value}ms`, 'Duration']}
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
          </CardContent>
        </Card>
      )}

      {/* Test Configuration */}
      <Card>
        <CardHeader className="py-2 px-3"><CardTitle className="text-xs">Configuration</CardTitle></CardHeader>
        <CardContent className="px-3 pb-3 pt-0">
          <div className="grid grid-cols-2 gap-2 text-xs">
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
        </CardContent>
      </Card>

      {/* Results History */}
      <Card>
        <CardHeader className="py-2 px-3"><CardTitle className="text-xs">Results History</CardTitle></CardHeader>
        <CardContent className="px-3 pb-3 pt-0">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b text-left text-muted-foreground">
                  <th className="pb-1.5 pr-2 font-medium">Status</th>
                  <th className="pb-1.5 pr-2 font-medium">Duration</th>
                  <th className="pb-1.5 pr-2 font-medium">Error</th>
                  <th className="pb-1.5 font-medium">Time</th>
                </tr>
              </thead>
              <tbody>
                {results.slice(0, 50).map((r) => (
                  <tr key={r.resultId} className="border-b last:border-0 hover:bg-muted/30">
                    <td className="py-1.5 pr-2">
                      <Badge variant="outline" className={cn('text-[10px]', statusColors[r.status] || '')}>
                        {r.status}
                      </Badge>
                    </td>
                    <td className="py-1.5 pr-2">{r.durationMs}ms</td>
                    <td className="py-1.5 pr-2 text-muted-foreground max-w-xs truncate">
                      {r.errorMessage || '—'}
                    </td>
                    <td className="py-1.5 text-muted-foreground">
                      {new Date(r.timestamp).toLocaleString()}
                    </td>
                  </tr>
                ))}
                {results.length === 0 && (
                  <tr>
                    <td colSpan={4} className="py-6 text-center text-muted-foreground">
                      No results yet. Run the test to see results.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
