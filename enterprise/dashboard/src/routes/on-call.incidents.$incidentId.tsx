// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type IncidentTimeline} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
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
import {useToast} from '@/hooks/use-toast'
import {AlertTriangle, CheckCircle, Clock, MessageSquare, ArrowLeft, Zap, UserPlus, Bell, CheckCircle2, Eye, Send} from 'lucide-react'
import {useState, useEffect} from 'react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/incidents/$incidentId')({
  component: IncidentDetailPage,
})

const getPriorityConfig = (priority: string) => {
  if (priority.startsWith('P0')) return {color: 'bg-red-500/15 text-red-400 border-red-500/30', label: 'Critical'}
  if (priority.startsWith('P1')) return {color: 'bg-orange-500/15 text-orange-400 border-orange-500/30', label: 'High'}
  if (priority.startsWith('P2')) return {color: 'bg-amber-500/15 text-amber-400 border-amber-500/30', label: 'Medium'}
  if (priority.startsWith('P3')) return {color: 'bg-blue-500/15 text-blue-400 border-blue-500/30', label: 'Low'}
  return {color: 'bg-muted text-muted-foreground', label: priority}
}

const getStatusConfig = (status: string) => {
  if (status === 'TRIGGERED') return {color: 'bg-red-500/15 text-red-400 border-red-500/30', icon: Zap, label: 'Triggered', accent: 'text-red-500'}
  if (status === 'ACKNOWLEDGED') return {color: 'bg-amber-500/15 text-amber-400 border-amber-500/30', icon: Clock, label: 'Acknowledged', accent: 'text-amber-500'}
  return {color: 'bg-green-500/15 text-green-400 border-green-500/30', icon: CheckCircle2, label: 'Resolved', accent: 'text-green-500'}
}

