import { createFileRoute, redirect, useNavigate } from '@tanstack/react-router'
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
import { EventsChart } from '@/components/charts/events-chart'
import { BarChart } from '@/components/charts/bar-chart'
import { StatsCard } from '@/components/charts/stats-card'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Activity, ArrowUpDown, Gauge, Timer } from 'lucide-react'
import { Button } from '@/components/ui/button'

type SortKey = 'name' | 'op' | 'tpm' | 'p50' | 'p75' | 'p95' | 'failureRate'

export const Route = createFileRoute('/performance')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }

    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      console.error('Failed to fetch user:', error)
    }
  },
  component: PerformancePage,
})

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(1)}ms`
}

function formatRate(value: number) {
  return `${value.toFixed(2)}%`
}

function PerformancePage() {
  const navigate = useNavigate()
  const { selectedProjectId } = useProject()
  const [period, setPeriod] = useState<'24h' | '7d' | '30d'>('7d')
  const [environment, setEnvironment] = useState('all')
  const [operation, setOperation] = useState('all')
  const [sortKey, setSortKey] = useState<SortKey>('p95')
  const [sortAsc, setSortAsc] = useState(false)

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  const filters = {
    period,
    environment: environment === 'all' ? undefined : environment,
    operation: operation === 'all' ? undefined : operation,
  } as const

  const { data: transactions = [], isLoading } = useQuery({
    queryKey: ['transactions', projectId, period, environment, operation],
    queryFn: () => (projectId ? api.getTransactions(projectId, filters) : []),
    enabled: !!projectId,
  })

  const { data: stats } = useQuery({
    queryKey: ['performance-stats', projectId, period, environment, operation],
    queryFn: () => (projectId ? api.getPerformanceStats(projectId, filters) : null),
    enabled: !!projectId,
  })

  const operationOptions = useMemo(() => {
    return Array.from(new Set(transactions.map((tx) => tx.op).filter(Boolean))).sort()
  }, [transactions])

  const sortedTransactions = useMemo(() => {
    const sorted = [...transactions].sort((a, b) => {
      const left = a[sortKey]
      const right = b[sortKey]
      if (typeof left === 'number' && typeof right === 'number') {
        return sortAsc ? left - right : right - left
      }
      const l = String(left || '')
      const r = String(right || '')
      return sortAsc ? l.localeCompare(r) : r.localeCompare(l)
    })
    return sorted
  }, [transactions, sortAsc, sortKey])

  const durationDistribution = useMemo(() => {
    const buckets: Record<string, number> = {
      '<100ms': 0,
      '100-300ms': 0,
      '300-1000ms': 0,
      '1s+': 0,
    }
    for (const transaction of transactions) {
      const duration = transaction.p95
      if (duration < 100) buckets['<100ms'] += 1
      else if (duration < 300) buckets['100-300ms'] += 1
      else if (duration < 1000) buckets['300-1000ms'] += 1
      else buckets['1s+'] += 1
    }
    return buckets
  }, [transactions])

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortAsc((current) => !current)
      return
    }
    setSortKey(key)
    setSortAsc(false)
  }

  if (!projects || projects.length === 0) {
    return (
      <div className="min-h-screen bg-background p-6">
        <Card className="p-12 text-center">
          <p className="text-muted-foreground">No projects yet. Create a project to view performance data.</p>
        </Card>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background">
      <div className="mx-auto max-w-7xl p-6">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-2xl font-bold">Performance</h2>
          <div className="flex flex-wrap items-center gap-2">
            <Select value={period} onValueChange={(value) => setPeriod(value as '24h' | '7d' | '30d')}>
              <SelectTrigger className="w-[140px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="24h">Last 24h</SelectItem>
                <SelectItem value="7d">Last 7d</SelectItem>
                <SelectItem value="30d">Last 30d</SelectItem>
              </SelectContent>
            </Select>

            <Select value={environment} onValueChange={setEnvironment}>
              <SelectTrigger className="w-[150px]">
                <SelectValue placeholder="Environment" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Environments</SelectItem>
                <SelectItem value="production">Production</SelectItem>
                <SelectItem value="staging">Staging</SelectItem>
                <SelectItem value="development">Development</SelectItem>
              </SelectContent>
            </Select>

            <Select value={operation} onValueChange={setOperation}>
              <SelectTrigger className="w-[170px]">
                <SelectValue placeholder="Operation" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Operations</SelectItem>
                {operationOptions.map((option) => (
                  <SelectItem key={option} value={option}>
                    {option}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        {isLoading ? (
          <div className="p-8 text-center">Loading performance data...</div>
        ) : (
          <div className="space-y-6">
            {stats && (
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
                <StatsCard
                  title="Apdex"
                  value={stats.apdex.toFixed(3)}
                  icon={Gauge}
                  accent="blue"
                />
                <StatsCard
                  title="Transactions"
                  value={stats.totalTransactions.toLocaleString()}
                  icon={Activity}
                  accent="emerald"
                />
                <StatsCard
                  title="Avg Duration"
                  value={formatDuration(stats.avgDuration)}
                  icon={Timer}
                  accent="amber"
                />
                <StatsCard
                  title="Groups"
                  value={transactions.length.toLocaleString()}
                  icon={Activity}
                  accent="violet"
                />
              </div>
            )}

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
              {stats && (
                <EventsChart
                  data={stats.throughput}
                  title="Transaction Throughput"
                  height={320}
                />
              )}
              <BarChart
                data={durationDistribution}
                title="P95 Duration Distribution"
                color="hsl(220, 80%, 60%)"
              />
            </div>

            {stats && stats.slowestTransactions.length > 0 && (
              <Card>
                <CardHeader>
                  <CardTitle>Slowest Transactions</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="space-y-2">
                    {stats.slowestTransactions.map((tx) => (
                      <button
                        key={tx.eventId}
                        type="button"
                        onClick={() =>
                          navigate({
                            to: '/performance/$transactionId',
                            params: { transactionId: tx.eventId },
                          })
                        }
                        className="flex w-full items-center justify-between rounded-lg border p-3 text-left transition-colors hover:bg-accent"
                      >
                        <div>
                          <div className="font-medium">{tx.name}</div>
                          <div className="text-xs text-muted-foreground">{tx.op}</div>
                        </div>
                        <div className="font-semibold">{formatDuration(tx.duration)}</div>
                      </button>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}

            <Card>
              <CardHeader>
                <CardTitle>Transaction Groups</CardTitle>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>
                        <Button variant="ghost" size="sm" onClick={() => handleSort('name')} className="px-0">
                          Name
                          <ArrowUpDown className="ml-1 h-3.5 w-3.5" />
                        </Button>
                      </TableHead>
                      <TableHead>
                        <Button variant="ghost" size="sm" onClick={() => handleSort('op')} className="px-0">
                          Operation
                          <ArrowUpDown className="ml-1 h-3.5 w-3.5" />
                        </Button>
                      </TableHead>
                      <TableHead className="text-right">
                        <Button variant="ghost" size="sm" onClick={() => handleSort('tpm')} className="px-0">
                          TPM
                          <ArrowUpDown className="ml-1 h-3.5 w-3.5" />
                        </Button>
                      </TableHead>
                      <TableHead className="text-right">p50</TableHead>
                      <TableHead className="text-right">p75</TableHead>
                      <TableHead className="text-right">
                        <Button variant="ghost" size="sm" onClick={() => handleSort('p95')} className="px-0">
                          p95
                          <ArrowUpDown className="ml-1 h-3.5 w-3.5" />
                        </Button>
                      </TableHead>
                      <TableHead className="text-right">
                        <Button variant="ghost" size="sm" onClick={() => handleSort('failureRate')} className="px-0">
                          Failure
                          <ArrowUpDown className="ml-1 h-3.5 w-3.5" />
                        </Button>
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {sortedTransactions.map((transaction) => (
                      <TableRow
                        key={`${transaction.name}-${transaction.op}`}
                        className={transaction.latestEventId ? 'cursor-pointer' : ''}
                        onClick={() => {
                          if (!transaction.latestEventId) return
                          navigate({
                            to: '/performance/$transactionId',
                            params: { transactionId: transaction.latestEventId },
                          })
                        }}
                      >
                        <TableCell className="font-medium">{transaction.name || '(unnamed)'}</TableCell>
                        <TableCell>{transaction.op || '-'}</TableCell>
                        <TableCell className="text-right">{transaction.tpm.toFixed(2)}</TableCell>
                        <TableCell className="text-right">{formatDuration(transaction.p50)}</TableCell>
                        <TableCell className="text-right">{formatDuration(transaction.p75)}</TableCell>
                        <TableCell className="text-right">{formatDuration(transaction.p95)}</TableCell>
                        <TableCell className="text-right">{formatRate(transaction.failureRate)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          </div>
        )}
      </div>
    </div>
  )
}
