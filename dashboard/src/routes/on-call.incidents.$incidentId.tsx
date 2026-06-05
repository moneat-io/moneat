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
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type IncidentTimeline} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Textarea} from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import {useToast} from '@/hooks/useToast'
import {AlertTriangle, CheckCircle2, Clock, ArrowLeft, Zap, User, Calendar, CheckCircle, Bell, UserPlus, MessageSquare, Eye, Send, Link as LinkIcon} from 'lucide-react'
import {useState} from 'react'
import {cn} from '@/lib/utils'

interface DeclaredIncidentDetail {
  id: number
  title: string
  description?: string
  severity: string
  status: string
  declaredAt: string
  declaredByName: string
  resolvedAt?: string
  resolvedBy?: number
  resolvedByName?: string
  alerts?: Array<{id: number; title: string; status: string; priority?: string}>
  [key: string]: unknown
}

interface DeclaredIncidentTimelineEvent extends Omit<IncidentTimeline, 'eventType'> {
  eventType: string
  source?: string
  alertTitle?: string
  actorName?: string
}

export const Route = createFileRoute('/on-call/incidents/$incidentId')({
  component: DeclaredIncidentDetailComponent,
})

// Incident severity mapped onto the shared status language.
function severityBadgeVariant(severity: string): BadgeProps['variant'] {
  if (severity.startsWith('SEV-0') || severity.startsWith('SEV-1')) return 'danger'
  if (severity.startsWith('SEV-2')) return 'warning'
  if (severity.startsWith('SEV-3')) return 'info'
  return 'neutral'
}

const getStatusConfig = (
  status: string,
): {variant: BadgeProps['variant']; icon: typeof Zap; label: string} => {
  if (status === 'OPEN') return {variant: 'danger', icon: Zap, label: 'Open'}
  if (status === 'RESOLVED') return {variant: 'success', icon: CheckCircle2, label: 'Resolved'}
  return {variant: 'neutral', icon: Clock, label: status}
}

