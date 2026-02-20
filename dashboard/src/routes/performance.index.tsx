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
import {useMemo, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {EventsChart} from '@/components/charts/events-chart'
import {BarChart} from '@/components/charts/bar-chart'
import {StatsCard} from '@/components/charts/stats-card'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow,} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'
import {Activity, AlertTriangle, ArrowUpDown, ChevronLeft, ChevronRight, Clock, Gauge, Hash, Timer, TrendingUp, Zap} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'

type SortKey = 'name' | 'op' | 'tpm' | 'p50' | 'p75' | 'p95' | 'failureRate'

export const Route = createFileRoute('/performance/')({
  component: PerformancePage,
})

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

function formatRate(value: number) {
  return `${value.toFixed(1)}%`
}

/** Returns a Tailwind color class based on duration severity */
function durationColor(ms: number) {
  if (ms < 300) return 'text-emerald-600 dark:text-emerald-400'
  if (ms < 1000) return 'text-amber-600 dark:text-amber-400'
  return 'text-rose-600 dark:text-rose-400'
}

/** Returns a Tailwind background class for a mini duration bar */
function durationBarColor(ms: number) {
  if (ms < 300) return 'bg-emerald-500'
  if (ms < 1000) return 'bg-amber-500'
  return 'bg-rose-500'
}

/** Returns Tailwind color class based on failure rate severity */
function failureRateColor(rate: number) {
  if (rate < 1) return 'text-emerald-600 dark:text-emerald-400'
  if (rate < 5) return 'text-amber-600 dark:text-amber-400'
  return 'text-rose-600 dark:text-rose-400'
}

/** Returns Tailwind color class based on Apdex score */
function apdexColor(score: number) {
  if (score >= 0.94) return 'text-emerald-600 dark:text-emerald-400'
  if (score >= 0.85) return 'text-blue-600 dark:text-blue-400'
  if (score >= 0.7) return 'text-amber-600 dark:text-amber-400'
  return 'text-rose-600 dark:text-rose-400'
}

function apdexLabel(score: number) {
  if (score >= 0.94) return 'Excellent'
  if (score >= 0.85) return 'Good'
  if (score >= 0.7) return 'Fair'
  if (score >= 0.5) return 'Poor'
  return 'Unacceptable'
}

/** Color for operation badge */
const OP_COLORS: Record<string, string> = {
  'http.server': 'bg-blue-500/15 text-blue-700 dark:text-blue-300 border-blue-500/20',
  'http.client': 'bg-sky-500/15 text-sky-700 dark:text-sky-300 border-sky-500/20',
  'db': 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/20',
  'db.query': 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/20',
  'db.sql.query': 'bg-violet-500/15 text-violet-700 dark:text-violet-300 border-violet-500/20',
  'navigation': 'bg-cyan-500/15 text-cyan-700 dark:text-cyan-300 border-cyan-500/20',
  'pageload': 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/20',
  'task': 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
  'queue.task': 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/20',
  'function': 'bg-pink-500/15 text-pink-700 dark:text-pink-300 border-pink-500/20',
}

function getOpColor(op: string) {
  return OP_COLORS[op] || 'bg-muted text-muted-foreground border-border'
}

