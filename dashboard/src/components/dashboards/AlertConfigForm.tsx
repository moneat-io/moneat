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

import {useEffect, useMemo, useState} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import type {AlertPriority, CreateDashboardAlertRequest, DashboardAlertCondition, QueryDsl} from '@/lib/api'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Bell, BellOff, Plus, Trash2} from 'lucide-react'
import {
  getWarningThresholdMessage,
  isWarningThresholdValid,
  type AlertThresholdPreview,
} from './alertThresholds'

const CONDITIONS = [
  {value: '>', label: '>'},
  {value: '<', label: '<'},
  {value: '>=', label: '>='},
  {value: '<=', label: '<='},
  {value: '==', label: '=='},
] as const

const ALERT_PRIORITIES = [
  {value: '', label: 'None'},
  {value: 'P0', label: 'P0'},
  {value: 'P1', label: 'P1'},
  {value: 'P2', label: 'P2'},
  {value: 'P3', label: 'P3'},
  {value: 'P4', label: 'P4'},
  {value: 'P5', label: 'P5'},
] as const

interface AlertConfigFormProps {
  dashboardId: number
  widgetId: number
  queryConfigs: QueryDsl[]
  onPreviewChange?: (preview: AlertThresholdPreview | null) => void
}

function getMetricLabels(queryConfigs: QueryDsl[]): string[] {
  const labels: string[] = []
  for (const q of queryConfigs) {
    for (const m of q.metrics ?? []) {
      labels.push(m.alias || `${m.function}(${m.field ?? ''})`)
    }
  }
  return labels.length > 0 ? labels : ['count()']
}

function parseOptionalNumber(value: string): number | null {
  if (value.trim() === '') return null
  const parsed = parseFloat(value)
  return Number.isFinite(parsed) ? parsed : null
}

function formatAlertThresholdSummary(alert: {
  condition: DashboardAlertCondition
  threshold: number
  warning_threshold: number | null
}): string {
  const error = `error ${alert.condition} ${alert.threshold}`
  if (alert.warning_threshold == null) return error
  return `warning ${alert.condition} ${alert.warning_threshold}, ${error}`
}