const EVENT_CONFIG: Record<string, {icon: typeof Zap; color: string; bgColor: string; label: string}> = {
  TRIGGERED: {icon: Zap, color: 'text-red-500', bgColor: 'bg-red-500/15', label: 'Alert triggered'},
  ESCALATED: {icon: Bell, color: 'text-orange-500', bgColor: 'bg-orange-500/15', label: 'Escalated'},
  ACKNOWLEDGED: {icon: CheckCircle, color: 'text-blue-500', bgColor: 'bg-blue-500/15', label: 'Acknowledged'},
  RESOLVED: {icon: CheckCircle2, color: 'text-green-500', bgColor: 'bg-green-500/15', label: 'Resolved'},
  REASSIGNED: {icon: UserPlus, color: 'text-violet-500', bgColor: 'bg-violet-500/15', label: 'Reassigned'},
  NOTE_ADDED: {icon: MessageSquare, color: 'text-slate-400', bgColor: 'bg-slate-500/15', label: 'Note added'},
  STEP_TIMEOUT: {icon: Clock, color: 'text-orange-500', bgColor: 'bg-orange-500/15', label: 'Step timed out'},
  NOTIFICATION_SENT: {icon: Send, color: 'text-cyan-500', bgColor: 'bg-cyan-500/15', label: 'Notification sent'},
  VIEWED: {icon: Eye, color: 'text-slate-400', bgColor: 'bg-slate-500/10', label: 'Viewed'},
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

function getTimelineDescription(event: IncidentTimeline): string | null {
  if (event.eventType === 'NOTIFICATION_SENT' && event.details) {
    const toName = event.details.toUserName || event.actorUserName
    const channel = event.details.channel
    if (toName && channel) return `to ${toName} via ${channel}`
    if (toName) return `to ${toName}`
    if (channel) return `via ${channel}`
  }
  if (event.eventType === 'ESCALATED' && event.details?.stepNumber !== undefined) {
    return `to step ${Number(event.details.stepNumber) + 1}`
  }
  if (event.eventType === 'REASSIGNED' && event.details?.toUserName) {
    return `to ${event.details.toUserName}`
  }
  if (event.eventType === 'REASSIGNED' && event.details?.reason === 'unavailable') {
    return 'user marked as unavailable'
  }
  return null
}

function IncidentDetailPage() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [note, setNote] = useState('')
  const [declareOpen, setDeclareOpen] = useState(false)
  const [declareTitle, setDeclareTitle] = useState('')
  const [declareDesc, setDeclareDesc] = useState('')
  const [declareSeverity, setDeclareSeverity] = useState('P2')

  const {data: incident, isLoading} = useQuery({
    queryKey: ['incident', incidentId],
    queryFn: () => api.getIncident(Number(incidentId)),
  })

  useEffect(() => {
    if (incident) {
      setDeclareTitle(incident.title)
      setDeclareDesc(incident.description || '')
      setDeclareSeverity(incident.priorityLevel || 'P2')
    }
  }, [incident])

  const declareMutation = useMutation({
    mutationFn: () => api.declareIncident(Number(incidentId), {
      title: declareTitle,
      description: declareDesc,
      severity: declareSeverity
    }),
    onSuccess: () => {
      setDeclareOpen(false)
      toast({title: 'Incident Declared', description: 'New incident created successfully.'})
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    }
  })

  const {data: timeline = [], isLoading: timelineLoading} = useQuery({
    queryKey: ['incident-timeline', incidentId],
    queryFn: () => api.getIncidentTimeline(Number(incidentId)),
  })

  // Mark as viewed (deduplicated on backend)
  const incidentLoaded = !!incident
  useEffect(() => {
    if (incidentLoaded) {
      api.viewIncident(Number(incidentId)).catch(() => {})
    }
  }, [incidentLoaded, incidentId])

  const acknowledgeMutation = useMutation({
    mutationFn: () => api.acknowledgeIncident(Number(incidentId)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incident-timeline', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incidents']})
      toast({
        title: 'Incident Acknowledged',
        description: 'You have been assigned to this incident.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const resolveMutation = useMutation({
    mutationFn: () => api.resolveIncident(Number(incidentId)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incident-timeline', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incidents']})
      toast({
        title: 'Incident Resolved',
        description: 'This incident has been marked as resolved.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const markUnavailableMutation = useMutation({
    mutationFn: () => api.markUnavailable(Number(incidentId)),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incident-timeline', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incidents']})
      toast({
        title: 'Marked Unavailable',
        description: 'You have been marked as unavailable. The incident will be escalated.',
      })
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  const addNoteMutation = useMutation({
    mutationFn: (note: string) => api.addIncidentNote(Number(incidentId), note),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['incident-timeline', incidentId]})
      setNote('')
      toast({title: 'Note Added', description: 'Your note has been added to the incident timeline.'})
    },
    onError: (error: Error) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  if (isLoading || timelineLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-red-500" />
      </div>
    )
  }

  if (!incident) {
    return (
      <div className="text-center py-16">
        <AlertTriangle className="h-12 w-12 mx-auto mb-3 text-muted-foreground" />
        <p className="text-lg font-medium">Incident not found</p>
      </div>
    )
  }

  const incidentTimeline = incident.timeline ?? timeline
  const statusCfg = getStatusConfig(incident.status)
  const priorityCfg = getPriorityConfig(incident.priorityLevel)
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
            <Badge variant="outline" className={cn('text-xs gap-1', statusCfg.color)}>
              <StatusIcon className="h-3 w-3" />
              {statusCfg.label}
            </Badge>
            <Badge variant="outline" className={cn('text-xs', priorityCfg.color)}>
              {incident.priorityLevel}
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
            ? 'bg-red-500/5 border-red-500/20'
            : 'bg-amber-500/5 border-amber-500/20'
        )}>
          <div className="flex items-center gap-3">
            <div className={cn(
              'flex items-center justify-center h-10 w-10 rounded-full',
              incident.status === 'TRIGGERED' ? 'bg-red-500/15' : 'bg-amber-500/15'
            )}>
              <StatusIcon className={cn('h-5 w-5', statusCfg.accent)} />
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
                    className="bg-amber-600 hover:bg-amber-700"
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
                    className="border-green-500/30 text-green-400 hover:bg-green-500/10"
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
                  className="bg-green-600 hover:bg-green-700"
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
                      {['P0', 'P1', 'P2', 'P3'].map((sev) => (
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
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Description</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground leading-relaxed">{incident.description}</p>
              </CardContent>
            </Card>
          )}

          {/* Timeline */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Timeline</CardTitle>
              <CardDescription>Incident history and updates</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="relative">
                {incidentTimeline.map((event: IncidentTimeline, idx: number) => {
                  const config = EVENT_CONFIG[event.eventType] || {
                    icon: Clock,
                    color: 'text-muted-foreground',
                    bgColor: 'bg-muted',
                    label: event.eventType.replace(/_/g, ' '),
                  }
                  const Icon = config.icon
                  const description = getTimelineDescription(event)

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
                        {event.details && event.eventType === 'NOTE_ADDED' && !!event.details.note && (
                          <p className="italic bg-muted/50 rounded-lg p-2.5 mt-1.5 text-xs text-muted-foreground">&quot;{String(event.details.note)}&quot;</p>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>

          {/* Add Note - Always visible regardless of status */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Add Note</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
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
                  Add Note
                </Button>
              </CardContent>
            </Card>
        </div>

        {/* Sidebar */}
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Details</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Status</p>
                <Badge variant="outline" className={cn('gap-1', statusCfg.color)}>
                  <StatusIcon className="h-3 w-3" />
                  {statusCfg.label}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1.5">Priority</p>
                <Badge variant="outline" className={cn(priorityCfg.color)}>
                  {incident.priorityLevel}
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
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
