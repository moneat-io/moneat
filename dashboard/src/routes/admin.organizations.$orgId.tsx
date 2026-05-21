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
import {api, type BillingUsage} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis} from 'recharts'
import {
    AlertCircle,
    ArrowLeft,
    ArrowRightLeft,
    Eye,
    FileText,
    FolderKanban,
    HardDrive,
    MessageSquare,
    MonitorPlay,
    RotateCcw,
    SlidersHorizontal,
    Users,
    Zap
} from 'lucide-react'
import {useMemo, useState} from 'react'
import {useToast} from '@/hooks/useToast'
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
} from '@/components/AdminComponents'

export const Route = createFileRoute('/admin/organizations/$orgId')({
  component: AdminOrgDetailPage,
})

const QUOTA_TARGET_PERCENT_MIN = 0
const QUOTA_TARGET_PERCENT_MAX = 500
const PERCENT_MULTIPLIER = 100

const QUOTA_RESET_OPTIONS = [
  {value: 'ingestion_bytes', label: 'Ingestion GB'},
  {value: 'apm_spans', label: 'APM spans'},
  {value: 'custom_metrics', label: 'Custom metrics'},
  {value: 'analytics_pageviews', label: 'Analytics pageviews'},
  {value: 'errors', label: 'Errors'},
  {value: 'transactions', label: 'Transactions'},
  {value: 'replays', label: 'Replays'},
  {value: 'feedback', label: 'Feedback'},
  {value: 'llm_events', label: 'LLM events'},
] as const

type AdminQuotaType = (typeof QUOTA_RESET_OPTIONS)[number]['value']

interface QuotaUsageSnapshot {
  used: number
  limit: number
}

function getQuotaUsageSnapshot(usage: BillingUsage, quotaType: AdminQuotaType): QuotaUsageSnapshot {
  switch (quotaType) {
    case 'ingestion_bytes':
      return {
        used: Math.max(0, usage.usedBytes - (usage.usedApmSpanBytes ?? 0)),
        limit: usage.bytesLimit,
      }
    case 'apm_spans':
      return {used: usage.usedApmSpans ?? 0, limit: usage.apmSpanLimit ?? 0}
    case 'custom_metrics':
      return {used: usage.usedCustomMetrics ?? 0, limit: usage.customMetricLimit ?? 0}
    case 'analytics_pageviews':
      return {used: usage.usedAnalyticsPageviews ?? 0, limit: usage.analyticsPageviewLimit ?? 0}
    case 'errors':
      return {used: usage.usedErrors, limit: usage.errorLimit}
    case 'transactions':
      return {used: usage.usedTransactions, limit: usage.transactionLimit}
    case 'replays':
      return {used: usage.usedReplays, limit: usage.replayLimit}
    case 'feedback':
      return {used: usage.usedFeedback, limit: usage.feedbackLimit}
    case 'llm_events':
      return {used: usage.usedLlmEvents ?? 0, limit: usage.llmEventLimit ?? 0}
  }
}

function formatQuotaValue(quotaType: AdminQuotaType, value: number): string {
  return quotaType === 'ingestion_bytes' ? formatBytes(value) : formatNumber(value)
}

function parseTargetPercent(value: string): number | null {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return null
  if (parsed < QUOTA_TARGET_PERCENT_MIN || parsed > QUOTA_TARGET_PERCENT_MAX) return null
  return parsed
}

