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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {type ComponentType, type ReactNode} from 'react'
import {api, type BusinessHours, type Incident, type OnCallSchedule} from '@/lib/api'
import {SectionCard} from '@/components/ui/section-card'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Calendar, Users, AlertTriangle, Clock, ChevronRight, Plus, Zap, CheckCircle2, ArrowUpRight, Bell, BellOff, Shield} from 'lucide-react'
import {Link} from '@tanstack/react-router'
import {cn} from '@/lib/utils'
import {useAuth} from '@/hooks/useAuth'

export const Route = createFileRoute('/on-call/')({
  component: OnCallOverview,
})

// Priority / status mapped onto the shared status language. Pair each chip with
// a status dot so meaning survives in grayscale.
function priorityTone(priority: string): StatusTone {
  if (priority.startsWith('P0') || priority.startsWith('P1')) return 'danger'
  if (priority.startsWith('P2')) return 'warning'
  if (priority.startsWith('P3')) return 'info'
  return 'neutral'
}

function priorityBadgeVariant(priority: string): BadgeProps['variant'] {
  switch (priorityTone(priority)) {
    case 'danger':
      return 'danger'
    case 'warning':
      return 'warning'
    case 'info':
      return 'info'
    default:
      return 'neutral'
  }
}

const getStatusConfig = (status: string) => {
  if (status === 'TRIGGERED') return {variant: 'danger' as const, label: 'Triggered', icon: Zap}
  if (status === 'ACKNOWLEDGED') return {variant: 'warning' as const, label: 'Acknowledged', icon: Clock}
  return {variant: 'success' as const, label: 'Resolved', icon: CheckCircle2}
}

function getInitials(name: string) {
  return name.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
}

// Categorical avatar tints draw from the shared chart palette (literal classes so
// Tailwind emits them).
const avatarColors = [
  'bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4',
  'bg-chart-5', 'bg-chart-6', 'bg-chart-7', 'bg-chart-8',
]

type OnCallScheduleSummary = {
  currentOnCall?: {userId?: number | null} | null
}

type OnCallAlertSummary = {
  status: string
  priority: string
}

type BusinessHoursSummary = {
  enabled: boolean
  timezone: string
  windows?: Array<{
    dayOfWeek: number
    startTime: string
    endTime: string
  }>
}

function isActiveAlert(alert: OnCallAlertSummary): boolean {
  return alert.status === 'TRIGGERED' || alert.status === 'ACKNOWLEDGED'
}

function isPageableAlert(alert: OnCallAlertSummary): boolean {
  return alert.priority.startsWith('P0') || alert.priority.startsWith('P1') || alert.priority.startsWith('P2')
}

function isLowPriorityAlert(alert: OnCallAlertSummary): boolean {
  return alert.priority.startsWith('P3') || alert.priority.startsWith('P4') || alert.priority.startsWith('P5')
}

function userOnCallSchedules<T extends OnCallScheduleSummary>(schedules: T[] | undefined, userId: number | undefined) {
  return schedules?.filter(s => s.currentOnCall?.userId === userId) || []
}

function businessHoursActiveNow(businessHours: BusinessHoursSummary | undefined): boolean | null {
  if (!businessHours?.enabled || !businessHours.windows?.length) return null
  try {
    const now = new Date()
    const formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: businessHours.timezone,
      weekday: 'long',
      hour: 'numeric',
      minute: 'numeric',
      hour12: false,
    })
    const parts = formatter.formatToParts(now)
    const weekday = parts.find(p => p.type === 'weekday')?.value?.toUpperCase()
    const hour = parseInt(parts.find(p => p.type === 'hour')?.value || '0')
    const minute = parseInt(parts.find(p => p.type === 'minute')?.value || '0')
    const nowMinutes = hour * 60 + minute

    const dayIndex = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'].indexOf(weekday || '')
    const todayWindows = businessHours.windows.filter(w => w.dayOfWeek === dayIndex)
    return todayWindows.some(w => {
      const [startHour, startMinute] = w.startTime.split(':').map(Number)
      const [endHour, endMinute] = w.endTime.split(':').map(Number)
      return nowMinutes >= startHour * 60 + startMinute && nowMinutes < endHour * 60 + endMinute
    })
  } catch {
    return null
  }
}

function onCallSummarySubtitle(scheduleCount: number): string {
  if (scheduleCount === 0) return "You're not currently on call"

  const scheduleLabel = scheduleCount === 1 ? 'schedule' : 'schedules'
  return `You're on call for ${scheduleCount} ${scheduleLabel}`
}

