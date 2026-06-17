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
import { Input } from "@/components/ui/input"
import {useToast} from '@/hooks/useToast'
import {AlertTriangle, CheckCircle, Clock, MessageSquare, ArrowLeft, Zap, UserPlus, Bell, CheckCircle2, Eye, Send} from 'lucide-react'
import {useState, useEffect, useRef} from 'react'
import {cn} from '@/lib/utils'
import {isUuidResourceId} from '@/lib/api/utils'

export const Route = createFileRoute('/on-call/alerts/$alertId')({
  component: IncidentDetailPage,
})

// Priority / status mapped onto the shared status language.
function priorityBadgeVariant(priority: string): BadgeProps['variant'] {
  if (priority.startsWith('P0') || priority.startsWith('P1')) return 'danger'
  if (priority.startsWith('P2')) return 'warning'
  if (priority.startsWith('P3')) return 'info'
  return 'neutral'
}

type IncidentStatusKind = 'danger' | 'warning' | 'success'

const getStatusConfig = (
  status: string,
): {variant: BadgeProps['variant']; icon: typeof Zap; label: string; kind: IncidentStatusKind} => {
  if (status === 'TRIGGERED') return {variant: 'danger', icon: Zap, label: 'Triggered', kind: 'danger'}
  if (status === 'ACKNOWLEDGED') return {variant: 'warning', icon: Clock, label: 'Acknowledged', kind: 'warning'}
  return {variant: 'success', icon: CheckCircle2, label: 'Resolved', kind: 'success'}
}