function PerformancePage() {
  const navigate = useNavigate()
  const { selectedProjectId } = useProject()
  const [period, setPeriod] = useState<'24h' | '7d' | '30d' | '90d'>('7d')
  const [environment, setEnvironment] = useState('all')
  const [operation, setOperation] = useState('all')
  const [sortKey, setSortKey] = useState<SortKey>('p95')
  const [sortAsc, setSortAsc] = useState(false)
  const [currentPage, setCurrentPage] = useState(1)
  const pageSize = 20

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id
  const { data: billingUsage } = useQuery({
    queryKey: ['billing-usage'],
    queryFn: () => api.getBillingUsage(),
  })
  const retentionDays = billingUsage?.retentionDays ?? 30
  const availablePeriods = useMemo(() => {
    const options = [
      { value: '24h', label: 'Last 24h', minDays: 1 },
      { value: '7d', label: 'Last 7d', minDays: 7 },
      { value: '30d', label: 'Last 30d', minDays: 30 },
      { value: '90d', label: 'Last 90d', minDays: 90 },
    ] as const
    const filtered = options.filter((option) => retentionDays >= option.minDays)
    return filtered.length > 0 ? filtered : [options[0]]
  }, [retentionDays])

  const effectivePeriod = (availablePeriods.some((option) => option.value === period)
    ? period
    : availablePeriods[availablePeriods.length - 1]?.value ?? '7d') as '24h' | '7d' | '30d' | '90d'

  const filters = {
    period: effectivePeriod,
    environment: environment === 'all' ? undefined : environment,
    operation: operation === 'all' ? undefined : operation,
  } as const

  const { data: transactions = [], isLoading } = useQuery({
    queryKey: ['transactions', projectId, effectivePeriod, environment, operation],
    queryFn: () => (projectId ? api.getTransactions(projectId, filters) : []),
    enabled: !!projectId,
  })

  const { data: stats } = useQuery({
    queryKey: ['performance-stats', projectId, effectivePeriod, environment, operation],
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

  // Paginate sorted transactions
  const paginatedTransactions = useMemo(() => {
    const startIndex = (currentPage - 1) * pageSize
    const endIndex = startIndex + pageSize
    return sortedTransactions.slice(startIndex, endIndex)
  }, [sortedTransactions, currentPage, pageSize])

  const totalPages = Math.ceil(sortedTransactions.length / pageSize)
  const startIndex = sortedTransactions.length === 0 ? 0 : (currentPage - 1) * pageSize + 1
  const endIndex = Math.min(currentPage * pageSize, sortedTransactions.length)

  const durationDistribution = useMemo(() => {
    const buckets: Record<string, number> = {
      '<100ms': 0,
      '100-300ms': 0,
      '300ms-1s': 0,
      '1s+': 0,
    }
    for (const transaction of transactions) {
      const duration = transaction.p95
      if (duration < 100) buckets['<100ms'] += 1
      else if (duration < 300) buckets['100-300ms'] += 1
      else if (duration < 1000) buckets['300ms-1s'] += 1
      else buckets['1s+'] += 1
    }
    return buckets
  }, [transactions])

  // Derive extra aggregated stats
  const derivedStats = useMemo(() => {
    if (transactions.length === 0) return null
    const failureRates = transactions.map((t) => t.failureRate)
    const avgFailureRate = failureRates.reduce((s, v) => s + v, 0) / failureRates.length
    const p95Values = transactions.map((t) => t.p95)
    const maxP95 = Math.max(...p95Values)
    const medianP95 = [...p95Values].sort((a, b) => a - b)[Math.floor(p95Values.length / 2)] ?? 0
    const totalTpm = transactions.reduce((s, t) => s + t.tpm, 0)
    return { avgFailureRate, maxP95, medianP95, totalTpm }
  }, [transactions])

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortAsc((current) => !current)
      return
    }
    setSortKey(key)
    setSortAsc(false)
  }

  // Reset to page 1 when filters change
  if (!projects || projects.length === 0) {
    return (
      <div className="p-6">
        <Card className="p-12 text-center">
          <p className="text-muted-foreground">No projects yet. Create a project to view performance data.</p>
        </Card>
      </div>
    )
  }

  return (
    <TooltipProvider>
      <div>
        <div className="px-4 py-4 sm:px-6 lg:px-8">
          {/* Compact header */}
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-xl font-bold">Performance</h2>
            <div className="flex flex-wrap items-center gap-1.5">
              <Select value={effectivePeriod} onValueChange={(value) => { setPeriod(value as '24h' | '7d' | '30d' | '90d'); setCurrentPage(1) }}>
                <SelectTrigger className="h-8 w-[110px] text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {availablePeriods.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <Select value={environment} onValueChange={(value) => { setEnvironment(value); setCurrentPage(1) }}>
                <SelectTrigger className="h-8 w-[130px] text-xs">
                  <SelectValue placeholder="Environment" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Envs</SelectItem>
                  <SelectItem value="production">Production</SelectItem>
                  <SelectItem value="staging">Staging</SelectItem>
                  <SelectItem value="development">Development</SelectItem>
                </SelectContent>
              </Select>

              <Select value={operation} onValueChange={(value) => { setOperation(value); setCurrentPage(1) }}>
                <SelectTrigger className="h-8 w-[140px] text-xs">
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
            <div className="p-8 text-center text-muted-foreground">Loading performance data...</div>
          ) : (
            <div className="space-y-4">
              {/* Stats row - 6 cards */}
              {stats && (
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
                  <StatsCard
                    title="Apdex Score"
                    value={stats.apdex.toFixed(3)}
                    subtitle={apdexLabel(stats.apdex)}
                    icon={Gauge}
                    accent="blue"
                    valueColor={apdexColor(stats.apdex)}
                  />
                  <StatsCard
                    title="Total Transactions"
                    value={stats.totalTransactions.toLocaleString()}
                    icon={Activity}
                    accent="emerald"
                  />
                  <StatsCard
                    title="Avg Duration"
                    value={formatDuration(stats.avgDuration)}
                    icon={Timer}
                    accent="amber"
                    valueColor={durationColor(stats.avgDuration)}
                  />
                  <StatsCard
                    title="Failure Rate"
                    value={derivedStats ? formatRate(derivedStats.avgFailureRate) : '0%'}
                    icon={AlertTriangle}
                    accent="rose"
                    valueColor={derivedStats ? failureRateColor(derivedStats.avgFailureRate) : undefined}
                  />
                  <StatsCard
                    title="Throughput"
                    value={derivedStats ? `${derivedStats.totalTpm.toFixed(1)} tpm` : '0 tpm'}
                    icon={Zap}
                    accent="cyan"
                  />
                  <StatsCard
                    title="Transaction Groups"
                    value={transactions.length.toLocaleString()}
                    icon={Hash}
                    accent="violet"
                  />
                </div>
              )}

              {/* Charts + Slowest Transactions in a 3-column grid */}
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
                {stats && (
                  <div className="lg:col-span-1">
                    <EventsChart
                      data={stats.throughput}
                      title="Throughput"
                      height={240}
                    />
                  </div>
                )}
                <div className="lg:col-span-1">
                  <BarChart
                    data={durationDistribution}
                    title="P95 Distribution"
                    color="hsl(220, 80%, 60%)"
                    height={240}
                  />
                </div>
                {stats && stats.slowestTransactions.length > 0 && (
                  <div className="lg:col-span-1">
                    <Card className="h-full border-t-4 border-t-rose-500/50">
                      <CardHeader className="px-4 py-3">
                        <CardTitle className="flex items-center gap-2 text-sm">
                          <Clock className="h-4 w-4 text-rose-500" />
                          Slowest Transactions
                        </CardTitle>
                      </CardHeader>
                      <CardContent className="px-4 pb-3 pt-0">
                        <div className="space-y-1">
                          {stats.slowestTransactions.slice(0, 6).map((tx) => (
                            <button
                              key={tx.eventId}
                              type="button"
                              onClick={() =>
                                navigate({
                                  to: '/performance/$transactionId',
                                  params: { transactionId: tx.eventId },
                                })
                              }
                              className="group flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left transition-colors hover:bg-accent"
                            >
                              <div className="min-w-0 flex-1 mr-2">
                                <div className="text-sm font-medium truncate group-hover:text-foreground">{tx.name}</div>
                                <div className="text-[11px] text-muted-foreground">{tx.op}</div>
                              </div>
                              <span className={cn('text-sm font-semibold tabular-nums whitespace-nowrap', durationColor(tx.duration))}>
                                {formatDuration(tx.duration)}
                              </span>
                            </button>
                          ))}
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                )}
              </div>

              {/* Transaction Groups table */}
              <Card>
                <CardHeader className="px-4 py-3 sm:px-6">
                  <div className="flex items-center justify-between">
                    <CardTitle className="flex items-center gap-2 text-sm">
                      <TrendingUp className="h-4 w-4 text-muted-foreground" />
                      Transaction Groups
                    </CardTitle>
                    <span className="text-xs text-muted-foreground">
                      {transactions.length === 0 ? '0 groups' : `${startIndex}-${endIndex} of ${transactions.length} group${transactions.length !== 1 ? 's' : ''}`}
                    </span>
                  </div>
                </CardHeader>
                <CardContent className="px-0 pb-0 pt-0">
                  <div className="overflow-x-auto">
                    <Table>
                      <TableHeader>
                        <TableRow className="hover:bg-transparent">
                          <TableHead className="pl-4 sm:pl-6">
                            <SortButton active={sortKey === 'name'} asc={sortAsc} onClick={() => handleSort('name')}>
                              Name
                            </SortButton>
                          </TableHead>
                          <TableHead className="hidden sm:table-cell">
                            <SortButton active={sortKey === 'op'} asc={sortAsc} onClick={() => handleSort('op')}>
                              Operation
                            </SortButton>
                          </TableHead>
                          <TableHead className="text-right">
                            <SortButton active={sortKey === 'tpm'} asc={sortAsc} onClick={() => handleSort('tpm')}>
                              TPM
                            </SortButton>
                          </TableHead>
                          <TableHead className="text-right hidden md:table-cell">
                            <SortButton active={sortKey === 'p50'} asc={sortAsc} onClick={() => handleSort('p50')}>
                              p50
                            </SortButton>
                          </TableHead>
                          <TableHead className="text-right hidden lg:table-cell">
                            <SortButton active={sortKey === 'p75'} asc={sortAsc} onClick={() => handleSort('p75')}>
                              p75
                            </SortButton>
                          </TableHead>
                          <TableHead className="text-right">
                            <SortButton active={sortKey === 'p95'} asc={sortAsc} onClick={() => handleSort('p95')}>
                              p95
                            </SortButton>
                          </TableHead>
                          <TableHead className="hidden xl:table-cell text-right">
                            Duration
                          </TableHead>
                          <TableHead className="text-right pr-4 sm:pr-6">
                            <SortButton active={sortKey === 'failureRate'} asc={sortAsc} onClick={() => handleSort('failureRate')}>
                              Failure
                            </SortButton>
                          </TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {paginatedTransactions.length === 0 ? (
                          <TableRow>
                            <TableCell colSpan={8} className="py-8 text-center text-muted-foreground">
                              No transaction data for this period
                            </TableCell>
                          </TableRow>
                        ) : (
                          paginatedTransactions.map((transaction) => {
                            const maxP95 = derivedStats?.maxP95 || 1
                            const barWidth = Math.min(100, (transaction.p95 / maxP95) * 100)
                            return (
                              <TableRow
                                key={`${transaction.name}-${transaction.op}`}
                                className={cn(
                                  'group transition-colors',
                                  transaction.latestEventId ? 'cursor-pointer hover:bg-accent/50' : ''
                                )}
                                onClick={() => {
                                  if (!transaction.latestEventId) return
                                  navigate({
                                    to: '/performance/$transactionId',
                                    params: { transactionId: transaction.latestEventId },
                                  })
                                }}
                              >
                                <TableCell className="pl-4 sm:pl-6 max-w-[200px] lg:max-w-[300px]">
                                  <div className="truncate font-medium text-sm">
                                    {transaction.name || '(unnamed)'}
                                  </div>
                                  {/* Show op as badge on mobile where column is hidden */}
                                  <div className="sm:hidden mt-0.5">
                                    <Badge
                                      variant="outline"
                                      className={cn('text-[10px] px-1.5 py-0 font-normal', getOpColor(transaction.op))}
                                    >
                                      {transaction.op || '-'}
                                    </Badge>
                                  </div>
                                </TableCell>
                                <TableCell className="hidden sm:table-cell">
                                  <Badge
                                    variant="outline"
                                    className={cn('text-[11px] px-1.5 py-0 font-normal', getOpColor(transaction.op))}
                                  >
                                    {transaction.op || '-'}
                                  </Badge>
                                </TableCell>
                                <TableCell className="text-right tabular-nums text-sm text-muted-foreground">
                                  {transaction.tpm.toFixed(1)}
                                </TableCell>
                                <TableCell className={cn('text-right tabular-nums text-sm hidden md:table-cell', durationColor(transaction.p50))}>
                                  {formatDuration(transaction.p50)}
                                </TableCell>
                                <TableCell className={cn('text-right tabular-nums text-sm hidden lg:table-cell', durationColor(transaction.p75))}>
                                  {formatDuration(transaction.p75)}
                                </TableCell>
                                <TableCell className={cn('text-right tabular-nums text-sm font-medium', durationColor(transaction.p95))}>
                                  {formatDuration(transaction.p95)}
                                </TableCell>
                                <TableCell className="hidden xl:table-cell pr-2">
                                  <Tooltip>
                                    <TooltipTrigger asChild>
                                      <div className="w-24 ml-auto">
                                        <div className="h-2 w-full rounded-full bg-muted overflow-hidden">
                                          <div
                                            className={cn('h-full rounded-full transition-all', durationBarColor(transaction.p95))}
                                            style={{ width: `${barWidth}%` }}
                                          />
                                        </div>
                                      </div>
                                    </TooltipTrigger>
                                    <TooltipContent>
                                      <p className="text-xs">p50: {formatDuration(transaction.p50)} / p75: {formatDuration(transaction.p75)} / p95: {formatDuration(transaction.p95)}</p>
                                    </TooltipContent>
                                  </Tooltip>
                                </TableCell>
                                <TableCell className={cn('text-right tabular-nums text-sm pr-4 sm:pr-6 font-medium', failureRateColor(transaction.failureRate))}>
                                  {formatRate(transaction.failureRate)}
                                </TableCell>
                              </TableRow>
                            )
                          })
                        )}
                      </TableBody>
                    </Table>
                  </div>

                  {/* Pagination Controls */}
                  {transactions.length > pageSize && (
                    <div className="flex items-center justify-between border-t px-4 py-3 sm:px-6">
                      <div className="flex items-center gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                          disabled={currentPage === 1}
                          className="h-8"
                        >
                          <ChevronLeft className="h-4 w-4" />
                          <span className="hidden sm:inline ml-1">Previous</span>
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                          disabled={currentPage === totalPages}
                          className="h-8"
                        >
                          <span className="hidden sm:inline mr-1">Next</span>
                          <ChevronRight className="h-4 w-4" />
                        </Button>
                      </div>
                      <div className="text-xs text-muted-foreground">
                        Page {currentPage} of {totalPages}
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>
            </div>
          )}
        </div>
      </div>
    </TooltipProvider>
  )
}

/** Compact sortable column header button */
function SortButton({
  children,
  active,
  asc,
  onClick,
}: {
  children: React.ReactNode
  active: boolean
  asc: boolean
  onClick: () => void
}) {
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={onClick}
      className={cn('h-7 px-0 text-xs font-medium', active && 'text-foreground')}
    >
      {children}
      <ArrowUpDown className={cn('ml-1 h-3 w-3', active ? 'opacity-100' : 'opacity-40')} />
      {active && (
        <span className="ml-0.5 text-[10px] text-muted-foreground">
          {asc ? '↑' : '↓'}
        </span>
      )}
    </Button>
  )
}