function BusinessHoursBadge({
  businessHours,
  isWithinBusinessHours,
}: Readonly<{
  businessHours?: BusinessHours
  isWithinBusinessHours: boolean | null
}>) {
  if (!businessHours?.enabled || isWithinBusinessHours === null) return null

  return (
    <Badge variant={isWithinBusinessHours ? 'success' : 'neutral'} size="sm" className="gap-1.5">
      <StatusDot tone={isWithinBusinessHours ? 'success' : 'neutral'} size="sm" />
      {isWithinBusinessHours ? 'Business hours' : 'Outside business hours'}
    </Badge>
  )
}

function CurrentUserScheduleBadges({schedules}: Readonly<{ schedules: OnCallSchedule[] }>) {
  if (schedules.length === 0) return null

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">On call for</p>
      <div className="flex flex-wrap gap-2">
        {schedules.map(schedule => (
          <Badge key={schedule.id} variant="info" className="gap-1.5">
            <Calendar className="h-3 w-3" />
            {schedule.name}
          </Badge>
        ))}
      </div>
    </div>
  )
}

function PriorityAlertList({
  alerts,
  limit,
  emptyMessage,
  compact = false,
}: Readonly<{
  alerts: Incident[]
  limit: number
  emptyMessage: string
  compact?: boolean
}>) {
  if (alerts.length === 0) {
    return <p className="text-sm text-muted-foreground pl-5">{emptyMessage}</p>
  }

  const visibleAlerts = alerts.slice(0, limit)
  const moreCount = alerts.length - limit
  const rowClassName = compact
    ? 'flex items-center justify-between px-2.5 py-1.5 rounded-md border hover:bg-accent/50 transition-colors group'
    : 'flex items-center justify-between px-3 py-2 rounded-md border hover:bg-accent/50 transition-colors group'
  const titleClassName = compact ? 'text-xs truncate' : 'text-sm truncate'

  return (
    <div className="space-y-1.5">
      {visibleAlerts.map(alert => (
        <Link
          key={alert.id}
          to="/on-call/alerts/$alertId"
          params={{alertId: String(alert.id)}}
          className={rowClassName}
        >
          <div className={compact ? 'flex items-center gap-1.5 min-w-0' : 'flex items-center gap-2 min-w-0'}>
            <StatusDot tone={priorityTone(alert.priority)} size={compact ? 'sm' : undefined} />
            <span className={titleClassName}>{alert.title}</span>
          </div>
          <Badge variant={priorityBadgeVariant(alert.priority)} size="sm" className="ml-2 shrink-0">
            {alert.priority}
          </Badge>
        </Link>
      ))}
      {moreCount > 0 && (
        <p className={compact ? 'text-xs text-muted-foreground pl-5' : 'text-xs text-muted-foreground pl-3'}>
          +{moreCount} more
        </p>
      )}
    </div>
  )
}

function AlertSummarySection({
  title,
  icon: Icon,
  iconClassName,
  children,
}: Readonly<{
  title: string
  icon: ComponentType<{ className?: string }>
  iconClassName: string
  children: ReactNode
}>) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <Icon className={`h-3.5 w-3.5 ${iconClassName}`} />
        <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">{title}</p>
      </div>
      {children}
    </div>
  )
}

function YourAlertSummaryCard({
  businessHours,
  isWithinBusinessHours,
  currentUserOnCallSchedules,
  pageableAlerts,
  lowPriorityAlerts,
}: Readonly<{
  businessHours?: BusinessHours
  isWithinBusinessHours: boolean | null
  currentUserOnCallSchedules: OnCallSchedule[]
  pageableAlerts: Incident[]
  lowPriorityAlerts: Incident[]
}>) {
  return (
    <SectionCard
      title="Your alert summary"
      icon={Shield}
      iconTone="info"
      className="h-full"
      actions={<BusinessHoursBadge businessHours={businessHours} isWithinBusinessHours={isWithinBusinessHours} />}
    >
      <p className="-mt-1 mb-3 text-xs text-muted-foreground">
        {onCallSummarySubtitle(currentUserOnCallSchedules.length)}
      </p>
      <div className="space-y-3">
        <CurrentUserScheduleBadges schedules={currentUserOnCallSchedules} />
        <AlertSummarySection title="Pages 24/7 — P0 · P1 · P2" icon={Bell} iconClassName="text-danger-fg">
          <PriorityAlertList alerts={pageableAlerts} limit={5} emptyMessage="No active high-priority alerts" />
        </AlertSummarySection>
        <AlertSummarySection title="Business hours only — P3+" icon={BellOff} iconClassName="text-muted-foreground">
          <PriorityAlertList alerts={lowPriorityAlerts} limit={3} emptyMessage="No low-priority alerts" compact />
        </AlertSummarySection>
      </div>
    </SectionCard>
  )
}

