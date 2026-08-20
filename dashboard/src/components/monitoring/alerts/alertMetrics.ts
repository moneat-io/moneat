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

// ─────────────────────────────────────────────────────────────────────────────
// Metric vocabulary shared by the alert rule list and the rule form. The values
// mirror the metric keys the alert evaluator understands (MonitorAlertService
// `currentMetricValueQuery`); adding one here without adding it there yields a
// rule that never fires.
// ─────────────────────────────────────────────────────────────────────────────

import type {StatusTone} from '@/components/ui/status-dot'

export type AlertMetricOption = Readonly<{
  value: string
  label: string
  /** Short form for dense rows, where the group is already implied. */
  shortLabel: string
  group: string
  unit?: string
  tone: StatusTone
}>

export const ALERT_METRIC_OPTIONS: readonly AlertMetricOption[] = [
  {value: 'cpu_percent', label: 'CPU usage', shortLabel: 'CPU usage', group: 'Compute', unit: '%', tone: 'info'},
  {value: 'mem_percent', label: 'Memory usage', shortLabel: 'Memory usage', group: 'Compute', unit: '%', tone: 'accent'},
  {value: 'gpu_percent', label: 'GPU usage', shortLabel: 'GPU usage', group: 'Compute', unit: '%', tone: 'info'},
  {value: 'disk_percent', label: 'Disk usage', shortLabel: 'Disk usage', group: 'Storage', unit: '%', tone: 'warning'},
  {value: 'load_1', label: 'Load average (1m)', shortLabel: 'Load 1m', group: 'Load', tone: 'accent'},
  {value: 'load_5', label: 'Load average (5m)', shortLabel: 'Load 5m', group: 'Load', tone: 'accent'},
  {value: 'load_15', label: 'Load average (15m)', shortLabel: 'Load 15m', group: 'Load', tone: 'accent'},
  {value: 'temp_max', label: 'Max temperature', shortLabel: 'Max temp', group: 'Hardware', unit: '°C', tone: 'danger'},
  {
    value: 'battery_percent',
    label: 'Battery level',
    shortLabel: 'Battery level',
    group: 'Hardware',
    unit: '%',
    tone: 'success',
  },
]

/** Metric groups in declaration order, for the grouped picker. */
export const ALERT_METRIC_GROUPS: readonly string[] = [
  ...new Set(ALERT_METRIC_OPTIONS.map((option) => option.group)),
]

export const ALERT_CONDITION_OPTIONS = [
  {value: '>', label: 'is above'},
  {value: '>=', label: 'is at or above'},
  {value: '<', label: 'is below'},
  {value: '<=', label: 'is at or below'},
  {value: '==', label: 'equals'},
] as const

export const ALERT_PRIORITY_OPTIONS = ['P0', 'P1', 'P2', 'P3', 'P4', 'P5'] as const

/** Sentinel for "no priority override" — Select cannot hold an empty value. */
export const ALERT_PRIORITY_INHERIT = 'none'

export function findAlertMetric(metric: string): AlertMetricOption | undefined {
  return ALERT_METRIC_OPTIONS.find((option) => option.value === metric)
}

export function alertMetricLabel(metric: string): string {
  return findAlertMetric(metric)?.label ?? metric
}

export function alertMetricTone(metric: string): StatusTone {
  return findAlertMetric(metric)?.tone ?? 'neutral'
}

/** `82` on `cpu_percent` reads as `82%`; on `load_5` it stays bare. */
export function formatAlertThreshold(metric: string, threshold: number): string {
  const unit = findAlertMetric(metric)?.unit
  return unit ? `${threshold}${unit}` : String(threshold)
}

/** Renders the rule as the sentence it represents: "CPU usage is above 80%". */
export function describeAlertRule(metric: string, condition: string, threshold: number): string {
  const conditionLabel =
    ALERT_CONDITION_OPTIONS.find((option) => option.value === condition)?.label ?? condition
  return `${alertMetricLabel(metric)} ${conditionLabel} ${formatAlertThreshold(metric, threshold)}`
}

export function formatAlertDuration(durationSeconds: number): string {
  if (durationSeconds <= 0) return 'Immediately'
  const minutes = Math.round(durationSeconds / 60)
  if (minutes < 60) return `for ${minutes}m`
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  return remainder === 0 ? `for ${hours}h` : `for ${hours}h ${remainder}m`
}
