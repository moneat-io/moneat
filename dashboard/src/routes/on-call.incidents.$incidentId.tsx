import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Textarea} from '@/components/ui/textarea'
import {useToast} from '@/hooks/use-toast'
import {AlertTriangle, CheckCircle, Clock, MessageSquare, ArrowLeft, Zap, UserPlus, Bell, CheckCircle2} from 'lucide-react'
import {useState} from 'react'
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

const EVENT_CONFIG: Record<string, {icon: typeof Zap; color: string; bgColor: string}> = {
  TRIGGERED: {icon: Zap, color: 'text-red-500', bgColor: 'bg-red-500/15'},
  ESCALATED: {icon: Bell, color: 'text-orange-500', bgColor: 'bg-orange-500/15'},
  ACKNOWLEDGED: {icon: CheckCircle, color: 'text-blue-500', bgColor: 'bg-blue-500/15'},
  RESOLVED: {icon: CheckCircle2, color: 'text-green-500', bgColor: 'bg-green-500/15'},
  REASSIGNED: {icon: UserPlus, color: 'text-violet-500', bgColor: 'bg-violet-500/15'},
  NOTE_ADDED: {icon: MessageSquare, color: 'text-slate-400', bgColor: 'bg-slate-500/15'},
  STEP_TIMEOUT: {icon: Clock, color: 'text-orange-500', bgColor: 'bg-orange-500/15'},
  NOTIFICATION_SENT: {icon: Bell, color: 'text-cyan-500', bgColor: 'bg-cyan-500/15'},
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

function IncidentDetailPage() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [note, setNote] = useState('')

  const {data: incident, isLoading} = useQuery({
    queryKey: ['incident', incidentId],
    queryFn: () => api.getIncident(Number(incidentId)),
  })

  const {data: timeline = [], isLoading: timelineLoading} = useQuery({
    queryKey: ['incident-timeline', incidentId],
    queryFn: () => api.getIncidentTimeline(Number(incidentId)),
  })

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
    onError: (error: any) => {
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
    onError: (error: any) => {
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
    onError: (error: any) => {
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
            <span className="text-xs text-muted-foreground">#{incident.id}</span>
          </div>
          <h2 className="text-2xl font-bold">{incident.title}</h2>
          <p className="text-sm text-muted-foreground mt-1">
            Triggered {timeAgo(incident.triggeredAt)} · {new Date(incident.triggeredAt).toLocaleString()}
          </p>
        </div>
      </div>

      {/* Action Banner */}
      {incident.status !== 'RESOLVED' && (
        <div className={cn(
          'flex items-center justify-between p-4 rounded-lg border',
          incident.status === 'TRIGGERED'
            ? 'bg-red-500/5 border-red-500/30'
            : 'bg-amber-500/5 border-amber-500/30'
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
                {incident.status === 'TRIGGERED' ? 'This incident needs attention' : 'Incident acknowledged'}
              </p>
              <p className="text-xs text-muted-foreground">
                {incident.status === 'TRIGGERED'
                  ? 'Acknowledge to assign yourself, or resolve directly'
                  : `Acknowledged by ${incident.acknowledgedByName || 'you'}`}
              </p>
            </div>
          </div>
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
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        {/* Main Column */}
        <div className="lg:col-span-2 space-y-6">
          {/* Details */}
          {incident.description && (
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Description</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground">{incident.description}</p>
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
                {incidentTimeline.map((event, idx) => {
                  const config = EVENT_CONFIG[event.eventType] || {
                    icon: Clock,
                    color: 'text-muted-foreground',
                    bgColor: 'bg-muted',
                  }
                  const Icon = config.icon

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
                            {event.eventType.replace(/_/g, ' ')}
                          </p>
                          <span className="text-xs text-muted-foreground flex-shrink-0">
                            {timeAgo(event.createdAt)}
                          </span>
                        </div>
                        {event.actorUserName && (
                          <p className="text-xs text-muted-foreground mt-0.5">
                            by {event.actorUserName}
                          </p>
                        )}
                        {event.details && (
                          <div className="mt-1 text-xs text-muted-foreground">
                            {event.eventType === 'NOTE_ADDED' && event.details.note && (
                              <p className="italic bg-muted/50 rounded p-2 mt-1">&quot;{event.details.note}&quot;</p>
                            )}
                            {(event.eventType as string) === 'NOTIFICATION_SENT' && event.details.channel && (
                              <p>via {event.details.channel}</p>
                            )}
                            {event.eventType === 'ESCALATED' && event.details.stepNumber !== undefined && (
                              <p>to step {Number(event.details.stepNumber) + 1}</p>
                            )}
                            {event.eventType === 'REASSIGNED' && event.details.toUserName && (
                              <p>to {event.details.toUserName}</p>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>

          {/* Add Note */}
          {incident.status !== 'RESOLVED' && (
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
          )}
        </div>

        {/* Sidebar */}
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Details</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">Status</p>
                <Badge variant="outline" className={cn('gap-1', statusCfg.color)}>
                  <StatusIcon className="h-3 w-3" />
                  {statusCfg.label}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">Priority</p>
                <Badge variant="outline" className={cn(priorityCfg.color)}>
                  {incident.priorityLevel}
                </Badge>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">Alert Source</p>
                <p className="text-sm">{incident.alertSource || 'Unknown'}</p>
              </div>
              <div>
                <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">Triggered At</p>
                <p className="text-sm">{new Date(incident.triggeredAt).toLocaleString()}</p>
              </div>
              {incident.acknowledgedBy && (
                <div>
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">Acknowledged By</p>
                  <p className="text-sm">
                    {incident.acknowledgedByName}
                    <span className="text-xs text-muted-foreground block">
                      {new Date(incident.acknowledgedAt!).toLocaleString()}
                    </span>
                  </p>
                </div>
              )}
              {incident.resolvedBy && (
                <div>
                  <p className="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-1">Resolved By</p>
                  <p className="text-sm">
                    {incident.resolvedByName}
                    <span className="text-xs text-muted-foreground block">
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
