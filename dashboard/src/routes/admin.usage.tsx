import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { AlertCircle, ArrowRightLeft, HardDrive, MonitorPlay } from 'lucide-react'
import {
  MetricCard,
  SectionHeader,
  AdminSkeleton,
  EmptyState,
  ChartTooltipContent,
  formatBytes,
  formatNumber,
  eventTypeColors,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/usage')({
  component: AdminUsagePage,
})

function AdminUsagePage() {
  const [period, setPeriod] = useState<'24h' | '7d' | '30d'>('7d')
  const { data, isLoading } = useQuery({
    queryKey: ['admin-usage', period],
    queryFn: () => api.getAdminUsage(period),
  })

  if (isLoading || !data) {
    return <AdminSkeleton />
  }

  // Compute totals per event type
  const totals = data.daily.reduce(
    (acc, d) => ({
      error: acc.error + d.error,
      transaction: acc.transaction + d.transaction,
      replay: acc.replay + d.replay,
      feedback: acc.feedback + d.feedback,
      total: acc.total + d.total,
    }),
    { error: 0, transaction: 0, replay: 0, feedback: 0, total: 0 }
  )

  const periodLabel = period === '24h' ? 'Last 24 Hours' : period === '7d' ? 'Last 7 Days' : 'Last 30 Days'

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Usage"
        description="Platform-wide event ingestion and data volume."
      >
        <Select value={period} onValueChange={(v) => setPeriod(v as '24h' | '7d' | '30d')}>
          <SelectTrigger className="w-[160px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="24h">Last 24 Hours</SelectItem>
            <SelectItem value="7d">Last 7 Days</SelectItem>
            <SelectItem value="30d">Last 30 Days</SelectItem>
          </SelectContent>
        </Select>
      </SectionHeader>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
        <MetricCard
          title="Total Events"
          value={formatNumber(totals.total)}
          subtitle={periodLabel}
          icon={ArrowRightLeft}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Errors"
          value={formatNumber(totals.error)}
          icon={AlertCircle}
          iconColor="text-red-600 dark:text-red-400"
          iconBg="bg-red-100 dark:bg-red-950"
        />
        <MetricCard
          title="Transactions"
          value={formatNumber(totals.transaction)}
          icon={ArrowRightLeft}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Replays"
          value={formatNumber(totals.replay)}
          icon={MonitorPlay}
          iconColor="text-violet-600 dark:text-violet-400"
          iconBg="bg-violet-100 dark:bg-violet-950"
        />
        <MetricCard
          title="Data Ingested"
          value={formatBytes(data.totalBytes)}
          subtitle={periodLabel}
          icon={HardDrive}
          iconColor="text-emerald-600 dark:text-emerald-400"
          iconBg="bg-emerald-100 dark:bg-emerald-950"
          className="col-span-2 sm:col-span-1"
        />
      </div>

      {/* Area Chart */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Daily Events by Type</CardTitle>
          <CardDescription>Stacked area chart showing event volume breakdown</CardDescription>
        </CardHeader>
        <CardContent>
          {data.daily.length > 0 ? (
            <ResponsiveContainer width="100%" height={400}>
              <AreaChart data={data.daily}>
                <defs>
                  {Object.entries(eventTypeColors).map(([key, cfg]) => (
                    <linearGradient key={key} id={`gradient-${key}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor={cfg.stroke} stopOpacity={0.3} />
                      <stop offset="95%" stopColor={cfg.stroke} stopOpacity={0.02} />
                    </linearGradient>
                  ))}
                </defs>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" vertical={false} />
                <XAxis
                  dataKey="date"
                  className="text-xs"
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v) => {
                    const d = new Date(v)
                    return `${d.getMonth() + 1}/${d.getDate()}`
                  }}
                />
                <YAxis
                  className="text-xs"
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={(v) => formatNumber(v)}
                />
                <Tooltip content={<ChartTooltipContent />} />
                <Legend
                  iconType="circle"
                  iconSize={8}
                  wrapperStyle={{ paddingTop: '12px' }}
                />
                <Area
                  type="monotone"
                  dataKey="error"
                  stackId="1"
                  stroke={eventTypeColors.error.stroke}
                  fill={`url(#gradient-error)`}
                  strokeWidth={2}
                  name={eventTypeColors.error.label}
                />
                <Area
                  type="monotone"
                  dataKey="transaction"
                  stackId="1"
                  stroke={eventTypeColors.transaction.stroke}
                  fill={`url(#gradient-transaction)`}
                  strokeWidth={2}
                  name={eventTypeColors.transaction.label}
                />
                <Area
                  type="monotone"
                  dataKey="replay"
                  stackId="1"
                  stroke={eventTypeColors.replay.stroke}
                  fill={`url(#gradient-replay)`}
                  strokeWidth={2}
                  name={eventTypeColors.replay.label}
                />
                <Area
                  type="monotone"
                  dataKey="feedback"
                  stackId="1"
                  stroke={eventTypeColors.feedback.stroke}
                  fill={`url(#gradient-feedback)`}
                  strokeWidth={2}
                  name={eventTypeColors.feedback.label}
                />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState message="No usage data yet" icon={ArrowRightLeft} />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
