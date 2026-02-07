import {useState} from 'react'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type SystemAlert} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle, CardDescription} from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {Badge} from '@/components/ui/badge'
import {
  Bell,
  BellRing,
  Clock,
  Edit,
  Mail,
  Plus,
  Shield,
  Trash2,
  Zap,
} from 'lucide-react'
import {formatRelativeTime} from '@/lib/utils'

interface AlertsTabProps {
  systemId: string
}

const METRIC_OPTIONS = [
  {value: 'cpu_percent', label: 'CPU Usage (%)', icon: '🔵', color: 'text-blue-500'},
  {value: 'mem_percent', label: 'Memory Usage (%)', icon: '🟣', color: 'text-violet-500'},
  {value: 'disk_percent', label: 'Disk Usage (%)', icon: '🟡', color: 'text-amber-500'},
  {value: 'load_1', label: 'Load Average (1m)', icon: '🟢', color: 'text-emerald-500'},
  {value: 'load_5', label: 'Load Average (5m)', icon: '🟢', color: 'text-emerald-500'},
  {value: 'load_15', label: 'Load Average (15m)', icon: '🟢', color: 'text-emerald-500'},
  {value: 'temp_max', label: 'Max Temperature (°C)', icon: '🔴', color: 'text-rose-500'},
  {value: 'gpu_percent', label: 'GPU Usage (%)', icon: '🔵', color: 'text-teal-500'},
  {value: 'battery_percent', label: 'Battery Level (%)', icon: '🟡', color: 'text-yellow-500'},
]

const CONDITION_OPTIONS = [
  {value: '>', label: 'Greater than (>)'},
  {value: '<', label: 'Less than (<)'},
  {value: '>=', label: 'Greater or equal (>=)'},
  {value: '<=', label: 'Less or equal (<=)'},
  {value: '==', label: 'Equal to (==)'},
]

