import { createFileRoute, Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Building2, Users, Activity, DollarSign, BarChart3, Database, ArrowUpRight, Crown } from 'lucide-react'
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'
import {
  MetricCard,
  PlanBadge,
  ChartTooltipContent,
  SectionHeader,
  AdminSkeleton,
  EmptyState,
  formatBytes,
  formatNumber,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/')({
  component: AdminOverviewPage,
})

function AdminOverviewPage() {
  const { data: stats, isLoading } = useQuery({
    queryKey: ['admin-overview'],
    queryFn: () => api.getAdminOverview(),
  })

  const { data: topConsumers } = useQuery({
    queryKey: ['admin-top-consumers'],
    queryFn: () => api.getAdminTopConsumers(5),
  })

  if (isLoading || !stats) {
    return <AdminSkeleton />
  }

  const totalSubscribers = Object.values(stats.subscriptionsByPlan).reduce((a, b) => a + b, 0)

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Overview"
        description="A high-level snapshot of your platform's health and activity."
      />

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          title="Total Organizations"
          value={stats.totalOrganizations.toLocaleString()}
          icon={Building2}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Total Users"
          value={stats.totalUsers.toLocaleString()}
          subtitle={`~${(stats.totalUsers / Math.max(stats.totalOrganizations, 1)).toFixed(1)} per org`}
          icon={Users}
          iconColor="text-violet-600 dark:text-violet-400"
          iconBg="bg-violet-100 dark:bg-violet-950"
        />
        <MetricCard
          title="Events (30 days)"
          value={formatNumber(stats.totalEventsLast30Days)}
          subtitle={`${formatNumber(stats.totalEventsAllTime)} all time`}
          icon={Activity}
          iconColor="text-orange-600 dark:text-orange-400"
          iconBg="bg-orange-100 dark:bg-orange-950"
        />
        <MetricCard
          title="MRR"
          value={`$${stats.mrr.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 0 })}`}
          subtitle={`$${(stats.mrr * 12).toLocaleString(undefined, { maximumFractionDigits: 0 })} ARR`}
          icon={DollarSign}
          iconColor="text-emerald-600 dark:text-emerald-400"
          iconBg="bg-emerald-100 dark:bg-emerald-950"
        />
      </div>

      {/* Plan Distribution */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Plan Distribution</CardTitle>
          <CardDescription>Breakdown of organizations by subscription tier</CardDescription>
        </CardHeader>
        <CardContent>
          {totalSubscribers > 0 ? (
            <div className="space-y-4">
              {/* Visual bar */}
              <div className="flex h-3 rounded-full overflow-hidden bg-muted">
                {Object.entries(stats.subscriptionsByPlan).map(([plan, count]) => {
                  const pct = (count / totalSubscribers) * 100
                  const colorMap: Record<string, string> = {
                    free: 'bg-zinc-400 dark:bg-zinc-500',
                    pro: 'bg-blue-500',
                    team: 'bg-violet-500',
                    business: 'bg-amber-500',
                  }
                  return (
                    <div
                      key={plan}
                      className={`${colorMap[plan.toLowerCase()] ?? 'bg-primary'} transition-all duration-500`}
                      style={{ width: `${pct}%` }}
                      title={`${plan}: ${count} (${pct.toFixed(1)}%)`}
                    />
                  )
                })}
              </div>
              {/* Legend */}
              <div className="flex flex-wrap gap-x-6 gap-y-2">
                {Object.entries(stats.subscriptionsByPlan).map(([plan, count]) => {
                  const dotColorMap: Record<string, string> = {
                    free: 'bg-zinc-400 dark:bg-zinc-500',
                    pro: 'bg-blue-500',
                    team: 'bg-violet-500',
                    business: 'bg-amber-500',
                  }
                  return (
                    <div key={plan} className="flex items-center gap-2 text-sm">
                      <div className={`h-2.5 w-2.5 rounded-full ${dotColorMap[plan.toLowerCase()] ?? 'bg-primary'}`} />
                      <span className="capitalize text-muted-foreground">{plan}</span>
                      <span className="font-semibold tabular-nums">{count}</span>
                      <span className="text-muted-foreground text-xs">
                        ({((count / totalSubscribers) * 100).toFixed(0)}%)
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          ) : (
            <p className="text-muted-foreground text-center py-6 text-sm">No active subscriptions</p>
          )}
        </CardContent>
      </Card>

      {/* Events Chart + Top Consumers side-by-side */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Events Chart */}
        <Card className="xl:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">Event Volume</CardTitle>
            <CardDescription>Total events ingested over the last 30 days</CardDescription>
          </CardHeader>
          <CardContent>
            {stats.eventsLast30Days.length > 0 ? (
              <ResponsiveContainer width="100%" height={300}>
                <AreaChart data={stats.eventsLast30Days}>
                  <defs>
                    <linearGradient id="eventGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.2} />
                      <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                    </linearGradient>
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
                  <Area
                    type="monotone"
                    dataKey="count"
                    name="Events"
                    stroke="#3b82f6"
                    strokeWidth={2}
                    fill="url(#eventGradient)"
                    dot={false}
                    activeDot={{ r: 4, strokeWidth: 2 }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <EmptyState message="No event data yet" icon={BarChart3} />
            )}
          </CardContent>
        </Card>

        {/* Top Consumers */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <div>
              <CardTitle className="text-base">Top Consumers</CardTitle>
              <CardDescription>Highest usage this month</CardDescription>
            </div>
            <Link
              to="/admin/organizations"
              className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-0.5 transition-colors"
            >
              View all <ArrowUpRight className="h-3 w-3" />
            </Link>
          </CardHeader>
          <CardContent>
            {topConsumers && topConsumers.length > 0 ? (
              <div className="space-y-3">
                {topConsumers.map((c, index) => (
                  <div key={c.orgId} className="flex items-center gap-3">
                    <div className={`
                      flex items-center justify-center rounded-full text-xs font-bold tabular-nums shrink-0
                      ${index === 0 ? 'h-7 w-7 bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-400' :
                        index === 1 ? 'h-7 w-7 bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400' :
                        index === 2 ? 'h-7 w-7 bg-orange-100 text-orange-700 dark:bg-orange-950 dark:text-orange-400' :
                        'h-7 w-7 bg-muted text-muted-foreground'}
                    `}>
                      {index === 0 ? <Crown className="h-3.5 w-3.5" /> : index + 1}
                    </div>
                    <div className="flex-1 min-w-0">
                      <Link
                        to="/admin/organizations/$orgId"
                        params={{ orgId: String(c.orgId) }}
                        className="text-sm font-medium hover:underline truncate block"
                      >
                        {c.orgName}
                      </Link>
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                        <span>{formatNumber(c.eventCount)} events</span>
                        <span className="text-muted">|</span>
                        <span>{formatBytes(c.bytesIngested)}</span>
                      </div>
                    </div>
                    <PlanBadge plan={c.plan} />
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState message="No usage data yet" icon={Database} />
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