function OnCallSchedulesCard({
  schedules,
  schedulesLoading,
}: Readonly<{
  schedules?: OnCallSchedule[]
  schedulesLoading: boolean
}>) {
  let content: ReactNode

  if (schedulesLoading) {
    content = (
      <div className="flex items-center justify-center py-6">
        <div className="animate-spin rounded-full h-6 w-6 border-2 border-muted border-t-primary" />
      </div>
    )
  } else if (schedules && schedules.length > 0) {
    content = <OnCallScheduleList schedules={schedules} />
  } else {
    content = (
      <EmptyState
        icon={Calendar}
        title="No schedules configured"
        description="Create your first on-call schedule to start managing rotations."
        action={
          <Button asChild size="sm" className="gap-1.5">
            <Link to="/on-call/schedules">
              <Plus className="h-4 w-4" />
              Create schedule
            </Link>
          </Button>
        }
      />
    )
  }

  return (
    <SectionCard
      title="Who's on call"
      icon={Users}
      iconTone="info"
      className="h-full"
      actions={
        <Button asChild variant="outline" size="sm" className="gap-1 shrink-0">
          <Link to="/on-call/schedules">
            <Plus className="h-4 w-4" />
            New schedule
          </Link>
        </Button>
      }
    >
      <p className="-mt-1 mb-3 text-xs text-muted-foreground">Current on-call engineers for each schedule</p>
      {content}
    </SectionCard>
  )
}

function OnCallScheduleList({schedules}: Readonly<{ schedules: OnCallSchedule[] }>) {
  return (
    <div className="space-y-2">
      {schedules.map((schedule, idx) => (
        <div
          key={schedule.id}
          className="flex items-center justify-between p-3 rounded-lg border bg-card hover:bg-accent/30 transition-colors"
        >
          <div className="flex items-center gap-2.5">
            <div className={cn(
              'flex items-center justify-center h-8 w-8 rounded-lg text-white text-xs font-bold',
              avatarColors[idx % avatarColors.length],
            )}>
              {schedule.name.slice(0, 2).toUpperCase()}
            </div>
            <div className="min-w-0">
              <p className="font-medium text-sm">{schedule.name}</p>
              <div className="flex items-center gap-2 mt-0.5">
                <Badge variant="info" size="sm">
                  {schedule.rotationType}
                </Badge>
                <span className="text-xs text-muted-foreground">{schedule.timezone}</span>
              </div>
            </div>
          </div>
          <CurrentOnCallAssignee schedule={schedule} />
        </div>
      ))}
    </div>
  )
}

function CurrentOnCallAssignee({schedule}: Readonly<{ schedule: OnCallSchedule }>) {
  if (!schedule.currentOnCall) {
    return (
      <Badge variant="neutral" size="sm">
        No one assigned
      </Badge>
    )
  }

  return (
    <div className="flex items-center gap-1.5">
      <Avatar className="h-7 w-7">
        <AvatarFallback className={cn(
          'text-xs text-white',
          avatarColors[(schedule.currentOnCall.userId || 0) % avatarColors.length],
        )}>
          {getInitials(schedule.currentOnCall.userName)}
        </AvatarFallback>
      </Avatar>
      <div className="text-right">
        <p className="text-xs font-medium">{schedule.currentOnCall.userName}</p>
        <p className="text-xs text-success-fg flex items-center gap-1">
          <StatusDot tone="success" size="sm" />
          On call now
        </p>
      </div>
    </div>
  )
}

function ActiveAlertsCard({activeAlerts}: Readonly<{ activeAlerts: Incident[] }>) {
  if (activeAlerts.length === 0) return null

  return (
    <SectionCard
      title={
        <span className="flex items-center gap-2">
          <StatusDot tone="danger" pulse size="lg" />
          Active alerts
        </span>
      }
      className="border-danger-border"
      actions={
        <Button asChild variant="ghost" size="sm" className="text-muted-foreground">
          <Link to="/on-call/alerts">
            View all <ArrowUpRight className="h-3 w-3 ml-1" />
          </Link>
        </Button>
      }
    >
      <div className="space-y-1.5">
        {activeAlerts.slice(0, 5).map(alert => <ActiveAlertRow key={alert.id} alert={alert} />)}
      </div>
    </SectionCard>
  )
}