export function AlertsTab({systemId}: AlertsTabProps) {
  const queryClient = useQueryClient()
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false)
  const [isEditDialogOpen, setIsEditDialogOpen] = useState(false)
  const [editingAlert, setEditingAlert] = useState<SystemAlert | null>(null)

  const {data: alerts, isLoading} = useQuery({
    queryKey: ['system-alerts', systemId],
    queryFn: () => api.getSystemAlerts(systemId),
  })

  const createMutation = useMutation({
    mutationFn: (alert: {
      metric: string
      condition: string
      threshold: number
      durationSeconds: number
      enabled: boolean
    }) => api.createSystemAlert(systemId, alert),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['system-alerts', systemId]})
      setIsCreateDialogOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({alertId, updates}: {alertId: number; updates: Partial<SystemAlert>}) =>
      api.updateSystemAlert(systemId, alertId, updates),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['system-alerts', systemId]})
      setIsEditDialogOpen(false)
      setEditingAlert(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (alertId: number) => api.deleteSystemAlert(systemId, alertId),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['system-alerts', systemId]})
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({alertId, enabled}: {alertId: number; enabled: boolean}) =>
      api.updateSystemAlert(systemId, alertId, {enabled}),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['system-alerts', systemId]})
    },
  })

  const handleCreateAlert = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)
    createMutation.mutate({
      metric: formData.get('metric') as string,
      condition: formData.get('condition') as string,
      threshold: parseFloat(formData.get('threshold') as string),
      durationSeconds: parseInt(formData.get('durationSeconds') as string) || 0,
      enabled: true,
    })
  }

  const handleUpdateAlert = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (!editingAlert) return

    const formData = new FormData(e.currentTarget)
    updateMutation.mutate({
      alertId: editingAlert.id,
      updates: {
        metric: formData.get('metric') as string,
        condition: formData.get('condition') as string,
        threshold: parseFloat(formData.get('threshold') as string),
        durationSeconds: parseInt(formData.get('durationSeconds') as string) || 0,
      },
    })
  }

  const getMetricLabel = (metric: string) => {
    return METRIC_OPTIONS.find((m) => m.value === metric)?.label || metric
  }

  const getMetricColor = (metric: string) => {
    return METRIC_OPTIONS.find((m) => m.value === metric)?.color || 'text-muted-foreground'
  }

  const formatThreshold = (metric: string, threshold: number) => {
    if (metric.includes('percent')) {
      return `${threshold}%`
    } else if (metric === 'temp_max') {
      return `${threshold}°C`
    }
    return threshold.toString()
  }

  const enabledAlerts = alerts?.filter((a) => a.enabled).length || 0
  const totalAlerts = alerts?.length || 0

  return (
    <div className="space-y-6">
      {/* Alert Rules Card */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-amber-500/10">
                <BellRing className="h-5 w-5 text-amber-500" />
              </div>
              <div>
                <CardTitle>Alert Rules</CardTitle>
                <CardDescription>
                  {totalAlerts > 0
                    ? `${enabledAlerts} of ${totalAlerts} rules active`
                    : 'No rules configured yet'}
                </CardDescription>
              </div>
            </div>
            <Dialog open={isCreateDialogOpen} onOpenChange={setIsCreateDialogOpen}>
              <DialogTrigger asChild>
                <Button className="gap-2">
                  <Plus className="h-4 w-4" />
                  Create Alert
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle className="flex items-center gap-2">
                    <Zap className="h-5 w-5 text-amber-500" />
                    Create Alert Rule
                  </DialogTitle>
                  <DialogDescription>
                    Set up a new alert to be notified when metrics exceed thresholds.
                  </DialogDescription>
                </DialogHeader>
                <form onSubmit={handleCreateAlert}>
                  <div className="space-y-4 py-2">
                    <div className="space-y-2">
                      <Label htmlFor="metric">Metric</Label>
                      <Select name="metric" defaultValue="cpu_percent" required>
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {METRIC_OPTIONS.map((option) => (
                            <SelectItem key={option.value} value={option.value}>
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="condition">Condition</Label>
                      <Select name="condition" defaultValue=">" required>
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {CONDITION_OPTIONS.map((option) => (
                            <SelectItem key={option.value} value={option.value}>
                              {option.label}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="threshold">Threshold</Label>
                      <Input
                        id="threshold"
                        name="threshold"
                        type="number"
                        step="0.1"
                        placeholder="80"
                        required
                      />
                    </div>
                    <div className="space-y-2">
                      <Label htmlFor="durationSeconds">
                        Duration (seconds)
                      </Label>
                      <Input
                        id="durationSeconds"
                        name="durationSeconds"
                        type="number"
                        placeholder="0 (immediate)"
                        defaultValue="0"
                      />
                      <p className="text-xs text-muted-foreground">
                        How long the condition must be true before triggering. Set to 0 for immediate alerts.
                      </p>
                    </div>
                  </div>
                  <DialogFooter className="mt-4">
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => setIsCreateDialogOpen(false)}
                    >
                      Cancel
                    </Button>
                    <Button type="submit" disabled={createMutation.isPending}>
                      {createMutation.isPending ? 'Creating...' : 'Create Alert'}
                    </Button>
                  </DialogFooter>
                </form>
              </DialogContent>
            </Dialog>
          </div>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="flex flex-col items-center gap-3">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                <p className="text-muted-foreground text-sm">Loading alerts...</p>
              </div>
            </div>
          ) : alerts && alerts.length > 0 ? (
            <div className="space-y-3">
              {alerts.map((alert) => (
                <div
                  key={alert.id}
                  className={`group relative flex items-center gap-4 rounded-lg border p-4 transition-colors ${
                    alert.enabled
                      ? 'bg-card hover:bg-muted/30'
                      : 'bg-muted/20 opacity-60 hover:opacity-80'
                  }`}
                >
                  {/* Enable/Disable Toggle */}
                  <Switch
                    checked={alert.enabled}
                    onCheckedChange={(enabled) =>
                      toggleMutation.mutate({alertId: alert.id, enabled})
                    }
                    disabled={toggleMutation.isPending}
                    className="shrink-0"
                  />

                  {/* Alert Info */}
                  <div className="flex-1 min-w-0 space-y-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-sm font-medium ${getMetricColor(alert.metric)}`}>
                        {getMetricLabel(alert.metric)}
                      </span>
                      <Badge variant="outline" className="text-xs font-mono">
                        {alert.condition} {formatThreshold(alert.metric, alert.threshold)}
                      </Badge>
                      {alert.durationSeconds > 0 && (
                        <Badge variant="secondary" className="text-xs gap-1">
                          <Clock className="h-3 w-3" />
                          {alert.durationSeconds}s
                        </Badge>
                      )}
                    </div>
                    <div className="flex items-center gap-3 text-xs text-muted-foreground">
                      {alert.lastTriggeredAt ? (
                        <span className="flex items-center gap-1 text-orange-500">
                          <BellRing className="h-3 w-3" />
                          Triggered {formatRelativeTime(alert.lastTriggeredAt)}
                        </span>
                      ) : (
                        <span className="flex items-center gap-1">
                          <Bell className="h-3 w-3" />
                          Never triggered
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Actions */}
                  <div className="flex items-center gap-1 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-8 w-8 p-0"
                      onClick={() => {
                        setEditingAlert(alert)
                        setIsEditDialogOpen(true)
                      }}
                    >
                      <Edit className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-8 w-8 p-0"
                      onClick={() => {
                        if (confirm('Are you sure you want to delete this alert?')) {
                          deleteMutation.mutate(alert.id)
                        }
                      }}
                      disabled={deleteMutation.isPending}
                    >
                      <Trash2 className="h-3.5 w-3.5 text-destructive" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-16">
              <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-500/10">
                <Bell className="h-8 w-8 text-amber-500" />
              </div>
              <h3 className="text-lg font-medium mb-1">No alerts configured</h3>
              <p className="text-muted-foreground text-sm mb-6 max-w-sm mx-auto">
                Create your first alert to get notified when metrics exceed thresholds.
              </p>
              <Button onClick={() => setIsCreateDialogOpen(true)} className="gap-2">
                <Plus className="h-4 w-4" />
                Create Alert
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Edit Alert Dialog */}
      <Dialog open={isEditDialogOpen} onOpenChange={setIsEditDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Edit className="h-5 w-5 text-blue-500" />
              Edit Alert Rule
            </DialogTitle>
            <DialogDescription>Update the alert rule configuration.</DialogDescription>
          </DialogHeader>
          {editingAlert && (
            <form onSubmit={handleUpdateAlert}>
              <div className="space-y-4 py-2">
                <div className="space-y-2">
                  <Label htmlFor="edit-metric">Metric</Label>
                  <Select name="metric" defaultValue={editingAlert.metric} required>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {METRIC_OPTIONS.map((option) => (
                        <SelectItem key={option.value} value={option.value}>
                          {option.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-condition">Condition</Label>
                  <Select name="condition" defaultValue={editingAlert.condition} required>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {CONDITION_OPTIONS.map((option) => (
                        <SelectItem key={option.value} value={option.value}>
                          {option.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-threshold">Threshold</Label>
                  <Input
                    id="edit-threshold"
                    name="threshold"
                    type="number"
                    step="0.1"
                    defaultValue={editingAlert.threshold}
                    required
                  />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-duration">Duration (seconds)</Label>
                  <Input
                    id="edit-duration"
                    name="durationSeconds"
                    type="number"
                    defaultValue={editingAlert.durationSeconds}
                  />
                </div>
              </div>
              <DialogFooter className="mt-4">
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setIsEditDialogOpen(false)
                    setEditingAlert(null)
                  }}
                >
                  Cancel
                </Button>
                <Button type="submit" disabled={updateMutation.isPending}>
                  {updateMutation.isPending ? 'Saving...' : 'Save Changes'}
                </Button>
              </DialogFooter>
            </form>
          )}
        </DialogContent>
      </Dialog>

      {/* Notification Info Cards */}
      <div className="grid gap-4 md:grid-cols-2">
        <Card className="bg-gradient-to-br from-blue-500/5 to-indigo-500/5 border-blue-500/10">
          <CardContent className="pt-5 pb-4">
            <div className="flex items-start gap-3">
              <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-blue-500/15 shrink-0">
                <Mail className="h-4 w-4 text-blue-500" />
              </div>
              <div className="space-y-1">
                <h4 className="text-sm font-medium">Email Notifications</h4>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  Alert notifications are sent to all members of your organization via email.
                  Alerts are throttled to prevent spam (minimum 15 minutes between notifications
                  for the same alert).
                </p>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="bg-gradient-to-br from-emerald-500/5 to-teal-500/5 border-emerald-500/10">
          <CardContent className="pt-5 pb-4">
            <div className="flex items-start gap-3">
              <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-emerald-500/15 shrink-0">
                <Shield className="h-4 w-4 text-emerald-500" />
              </div>
              <div className="space-y-1">
                <h4 className="text-sm font-medium">System Status Notifications</h4>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  You'll also receive notifications when systems go down (no metrics for 5+
                  minutes) or come back online after being offline.
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
