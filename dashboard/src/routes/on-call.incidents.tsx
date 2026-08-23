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

import {createFileRoute, Link, Outlet, useNavigate, useRouterState} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {PageHeader} from '@/components/ui/page-header'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {Filter, Zap, CheckCircle2, ChevronRight, Clock, FileText, Plus, ShieldAlert} from 'lucide-react'
import {useState} from 'react'
import {cn} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {incidentStatusConfig} from '@/lib/incident-status'
import type {DeclareIncidentInput, OnCallIncident, OnCallIncidentStatus} from '@/lib/api/types'
import {IncidentDeclarationForm} from '@/components/on-call/IncidentDeclarationForm'
import {IncidentTriageActions, TriageSeverityBadge} from '@/components/on-call/IncidentTriageActions'
import {isTriageIncident} from '@/components/on-call/triage'
import {
  nativeIncidentUnavailableCopy,
  useNativeIncidentRollout,
} from '@/hooks/useNativeIncidentRollout'

export const Route = createFileRoute('/on-call/incidents')({
  component: DeclaredIncidents,
})

type IncidentSeverity = 'SEV-0' | 'SEV-1' | 'SEV-2' | 'SEV-3' | 'SEV-4'
type IncidentStatusFilter = OnCallIncidentStatus | 'all'
type IncidentSeverityFilter = IncidentSeverity | 'all'

const DEFAULT_DECLARED_INCIDENT_STATUS_FILTER: OnCallIncidentStatus = 'ACTIVE'

// Incident severity mapped onto the shared status language. Triage incidents may not
// carry a severity yet, so an absent value reads as unclassified.
const UNCLASSIFIED_SEVERITY_LABEL = 'Unclassified'

function severityTone(severity: string | undefined): StatusTone {
  if (!severity) return 'neutral'
  const severityMatch = /^SEV-?(\d)/i.exec(severity)
  const severityLevel = severityMatch?.[1]
  if (severityLevel === '0' || severityLevel === '1') return 'danger'
  if (severityLevel === '2') return 'warning'
  if (severityLevel === '3') return 'info'
  return 'neutral'
}