// Timeline event icons keep distinct categorical tints from the shared palette
// (literal classes so Tailwind emits them); status-laden events use status tokens.
const EVENT_CONFIG: Record<string, {icon: typeof Zap; color: string; bgColor: string; label: string}> = {
  DECLARED: {icon: Zap, color: 'text-danger-fg', bgColor: 'bg-danger-bg', label: 'Incident declared'},
  RESOLVED: {icon: CheckCircle2, color: 'text-success-fg', bgColor: 'bg-success-bg', label: 'Incident resolved'},
  NOTE_ADDED: {icon: MessageSquare, color: 'text-muted-foreground', bgColor: 'bg-muted', label: 'Note added'},
  ALERT_LINKED: {icon: LinkIcon, color: 'text-chart-5', bgColor: 'bg-chart-5/15', label: 'Alert linked'},
  // Alert-level events
  TRIGGERED: {icon: Zap, color: 'text-danger-fg', bgColor: 'bg-danger-bg', label: 'Alert triggered'},
  ESCALATED: {icon: Bell, color: 'text-warning-fg', bgColor: 'bg-warning-bg', label: 'Escalated'},
  ACKNOWLEDGED: {icon: CheckCircle, color: 'text-info-fg', bgColor: 'bg-info-bg', label: 'Acknowledged'},
  REASSIGNED: {icon: UserPlus, color: 'text-chart-5', bgColor: 'bg-chart-5/15', label: 'Reassigned'},
  STEP_TIMEOUT: {icon: Clock, color: 'text-warning-fg', bgColor: 'bg-warning-bg', label: 'Step timed out'},
  NOTIFICATION_SENT: {icon: Send, color: 'text-chart-6', bgColor: 'bg-chart-6/15', label: 'Notification sent'},
  VIEWED: {icon: Eye, color: 'text-muted-foreground', bgColor: 'bg-muted', label: 'Viewed'},
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

function detailString(value: unknown): string | null {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return null
}

function getTimelineDescription(event: DeclaredIncidentTimelineEvent): string | null {
  switch (event.eventType) {
    case 'NOTIFICATION_SENT':
      return notificationDescription(event)
    case 'ESCALATED':
      if (event.details?.stepNumber === undefined) return null
      return `to step ${Number(event.details.stepNumber) + 1}`
    case 'REASSIGNED':
      return reassignmentDescription(event)
    case 'ALERT_LINKED':
      return detailString(event.details?.alertTitle)
    default:
      return null
  }
}

function notificationDescription(event: DeclaredIncidentTimelineEvent): string | null {
  if (!event.details) return null
  const toName = detailString(event.details.toUserName) ?? event.actorUserName
  const channel = detailString(event.details.channel)
  if (toName && channel) return `to ${toName} via ${channel}`
  if (toName) return `to ${toName}`
  if (channel) return `via ${channel}`
  return null
}

function reassignmentDescription(event: DeclaredIncidentTimelineEvent): string | null {
  const toName = detailString(event.details?.toUserName)
  if (toName) return `to ${toName}`
  return event.details?.reason === 'unavailable' ? 'user marked as unavailable' : null
}

function DeclaredIncidentDetailComponent() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [resolveOpen, setResolveOpen] = useState(false)
  const [resolutionNote, setResolutionNote] = useState('')
  const [note, setNote] = useState('')

  const {data: incident, isLoading} = useQuery({
    queryKey: ['declared-incident', incidentId],
    queryFn: async () => {
      const result = await api.getOnCallIncident(Number(incidentId))
      return result as unknown as DeclaredIncidentDetail
    },
  })

  const {data: timeline} = useQuery({
    queryKey: ['declared-incident-timeline', incidentId],
    queryFn: () => api.getOnCallIncidentTimeline(Number(incidentId)) as Promise<DeclaredIncidentTimelineEvent[]>,
  })

  const resolveMutation = useMutation({
    mutationFn: () => api.resolveOnCallIncident(Number(incidentId)),
    onSuccess: () => {
      setResolveOpen(false)
      queryClient.invalidateQueries({queryKey: ['declared-incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['declared-incident-timeline', incidentId]})
      queryClient.invalidateQueries({queryKey: ['on-call-incidents']})
      toast({
        title: 'Incident Resolved',
        description: 'This incident has been marked as resolved.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const addNoteMutation = useMutation({
    mutationFn: (note: string) => api.addOnCallIncidentNote(Number(incidentId), note),
    onSuccess: () => {
      setNote('')
      queryClient.invalidateQueries({queryKey: ['declared-incident-timeline', incidentId]})
      toast({
        title: 'Note Added',
        description: 'Your note has been added to the incident timeline.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!incident) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Incident not found"
        description="This incident may have been removed or you may not have access to it."
      />
    )
  }

  const statusCfg = getStatusConfig(incident.status)
  const StatusIcon = statusCfg.icon

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Button variant="ghost" size="icon" className="mt-1 flex-shrink-0" onClick={() => navigate({to: '/on-call/incidents'})}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3 mb-2">
            <Badge variant={statusCfg.variant} size="sm" className="gap-1">
              <StatusIcon className="h-3 w-3" />
              {statusCfg.label}
            </Badge>
            <Badge variant={severityBadgeVariant(incident.severity)} size="sm">
              {incident.severity}
            </Badge>
            <span className="text-xs text-muted-foreground font-mono">#{incident.id}</span>
          </div>
          <h2 className="text-2xl font-bold tracking-tight">{incident.title}</h2>
          <p className="text-sm text-muted-foreground mt-1">
            Declared {timeAgo(incident.declaredAt)} by {incident.declaredByName}
          </p>
        </div>
      </div>

      {/* Action Banner */}
      {incident.status !== 'RESOLVED' && (
        <div className="flex items-center justify-between p-4 rounded-xl border bg-danger-bg border-danger-border">
          <div className="flex items-center gap-3">
            <div className="flex items-center justify-center h-10 w-10 rounded-full bg-danger-bg">
              <StatusIcon className="h-5 w-5 text-danger-fg" />
            </div>
            <div>
              <p className="font-medium text-sm">
                This incident is currently open
              </p>
              <p className="text-xs text-muted-foreground">
                Investigate and resolve when complete
              </p>
            </div>
          </div>
          <div className="flex flex-col items-end gap-1">
             <Dialog open={resolveOpen} onOpenChange={setResolveOpen}>
              <DialogTrigger asChild>
                <Button size="sm">
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  Resolve incident
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Resolve Incident</DialogTitle>
                  <DialogDescription>
                    Are you sure you want to resolve this incident? This action cannot be undone.
                  </DialogDescription>
                </DialogHeader>
                <div className="grid gap-2 py-2">
                    <Label htmlFor="resolutionNote">Resolution Note (Optional)</Label>
                    <Textarea
                        id="resolutionNote"
                        value={resolutionNote}
                        onChange={(e) => setResolutionNote(e.target.value)}
                        placeholder="What was the fix?"
                    />
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setResolveOpen(false)}>Cancel</Button>
                  <Button onClick={() => resolveMutation.mutate()} disabled={resolveMutation.isPending}>
                    {resolveMutation.isPending ? 'Resolving...' : 'Resolve Incident'}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Main Column */}
        <div className="lg:col-span-2 space-y-6">
          {/* Description */}
          {incident.description && (
            <SectionCard title="Description" icon={MessageSquare} iconTone="muted">
              <p className="text-sm text-muted-foreground leading-relaxed">{incident.description}</p>
            </SectionCard>
          )}

          {/* Linked Alerts */}
          {incident.alerts && incident.alerts.length > 0 && (
            <SectionCard
              title="Linked alerts"
              icon={LinkIcon}
              iconTone="accent"
              count={incident.alerts.length}
              bodyClassName="space-y-2"
            >
              {incident.alerts.map((alert: {id: number; title: string; status: string}) => (
                <div key={alert.id} className="flex items-center justify-between p-3 border rounded-lg hover:bg-accent/40 transition-colors">
                  <div className="flex-1">
                    <p className="text-sm font-medium">{alert.title}</p>
                    <p className="text-xs text-muted-foreground mt-0.5">
                      Alert #{alert.id} · {alert.status}
                    </p>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => navigate({to: '/on-call/alerts/$alertId', params: {alertId: String(alert.id)}})}
                  >
                    View
                  </Button>
                </div>
              ))}
            </SectionCard>
          )}

          {/* Timeline */}
          {timeline && timeline.length > 0 && (
            <SectionCard title="Timeline" icon={Clock} iconTone="muted">
                <p className="-mt-1 mb-3 text-xs text-muted-foreground">Incident history and linked alert events</p>
                <div className="relative">
                  {timeline.map((event: DeclaredIncidentTimelineEvent, idx: number) => {
                    const config = EVENT_CONFIG[event.eventType] || {
                      icon: Clock,
                      color: 'text-muted-foreground',
                      bgColor: 'bg-muted',
                      label: event.eventType.replace(/_/g, ' '),
                    }
                    const Icon = config.icon
                    const description = getTimelineDescription(event)

                    return (
                      <div key={`${event.source}-${event.id}`} className="flex gap-3 pb-6 last:pb-0 relative">
                        {idx < timeline.length - 1 && (
                          <div className="absolute left-[15px] top-8 bottom-0 w-px bg-border" />
                        )}
                        <div className={cn(
                          'flex-shrink-0 flex items-center justify-center h-8 w-8 rounded-full z-10',
                          config.bgColor
                        )}>
                          <Icon className={cn('h-4 w-4', config.color)} />
                        </div>
                        <div className="flex-1 min-w-0 pt-0.5">
                          <div className="flex items-center justify-between gap-2">
                            <p className="text-sm font-medium">
                              {config.label}
                              {event.source === 'alert' && event.alertTitle && (
                                <span className="text-xs text-muted-foreground ml-2">
                                  from {event.alertTitle}
                                </span>
                              )}
                            </p>
                            <span className="text-xs text-muted-foreground flex-shrink-0">
                              {timeAgo(event.createdAt)}
                            </span>
                          </div>
                          {event.actorName && event.eventType !== 'NOTIFICATION_SENT' && (
                            <p className="text-xs text-muted-foreground mt-0.5">
                              by {event.actorName}
                            </p>
                          )}
                          {description && (
                            <p className="text-xs text-muted-foreground mt-0.5">{description}</p>
                          )}
                          {event.details && event.eventType === 'NOTE_ADDED' && !!event.details.note && (
                            <p className="italic bg-muted/50 rounded-lg p-2.5 mt-1.5 text-xs text-muted-foreground">&quot;{String(event.details.note)}&quot;</p>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>
            </SectionCard>
          )}

          {/* Add Note */}
          <SectionCard title="Add note" icon={MessageSquare} iconTone="accent" bodyClassName="space-y-3">
              <Textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Enter your note..."
                rows={3}
                className="resize-none"
              />
              <Button
                onClick={() => addNoteMutation.mutate(note)}
                disabled={!note.trim() || addNoteMutation.isPending}
                size="sm"
              >
                <MessageSquare className="h-4 w-4 mr-2" />
                {addNoteMutation.isPending ? 'Adding...' : 'Add note'}
              </Button>
          </SectionCard>
        </div>

        {/* Sidebar */}
        <div className="space-y-4">
          <SectionCard title="Details" bodyClassName="space-y-4">
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Status</p>
                <Badge variant={statusCfg.variant} className="gap-1">
                  <StatusIcon className="h-3 w-3" />
                  {statusCfg.label}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Priority</p>
                <Badge variant={severityBadgeVariant(incident.severity)}>
                  {incident.severity}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Declared At</p>
                <div className="flex items-center gap-2 text-sm">
                    <Calendar className="h-3.5 w-3.5 text-muted-foreground" />
                    {new Date(incident.declaredAt).toLocaleString()}
                </div>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Declared By</p>
                <div className="flex items-center gap-2 text-sm">
                    <User className="h-3.5 w-3.5 text-muted-foreground" />
                    {incident.declaredByName}
                </div>
              </div>
              {incident.resolvedBy && (
                <div>
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Resolved By</p>
                  <div className="flex items-center gap-2 text-sm">
                     <User className="h-3.5 w-3.5 text-muted-foreground" />
                    {incident.resolvedByName}
                  </div>
                  <span className="text-xs text-muted-foreground block mt-0.5 ml-5">
                      {new Date(incident.resolvedAt!).toLocaleString()}
                  </span>
                </div>
              )}
          </SectionCard>
        </div>
      </div>
    </div>
  )
}
