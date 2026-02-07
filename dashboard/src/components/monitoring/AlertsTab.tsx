import {useState} from 'react'
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {api, type SystemAlert} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {AlertCircle, Bell, Plus, Trash2, Edit} from 'lucide-react'
import {formatRelativeTime} from '@/lib/utils'

interface AlertsTabProps {
  systemId: string
}

const METRIC_OPTIONS = [
  {value: 'cpu_percent', label: 'CPU Usage (%)'},
  {value: 'mem_percent', label: 'Memory Usage (%)'},
  {value: 'disk_percent', label: 'Disk Usage (%)'},
  {value: 'load_1', label: 'Load Average (1m)'},
  {value: 'load_5', label: 'Load Average (5m)'},
  {value: 'load_15', label: 'Load Average (15m)'},
  {value: 'temp_max', label: 'Max Temperature (°C)'},
  {value: 'gpu_percent', label: 'GPU Usage (%)'},
  {value: 'battery_percent', label: 'Battery Level (%)'},
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

  const formatThreshold = (metric: string, threshold: number) => {
    if (metric.includes('percent')) {
      return `${threshold}%`
    } else if (metric === 'temp_max') {
      return `${threshold}°C`
    }
    return threshold.toString()
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Bell className="h-5 w-5" />
              <CardTitle>Alert Rules</CardTitle>
            </div>
            <Dialog open={isCreateDialogOpen} onOpenChange={setIsCreateDialogOpen}>
              <DialogTrigger asChild>
                <Button>
                  <Plus className="h-4 w-4 mr-2" />
                  Create Alert
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Create Alert Rule</DialogTitle>
                  <DialogDescription>
                    Set up a new alert to be notified when metrics exceed thresholds.
                  </DialogDescription>
                </DialogHeader>
                <form onSubmit={handleCreateAlert}>
                  <div className="space-y-4">
                    <div>
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
                    <div>
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
                    <div>
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
                    <div>
                      <Label htmlFor="durationSeconds">
                        Duration (seconds) - Optional
                      </Label>
                      <Input
                        id="durationSeconds"
                        name="durationSeconds"
                        type="number"
                        placeholder="0 (immediate)"
                        defaultValue="0"
                      />
                      <p className="text-sm text-muted-foreground mt-1">
                        How long the condition must be true before triggering (0 = immediate)
                      </p>
                    </div>
                  </div>
                  <DialogFooter className="mt-6">
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
            <div className="text-center py-8 text-muted-foreground">Loading alerts...</div>
          ) : alerts && alerts.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Status</TableHead>
                  <TableHead>Metric</TableHead>
                  <TableHead>Condition</TableHead>
                  <TableHead>Threshold</TableHead>
                  <TableHead>Duration</TableHead>
                  <TableHead>Last Triggered</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {alerts.map((alert) => (
                  <TableRow key={alert.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Switch
                          checked={alert.enabled}
                          onCheckedChange={(enabled) =>
                            toggleMutation.mutate({alertId: alert.id, enabled})
                          }
                          disabled={toggleMutation.isPending}
                        />
                        <Badge variant={alert.enabled ? 'default' : 'secondary'}>
                          {alert.enabled ? 'Enabled' : 'Disabled'}
                        </Badge>
                      </div>
                    </TableCell>
                    <TableCell>{getMetricLabel(alert.metric)}</TableCell>
                    <TableCell>{alert.condition}</TableCell>
                    <TableCell>{formatThreshold(alert.metric, alert.threshold)}</TableCell>
                    <TableCell>
                      {alert.durationSeconds > 0 ? `${alert.durationSeconds}s` : 'Immediate'}
                    </TableCell>
                    <TableCell>
                      {alert.lastTriggeredAt ? (
                        <span className="text-orange-500">
                          {formatRelativeTime(alert.lastTriggeredAt)}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">Never</span>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-2">
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            setEditingAlert(alert)
                            setIsEditDialogOpen(true)
                          }}
                        >
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            if (
                              confirm('Are you sure you want to delete this alert?')
                            ) {
                              deleteMutation.mutate(alert.id)
                            }
                          }}
                          disabled={deleteMutation.isPending}
                        >
                          <Trash2 className="h-4 w-4 text-destructive" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <div className="text-center py-12">
              <AlertCircle className="h-12 w-12 mx-auto text-muted-foreground mb-4" />
              <h3 className="text-lg font-medium mb-2">No alerts configured</h3>
              <p className="text-muted-foreground mb-4">
                Create your first alert to get notified when metrics exceed thresholds.
              </p>
              <Button onClick={() => setIsCreateDialogOpen(true)}>
                <Plus className="h-4 w-4 mr-2" />
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
            <DialogTitle>Edit Alert Rule</DialogTitle>
            <DialogDescription>
              Update the alert rule configuration.
            </DialogDescription>
          </DialogHeader>
          {editingAlert && (
            <form onSubmit={handleUpdateAlert}>
              <div className="space-y-4">
                <div>
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
                <div>
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
                <div>
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
                <div>
                  <Label htmlFor="edit-duration">Duration (seconds)</Label>
                  <Input
                    id="edit-duration"
                    name="durationSeconds"
                    type="number"
                    defaultValue={editingAlert.durationSeconds}
                  />
                </div>
              </div>
              <DialogFooter className="mt-6">
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

      <Card>
        <CardHeader>
          <CardTitle>Alert Notifications</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            <div className="bg-muted p-4 rounded-lg">
              <h4 className="font-medium mb-2">Email Notifications</h4>
              <p className="text-sm text-muted-foreground">
                Alert notifications are sent to all members of your organization via email. Alerts
                are throttled to prevent spam (minimum 15 minutes between notifications for the
                same alert).
              </p>
            </div>
            <div className="bg-muted p-4 rounded-lg">
              <h4 className="font-medium mb-2">System Status Notifications</h4>
              <p className="text-sm text-muted-foreground">
                You'll also receive notifications when systems go down (no metrics for 5+ minutes)
                or come back online.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
