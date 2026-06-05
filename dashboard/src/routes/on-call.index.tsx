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
import {api} from '@/lib/api'
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

  const activeAlerts = alerts?.filter((i) => i.status === 'TRIGGERED' || i.status === 'ACKNOWLEDGED') || []
  const hasActiveAlerts = activeAlerts.length > 0

  // Determine which schedules the current user is on-call for
  const userOnCallSchedules = schedules?.filter(s => s.currentOnCall?.userId === user?.id) || []
  const isCurrentlyOnCall = userOnCallSchedules.length > 0

  // Split alerts by paging behavior
  const pageableAlerts = activeAlerts.filter(i => {
    const level = i.priority
    return level?.startsWith('P0') || level?.startsWith('P1') || level?.startsWith('P2')
  })
  const lowPriorityAlerts = activeAlerts.filter(i => {
    const level = i.priority
    return level?.startsWith('P3') || level?.startsWith('P4') || level?.startsWith('P5')
  })

  // Check if currently within business hours
  const isWithinBusinessHours = (() => {
    if (!businessHours?.enabled || !businessHours?.windows?.length) return null
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
        const [sh, sm] = w.startTime.split(':').map(Number)
        const [eh, em] = w.endTime.split(':').map(Number)
        return nowMinutes >= sh * 60 + sm && nowMinutes < eh * 60 + em
      })
    } catch {
      return null
    }
  })()

  return (
    <div className="space-y-4">
      {/* Stats */}
      <div className="grid gap-4 md:grid-cols-3">
        <StatCard
          label="Active alerts"
          value={alertsLoading ? '...' : activeAlerts.length}
          icon={AlertTriangle}
          tone={hasActiveAlerts ? 'danger' : 'neutral'}
          subtitle={hasActiveAlerts ? 'Requires immediate attention' : 'All clear — no active alerts'}
        />
        <StatCard
          label="On-call schedules"
          value={schedulesLoading ? '...' : schedules?.length || 0}
          icon={Calendar}
          tone="info"
          subtitle="Active rotation schedules"
        />
        <StatCard
          label="Escalation policies"
          value={policiesLoading ? '...' : policies?.length || 0}
          icon={Clock}
          tone="warning"
          subtitle="Configured policies"
        />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        {/* Your Alert Summary */}
        <SectionCard
          title="Your alert summary"
          icon={Shield}
          iconTone="info"
          className="h-full"
          actions={
            businessHours?.enabled && isWithinBusinessHours !== null ? (
              <Badge variant={isWithinBusinessHours ? 'success' : 'neutral'} size="sm" className="gap-1.5">
                <StatusDot tone={isWithinBusinessHours ? 'success' : 'neutral'} size="sm" />
                {isWithinBusinessHours ? 'Business hours' : 'Outside business hours'}
              </Badge>
            ) : undefined
          }
        >
          <p className="-mt-1 mb-3 text-xs text-muted-foreground">
            {isCurrentlyOnCall
              ? `You're on call for ${userOnCallSchedules.length} schedule${userOnCallSchedules.length > 1 ? 's' : ''}`
              : "You're not currently on call"}
          </p>
          <div className="space-y-3">
            {/* On-call schedules for current user */}
            {isCurrentlyOnCall && (
              <div className="space-y-2">
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">On call for</p>
                <div className="flex flex-wrap gap-2">
                  {userOnCallSchedules.map(s => (
                    <Badge key={s.id} variant="info" className="gap-1.5">
                      <Calendar className="h-3 w-3" />
                      {s.name}
                    </Badge>
                  ))}
                </div>
              </div>
            )}

            {/* Pageable alerts (P0-P2) */}
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <Bell className="h-3.5 w-3.5 text-danger-fg" />
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                  Pages 24/7 — P0 · P1 · P2
                </p>
              </div>
              {pageableAlerts.length > 0 ? (
                <div className="space-y-1.5">
                  {pageableAlerts.slice(0, 5).map(alert => (
                    <Link
                      key={alert.id}
                      to="/on-call/alerts/$alertId"
                      params={{alertId: String(alert.id)}}
                      className="flex items-center justify-between px-3 py-2 rounded-md border hover:bg-accent/50 transition-colors group"
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <StatusDot tone={priorityTone(alert.priority)} />
                        <span className="text-sm truncate">{alert.title}</span>
                      </div>
                      <Badge variant={priorityBadgeVariant(alert.priority)} size="sm" className="ml-2 shrink-0">
                        {alert.priority}
                      </Badge>
                    </Link>
                  ))}
                  {pageableAlerts.length > 5 && (
                    <p className="text-xs text-muted-foreground pl-3">+{pageableAlerts.length - 5} more</p>
                  )}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground pl-5">No active high-priority alerts</p>
              )}
            </div>

            {/* Low-priority (P3+, business hours only) */}
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <BellOff className="h-3.5 w-3.5 text-muted-foreground" />
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
                  Business hours only — P3+
                </p>
              </div>
              {lowPriorityAlerts.length > 0 ? (
                <div className="space-y-1.5">
                  {lowPriorityAlerts.slice(0, 3).map(alert => (
                    <Link
                      key={alert.id}
                      to="/on-call/alerts/$alertId"
                      params={{alertId: String(alert.id)}}
                      className="flex items-center justify-between px-2.5 py-1.5 rounded-md border hover:bg-accent/50 transition-colors group"
                    >
                      <div className="flex items-center gap-1.5 min-w-0">
                        <StatusDot tone={priorityTone(alert.priority)} size="sm" />
                        <span className="text-xs truncate">{alert.title}</span>
                      </div>
                      <Badge variant={priorityBadgeVariant(alert.priority)} size="sm" className="ml-2 shrink-0">
                        {alert.priority}
                      </Badge>
                    </Link>
                  ))}
                  {lowPriorityAlerts.length > 3 && (
                    <p className="text-xs text-muted-foreground pl-5">+{lowPriorityAlerts.length - 3} more</p>
                  )}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground pl-5">No low-priority alerts</p>
              )}
            </div>
          </div>
        </SectionCard>

        {/* Who's On Call Now */}
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
          {schedulesLoading ? (
            <div className="flex items-center justify-center py-6">
              <div className="animate-spin rounded-full h-6 w-6 border-2 border-muted border-t-primary" />
            </div>
          ) : schedules && schedules.length > 0 ? (
            <div className="space-y-2">
              {schedules.map((schedule, idx) => (
                <div
                  key={schedule.id}
                  className="flex items-center justify-between p-3 rounded-lg border bg-card hover:bg-accent/30 transition-colors"
                >
                  <div className="flex items-center gap-2.5">
                    <div className={cn(
                      'flex items-center justify-center h-8 w-8 rounded-lg text-white text-xs font-bold',
                      avatarColors[idx % avatarColors.length]
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
                  {schedule.currentOnCall ? (
                    <div className="flex items-center gap-1.5">
                      <Avatar className="h-7 w-7">
                        <AvatarFallback className={cn(
                          'text-xs text-white',
                          avatarColors[(schedule.currentOnCall.userId || 0) % avatarColors.length]
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
                  ) : (
                    <Badge variant="neutral" size="sm">
                      No one assigned
                    </Badge>
                  )}
                </div>
              ))}
            </div>
          ) : (
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
          )}
        </SectionCard>
      </div>

      {/* Active Alerts - prominent when present */}
      {hasActiveAlerts && (
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
            {activeAlerts.slice(0, 5).map((alert) => {
              const statusCfg = getStatusConfig(alert.status)
              const StatusIcon = statusCfg.icon
              return (
                <Link
                  key={alert.id}
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
            })}
          </div>
        </SectionCard>
      )}

    </div>
  )
}