export function AlertConfigForm({
  dashboardId,
  widgetId,
  queryConfigs,
  onPreviewChange,
}: AlertConfigFormProps) {
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const metricLabels = getMetricLabels(queryConfigs)

  const [form, setForm] = useState<CreateDashboardAlertRequest>({
    widget_id: widgetId,
    name: '',
    condition: '>',
    threshold: 0,
    warning_threshold: null,
    metric_index: 0,
    duration_seconds: 0,
    alert_priority: null,
    enabled: true,
    notification_channels: {email: true, slack: true, discord: true},
  })

  const {data: alerts = []} = useQuery({
    queryKey: ['dashboard-alerts', dashboardId],
    queryFn: () => api.listDashboardAlerts(dashboardId),
  })

  const widgetAlerts = alerts.filter((a) => a.widget_id === widgetId)
  const thresholdPreview: AlertThresholdPreview = useMemo(() => ({
    condition: form.condition,
    warningThreshold: form.warning_threshold ?? null,
    errorThreshold: form.threshold,
  }), [form.condition, form.threshold, form.warning_threshold])
  const warningThresholdValid = isWarningThresholdValid(thresholdPreview)

  useEffect(() => {
    if (!onPreviewChange) return undefined
    if (!showForm) {
      onPreviewChange(null)
      return undefined
    }

    onPreviewChange(thresholdPreview)
    return () => onPreviewChange(null)
  }, [onPreviewChange, showForm, thresholdPreview])

  const createMutation = useMutation({
    mutationFn: (data: CreateDashboardAlertRequest) => api.createDashboardAlert(dashboardId, data),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['dashboard-alerts', dashboardId]})
      setShowForm(false)
      setForm({...form, name: '', threshold: 0, warning_threshold: null})
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({alertId, enabled}: {alertId: number; enabled: boolean}) =>
      api.updateDashboardAlert(dashboardId, alertId, {enabled}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['dashboard-alerts', dashboardId]}),
  })

  const deleteMutation = useMutation({
    mutationFn: (alertId: number) => api.deleteDashboardAlert(dashboardId, alertId),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['dashboard-alerts', dashboardId]}),
  })

  return (
    <div className="space-y-3">
      {/* Existing alerts */}
      {widgetAlerts.map((alert) => (
        <div
          key={alert.id}
          className={`flex items-center justify-between rounded-md border px-3 py-2 text-sm ${
            alert.enabled ? 'border-border' : 'border-border/50 opacity-60'
          }`}
        >
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={() => toggleMutation.mutate({alertId: alert.id, enabled: !alert.enabled})}
              className="p-0.5 rounded hover:bg-muted"
              title={alert.enabled ? 'Disable' : 'Enable'}
            >
              {alert.enabled ? (
                <Bell className="h-3.5 w-3.5 text-primary" />
              ) : (
                <BellOff className="h-3.5 w-3.5 text-muted-foreground" />
              )}
            </button>
            <span className="truncate font-medium">{alert.name}</span>
            <span className="text-muted-foreground shrink-0">
              {metricLabels[alert.metric_index] || 'metric'} {formatAlertThresholdSummary(alert)}
            </span>
            {alert.last_triggered_at && (
              <span className="text-xs text-warning-fg shrink-0">
                {alert.last_triggered_level ?? 'FIRING'}
              </span>
            )}
          </div>
          <button
            onClick={() => deleteMutation.mutate(alert.id)}
            className="p-1 text-muted-foreground hover:text-destructive shrink-0"
          >
            <Trash2 className="h-3 w-3" />
          </button>
        </div>
      ))}

      {/* Add alert form */}
      {showForm ? (
        <div className="space-y-2 rounded-md border p-3">
          <div>
            <label className="text-xs text-muted-foreground mb-0.5 block">Name</label>
            <input
              className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
              value={form.name}
              onChange={(e) => setForm({...form, name: e.target.value})}
              placeholder="Alert name"
            />
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <div>
              <label className="text-xs text-muted-foreground mb-0.5 block">Metric</label>
              <select
                className="w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                value={form.metric_index}
                onChange={(e) => setForm({...form, metric_index: parseInt(e.target.value)})}
              >
                {metricLabels.map((label, i) => (
                  <option key={i} value={i}>
                    {label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs text-muted-foreground mb-0.5 block">Duration (seconds)</label>
              <input
                type="number"
                className="w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                value={form.duration_seconds}
                onChange={(e) => setForm({...form, duration_seconds: parseInt(e.target.value) || 0})}
                placeholder="0"
              />
            </div>
          </div>
          <div className="rounded-md border bg-muted/20 p-2.5">
            <div className="mb-2 text-xs font-medium text-foreground">Thresholds</div>
            <div className="grid grid-cols-[88px_minmax(0,1fr)_minmax(0,1fr)] gap-2">
              <div>
                <label className="text-xs text-muted-foreground mb-0.5 block">Condition</label>
                <select
                  className="w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={form.condition}
                  onChange={(e) => setForm({...form, condition: e.target.value as DashboardAlertCondition})}
                >
                  {CONDITIONS.map((c) => (
                    <option key={c.value} value={c.value}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label
                  htmlFor="alert-warning-threshold"
                  className="mb-0.5 flex items-center gap-1.5 text-xs text-muted-foreground"
                >
                  <span className="h-2 w-2 rounded-full bg-warning-solid" />
                  Warning<span className="sr-only"> threshold</span>
                </label>
                <input
                  id="alert-warning-threshold"
                  type="number"
                  className="w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={form.warning_threshold ?? ''}
                  onChange={(e) => setForm({...form, warning_threshold: parseOptionalNumber(e.target.value)})}
                />
              </div>
              <div>
                <label
                  htmlFor="alert-error-threshold"
                  className="mb-0.5 flex items-center gap-1.5 text-xs text-muted-foreground"
                >
                  <span className="h-2 w-2 rounded-full bg-destructive" />
                  Error<span className="sr-only"> threshold</span>
                </label>
                <input
                  id="alert-error-threshold"
                  type="number"
                  className="w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={form.threshold}
                  onChange={(e) => setForm({...form, threshold: parseFloat(e.target.value) || 0})}
                />
              </div>
            </div>
            {!warningThresholdValid && (
              <p className="mt-1 text-xs text-destructive">
                {getWarningThresholdMessage(form.condition)}
              </p>
            )}
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <div>
              <label htmlFor="alert-priority" className="text-xs text-muted-foreground mb-0.5 block">
                Alert priority
              </label>
              <select
                id="alert-priority"
                className="w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                value={form.alert_priority ?? ''}
                onChange={(e) =>
                  setForm({
                    ...form,
                    alert_priority: e.target.value
                      ? (e.target.value as AlertPriority)
                      : null,
                  })
                }
              >
                {ALERT_PRIORITIES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs text-muted-foreground mb-1 block">Notification Channels</label>
              <div className="flex min-h-8 flex-wrap items-center gap-x-3 gap-y-1">
                {(['email', 'slack', 'discord'] as const).map((ch) => (
                  <label key={ch} className="flex items-center gap-1 text-xs">
                    <input
                      type="checkbox"
                      checked={form.notification_channels?.[ch] ?? true}
                      onChange={(e) =>
                        setForm({
                          ...form,
                          notification_channels: {
                            ...form.notification_channels!,
                            [ch]: e.target.checked,
                          },
                        })
                      }
                      className="rounded"
                    />
                    {ch.charAt(0).toUpperCase() + ch.slice(1)}
                  </label>
                ))}
              </div>
            </div>
          </div>
          <div className="flex gap-2 pt-1">
            <Button
              size="sm"
              onClick={() => createMutation.mutate(form)}
              disabled={!form.name || !warningThresholdValid || createMutation.isPending}
            >
              {createMutation.isPending ? 'Creating...' : 'Create Alert'}
            </Button>
            <Button size="sm" variant="outline" onClick={() => setShowForm(false)}>
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <button
          onClick={() => setShowForm(true)}
          className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <Plus className="h-3 w-3" /> Add alert
        </button>
      )}
    </div>
  )
}