function severityBadgeVariant(severity: string | undefined): BadgeProps['variant'] {
  switch (severityTone(severity)) {
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
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [statusFilter, setStatusFilter] =
    useState<IncidentStatusFilter>(DEFAULT_DECLARED_INCIDENT_STATUS_FILTER)
  const [severityFilter, setSeverityFilter] = useState<IncidentSeverityFilter>('all')
  const [declareOpen, setDeclareOpen] = useState(false)
  const [triageOnly, setTriageOnly] = useState(false)
  const rollout = useNativeIncidentRollout()
  const isDetailRoute = pathname.startsWith('/on-call/incidents/')

  const declareMutation = useMutation({
    mutationFn: (input: DeclareIncidentInput) => api.declareOnCallIncident(input),
    onSuccess: (incident) => {
      queryClient.invalidateQueries({queryKey: ['on-call-incidents']})
      setDeclareOpen(false)
      toast({title: 'Incident declared', description: 'Your incident has been created.'})
      if (incident?.id) {
        navigate({to: '/on-call/incidents/$incidentId', params: {incidentId: incident.id}})
      }
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const {data: incidents, isLoading} = useQuery({
    queryKey: ['on-call-incidents', statusFilter, severityFilter],
    queryFn: () => {
      const filters: { status?: string; severity?: string } = {}
      if (statusFilter !== 'all') filters.status = statusFilter
      if (severityFilter !== 'all') filters.severity = severityFilter
      return api.getOnCallIncidents(filters)
    },
    refetchInterval: 30000,
    // Never fetch native incidents while the rollout is loading or disabled.
    enabled: rollout.enabled,
  })

  // The triage queue is its own always-on surface: responders act on unclassified
  // incidents without hunting through the status filter. Fetched separately so the
  // count and queue stay visible regardless of the main list's filter.
  const {data: triageIncidents} = useQuery({
    queryKey: ['on-call-incidents', 'triage-queue'],
    queryFn: () => api.getOnCallIncidents({status: 'TRIAGE'}),
    refetchInterval: 30000,
    enabled: rollout.enabled,
  })

  // Treat the server-side status filter as an optimization, not a trust boundary.
  // This also prevents stale/intermediate cache data from duplicating active
  // incidents in the triage queue.
  const triageQueue = (triageIncidents ?? []).filter((incident) =>
    isTriageIncident(incident.status)
  )
  const triageCount = triageQueue.length
  const activeCount = incidents?.filter(i => i.status === 'ACTIVE').length || 0
  const resolvedCount = incidents?.filter(i => i.status === 'RESOLVED').length || 0
  // Triage incidents live in the dedicated queue, so never duplicate them in the
  // main list (they can otherwise appear under the "all statuses" filter).
  const listIncidents = incidents?.filter((incident) => !isTriageIncident(incident.status)) ?? []
  const hasIncidents = listIncidents.length > 0

  if (isDetailRoute) {
    return <Outlet />
  }

  if (rollout.isLoading) {
    return (
      <div className="flex items-center justify-center py-10">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!rollout.enabled) {
    const copy = nativeIncidentUnavailableCopy(rollout)
    return (
      <div className="space-y-4">
        <PageHeader icon={ShieldAlert} title="Incidents" description="Manage user-declared incidents" />
        <EmptyState icon={ShieldAlert} title={copy.title} description={copy.description} />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon={ShieldAlert}
        title="Incidents"
        description="Manage user-declared incidents"
        actions={
          <Button size="sm" onClick={() => setDeclareOpen(true)}>
            <Plus className="mr-2 h-4 w-4" />
            Declare incident
          </Button>
        }
      />

      <Dialog open={declareOpen} onOpenChange={setDeclareOpen}>
        <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Declare incident</DialogTitle>
            <DialogDescription>
              Open a new incident. Choose a type to capture the configured details for your process.
            </DialogDescription>
          </DialogHeader>
          {declareOpen && (
            <IncidentDeclarationForm
              isSubmitting={declareMutation.isPending}
              onSubmit={(input) => declareMutation.mutate(input)}
              onCancel={() => setDeclareOpen(false)}
            />
          )}
        </DialogContent>
      </Dialog>

      {/* Stats Row */}
      {!isLoading && (triageCount > 0 || (incidents && incidents.length > 0)) && (
        <div className="flex flex-wrap gap-1.5">
          {triageCount > 0 && (
            <button
              onClick={() => setTriageOnly((prev) => !prev)}
              aria-pressed={triageOnly}
              className={cn(
                'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-xs font-medium transition-colors',
                triageOnly
                  ? 'bg-warning-bg border-warning-border text-warning-fg'
                  : 'hover:bg-muted/60'
              )}
            >
              <Clock className="h-3 w-3" />
              <span>{triageCount} Needs triage</span>
            </button>
          )}
          <button
            onClick={() => {
              setTriageOnly(false)
              setStatusFilter(statusFilter === 'ACTIVE' ? 'all' : 'ACTIVE')
            }}
            className={cn(
              'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-xs font-medium transition-colors',
              !triageOnly && statusFilter === 'ACTIVE'
                ? 'bg-danger-bg border-danger-border text-danger-fg'
                : 'hover:bg-muted/60'
            )}
          >
            <Zap className="h-3 w-3" />
            <span>{activeCount} Active</span>
          </button>
          <button
            onClick={() => {
              setTriageOnly(false)
              setStatusFilter(statusFilter === 'RESOLVED' ? 'all' : 'RESOLVED')
            }}
            className={cn(
              'flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-xs font-medium transition-colors',
              !triageOnly && statusFilter === 'RESOLVED'
                ? 'bg-success-bg border-success-border text-success-fg'
                : 'hover:bg-muted/60'
            )}
          >
            <CheckCircle2 className="h-3 w-3" />
            <span>{resolvedCount} Resolved</span>
          </button>
        </div>
      )}

      {/* Triage queue */}
      {triageCount > 0 && (
        <TriageQueue incidents={triageQueue} />
      )}

      {(!triageOnly || triageCount === 0) && (
      <>
      {/* Filters */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Filter className="h-3 w-3" />
          <span>Filter:</span>
        </div>
        <div className="w-44">
          <Select value={statusFilter} onValueChange={(value) => setStatusFilter(value as IncidentStatusFilter)}>
            <SelectTrigger className="h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Statuses</SelectItem>
              <SelectItem value="ACTIVE">Active</SelectItem>
              <SelectItem value="RESOLVED">Resolved</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="w-44">
          <Select value={severityFilter} onValueChange={(value) => setSeverityFilter(value as IncidentSeverityFilter)}>
            <SelectTrigger className="h-8">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All Severities</SelectItem>
              <SelectItem value="SEV-0">SEV-0</SelectItem>
              <SelectItem value="SEV-1">SEV-1</SelectItem>
              <SelectItem value="SEV-2">SEV-2</SelectItem>
              <SelectItem value="SEV-3">SEV-3</SelectItem>
              <SelectItem value="SEV-4">SEV-4</SelectItem>
            </SelectContent>
          </Select>
        </div>
        {(statusFilter !== 'all' || severityFilter !== 'all') && (
          <button
            onClick={() => {setStatusFilter('all'); setSeverityFilter('all')}}
            className="text-xs text-muted-foreground hover:text-foreground underline"
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Incidents List */}
      {isLoading && (
        <div className="flex items-center justify-center py-10">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
        </div>
      )}
      {!isLoading && hasIncidents && (
        <div className="space-y-2">
          {listIncidents.map((incident) => {
            const statusCfg = incidentStatusConfig(incident.status)
            const StatusIcon = statusCfg.icon
            return (
              <Link
                key={incident.id}
                to="/on-call/incidents/$incidentId"
                params={{incidentId: String(incident.id)}}
                className="block group"
              >
                <Card className={cn(
                  'transition-colors border-l-[3px]',
                  incident.status === 'ACTIVE' && 'border-l-danger-solid hover:border-danger-border',
                  incident.status === 'RESOLVED' && 'border-l-transparent hover:border-muted-foreground/20',
                )}>
                  <CardContent className="p-3">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-start gap-3 min-w-0">
                        <StatusDot tone={severityTone(incident.severity)} className="mt-2" />
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
                        <Badge variant={severityBadgeVariant(incident.severity)} size="sm">
                          {incident.severity ?? UNCLASSIFIED_SEVERITY_LABEL}
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
      )}
      {!isLoading && !hasIncidents && (
        <EmptyState
          icon={FileText}
          title="No incidents found"
          description={
            statusFilter !== 'all' || severityFilter !== 'all'
              ? 'No incidents match your current filters. Try adjusting or clearing filters.'
              : 'User-declared incidents will appear here.'
          }
        />
      )}
      </>
      )}
    </div>
  )
}

// Dedicated triage queue: unclassified incidents a responder must accept, merge,
// or decline. Rendered above the main list so the work is never buried. Each row's
// title links to the detail view while the action controls sit outside the link.
function TriageQueue({incidents}: Readonly<{incidents: OnCallIncident[]}>) {
  return (
    <Card className="border-l-[3px] border-l-warning-solid">
      <CardContent className="p-3">
        <div className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          <Clock className="h-3.5 w-3.5" />
          Needs triage
          <Badge variant="warning" size="sm" className="ml-1">
            {incidents.length}
          </Badge>
        </div>
        <div className="space-y-2">
          {incidents.map((incident) => (
            <TriageQueueRow key={incident.id} incident={incident} />
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function TriageQueueRow({incident}: Readonly<{incident: OnCallIncident}>) {
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <TriageSeverityBadge severity={incident.severity} />
          <Link
            to="/on-call/incidents/$incidentId"
            params={{incidentId: String(incident.id)}}
            className="truncate text-sm font-semibold hover:underline"
          >
            {incident.title}
          </Link>
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
          <span>Declared {timeAgo(incident.declaredAt)}</span>
          {incident.declaredByName && (
            <>
              <span className="text-muted-foreground/40">·</span>
              <span>by {incident.declaredByName}</span>
            </>
          )}
          {incident.alertCount > 0 && (
            <>
              <span className="text-muted-foreground/40">·</span>
              <span>{incident.alertCount} alert{incident.alertCount > 1 ? 's' : ''}</span>
            </>
          )}
        </div>
      </div>
      <div className="shrink-0">
        <IncidentTriageActions incident={incident} layout="inline" />
      </div>
    </div>
  )
}
