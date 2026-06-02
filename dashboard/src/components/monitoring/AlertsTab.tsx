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

import {useMemo, useState} from 'react'
import {Link} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type HostAlert} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue,} from '@/components/ui/select'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {Badge} from '@/components/ui/badge'
import {Bell, BellRing, Clock, Edit, Globe2, Plus, Server, Trash2, Zap} from 'lucide-react'
import {formatRelativeTime} from '@/lib/utils'

type HostAlertScope = 'global' | 'host'

interface AlertsTabProps {
  hostId: number
}

const METRIC_OPTIONS = [
  {value: 'cpu_percent', label: 'CPU Usage (%)', color: 'text-chart-1'},
  {value: 'mem_percent', label: 'Memory Usage (%)', color: 'text-chart-2'},
  {value: 'disk_percent', label: 'Disk Usage (%)', color: 'text-chart-5'},
  {value: 'load_1', label: 'Load Average (1m)', color: 'text-chart-4'},
  {value: 'load_5', label: 'Load Average (5m)', color: 'text-chart-4'},
  {value: 'load_15', label: 'Load Average (15m)', color: 'text-chart-4'},
  {value: 'temp_max', label: 'Max Temperature (°C)', color: 'text-chart-8'},
  {value: 'gpu_percent', label: 'GPU Usage (%)', color: 'text-chart-3'},
  {value: 'battery_percent', label: 'Battery Level (%)', color: 'text-chart-5'},
]

const CONDITION_OPTIONS = [
  {value: '>', label: 'Greater than (>)'},
  {value: '<', label: 'Less than (<)'},
  {value: '>=', label: 'Greater or equal (>=)'},
  {value: '<=', label: 'Less or equal (<=)'},
  {value: '==', label: 'Equal to (==)'},
]

