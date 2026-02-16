// Moneat - Mobile-First Error Monitoring Platform
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
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {
    Activity,
    AlertCircle,
    AlertTriangle,
    ArrowRightLeft,
    ArrowUpRight,
    BarChart3,
    Building2,
    CheckCircle2,
    Crown,
    Database,
    DollarSign,
    FileText,
    HardDrive,
    MessageSquare,
    MonitorPlay,
    Users,
} from 'lucide-react'
import {Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis} from 'recharts'
import {
    AdminSkeleton,
    ChartTooltipContent,
    EmptyState,
    eventTypeColors,
    formatBytes,
    formatNumber,
    MetricCard,
    PlanBadge,
    QuotaBar,
    SectionHeader,
    StorageRing,
} from '@/components/admin-components'

export const Route = createFileRoute('/admin/')({
  component: AdminOverviewPage,
})

function AdminOverviewPage() {
  const {data: stats, isLoading} = useQuery({
    queryKey: ['admin-overview'],
    queryFn: () => api.getAdminOverview(),
  })

  const {data: topConsumers} = useQuery({
    queryKey: ['admin-top-consumers'],
    queryFn: () => api.getAdminTopConsumers(8),
  })

  const {data: usageData} = useQuery({
    queryKey: ['admin-usage', '30d'],
    queryFn: () => api.getAdminUsage('30d'),
  })

  const {data: infraData} = useQuery({
    queryKey: ['admin-infrastructure'],
    queryFn: () => api.getAdminInfrastructure(),
  })

  const {data: orgs} = useQuery({
    queryKey: ['admin-organizations', 1],
    queryFn: () => api.getAdminOrganizations(1, 50),
  })

  if (isLoading || !stats) {
    return <AdminSkeleton />
  }

  const totalSubscribers = Object.values(stats.subscriptionsByPlan).reduce((a, b) => a + b, 0)

  // Compute totals from usage data
  const usageTotals = usageData?.daily.reduce(
    (acc, d) => ({
      error: acc.error + d.error,
      transaction: acc.transaction + d.transaction,
      replay: acc.replay + d.replay,
      feedback: acc.feedback + d.feedback,
      log: acc.log + (d.log ?? 0),
      total: acc.total + d.total,
    }),
    {error: 0, transaction: 0, replay: 0, feedback: 0, log: 0, total: 0}
  )

  // Orgs approaching quota
  const orgsNearQuota = orgs?.filter((o) => o.quotaUsedPercent != null && o.quotaUsedPercent >= 70) ?? []
  const orgsCriticalQuota = orgsNearQuota.filter((o) => o.quotaUsedPercent! >= 90)

  // Storage health
  const storageWarning = infraData ? infraData.storageUsedPercent >= 70 && infraData.storageUsedPercent < 90 : false
  const storageCritical = infraData ? infraData.storageUsedPercent >= 90 : false

  // Find storage for key event tables
  const replayTable = infraData?.clickhouseTables.find((t) => t.table.includes('replay'))
  const transactionTable = infraData?.clickhouseTables.find((t) => t.table.includes('transaction') || t.table.includes('span'))
  const errorTable = infraData?.clickhouseTables.find((t) => t.table.includes('error') || t.table.includes('issue') || t.table.includes('event'))

  return (
    <div className="space-y-8">
      <SectionHeader
        title="Overview"
        description="Platform health, usage, and key metrics at a glance."
      />

      {/* Scaling / Critical Alerts Banner */}
      {(infraData?.scalingTriggerAlerts?.length ?? 0) > 0 && (
        <Card className="border-red-200 dark:border-red-900 bg-red-50/50 dark:bg-red-950/20">
          <CardContent className="pt-5 pb-4">
            <div className="flex gap-3">
              <div className="rounded-lg bg-red-100 dark:bg-red-950 p-2 h-fit">
                <AlertTriangle className="h-4 w-4 text-red-600 dark:text-red-400" />
              </div>
              <div>
                <p className="text-sm font-semibold text-red-700 dark:text-red-400 mb-1">Active Alerts</p>
                <ul className="space-y-0.5">
                  {infraData!.scalingTriggerAlerts.map((msg, i) => (
                    <li key={i} className="text-sm text-red-600 dark:text-red-400 flex items-start gap-1.5">
                      <span className="mt-1.5 h-1.5 w-1.5 rounded-full bg-red-500 shrink-0" />
                      {msg}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Primary KPI Row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
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
          value={`$${stats.mrr.toLocaleString(undefined, {minimumFractionDigits: 0, maximumFractionDigits: 0})}`}
          subtitle={`$${(stats.mrr * 12).toLocaleString(undefined, {maximumFractionDigits: 0})} ARR`}
          icon={DollarSign}
          iconColor="text-emerald-600 dark:text-emerald-400"
          iconBg="bg-emerald-100 dark:bg-emerald-950"
        />
      </div>

      {/* Usage Breakdown by Event Type (30d) */}
      {usageTotals && (
        <div>
          <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3">
            Event Volume (30 days)
          </h3>
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4">
            <MetricCard
              title="Errors"
              value={formatNumber(usageTotals.error)}
              subtitle={`${((usageTotals.error / Math.max(usageTotals.total, 1)) * 100).toFixed(1)}% of total`}
              icon={AlertCircle}
              iconColor="text-red-600 dark:text-red-400"
              iconBg="bg-red-100 dark:bg-red-950"
            />
            <MetricCard
              title="Transactions"
              value={formatNumber(usageTotals.transaction)}
              subtitle={`${((usageTotals.transaction / Math.max(usageTotals.total, 1)) * 100).toFixed(1)}% of total`}
              icon={ArrowRightLeft}
              iconColor="text-blue-600 dark:text-blue-400"
              iconBg="bg-blue-100 dark:bg-blue-950"
            />
            <MetricCard
              title="Replays"
              value={formatNumber(usageTotals.replay)}
              subtitle={`${((usageTotals.replay / Math.max(usageTotals.total, 1)) * 100).toFixed(1)}% of total`}
              icon={MonitorPlay}
              iconColor="text-violet-600 dark:text-violet-400"
              iconBg="bg-violet-100 dark:bg-violet-950"
            />
            <MetricCard
              title="Feedback"
              value={formatNumber(usageTotals.feedback)}
              subtitle={`${((usageTotals.feedback / Math.max(usageTotals.total, 1)) * 100).toFixed(1)}% of total`}
              icon={MessageSquare}
              iconColor="text-amber-600 dark:text-amber-400"
              iconBg="bg-amber-100 dark:bg-amber-950"
            />
            <MetricCard
              title="Logs"
              value={formatNumber(usageTotals.log)}
              subtitle={`${((usageTotals.log / Math.max(usageTotals.total, 1)) * 100).toFixed(1)}% of total`}
              icon={FileText}
              iconColor="text-cyan-600 dark:text-cyan-400"
              iconBg="bg-cyan-100 dark:bg-cyan-950"
            />
            <MetricCard
              title="Data Ingested"
              value={formatBytes(usageData?.totalBytes ?? 0)}
              subtitle="30 day total"
              icon={HardDrive}
              iconColor="text-emerald-600 dark:text-emerald-400"
              iconBg="bg-emerald-100 dark:bg-emerald-950"
              className="col-span-2 sm:col-span-1"
            />
          </div>
        </div>
      )}

      {/* Event Volume Chart + Top Consumers */}
      <div className="grid grid-cols-1 xl:grid-cols-5 gap-6">
        {/* Events Chart */}
        <Card className="xl:col-span-3">
          <CardHeader>
            <CardTitle className="text-base">Event Volume</CardTitle>
            <CardDescription>Total events ingested over the last 30 days</CardDescription>
          </CardHeader>
          <CardContent>
            {stats.eventsLast30Days.length > 0 ? (
              <ResponsiveContainer width="100%" height={320}>
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
                    activeDot={{r: 4, strokeWidth: 2}}
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <EmptyState message="No event data yet" icon={BarChart3} />
            )}
          </CardContent>
        </Card>

        {/* Top Consumers */}
        <Card className="xl:col-span-2">
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
              <div className="space-y-2.5">
                {topConsumers.map((c, index) => (
                  <div key={c.orgId} className="flex items-center gap-3">
                    <div
                      className={`
                        flex items-center justify-center rounded-full text-xs font-bold tabular-nums shrink-0
                        ${
                          index === 0
                            ? 'h-7 w-7 bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-400'
                            : index === 1
                              ? 'h-7 w-7 bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400'
                              : index === 2
                                ? 'h-7 w-7 bg-orange-100 text-orange-700 dark:bg-orange-950 dark:text-orange-400'
                                : 'h-7 w-7 bg-muted text-muted-foreground'
                        }
                      `}
                    >
                      {index === 0 ? <Crown className="h-3.5 w-3.5" /> : index + 1}
                    </div>
                    <div className="flex-1 min-w-0">
                      <Link
                        to="/admin/organizations/$orgId"
                        params={{orgId: String(c.orgId)}}
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

      {/* Plan Distribution + Storage & Launch Health */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Plan Distribution */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Plan Distribution</CardTitle>
            <CardDescription>Breakdown of organizations by subscription tier</CardDescription>
          </CardHeader>
          <CardContent>
            {totalSubscribers > 0 ? (
              <div className="space-y-4">
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
                        style={{width: `${pct}%`}}
                        title={`${plan}: ${count} (${pct.toFixed(1)}%)`}
                      />
                    )
                  })}
                </div>
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
                        <div
                          className={`h-2.5 w-2.5 rounded-full ${dotColorMap[plan.toLowerCase()] ?? 'bg-primary'}`}
                        />
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

        {/* Storage by Data Type */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Storage by Data Type</CardTitle>
            <CardDescription>ClickHouse storage for key event tables</CardDescription>
          </CardHeader>
          <CardContent>
            {infraData ? (
              <div className="space-y-4">
                <div className="flex items-center gap-4 mb-2">
                  <StorageRing percent={infraData.storageUsedPercent} size={64} />
                  <div>
                    <div
                      className={`text-xl font-bold tracking-tight ${
                        storageCritical
                          ? 'text-red-600 dark:text-red-400'
                          : storageWarning
                            ? 'text-amber-600 dark:text-amber-400'
                            : 'text-foreground'
                      }`}
                    >
                      {infraData.storageUsedPercent.toFixed(1)}% used
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {formatBytes(infraData.totalDiskBytes)} total &middot;{' '}
                      {formatNumber(infraData.totalRows)} rows
                    </p>
                  </div>
                </div>

                <div className="space-y-3">
                  {[
                    {label: 'Errors / Events', table: errorTable, color: eventTypeColors.error.stroke},
                    {label: 'Transactions / Spans', table: transactionTable, color: eventTypeColors.transaction.stroke},
                    {label: 'Replays', table: replayTable, color: eventTypeColors.replay.stroke},
                  ].map((item) => {
                    if (!item.table) return null
                    const pct = (item.table.bytesOnDisk / Math.max(infraData.totalDiskBytes, 1)) * 100
                    return (
                      <div key={item.label} className="space-y-1">
                        <div className="flex items-center justify-between text-sm">
                          <div className="flex items-center gap-2">
                            <div className="h-2.5 w-2.5 rounded-full" style={{backgroundColor: item.color}} />
                            <span className="text-muted-foreground">{item.label}</span>
                          </div>
                          <span className="font-medium tabular-nums text-xs">
                            {item.table.bytesOnDiskFormatted}
                          </span>
                        </div>
                        <div className="h-1.5 rounded-full bg-muted overflow-hidden">
                          <div
                            className="h-full rounded-full transition-all duration-500"
                            style={{width: `${Math.max(pct, 1)}%`, backgroundColor: item.color}}
                          />
                        </div>
                        <p className="text-[11px] text-muted-foreground tabular-nums">
                          {formatNumber(item.table.rows)} rows &middot; {pct.toFixed(1)}% of storage
                        </p>
                      </div>
                    )
                  })}
                </div>

                <Link
                  to="/admin/infrastructure"
                  className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-0.5 transition-colors pt-1"
                >
                  View all tables <ArrowUpRight className="h-3 w-3" />
                </Link>
              </div>
            ) : (
              <EmptyState message="Loading storage data..." icon={Database} />
            )}
          </CardContent>
        </Card>

        {/* Launch Health */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Platform Health</CardTitle>
            <CardDescription>Key indicators for production readiness</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {/* Storage Health */}
              <HealthIndicator
                label="Storage"
                status={storageCritical ? 'critical' : storageWarning ? 'warning' : 'healthy'}
                detail={infraData ? `${infraData.storageUsedPercent.toFixed(1)}% used` : 'Loading...'}
              />

              {/* Scaling Alerts */}
              <HealthIndicator
                label="Scaling Alerts"
                status={(infraData?.scalingTriggerAlerts?.length ?? 0) > 0 ? 'critical' : 'healthy'}
                detail={
                  infraData
                    ? infraData.scalingTriggerAlerts.length > 0
                      ? `${infraData.scalingTriggerAlerts.length} active`
                      : 'None'
                    : 'Loading...'
                }
              />

              {/* Ingestion Rate */}
              <HealthIndicator
                label="Ingestion (30d)"
                status={stats.totalEventsLast30Days > 0 ? 'healthy' : 'warning'}
                detail={`${formatNumber(stats.totalEventsLast30Days)} events`}
              />

              {/* Quota Pressure */}
              <HealthIndicator
                label="Quota Pressure"
                status={
                  orgsCriticalQuota.length > 0
                    ? 'critical'
                    : orgsNearQuota.length > 0
                      ? 'warning'
                      : 'healthy'
                }
                detail={
                  orgsCriticalQuota.length > 0
                    ? `${orgsCriticalQuota.length} org(s) >90%`
                    : orgsNearQuota.length > 0
                      ? `${orgsNearQuota.length} org(s) >70%`
                      : 'All orgs within limits'
                }
              />

              {/* ClickHouse Tables */}
              <HealthIndicator
                label="Database Tables"
                status="healthy"
                detail={infraData ? `${infraData.clickhouseTables.length} active tables` : 'Loading...'}
              />

              {/* Active Plans */}
              <HealthIndicator
                label="Active Subscriptions"
                status={totalSubscribers > 0 ? 'healthy' : 'warning'}
                detail={`${totalSubscribers} subscriber${totalSubscribers !== 1 ? 's' : ''}`}
              />
            </div>

            {/* Quota Alert List */}
            {orgsNearQuota.length > 0 && (
              <div className="mt-5 pt-4 border-t">
                <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                  Organizations Near Quota
                </p>
                <div className="space-y-2">
                  {orgsNearQuota
                    .sort((a, b) => (b.quotaUsedPercent ?? 0) - (a.quotaUsedPercent ?? 0))
                    .slice(0, 5)
                    .map((org) => (
                      <div key={org.id} className="flex items-center gap-2">
                        <Link
                          to="/admin/organizations/$orgId"
                          params={{orgId: String(org.id)}}
                          className="text-xs font-medium hover:underline truncate flex-1 min-w-0"
                        >
                          {org.name}
                        </Link>
                        <QuotaBar percent={org.quotaUsedPercent} className="w-24" />
                      </div>
                    ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

// ─── Health Indicator Component ──────────────────────────────────────────────

function HealthIndicator({
  label,
  status,
  detail,
}: {
  label: string
  status: 'healthy' | 'warning' | 'critical'
  detail: string
}) {
  const statusConfig = {
    healthy: {
      icon: CheckCircle2,
      className: 'text-emerald-600 dark:text-emerald-400',
      bgClassName: 'bg-emerald-100 dark:bg-emerald-950',
      badgeVariant: 'default' as const,
      badgeClassName: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-400 border-emerald-200 dark:border-emerald-800',
    },
    warning: {
      icon: AlertTriangle,
      className: 'text-amber-600 dark:text-amber-400',
      bgClassName: 'bg-amber-100 dark:bg-amber-950',
      badgeVariant: 'outline' as const,
      badgeClassName: 'bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-400 border-amber-200 dark:border-amber-800',
    },
    critical: {
      icon: AlertCircle,
      className: 'text-red-600 dark:text-red-400',
      bgClassName: 'bg-red-100 dark:bg-red-950',
      badgeVariant: 'destructive' as const,
      badgeClassName: '',
    },
  }

  const config = statusConfig[status]
  const Icon = config.icon

  return (
    <div className="flex items-center gap-3 py-1.5">
      <div className={`rounded-md p-1.5 ${config.bgClassName}`}>
        <Icon className={`h-3.5 w-3.5 ${config.className}`} />
      </div>
      <span className="text-sm flex-1">{label}</span>
      <Badge variant={config.badgeVariant} className={`text-xs ${config.badgeClassName}`}>
        {detail}
      </Badge>
    </div>
  )
}
