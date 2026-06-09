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

import type {DashboardVariable, TimeRangeDef} from '@/lib/api'

/** Grafana sentinel for an "All" variable selection. */
export const ALL_VALUE = '$__all'
/** Multi-select variable values are stored comma-joined. */
export const MULTI_SEPARATOR = ','

export interface TimeRangePreset {
  label: string
  full: string
  from: string
  to: string
}

export const TIME_RANGE_PRESETS: readonly TimeRangePreset[] = [
  {label: '15m', full: 'Last 15 minutes', from: 'now-15m', to: 'now'},
  {label: '1h', full: 'Last 1 hour', from: 'now-1h', to: 'now'},
  {label: '4h', full: 'Last 4 hours', from: 'now-4h', to: 'now'},
  {label: '24h', full: 'Last 24 hours', from: 'now-24h', to: 'now'},
  {label: '7d', full: 'Last 7 days', from: 'now-7d', to: 'now'},
  {label: '30d', full: 'Last 30 days', from: 'now-30d', to: 'now'},
]

export interface RefreshOption {
  label: string
  ms: number
}

export const REFRESH_OPTIONS: readonly RefreshOption[] = [
  {label: 'Off', ms: 0},
  {label: '10s', ms: 10_000},
  {label: '30s', ms: 30_000},
  {label: '1m', ms: 60_000},
  {label: '5m', ms: 300_000},
  {label: '15m', ms: 900_000},
]

export function activePreset(timeRange: TimeRangeDef): TimeRangePreset | undefined {
  return TIME_RANGE_PRESETS.find((p) => p.from === timeRange.from && p.to === timeRange.to)
}

const RELATIVE_RE = /^now-(\d+)([smhdwMy])$/
const UNIT_LABEL: Record<string, string> = {
  s: 'second', m: 'minute', h: 'hour', d: 'day', w: 'week', M: 'month', y: 'year',
}

function humanizeRelative(from: string): string {
  const match = RELATIVE_RE.exec(from)
  if (!match) return from
  const amount = Number(match[1])
  const unit = UNIT_LABEL[match[2]] ?? match[2]
  return `${amount} ${unit}${amount === 1 ? '' : 's'}`
}

/** Trigger label for the time picker, e.g. "Past 1 hour" or "Custom". */
export function timeRangeLabel(timeRange: TimeRangeDef): string {
  const preset = activePreset(timeRange)
  if (preset) return `Past ${humanizeRelative(preset.from)}`
  if (timeRange.from && timeRange.to) return 'Custom range'
  return 'Custom'
}

/** Compact resolved window shown in mono, e.g. "now-1h → now". */
export function timeRangeWindow(timeRange: TimeRangeDef): string {
  return `${timeRange.from} → ${timeRange.to}`
}

export function refreshLabel(ms: number): string {
  return REFRESH_OPTIONS.find((o) => o.ms === ms)?.label ?? 'Off'
}

/** Individual values for a (possibly multi-value, possibly empty) variable selection. */
export function selectedValues(value: string | undefined | null): string[] {
  if (!value || value === ALL_VALUE) return []
  return value.split(MULTI_SEPARATOR).map((v) => v.trim()).filter(Boolean)
}

/** Selectable options for a variable, with the "All" sentinel removed. */
export function realOptions(variable: DashboardVariable): string[] {
  return variable.options.filter((o) => o !== ALL_VALUE)
}

/** Resolves the value a variable should show, falling back to its current/default. */
export function effectiveValue(
  variable: DashboardVariable,
  value: string | undefined,
): string {
  return value ?? variable.current ?? variable.default_value ?? ''
}

/** Display text for a variable pill, e.g. "production", "all", or "3 of 8". */
export function variableDisplay(
  variable: DashboardVariable,
  value: string | undefined,
): string {
  const current = effectiveValue(variable, value)
  if (current === '') return '(none)'
  if (current === ALL_VALUE) return 'all'
  if (variable.multi) {
    const values = selectedValues(current)
    if (values.length === 0) return '(none)'
    if (values.length === 1) return values[0]
    const total = realOptions(variable).length
    return total > 0 ? `${values.length} of ${total}` : `${values.length} selected`
  }
  return current
}
