import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
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
import { Textarea } from "@/components/ui/textarea"
import {useToast} from '@/hooks/use-toast'
import {AlertTriangle, CheckCircle2, Clock, ArrowLeft, Zap, User, Calendar} from 'lucide-react'
import {useState} from 'react'
import {cn} from '@/lib/utils'

export const Route = createFileRoute('/on-call/declared-incidents/$incidentId')({
  component: DeclaredIncidentDetail,
})

const getPriorityConfig = (priority: string) => {
  if (priority.startsWith('P0')) return {color: 'bg-red-500/15 text-red-400 border-red-500/30', label: 'Critical'}
  if (priority.startsWith('P1')) return {color: 'bg-orange-500/15 text-orange-400 border-orange-500/30', label: 'High'}
  if (priority.startsWith('P2')) return {color: 'bg-amber-500/15 text-amber-400 border-amber-500/30', label: 'Medium'}
  if (priority.startsWith('P3')) return {color: 'bg-blue-500/15 text-blue-400 border-blue-500/30', label: 'Low'}
  return {color: 'bg-muted text-muted-foreground', label: priority}
}

const getStatusConfig = (status: string) => {
  if (status === 'OPEN') return {color: 'bg-red-500/15 text-red-400 border-red-500/30', icon: Zap, label: 'Open', accent: 'text-red-500'}
  if (status === 'RESOLVED') return {color: 'bg-green-500/15 text-green-400 border-green-500/30', icon: CheckCircle2, label: 'Resolved', accent: 'text-green-500'}
  return {color: 'bg-muted text-muted-foreground', icon: Clock, label: status, accent: 'text-muted-foreground'}
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

function DeclaredIncidentDetail() {
  const {incidentId} = Route.useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [resolveOpen, setResolveOpen] = useState(false)
  const [resolutionNote, setResolutionNote] = useState('')

  const {data: incident, isLoading} = useQuery({
    queryKey: ['declared-incident', incidentId],
    queryFn: () => api.getOnCallIncident(Number(incidentId)),
  })

  const resolveMutation = useMutation({
    mutationFn: () => api.resolveOnCallIncident(Number(incidentId)),
    onSuccess: () => {
      setResolveOpen(false)
      queryClient.invalidateQueries({queryKey: ['declared-incident', incidentId]})
      queryClient.invalidateQueries({queryKey: ['declared-incidents']})
      toast({
        title: 'Incident Resolved',
        description: 'This incident has been marked as resolved.',
      })
    },
    onError: (error: any) => {
      toast({title: 'Error', description: error.message, variant: 'destructive'})
    },
  })

  if (isLoading) {
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

  const statusCfg = getStatusConfig(incident.status)
  const priorityCfg = getPriorityConfig(incident.priorityLevel)
  const StatusIcon = statusCfg.icon

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start gap-4">
        <Button variant="ghost" size="icon" className="mt-1 flex-shrink-0" onClick={() => navigate({to: '/on-call/declared-incidents'})}>
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
            Declared {timeAgo(incident.declaredAt)} by {incident.declaredByName}
          </p>
        </div>
      </div>

      {/* Action Banner */}
      {incident.status !== 'RESOLVED' && (
        <div className={cn(
          'flex items-center justify-between p-4 rounded-xl border',
          'bg-red-500/5 border-red-500/20'
        )}>
          <div className="flex items-center gap-3">
            <div className={cn(
              'flex items-center justify-center h-10 w-10 rounded-full',
              'bg-red-500/15'
            )}>
              <StatusIcon className={cn('h-5 w-5', statusCfg.accent)} />
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
                <Button
                  className="bg-green-600 hover:bg-green-700"
                  size="sm"
                >
                  <CheckCircle2 className="h-4 w-4 mr-2" />
                  Resolve Incident
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
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base">Description</CardTitle>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground leading-relaxed">{incident.description}</p>
              </CardContent>
            </Card>
          )}

          {/* Linked Alerts (Placeholder for now) */}
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Linked Alerts</CardTitle>
              <CardDescription>System alerts associated with this incident</CardDescription>
            </CardHeader>
            <CardContent>
                <p className="text-sm text-muted-foreground italic">Alert linking visualization coming soon.</p>
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
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