export function AlertsTab({hostId}: AlertsTabProps) {
  const queryClient = useQueryClient()
  const [isCreateDialogOpen, setIsCreateDialogOpen] = useState(false)
  const [isEditDialogOpen, setIsEditDialogOpen] = useState(false)
  const [editingAlert, setEditingAlert] = useState<HostAlert | null>(null)
  const [createEnabled, setCreateEnabled] = useState(false)

  const {data: alertConfig, isLoading} = useQuery({
    queryKey: ['host-alert-config', hostId],
    queryFn: () => api.getHostAlertConfig(hostId),
    enabled: api.isAuthenticated(),
  })

  const activeScope: HostAlertScope = (alertConfig?.scope as HostAlertScope) ?? 'global'

  const alerts = useMemo(() => {
    if (!alertConfig) return []
    const baseAlerts = activeScope === 'global' ? alertConfig.globalAlerts : alertConfig.hostAlerts
    return [...baseAlerts].sort((a, b) => a.id - b.id)
  }, [alertConfig, activeScope])

  const scopeMutation = useMutation({
    mutationFn: (scope: HostAlertScope) => api.updateHostAlertScope(hostId, scope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
    },
  })

  const createMutation = useMutation({
    mutationFn: ({scope, alert}: {
      scope: HostAlertScope
      alert: {
        metric: string
        condition: string
        threshold: number
        durationSeconds: number
        enabled: boolean
        incidentSeverity?: string
      }
    }) => api.createHostAlert(hostId, alert, scope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
      setIsCreateDialogOpen(false)
      setCreateEnabled(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({alert, updates}: {alert: HostAlert; updates: Partial<HostAlert>}) =>
      api.updateHostAlert(hostId, alert.id, updates, alert.scope as HostAlertScope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
      setIsEditDialogOpen(false)
      setEditingAlert(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (alert: HostAlert) => api.deleteHostAlert(hostId, alert.id, alert.scope as HostAlertScope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({alert, enabled}: {alert: HostAlert; enabled: boolean}) =>
      api.updateHostAlert(hostId, alert.id, {enabled}, alert.scope as HostAlertScope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
    },
  })

  const handleCreateAlert = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const formData = new FormData(e.currentTarget)
    const severity = formData.get('incidentSeverity') as string
    const durationMinutes = parseInt(formData.get('durationMinutes') as string) || 0

    createMutation.mutate({
      scope: activeScope,
      alert: {
        metric: formData.get('metric') as string,
        condition: formData.get('condition') as string,
        threshold: parseFloat(formData.get('threshold') as string),
        durationSeconds: durationMinutes * 60,
        enabled: createEnabled,
        incidentSeverity: severity && severity !== 'none' ? severity : undefined,
      },
    })
  }

  const handleUpdateAlert = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (!editingAlert) return

    const formData = new FormData(e.currentTarget)
    const severity = formData.get('incidentSeverity') as string
    const durationMinutes = parseInt(formData.get('durationMinutes') as string) || 0
    updateMutation.mutate({
      alert: editingAlert,
      updates: {
        metric: formData.get('metric') as string,
        condition: formData.get('condition') as string,
        threshold: parseFloat(formData.get('threshold') as string),
        durationSeconds: durationMinutes * 60,
        incidentSeverity: severity && severity !== 'none' ? severity : null,
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
    }
    if (metric === 'temp_max') {
      return `${threshold}°C`
    }
    return threshold.toString()
  }

  const enabledAlerts = alerts.filter((a) => a.enabled).length
  const totalAlerts = alerts.length

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader className="py-3 px-4 pb-2">
          <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
            <div className="flex items-center gap-2.5">
              <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-warning-bg shrink-0">
                <BellRing className="h-4 w-4 text-warning-fg" />
              </div>
              <div className="min-w-0">
                <CardTitle className="text-base">Alert Rules</CardTitle>
                <CardDescription className="text-xs">
                  {totalAlerts > 0
                    ? `${enabledAlerts} of ${totalAlerts} rules active (${activeScope === 'global' ? 'shared globally' : 'host-only'})`
                    : 'No rules available'}
                </CardDescription>
              </div>
            </div>

            <div className="flex flex-col items-stretch gap-1.5 sm:flex-row sm:items-center">
              <div className="flex items-center rounded-lg border p-0.5 bg-muted/30">
                <Button
                  type="button"
                  size="sm"
                  variant={activeScope === 'global' ? 'default' : 'ghost'}
                  className="h-7 gap-1 text-xs"
                  onClick={() => scopeMutation.mutate('global')}
                  disabled={scopeMutation.isPending || activeScope === 'global'}
                >
                  <Globe2 className="h-3 w-3" />
                  Global
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={activeScope === 'host' ? 'default' : 'ghost'}
                  className="h-7 gap-1 text-xs"
                  onClick={() => scopeMutation.mutate('host')}
                  disabled={scopeMutation.isPending || activeScope === 'host'}
                >
                  <Server className="h-3 w-3" />
                  This Host
                </Button>
              </div>

              <Dialog open={isCreateDialogOpen} onOpenChange={setIsCreateDialogOpen}>
                <DialogTrigger asChild>
                  <Button size="sm" className="gap-1.5">
                    <Plus className="h-4 w-4" />
                    Add Rule
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle className="flex items-center gap-2">
                      <Zap className="h-5 w-5 text-warning-fg" />
                      Create Alert Rule
                    </DialogTitle>
                    <DialogDescription>
                      This rule will be added to{' '}
                      {activeScope === 'global' ? 'the global shared profile.' : 'this host only.'}
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
                        <Label htmlFor="durationMinutes">Duration (minutes)</Label>
                        <Input
                          id="durationMinutes"
                          name="durationMinutes"
                          type="number"
                          min="0"
                          placeholder="0 (immediate)"
                          defaultValue="0"
                        />
                        <p className="text-xs text-muted-foreground">
                          Condition must be met for this long before alert triggers. 0 = immediate.
                        </p>
                      </div>
                      <div className="space-y-2">
                        <Label htmlFor="incidentSeverity">Alert Severity</Label>
                        <Select name="incidentSeverity" defaultValue="">
                          <SelectTrigger>
                            <SelectValue placeholder="Use routing rule default" />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="none">Use routing rule default</SelectItem>
                            <SelectItem value="CRITICAL">[P0] Critical</SelectItem>
                            <SelectItem value="HIGH">[P1] High</SelectItem>
                            <SelectItem value="MEDIUM">[P2] Medium</SelectItem>
                            <SelectItem value="LOW">[P3] Low</SelectItem>
                          </SelectContent>
                        </Select>
                        <p className="text-xs text-muted-foreground">
                          Override the default severity when this alert triggers an incident. P0–P2 page on-call 24/7. P3 notifies during business hours only.
                        </p>
                      </div>
                      <div className="flex items-center justify-between rounded-lg border p-3">
                        <div className="space-y-0.5">
                          <p className="text-sm font-medium">Enabled</p>
                          <p className="text-xs text-muted-foreground">Leave off to save and enable later.</p>
                        </div>
                        <Switch checked={createEnabled} onCheckedChange={setCreateEnabled} />
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
          </div>

          <p className="text-[11px] text-muted-foreground mt-1">
            {activeScope === 'global'
              ? 'Global profile applies to all hosts currently set to Global.'
              : 'Host profile applies only to this host. Global changes will not affect it.'}
          </p>
        </CardHeader>

        <CardContent className="pt-0 px-4 pb-4">
          <div className="mb-4 rounded-md bg-info-bg p-3 text-xs text-info-fg flex items-start gap-2 border border-info-border">
            <BellRing className="h-4 w-4 shrink-0 mt-0.5" />
            <div className="space-y-0.5 min-w-0">
              <p className="font-medium">Notification Channels</p>
              <p className="text-info-fg/80">
                Configure which channels (Email, Slack, Discord) receive these alerts in{' '}
                <Link to="/settings" search={{ tab: 'notifications' }} className="underline hover:text-info-fg/90">
                  Settings &gt; Notifications
                </Link>.
              </p>
            </div>
          </div>
          {isLoading ? (
            <div className="flex items-center justify-center py-8">
              <div className="flex flex-col items-center gap-2">
                <div className="h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                <p className="text-muted-foreground text-xs">Loading alerts...</p>
              </div>
            </div>
          ) : alerts.length > 0 ? (
            <div className="space-y-2">
              {alerts.map((alert) => (
                <div
                  key={`${alert.scope}-${alert.id}`}
                  className={`group relative flex items-center gap-3 rounded-lg border p-3 transition-colors ${
                    alert.enabled
                      ? 'bg-card hover:bg-muted/30'
                      : 'bg-muted/20 opacity-60 hover:opacity-80'
                  }`}
                >
                  <Switch
                    checked={alert.enabled}
                    onCheckedChange={(enabled) => toggleMutation.mutate({alert, enabled})}
                    disabled={toggleMutation.isPending}
                    className="shrink-0"
                  />

                  <div className="flex-1 min-w-0 space-y-0.5">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className={`text-xs font-medium ${getMetricColor(alert.metric)}`}>
                        {getMetricLabel(alert.metric)}
                      </span>
                      <Badge variant="outline" className="text-[10px] font-mono px-1.5 py-0">
                        {alert.condition} {formatThreshold(alert.metric, alert.threshold)}
                      </Badge>
                      {alert.durationSeconds > 0 && (
                        <Badge variant="secondary" className="text-[10px] gap-0.5 px-1.5 py-0">
                          <Clock className="h-2.5 w-2.5" />
                          {Math.floor(alert.durationSeconds / 60)}m
                        </Badge>
                      )}
                      <Badge variant="secondary" className="text-[9px] uppercase px-1.5 py-0">
                        {alert.scope}
                      </Badge>
                    </div>
                    <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                      {alert.lastTriggeredAt ? (
                        <span className="flex items-center gap-0.5 text-warning-fg">
                          <BellRing className="h-2.5 w-2.5" />
                          Triggered {formatRelativeTime(alert.lastTriggeredAt)}
                        </span>
                      ) : (
                        <span className="flex items-center gap-0.5">
                          <Bell className="h-2.5 w-2.5" />
                          Never triggered
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="flex items-center gap-0.5 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-7 w-7 p-0"
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
                      className="h-7 w-7 p-0"
                      onClick={() => {
                        if (confirm('Are you sure you want to delete this alert?')) {
                          deleteMutation.mutate(alert)
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
            <div className="text-center py-10">
              <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-warning-bg">
                <Bell className="h-6 w-6 text-warning-fg" />
              </div>
              <h3 className="text-base font-medium mb-0.5">No rules in this scope</h3>
              <p className="text-muted-foreground text-xs mb-4 max-w-sm mx-auto">
                Default recommendations are seeded automatically. Add custom rules if you need stricter thresholds.
              </p>
              <Button size="sm" onClick={() => setIsCreateDialogOpen(true)} className="gap-1.5">
                <Plus className="h-4 w-4" />
                Add Rule
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={isEditDialogOpen} onOpenChange={setIsEditDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Edit className="h-5 w-5 text-info-fg" />
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
                  <Label htmlFor="edit-duration">Duration (minutes)</Label>
                  <Input
                    id="edit-duration"
                    name="durationMinutes"
                    type="number"
                    min="0"
                    defaultValue={Math.floor(editingAlert.durationSeconds / 60)}
                  />
                  <p className="text-xs text-muted-foreground">
                    Condition must be met for this long before alert triggers. 0 = immediate.
                  </p>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="edit-incidentSeverity">Alert Severity</Label>
                  <Select name="incidentSeverity" defaultValue={editingAlert.incidentSeverity || ''}>
                    <SelectTrigger>
                      <SelectValue placeholder="Use routing rule default" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="none">Use routing rule default</SelectItem>
                      <SelectItem value="CRITICAL">[P0] Critical</SelectItem>
                      <SelectItem value="HIGH">[P1] High</SelectItem>
                      <SelectItem value="MEDIUM">[P2] Medium</SelectItem>
                      <SelectItem value="LOW">[P3] Low</SelectItem>
                    </SelectContent>
                  </Select>
                  <p className="text-xs text-muted-foreground">
                    Override the default severity when this alert triggers an incident. P0–P2 page on-call 24/7. P3 notifies during business hours only.
                  </p>
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
    </div>
  )
}