// Timeline event icons keep distinct categorical tints from the shared chart
// palette (literal classes so Tailwind emits them); status-laden events lean on
// the status tokens.
const EVENT_CONFIG: Record<string, {icon: typeof Zap; color: string; bgColor: string; label: string}> = {
  TRIGGERED: {icon: Zap, color: 'text-danger-fg', bgColor: 'bg-danger-bg', label: 'Alert triggered'},
  ESCALATED: {icon: Bell, color: 'text-warning-fg', bgColor: 'bg-warning-bg', label: 'Escalated'},
  ACKNOWLEDGED: {icon: CheckCircle, color: 'text-info-fg', bgColor: 'bg-info-bg', label: 'Acknowledged'},
  RESOLVED: {icon: CheckCircle2, color: 'text-success-fg', bgColor: 'bg-success-bg', label: 'Resolved'},
  REASSIGNED: {icon: UserPlus, color: 'text-chart-5', bgColor: 'bg-chart-5/15', label: 'Reassigned'},
  NOTE_ADDED: {icon: MessageSquare, color: 'text-muted-foreground', bgColor: 'bg-muted', label: 'Note added'},
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

function getTimelineDescription(event: IncidentTimeline): string | null {
  if (event.eventType === 'NOTIFICATION_SENT' && event.details) {
    const toName = detailString(event.details.toUserName) ?? event.actorUserName
    const channel = detailString(event.details.channel)
    if (toName && channel) return `to ${toName} via ${channel}`
    if (toName) return `to ${toName}`
    if (channel) return `via ${channel}`
  }
  if (event.eventType === 'ESCALATED' && event.details?.stepNumber !== undefined) {
    return `to step ${Number(event.details.stepNumber) + 1}`
  }
  const reassignedUser = detailString(event.details?.toUserName)
  if (event.eventType === 'REASSIGNED' && reassignedUser) {
    return `to ${reassignedUser}`
  }
  if (event.eventType === 'REASSIGNED' && event.details?.reason === 'unavailable') {
    return 'user marked as unavailable'
  }
  return null
}

function IncidentDetailPage() {
  const {alertId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [note, setNote] = useState('')
  const [declareOpen, setDeclareOpen] = useState(false)
  const [declareTitle, setDeclareTitle] = useState('')
  const [declareDesc, setDeclareDesc] = useState('')
  const [declareSeverity, setDeclareSeverity] = useState('SEV-2')
  const normalizedAlertId = alertId.trim()
  const hasValidAlertId = isUuidResourceId(normalizedAlertId)
  const alertQueryKey = ['incident', normalizedAlertId] as const
  const alertTimelineQueryKey = ['incident-timeline', normalizedAlertId] as const

  const {data: incident, isLoading} = useQuery({
    queryKey: alertQueryKey,
    queryFn: () => api.getIncident(normalizedAlertId),
    enabled: hasValidAlertId,
  })

  // Initialize declare form when dialog opens
  const lastInitializedRef = useRef<string | null>(null)
  useEffect(() => {
    if (declareOpen && incident && lastInitializedRef.current !== incident.id) {
      lastInitializedRef.current = incident.id
      setDeclareTitle(incident.title)
      setDeclareDesc(incident.description || '')
      setDeclareSeverity('SEV-2')
    }
  }, [declareOpen, incident])

  const declareMutation = useMutation({
    mutationFn: () =>
      api.declareIncident(normalizedAlertId, {
        title: declareTitle,
        description: declareDesc,
        severity: declareSeverity,
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({queryKey: alertQueryKey}),
        queryClient.invalidateQueries({queryKey: alertTimelineQueryKey}),
        queryClient.invalidateQueries({queryKey: ['alerts']}),
      ])
      setDeclareOpen(false)
      toast({title: 'Incident Declared', description: 'New incident created successfully.'})
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    }
  })

  const {data: timeline = [], isLoading: timelineLoading} = useQuery({
    queryKey: alertTimelineQueryKey,
    queryFn: () => api.getIncidentTimeline(normalizedAlertId),
    enabled: hasValidAlertId,
  })

  // Mark as viewed (deduplicated on backend)
  const incidentLoaded = !!incident
  useEffect(() => {
    if (hasValidAlertId && incidentLoaded) {
      api.viewIncident(normalizedAlertId).catch(() => {})
    }
  }, [normalizedAlertId, hasValidAlertId, incidentLoaded])

  const acknowledgeMutation = useMutation({
    mutationFn: () => api.acknowledgeIncident(normalizedAlertId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: alertQueryKey})
      queryClient.invalidateQueries({queryKey: alertTimelineQueryKey})
      queryClient.invalidateQueries({queryKey: ['alerts']})
      toast({
        title: 'Alert Acknowledged',
        description: 'You have acknowledged this alert.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const resolveMutation = useMutation({
    mutationFn: () => api.resolveIncident(normalizedAlertId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: alertQueryKey})
      queryClient.invalidateQueries({queryKey: alertTimelineQueryKey})
      queryClient.invalidateQueries({queryKey: ['alerts']})
      toast({
        title: 'Alert Resolved',
        description: 'This alert has been marked as resolved.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const markUnavailableMutation = useMutation({
    mutationFn: () => api.markUnavailable(normalizedAlertId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: alertQueryKey})
      queryClient.invalidateQueries({queryKey: alertTimelineQueryKey})
      queryClient.invalidateQueries({queryKey: ['alerts']})
      toast({
        title: 'Marked Unavailable',
        description: 'You have been marked as unavailable. The alert will be escalated.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const addNoteMutation = useMutation({
    mutationFn: (note: string) => api.addIncidentNote(normalizedAlertId, note),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: alertQueryKey})
      queryClient.invalidateQueries({queryKey: alertTimelineQueryKey})
      setNote('')
      toast({title: 'Note Added', description: 'Your note has been added to the alert timeline.'})
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  if (!hasValidAlertId) {
    return (
      <EmptyState
        icon={AlertTriangle}
        title="Invalid alert ID"
        description="The alert identifier in the URL is invalid."
      />
    )
  }

  if (isLoading || timelineLoading) {
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
        title="Alert not found"
        description="This alert may have been removed or you may not have access to it."
      />
    )
  }

  const incidentTimeline = incident.timeline ?? timeline
  const statusCfg = getStatusConfig(incident.status)
  const StatusIcon = statusCfg.icon

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Button variant="ghost" size="icon" className="mt-1 flex-shrink-0" onClick={() => navigate({to: '/on-call/alerts'})}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-3 mb-2">
            <Badge variant={statusCfg.variant} size="sm" className="gap-1">
              <StatusIcon className="h-3 w-3" />
              {statusCfg.label}
            </Badge>
            <Badge variant={priorityBadgeVariant(incident.priority)} size="sm">
              {incident.priority}
            </Badge>
            <span className="text-xs text-muted-foreground font-mono">#{incident.id}</span>
          </div>
          <h2 className="text-2xl font-bold tracking-tight">{incident.title}</h2>
          <p className="text-sm text-muted-foreground mt-1">
            Triggered {timeAgo(incident.triggeredAt)} · {new Date(incident.triggeredAt).toLocaleString()}
          </p>
        </div>
      </div>

      {/* Action Banner */}
      {incident.status !== 'RESOLVED' && (
        <div className={cn(
          'flex items-center justify-between p-4 rounded-xl border',
          incident.status === 'TRIGGERED'
            ? 'bg-danger-bg border-danger-border'
            : 'bg-warning-bg border-warning-border'
        )}>
          <div className="flex items-center gap-3">
            <div className={cn(
              'flex items-center justify-center h-10 w-10 rounded-full',
              incident.status === 'TRIGGERED' ? 'bg-danger-bg' : 'bg-warning-bg'
            )}>
              <StatusIcon className={cn('h-5 w-5', incident.status === 'TRIGGERED' ? 'text-danger-fg' : 'text-warning-fg')} />
            </div>
            <div>
              <p className="font-medium text-sm">
                {incident.status === 'TRIGGERED' ? 'This alert needs attention' : 'Alert acknowledged'}
              </p>
              <p className="text-xs text-muted-foreground">
                {incident.status === 'TRIGGERED'
                  ? 'Acknowledge to assign yourself, or resolve directly'
                  : `Acknowledged by ${incident.acknowledgedByName || 'you'}`}
              </p>
            </div>
          </div>
          <div className="flex flex-col items-end gap-1">
            <div className="flex gap-2">
              {incident.status === 'TRIGGERED' && (
                <>
                  <Button
                    onClick={() => acknowledgeMutation.mutate()}
                    disabled={acknowledgeMutation.isPending}
                    size="sm"
                  >
                    <Clock className="h-4 w-4 mr-2" />
                    Acknowledge
                  </Button>
                  <Button
                    onClick={() => resolveMutation.mutate()}
                    disabled={resolveMutation.isPending}
                    variant="outline"
                    size="sm"
                    className="border-success-border text-success-fg hover:bg-success-bg"
                  >
                    <CheckCircle2 className="h-4 w-4 mr-2" />
                    Resolve
                  </Button>
                </>
              )}
              {incident.status === 'ACKNOWLEDGED' && (
                <Button
                  onClick={() => resolveMutation.mutate()}
                  disabled={resolveMutation.isPending}
                  size="sm"
                >
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  Resolve
                </Button>
              )}
            </div>
            <Button
              variant="link"
              size="sm"
              className="h-auto p-0 text-muted-foreground hover:text-foreground text-xs"
              onClick={() => markUnavailableMutation.mutate()}
              disabled={markUnavailableMutation.isPending}
            >
              I'm not available
            </Button>

            <Dialog open={declareOpen} onOpenChange={setDeclareOpen}>
              <DialogTrigger asChild>
                <Button variant="link" size="sm" className="h-auto p-0 text-muted-foreground hover:text-foreground text-xs">
                  Declare Incident
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Declare Incident</DialogTitle>
                  <DialogDescription>
                    Escalate this alert to a formal incident.
                  </DialogDescription>
                </DialogHeader>
                <div className="grid gap-4 py-4">
                  <div className="grid gap-2">
                    <Label htmlFor="title">Title</Label>
                    <Input id="title" value={declareTitle} onChange={(e) => setDeclareTitle(e.target.value)} />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="severity">Severity</Label>
                    <div className="flex gap-2">
                      {['SEV-0', 'SEV-1', 'SEV-2', 'SEV-3', 'SEV-4'].map((sev) => (
                        <Button
                          key={sev}
                          type="button"
                          variant={declareSeverity === sev ? 'default' : 'outline'}
                          size="sm"
                          onClick={() => setDeclareSeverity(sev)}
                        >
                          {sev}
                        </Button>
                      ))}
                    </div>
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="description">Description</Label>
                    <Textarea id="description" value={declareDesc} onChange={(e) => setDeclareDesc(e.target.value)} />
                  </div>
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setDeclareOpen(false)}>Cancel</Button>
                  <Button onClick={() => declareMutation.mutate()} disabled={declareMutation.isPending}>
                    {declareMutation.isPending ? 'Declaring...' : 'Declare Incident'}
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

          {/* Timeline */}
          <SectionCard title="Timeline" icon={Clock} iconTone="muted">
            <p className="-mt-1 mb-3 text-xs text-muted-foreground">Alert history and updates</p>
            <div className="relative">
                {incidentTimeline.map((event: IncidentTimeline, idx: number) => {
                  const config = EVENT_CONFIG[event.eventType] || {
                    icon: Clock,
                    color: 'text-muted-foreground',
                    bgColor: 'bg-muted',
                    label: event.eventType.replaceAll('_', ' '),
                  }
                  const Icon = config.icon
                  const description = getTimelineDescription(event)
                  const note =
                    event.eventType === 'NOTE_ADDED' && event.details
                      ? detailString(event.details.note)
                      : null

                  return (
                    <div key={event.id} className="flex gap-3 pb-6 last:pb-0 relative">
                      {idx < incidentTimeline.length - 1 && (
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
                          </p>
                          <span className="text-xs text-muted-foreground flex-shrink-0">
                            {timeAgo(event.createdAt)}
                          </span>
                        </div>
                        {event.actorUserName && event.eventType !== 'NOTIFICATION_SENT' && (
                          <p className="text-xs text-muted-foreground mt-0.5">
                            by {event.actorUserName}
                          </p>
                        )}
                        {description && (
                          <p className="text-xs text-muted-foreground mt-0.5">{description}</p>
                        )}
                        {note && (
                          <p className="italic bg-muted/50 rounded-lg p-2.5 mt-1.5 text-xs text-muted-foreground">&quot;{note}&quot;</p>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
          </SectionCard>

          {/* Add Note - Always visible regardless of status */}
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
                Add note
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
                <Badge variant={priorityBadgeVariant(incident.priority)}>
                  {incident.priority}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Alert Source</p>
                <p className="text-sm">{incident.alertSource || 'Unknown'}</p>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Triggered At</p>
                <p className="text-sm">{new Date(incident.triggeredAt).toLocaleString()}</p>
              </div>
              {incident.acknowledgedBy && (
                <div>
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Acknowledged By</p>
                  <p className="text-sm">
                    {incident.acknowledgedByName}
                    <span className="text-xs text-muted-foreground block mt-0.5">
                      {new Date(incident.acknowledgedAt!).toLocaleString()}
                    </span>
                  </p>
                </div>
              )}
              {incident.resolvedBy && (
                <div>
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Resolved By</p>
                  <p className="text-sm">
                    {incident.resolvedByName}
                    <span className="text-xs text-muted-foreground block mt-0.5">
                      {new Date(incident.resolvedAt!).toLocaleString()}
                    </span>
                  </p>
                </div>
              )}
          </SectionCard>
        </div>
      </div>
    </div>
  )
}
