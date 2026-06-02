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

import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent} from '@/components/ui/card'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {PageHeader} from '@/components/ui/page-header'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {Filter, Zap, Clock, CheckCircle2, ChevronRight, FileText, ShieldAlert} from 'lucide-react'
import {useState} from 'react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/declared-incidents')({
  component: DeclaredIncidents,
})

const DEFAULT_DECLARED_INCIDENT_STATUS_FILTER = 'OPEN'

// Priority / status mapped onto the shared status language.
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

const getStatusConfig = (status: string): {variant: BadgeProps['variant']; icon: typeof Zap; label: string} => {
  if (status === 'OPEN') return {variant: 'danger', icon: Zap, label: 'Open'}
  if (status === 'RESOLVED') return {variant: 'success', icon: CheckCircle2, label: 'Resolved'}
  return {variant: 'neutral', icon: Clock, label: status}
}

function timeAgo(date: string) {
  const seconds = Math.floor((Date.now() - new Date(date).getTime()) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

function DeclaredIncidents() {
  const pathname = useRouterState({select: state => state.location.pathname})
  const [statusFilter, setStatusFilter] = useState<string>(DEFAULT_DECLARED_INCIDENT_STATUS_FILTER)
  const [priorityFilter, setPriorityFilter] = useState<string>('all')
  const isDetailRoute = pathname.startsWith('/on-call/declared-incidents/')

  const {data: incidents, isLoading} = useQuery({
    queryKey: ['declared-incidents', statusFilter, priorityFilter],
    queryFn: () => {
      const filters: { status?: string; priorityLevel?: string } = {}
      if (statusFilter !== 'all') filters.status = statusFilter
      if (priorityFilter !== 'all') filters.priorityLevel = priorityFilter
      return api.getOnCallIncidents(filters)
    },
    refetchInterval: 30000,
  })

  const openCount = incidents?.filter(i => i.status === 'OPEN').length || 0
  const resolvedCount = incidents?.filter(i => i.status === 'RESOLVED').length || 0

  if (isDetailRoute) {
    return <Outlet />
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon={ShieldAlert}
        title="Incidents"
        description="Manage user-declared incidents"
      />

      {/* Stats Row */}
      {!isLoading && incidents && incidents.length > 0 && (
        <div className="flex gap-1.5">
          <button
            onClick={() => setStatusFilter(statusFilter === 'OPEN' ? 'all' : 'OPEN')}
            className={cn(
              'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-xs font-medium transition-colors',
              statusFilter === 'OPEN'
                ? 'bg-danger-bg border-danger-border text-danger-fg'
                : 'hover:bg-muted/60'
            )}
          >
            <Zap className="h-3 w-3" />
            <span>{openCount} Open</span>
          </button>
          <button
            onClick={() => setStatusFilter(statusFilter === 'RESOLVED' ? 'all' : 'RESOLVED')}
            className={cn(
              'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-xs font-medium transition-colors',
              statusFilter === 'RESOLVED'
                ? 'bg-success-bg border-success-border text-success-fg'
                : 'hover:bg-muted/60'
            )}
          >
            <CheckCircle2 className="h-3 w-3" />
            <span>{resolvedCount} Resolved</span>
          </button>
        </div>
      )}

      {/* Filters */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Filter className="h-3 w-3" />
          <span>Filter:</span>
        </div>
        <div className="w-44">
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Statuses</SelectItem>
              <SelectItem value="OPEN">Open</SelectItem>
              <SelectItem value="RESOLVED">Resolved</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="w-44">
          <Select value={priorityFilter} onValueChange={setPriorityFilter}>
            <SelectTrigger className="h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Priorities</SelectItem>
              <SelectItem value="P0">P0 - Critical</SelectItem>
              <SelectItem value="P1">P1 - High</SelectItem>
              <SelectItem value="P2">P2 - Medium</SelectItem>
              <SelectItem value="P3">P3 - Low</SelectItem>
              <SelectItem value="P4">P4 - Info</SelectItem>
              <SelectItem value="P5">P5 - None</SelectItem>
            </SelectContent>
          </Select>
        </div>
        {(statusFilter !== 'all' || priorityFilter !== 'all') && (
          <button
            onClick={() => {setStatusFilter('all'); setPriorityFilter('all')}}
            className="text-xs text-muted-foreground hover:text-foreground underline"
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Incidents List */}
      {isLoading ? (
        <div className="flex items-center justify-center py-10">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
        </div>
      ) : incidents && incidents.length > 0 ? (
        <div className="space-y-2">
          {incidents.map((incident) => {
            const statusCfg = getStatusConfig(incident.status)
            const StatusIcon = statusCfg.icon
            return (
              <Link
                key={incident.id}
                to="/on-call/declared-incidents/$incidentId"
                params={{incidentId: String(incident.id)}}
                className="block group"
              >
                <Card className={cn(
                  'transition-colors border-l-[3px]',
                  incident.status === 'OPEN' && 'border-l-danger-solid hover:border-danger-border',
                  incident.status === 'RESOLVED' && 'border-l-transparent hover:border-muted-foreground/20',
                )}>
                  <CardContent className="p-3">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-start gap-3 min-w-0">
                        <StatusDot tone={priorityTone(incident.priorityLevel)} className="mt-2" />
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <h3 className="font-semibold text-sm group-hover:text-foreground truncate">
                              {incident.title}
                            </h3>
                          </div>
                          {incident.description && (
                            <p className="text-sm text-muted-foreground mt-0.5 line-clamp-1">{incident.description}</p>
                          )}
                          <div className="flex items-center gap-3 mt-1.5 text-[11px] text-muted-foreground">
                            <span>Declared {timeAgo(incident.declaredAt)}</span>
                            {incident.declaredByName && (
                              <>
                                <span className="text-muted-foreground/40">·</span>
                                <span>by {incident.declaredByName}</span>
                              </>
                            )}
                            {incident.resolvedByName && (
                              <>
                                <span className="text-muted-foreground/40">·</span>
                                <span className="text-success-fg">Resolved by {incident.resolvedByName}</span>
                              </>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 flex-shrink-0">
                        <Badge variant={priorityBadgeVariant(incident.priorityLevel)} size="sm">
                          {incident.priorityLevel}
                        </Badge>
                        <Badge variant={statusCfg.variant} size="sm" className="gap-1">
                          <StatusIcon className="h-3 w-3" />
                          {statusCfg.label}
                        </Badge>
                        <ChevronRight className="h-3 w-3 text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity" />
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </Link>
            )
          })}
        </div>
      ) : (
        <EmptyState
          icon={FileText}
          title="No incidents found"
          description={
            statusFilter !== 'all' || priorityFilter !== 'all'
              ? 'No incidents match your current filters. Try adjusting or clearing filters.'
              : 'User-declared incidents will appear here.'
          }
        />
      )}
    </div>
  )
}