function ActiveAlertRow({alert}: Readonly<{ alert: Incident }>) {
  const statusCfg = getStatusConfig(alert.status)
  const StatusIcon = statusCfg.icon

  return (
    <Link
      to="/on-call/alerts/$alertId"
      params={{alertId: String(alert.id)}}
      className="flex items-center justify-between p-2.5 rounded-lg border hover:bg-accent/50 transition-colors group"
    >
      <div className="flex items-center gap-2 min-w-0">
        <StatusDot tone={priorityTone(alert.priority)} />
        <div className="min-w-0">
          <p className="font-medium text-sm truncate group-hover:text-foreground">{alert.title}</p>
          <p className="text-xs text-muted-foreground">
            {new Date(alert.triggeredAt).toLocaleString()}
          </p>
        </div>
      </div>
      <div className="flex items-center gap-2 flex-shrink-0 ml-3">
        <Badge variant={priorityBadgeVariant(alert.priority)} size="sm">
          {alert.priority}
        </Badge>
        <Badge variant={statusCfg.variant} size="sm" className="gap-1">
          <StatusIcon className="h-3 w-3" />
          {statusCfg.label}
        </Badge>
        <ChevronRight className="h-4 w-4 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
      </div>
    </Link>
  )
}

function OnCallStatsGrid({
  alertsLoading,
  activeAlertCount,
  schedulesLoading,
  scheduleCount,
  policiesLoading,
  policyCount,
}: Readonly<{
  alertsLoading: boolean
  activeAlertCount: number
  schedulesLoading: boolean
  scheduleCount: number
  policiesLoading: boolean
  policyCount: number
}>) {
  return (
    <div className="grid gap-4 md:grid-cols-3">
      <StatCard
        label="Active alerts"
        value={alertsLoading ? '...' : activeAlertCount}
        icon={AlertTriangle}
        tone={activeAlertCount > 0 ? 'danger' : 'neutral'}
        subtitle={activeAlertCount > 0 ? 'Requires immediate attention' : 'All clear — no active alerts'}
      />
      <StatCard
        label="On-call schedules"
        value={schedulesLoading ? '...' : scheduleCount}
        icon={Calendar}
        tone="info"
        subtitle="Active rotation schedules"
      />
      <StatCard
        label="Escalation policies"
        value={policiesLoading ? '...' : policyCount}
        icon={Clock}
        tone="warning"
        subtitle="Configured policies"
      />
    </div>
  )
}

function OnCallOverview() {
  const {user} = useAuth()

  const {data: schedules, isLoading: schedulesLoading} = useQuery({
    queryKey: ['on-call-schedules'],
    queryFn: () => api.getOnCallSchedules(),
  })

  const {data: alerts, isLoading: alertsLoading} = useQuery({
    queryKey: ['alerts', {active: true}],
    queryFn: () => api.getIncidents(),
  })

  const {data: policies, isLoading: policiesLoading} = useQuery({
    queryKey: ['escalation-policies'],
    queryFn: () => api.getEscalationPolicies(),
  })

  const {data: _priorities} = useQuery({ // eslint-disable-line @typescript-eslint/no-unused-vars
    queryKey: ['priorities'],
    queryFn: () => api.getPriorities(),
  })

  const {data: businessHours} = useQuery({
    queryKey: ['business-hours'],
    queryFn: () => api.getBusinessHours(),
  })

  const activeAlerts = alerts?.filter(isActiveAlert) || []

  const currentUserOnCallSchedules = userOnCallSchedules(schedules, user?.id)

  const pageableAlerts = activeAlerts.filter(isPageableAlert)
  const lowPriorityAlerts = activeAlerts.filter(isLowPriorityAlert)
  const isWithinBusinessHours = businessHoursActiveNow(businessHours)

  return (
    <div className="space-y-4">
      <OnCallStatsGrid
        alertsLoading={alertsLoading}
        activeAlertCount={activeAlerts.length}
        schedulesLoading={schedulesLoading}
        scheduleCount={schedules?.length || 0}
        policiesLoading={policiesLoading}
        policyCount={policies?.length || 0}
      />

      <div className="grid gap-4 xl:grid-cols-2">
        <YourAlertSummaryCard
          businessHours={businessHours}
          isWithinBusinessHours={isWithinBusinessHours}
          currentUserOnCallSchedules={currentUserOnCallSchedules}
          pageableAlerts={pageableAlerts}
          lowPriorityAlerts={lowPriorityAlerts}
        />
        <OnCallSchedulesCard schedules={schedules} schedulesLoading={schedulesLoading} />
      </div>

      <ActiveAlertsCard activeAlerts={activeAlerts} />
    </div>
  )
}