function AdminOrgDetailPage() {
  const {orgId} = Route.useParams()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [usagePeriod, setUsagePeriod] = useState<'7d' | '30d'>('30d')
  const [quotaType, setQuotaType] = useState<AdminQuotaType>('ingestion_bytes')
  const [targetPercent, setTargetPercent] = useState('80')

  const normalizeEventType = (eventType: string): 'error' | 'transaction' | 'replay' | 'feedback' | 'log' | null => {
    switch (eventType) {
      case 'error':
      case 'transaction':
      case 'replay':
      case 'feedback':
      case 'log':
        return eventType
      case 'logs':
        return 'log'
      default:
        return null
    }
  }

  const {data: org, isLoading} = useQuery({
    queryKey: ['admin-org', orgId],
    queryFn: () => api.getAdminOrgDetail(Number(orgId)),
    enabled: !!orgId,
  })

  const {data: usage} = useQuery({
    queryKey: ['admin-org-usage', orgId, usagePeriod],
    queryFn: () => api.getAdminOrgUsage(Number(orgId), usagePeriod),
    enabled: !!orgId,
  })

  const {data: quotaUsage} = useQuery({
    queryKey: ['admin-org-quota-usage', orgId],
    queryFn: () => api.getAdminOrgQuotaUsage(Number(orgId)),
    enabled: !!orgId,
  })

  const quotaResetMutation = useMutation({
    mutationFn: (targetPercentValue: number) =>
      api.resetAdminOrgQuotaUsage(Number(orgId), {
        quotaType,
        targetPercent: targetPercentValue,
      }),
    onSuccess: (response) => {
      const responseQuotaType = response.quotaType as AdminQuotaType
      queryClient.setQueryData(['admin-org-quota-usage', orgId], response.usage)
      void queryClient.invalidateQueries({queryKey: ['admin-org-quota-usage', orgId]})
      toast({
        title: 'Quota usage updated',
        description:
          `${formatQuotaValue(responseQuotaType, response.updatedUsed)} ${response.quotaType.replaceAll('_', ' ')}`,
      })
    },
    onError: (error) => {
      toast({
        title: 'Quota update failed',
        description: error instanceof Error ? error.message : 'Unable to update quota usage',
        variant: 'destructive',
      })
    },
  })

  // Aggregate usage by date for chart
  const usageByDate = useMemo(() => {
    if (!usage) return []
    const acc: Record<
      string,
      {date: string; error: number; transaction: number; replay: number; feedback: number; log: number; total: number}
    > = {}
    for (const u of usage) {
      if (!acc[u.date]) {
        acc[u.date] = {date: u.date, error: 0, transaction: 0, replay: 0, feedback: 0, log: 0, total: 0}
      }
      const key = normalizeEventType(u.eventType)
      if (key && key in acc[u.date]) {
        acc[u.date][key] += u.eventCount
      }
      acc[u.date].total += u.eventCount
    }
    return Object.values(acc).sort((a, b) => a.date.localeCompare(b.date))
  }, [usage])

  // Aggregate usage by event type for breakdown
  const usageByType = useMemo(() => {
    if (!usage) return {error: 0, transaction: 0, replay: 0, feedback: 0, log: 0, totalEvents: 0, totalBytes: 0}
    return usage.reduce(
      (acc, u) => {
        const key = normalizeEventType(u.eventType)
        if (key && key in acc) acc[key] += u.eventCount
        acc.totalEvents += u.eventCount
        acc.totalBytes += u.bytesIngested
        return acc
      },
      {error: 0, transaction: 0, replay: 0, feedback: 0, log: 0, totalEvents: 0, totalBytes: 0}
    )
  }, [usage])

  if (isLoading || !org) {
    return <AdminSkeleton />
  }

  const periodLabel = usagePeriod === '7d' ? 'Last 7 Days' : 'Last 30 Days'
  const selectedQuota = quotaUsage ? getQuotaUsageSnapshot(quotaUsage, quotaType) : null
  const parsedTargetPercent = parseTargetPercent(targetPercent)
  const canResetQuota =
    selectedQuota != null &&
    selectedQuota.limit > 0 &&
    parsedTargetPercent != null &&
    !quotaResetMutation.isPending
  const targetUsage =
    selectedQuota != null && parsedTargetPercent != null
      ? Math.round(selectedQuota.limit * parsedTargetPercent / PERCENT_MULTIPLIER)
      : null

  return (
    <div className="space-y-8">
      {/* Breadcrumb + Title */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <Link
            to="/admin/organizations"
            className="text-sm text-muted-foreground hover:text-foreground flex items-center gap-1.5 mb-3 transition-colors w-fit"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            All Organizations
          </Link>
          <div className="flex items-center gap-3">
            <h2 className="text-2xl font-bold tracking-tight">{org.name}</h2>
            <PlanBadge plan={org.plan} />
            {org.subscriptionStatus && org.subscriptionStatus !== 'active' && (
              <Badge
                variant="outline"
                className="text-amber-600 border-amber-300 dark:text-amber-400 dark:border-amber-700"
              >
                {org.subscriptionStatus}
              </Badge>
            )}
          </div>
          {org.companySize && <p className="text-sm text-muted-foreground mt-1">Company size: {org.companySize}</p>}
        </div>
        <Select value={usagePeriod} onValueChange={(v) => setUsagePeriod(v as '7d' | '30d')}>
          <SelectTrigger className="w-[140px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7d">Last 7 Days</SelectItem>
            <SelectItem value="30d">Last 30 Days</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Primary Metric Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <MetricCard
          title="Events This Month"
          value={formatNumber(org.eventCountThisMonth)}
          icon={Zap}
          iconColor="text-orange-600 dark:text-orange-400"
          iconBg="bg-orange-100 dark:bg-orange-950"
        />
        <MetricCard
          title="Bytes Ingested"
          value={formatBytes(org.bytesIngestedThisMonth)}
          subtitle="This month"
          icon={HardDrive}
          iconColor="text-blue-600 dark:text-blue-400"
          iconBg="bg-blue-100 dark:bg-blue-950"
        />
        <MetricCard
          title="Members"
          value={org.memberCount}
          icon={Users}
          iconColor="text-violet-600 dark:text-violet-400"
          iconBg="bg-violet-100 dark:bg-violet-950"
        />
        <Card className="relative overflow-hidden">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Quota Usage</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="text-2xl font-bold tracking-tight">
              {org.quotaUsedPercent != null ? `${org.quotaUsedPercent.toFixed(1)}%` : 'Unlimited'}
            </div>
            <QuotaBar percent={org.quotaUsedPercent} size="md" showLabel={false} />
          </CardContent>
        </Card>
      </div>

      {/* Quota Controls */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <SlidersHorizontal className="h-4 w-4 text-muted-foreground" />
            Quota Controls
          </CardTitle>
          <CardDescription>
            {quotaUsage ? `${quotaUsage.periodStart} to ${quotaUsage.periodEnd}` : 'Current billing period'}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 lg:grid-cols-[1.4fr_1fr_1fr_auto] lg:items-end">
            <div className="space-y-2">
              <Label htmlFor="quota-type">Type</Label>
              <Select value={quotaType} onValueChange={(value) => setQuotaType(value as AdminQuotaType)}>
                <SelectTrigger id="quota-type">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {QUOTA_RESET_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Current</Label>
              <div className="h-9 rounded-md border px-3 py-2 text-sm tabular-nums">
                {selectedQuota ? formatQuotaValue(quotaType, selectedQuota.used) : '\u2014'}
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="quota-target-percent">Target %</Label>
              <Input
                id="quota-target-percent"
                type="number"
                min={QUOTA_TARGET_PERCENT_MIN}
                max={QUOTA_TARGET_PERCENT_MAX}
                step="1"
                value={targetPercent}
                onChange={(event) => setTargetPercent(event.target.value)}
              />
            </div>
            <Button
              type="button"
              disabled={!canResetQuota}
              onClick={() => {
                if (parsedTargetPercent == null) return
                quotaResetMutation.mutate(parsedTargetPercent)
              }}
            >
              <RotateCcw className="h-4 w-4 mr-2" />
              Set Usage
            </Button>
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="rounded-md border px-3 py-2">
              <div className="text-xs text-muted-foreground">Limit</div>
              <div className="text-sm font-medium tabular-nums">
                {selectedQuota && selectedQuota.limit > 0 ? formatQuotaValue(quotaType, selectedQuota.limit) : '\u2014'}
              </div>
            </div>
            <div className="rounded-md border px-3 py-2">
              <div className="text-xs text-muted-foreground">Target</div>
              <div className="text-sm font-medium tabular-nums">
                {targetUsage != null ? formatQuotaValue(quotaType, targetUsage) : '\u2014'}
              </div>
            </div>
            <div className="rounded-md border px-3 py-2">
              <div className="text-xs text-muted-foreground">Status</div>
              <div className="text-sm font-medium">
                {quotaUsage ? (quotaUsage.withinQuota ? 'Within quota' : 'Over quota') : '\u2014'}
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Event Type Breakdown */}
      <div>
        <h3 className="text-sm font-semibold text-muted-foreground uppercase tracking-wider mb-3">
          Usage Breakdown ({periodLabel})
        </h3>
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-4">
          <Card className="px-4 py-3">
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <AlertCircle className="h-3 w-3 text-red-500" />
              Errors
            </div>
            <div className="text-lg font-bold tabular-nums">{formatNumber(usageByType.error)}</div>
            <div className="text-xs text-muted-foreground">
              {usageByType.totalEvents > 0
                ? `${((usageByType.error / usageByType.totalEvents) * 100).toFixed(1)}%`
                : '0%'}{' '}
              of total
            </div>
          </Card>
          <Card className="px-4 py-3">
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <ArrowRightLeft className="h-3 w-3 text-blue-500" />
              Transactions
            </div>
            <div className="text-lg font-bold tabular-nums">{formatNumber(usageByType.transaction)}</div>
            <div className="text-xs text-muted-foreground">
              {usageByType.totalEvents > 0
                ? `${((usageByType.transaction / usageByType.totalEvents) * 100).toFixed(1)}%`
                : '0%'}{' '}
              of total
            </div>
          </Card>
          <Card className="px-4 py-3">
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <MonitorPlay className="h-3 w-3 text-violet-500" />
              Replays
            </div>
            <div className="text-lg font-bold tabular-nums">{formatNumber(usageByType.replay)}</div>
            <div className="text-xs text-muted-foreground">
              {usageByType.totalEvents > 0
                ? `${((usageByType.replay / usageByType.totalEvents) * 100).toFixed(1)}%`
                : '0%'}{' '}
              of total
            </div>
          </Card>
          <Card className="px-4 py-3">
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <MessageSquare className="h-3 w-3 text-amber-500" />
              Feedback
            </div>
            <div className="text-lg font-bold tabular-nums">{formatNumber(usageByType.feedback)}</div>
            <div className="text-xs text-muted-foreground">
              {usageByType.totalEvents > 0
                ? `${((usageByType.feedback / usageByType.totalEvents) * 100).toFixed(1)}%`
                : '0%'}{' '}
              of total
            </div>
          </Card>
          <Card className="px-4 py-3">
            <div className="flex items-center gap-2 text-xs text-muted-foreground mb-1">
              <FileText className="h-3 w-3 text-cyan-500" />
              Logs
            </div>
            <div className="text-lg font-bold tabular-nums">{formatNumber(usageByType.log)}</div>
            <div className="text-xs text-muted-foreground">
              {usageByType.totalEvents > 0
                ? `${((usageByType.log / usageByType.totalEvents) * 100).toFixed(1)}%`
                : '0%'}{' '}
              of total
            </div>
          </Card>
        </div>
      </div>

      {/* Usage Chart */}
      {usageByDate.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Usage Over Time</CardTitle>
            <CardDescription>
              Daily event counts by type ({periodLabel.toLowerCase()}) &middot;{' '}
              {formatBytes(usageByType.totalBytes)} total data
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={320}>
              <BarChart data={usageByDate}>
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
                <Legend iconType="circle" iconSize={8} wrapperStyle={{paddingTop: '12px'}} />
                <Bar
                  dataKey="error"
                  stackId="a"
                  fill={eventTypeColors.error.stroke}
                  name="Errors"
                  radius={[0, 0, 0, 0]}
                />
                <Bar dataKey="transaction" stackId="a" fill={eventTypeColors.transaction.stroke} name="Transactions" />
                <Bar dataKey="replay" stackId="a" fill={eventTypeColors.replay.stroke} name="Replays" />
                <Bar
                  dataKey="feedback"
                  stackId="a"
                  fill={eventTypeColors.feedback.stroke}
                  name="Feedback"
                />
                <Bar
                  dataKey="log"
                  stackId="a"
                  fill={eventTypeColors.log.stroke}
                  name="Logs"
                  radius={[2, 2, 0, 0]}
                />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      )}

      {/* Members & Projects */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Members</CardTitle>
            <CardDescription>
              {org.memberCount} member{org.memberCount !== 1 ? 's' : ''}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {org.members.length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>Email</TableHead>
                    <TableHead>Name</TableHead>
                    <TableHead className="text-right">Role</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {org.members.map((m) => (
                    <TableRow key={m.userId}>
                      <TableCell className="font-medium">{m.email}</TableCell>
                      <TableCell className="text-muted-foreground">{m.name || '\u2014'}</TableCell>
                      <TableCell className="text-right">
                        <Badge variant={m.role === 'owner' ? 'default' : 'secondary'} className="capitalize">
                          {m.role}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={async () => {
                            try {
                              const { token } = await api.impersonateUser(m.userId)
                              const impersonationWindow = window.open('/impersonate-callback', '_blank')
                              if (!impersonationWindow) {
                                console.error('Failed to open impersonation window')
                                return
                              }

                              const expectedOrigin = window.location.origin
                              const timeoutId = window.setTimeout(() => {
                                window.removeEventListener('message', handleReady)
                              }, 10000)

                              const handleReady = (event: MessageEvent) => {
                                if (event.origin !== expectedOrigin) return
                                if (event.source !== impersonationWindow) return
                                if (event.data?.type !== 'MONEAT_IMPERSONATION_READY') return

                                impersonationWindow.postMessage(
                                  { type: 'MONEAT_IMPERSONATION_TOKEN', token },
                                  expectedOrigin
                                )
                                window.clearTimeout(timeoutId)
                                window.removeEventListener('message', handleReady)
                              }

                              window.addEventListener('message', handleReady)
                            } catch (err) {
                              console.error('Failed to impersonate user:', err)
                            }
                          }}
                        >
                          <Eye className="h-4 w-4" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <EmptyState message="No members" icon={Users} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Projects</CardTitle>
            <CardDescription>
              {org.projectCount} project{org.projectCount !== 1 ? 's' : ''}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {org.projects.length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead>Name</TableHead>
                    <TableHead>Slug</TableHead>
                    <TableHead className="text-right">Platform</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {org.projects.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell className="font-medium">{p.name}</TableCell>
                      <TableCell className="text-muted-foreground font-mono text-xs">{p.slug}</TableCell>
                      <TableCell className="text-right">
                        {p.platform ? (
                          <Badge variant="outline" className="capitalize">
                            {p.platform}
                          </Badge>
                        ) : (
                          <span className="text-muted-foreground">\u2014</span>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <EmptyState message="No projects" icon={FolderKanban} />
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
