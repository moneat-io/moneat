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
import {api, type SystemAlert} from '@/lib/api'
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
import {Bell, BellRing, Clock, Edit, Globe2, Mail, Plus, Server, Shield, Trash2, Zap} from 'lucide-react'
import {formatRelativeTime} from '@/lib/utils'

type HostAlertScope = 'global' | 'host'

interface AlertsTabProps {
  hostId: number
}

const METRIC_OPTIONS = [
  {value: 'cpu_percent', label: 'CPU Usage (%)', color: 'text-blue-500'},
  {value: 'mem_percent', label: 'Memory Usage (%)', color: 'text-violet-500'},
  {value: 'disk_percent', label: 'Disk Usage (%)', color: 'text-amber-500'},
  {value: 'load_1', label: 'Load Average (1m)', color: 'text-emerald-500'},
  {value: 'load_5', label: 'Load Average (5m)', color: 'text-emerald-500'},
  {value: 'load_15', label: 'Load Average (15m)', color: 'text-emerald-500'},
  {value: 'temp_max', label: 'Max Temperature (°C)', color: 'text-rose-500'},
  {value: 'gpu_percent', label: 'GPU Usage (%)', color: 'text-teal-500'},
  {value: 'battery_percent', label: 'Battery Level (%)', color: 'text-yellow-500'},
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
  const [editingAlert, setEditingAlert] = useState<SystemAlert | null>(null)
  const [createEnabled, setCreateEnabled] = useState(false)

  const {data: alertConfig, isLoading} = useQuery({
    queryKey: ['host-alert-config', hostId],
    queryFn: () => api.getHostAlertConfig(hostId),
    enabled: api.isAuthenticated(),
  })

  const {data: integrations = []} = useQuery({
    queryKey: ['integrations'],
    queryFn: () => api.getIntegrations(),
    enabled: api.isAuthenticated(),
  })

  const slackEnabled = integrations.some(i => i.integrationType === 'slack' && i.enabled)
  const discordEnabled = integrations.some(i => i.integrationType === 'discord' && i.enabled)

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
    mutationFn: ({alert, updates}: {alert: SystemAlert; updates: Partial<SystemAlert>}) =>
      api.updateHostAlert(hostId, alert.id, updates, alert.scope as HostAlertScope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
      setIsEditDialogOpen(false)
      setEditingAlert(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (alert: SystemAlert) => api.deleteHostAlert(hostId, alert.id, alert.scope as HostAlertScope),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['host-alert-config', hostId]})
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({alert, enabled}: {alert: SystemAlert; enabled: boolean}) =>
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
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
            <div className="flex items-center gap-3">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-amber-500/10">
                <BellRing className="h-5 w-5 text-amber-500" />
              </div>
              <div>
                <CardTitle>Alert Rules</CardTitle>
                <CardDescription>
                  {totalAlerts > 0
                    ? `${enabledAlerts} of ${totalAlerts} rules active (${activeScope === 'global' ? 'shared globally' : 'host-only'})`
                    : 'No rules available'}
                </CardDescription>
              </div>
            </div>

            <div className="flex flex-col items-stretch gap-2 sm:flex-row sm:items-center">
              <div className="flex items-center rounded-lg border p-1 bg-muted/30">
                <Button
                  type="button"
                  size="sm"
                  variant={activeScope === 'global' ? 'default' : 'ghost'}
                  className="h-8 gap-1.5"
                  onClick={() => scopeMutation.mutate('global')}
                  disabled={scopeMutation.isPending || activeScope === 'global'}
                >
                  <Globe2 className="h-3.5 w-3.5" />
                  Global
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={activeScope === 'host' ? 'default' : 'ghost'}
                  className="h-8 gap-1.5"
                  onClick={() => scopeMutation.mutate('host')}
                  disabled={scopeMutation.isPending || activeScope === 'host'}
                >
                  <Server className="h-3.5 w-3.5" />
                  This Host
                </Button>
              </div>

              <Dialog open={isCreateDialogOpen} onOpenChange={setIsCreateDialogOpen}>
                <DialogTrigger asChild>
                  <Button className="gap-2">
                    <Plus className="h-4 w-4" />
                    Add Rule
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle className="flex items-center gap-2">
                      <Zap className="h-5 w-5 text-amber-500" />
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
                        <Label htmlFor="incidentSeverity">Incident Severity</Label>
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

          <p className="text-xs text-muted-foreground">
            {activeScope === 'global'
              ? 'Global profile applies to all hosts currently set to Global.'
              : 'Host profile applies only to this host. Global changes will not affect it.'}
          </p>
        </CardHeader>

        <CardContent>
          <div className="mb-6 rounded-md bg-blue-500/10 p-4 text-sm text-blue-500 flex items-start gap-3 border border-blue-500/20">
            <BellRing className="h-5 w-5 shrink-0 mt-0.5" />
            <div className="space-y-1">
              <p className="font-medium">Notification Channels</p>
              <p className="text-blue-500/80">
                Configure which channels (Email, Slack, Discord) receive these alerts in{' '}
                <Link to="/settings" search={{ tab: 'notifications' }} className="underline hover:text-blue-400">
                  Settings &gt; Notifications
                </Link>.
              </p>
            </div>
          </div>
          {isLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="flex flex-col items-center gap-3">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                <p className="text-muted-foreground text-sm">Loading alerts...</p>
              </div>
            </div>
          ) : alerts.length > 0 ? (
            <div className="space-y-3">
              {alerts.map((alert) => (
                <div
                  key={`${alert.scope}-${alert.id}`}
                  className={`group relative flex items-center gap-4 rounded-lg border p-4 transition-colors ${
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
                          {Math.floor(alert.durationSeconds / 60)}m
                        </Badge>
                      )}
                      <Badge variant="secondary" className="text-[10px] uppercase">
                        {alert.scope}
                      </Badge>
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
            <div className="text-center py-16">
              <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-500/10">
                <Bell className="h-8 w-8 text-amber-500" />
              </div>
              <h3 className="text-lg font-medium mb-1">No rules in this scope</h3>
              <p className="text-muted-foreground text-sm mb-6 max-w-sm mx-auto">
                Default recommendations are seeded automatically. Add custom rules if you need stricter thresholds.
              </p>
              <Button onClick={() => setIsCreateDialogOpen(true)} className="gap-2">
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
                  <Label htmlFor="edit-incidentSeverity">Incident Severity</Label>
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

      <div className="grid gap-4 md:grid-cols-2">
        <Card className="bg-gradient-to-br from-blue-500/5 to-indigo-500/5 border-blue-500/10">
          <CardContent className="pt-5 pb-4">
            <div className="flex items-start gap-3">
              <div className="flex items-center justify-center h-9 w-9 rounded-lg bg-blue-500/15 shrink-0">
                <Mail className="h-4 w-4 text-blue-500" />
              </div>
              <div className="space-y-1">
                <h4 className="text-sm font-medium">
                  {slackEnabled && discordEnabled ? 'Email, Slack & Discord Notifications' : 
                   slackEnabled ? 'Email & Slack Notifications' :
                   discordEnabled ? 'Email & Discord Notifications' : 
                   'Email Notifications'}
                </h4>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  Alert notifications are sent to all members of your organization via email
                  {slackEnabled && discordEnabled && ', to your configured Slack channel, and to your configured Discord channel'}
                  {slackEnabled && !discordEnabled && ' and to your configured Slack channel'}
                  {!slackEnabled && discordEnabled && ' and to your configured Discord channel'}. 
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
                <h4 className="text-sm font-medium">Host Status Notifications</h4>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  You'll also receive notifications when hosts go down (no metrics for 5+
                  minutes) or come back online after being offline
                  {slackEnabled && ', both via email and Slack'}.
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
