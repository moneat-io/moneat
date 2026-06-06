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

/**
 * View-layer types, formatting, and class-mapping helpers for the APM service /
 * service-detail / resource-detail pages.
 *
 * The page data comes from the `/v1/services*` APIs (see `@/lib/api`); the types
 * here are the structural prop contracts the presentation components render. They
 * mirror the API response shapes so a typed API payload can be passed straight
 * into the visual components.
 */

import type {BadgeProps} from '@/components/ui/badge'
import type {StatusTone} from '@/components/ui/status-dot'
import type {ApmTimeRange} from '@/lib/api'
import type {FacetSchema} from '@/lib/filters/types'

// ---------------------------------------------------------------------------
// Domain types
// ---------------------------------------------------------------------------

export type ServiceType = 'web' | 'worker' | 'db' | 'cache'
export type ServiceStatus = 'alerting' | 'degraded' | 'healthy'
/** Scale buckets for meter / gauge fills (green → amber → red). */
export type Severity = 'good' | 'warn' | 'bad'

export interface EnvPill {
  readonly label: string
  /** Categorical chart slot 1-10 used for the pill tint. */
  readonly chart: number
}

export interface StatDeltaSpec {
  readonly value: string
  readonly direction: 'up' | 'down' | 'flat'
  readonly tone?: StatusTone
}

export interface KpiSpec {
  readonly label: string
  readonly value: string
  readonly valueTone?: StatusTone
  readonly delta?: StatDeltaSpec
}

export interface LatencyPoint {
  readonly t: string
  readonly p50: number
  readonly p90: number
  readonly p95: number
  readonly p99: number
}

export interface ThroughputPoint {
  readonly t: string
  readonly rps: number
  readonly errors?: number
}

export interface GaugeRow {
  readonly label: string
  readonly valueText: string
  readonly pct: number
  readonly level: Severity
}

export interface ErrorBar {
  readonly h: number
  readonly level: 'warn' | 'bad'
}

export interface DistBar {
  readonly h: number
  readonly band: Severity
}

export interface DistMarker {
  readonly label: string
  readonly left: number
  readonly p99?: boolean
}

export type WaterfallTone = 'root' | 'app' | 'db' | 'cache' | 'http' | 'error'

export interface WaterfallRow {
  readonly op: string
  readonly desc: string
  readonly left: number
  readonly width: number
  readonly label: string
  readonly tone: WaterfallTone
  readonly indent?: number
  readonly selected?: boolean
}

export type ErrorSeverity = 'fatal' | 'error' | 'warn'

export interface ErrorRow {
  readonly severity: ErrorSeverity
  readonly title: string
  readonly sub: string
  readonly chips: readonly string[]
  readonly events: string
  readonly users?: string
  readonly unhandled?: boolean
}

// ---------------------------------------------------------------------------
// Formatting + class-mapping helpers
// ---------------------------------------------------------------------------

export function formatRps(rps: number): string {
  if (rps >= 1000) return `${(rps / 1000).toFixed(1)}k`
  return rps.toLocaleString()
}

export function formatMs(ms: number): string {
  if (ms >= 1000) {
    const seconds = (ms / 1000).toFixed(2).replace(/\.?0+$/, '')
    return `${seconds}s`
  }
  return `${ms}ms`
}

const TYPE_LABEL: Record<ServiceType, string> = {
  web: 'web',
  worker: 'worker',
  db: 'db',
  cache: 'cache',
}

export function serviceTypeLabel(type: ServiceType): string {
  return TYPE_LABEL[type] ?? type
}

export function statusBadgeVariant(status: ServiceStatus): BadgeProps['variant'] {
  switch (status) {
    case 'alerting':
      return 'danger'
    case 'degraded':
      return 'warning'
    default:
      return 'success'
  }
}

export function statusTone(status: ServiceStatus): StatusTone {
  switch (status) {
    case 'alerting':
      return 'danger'
    case 'degraded':
      return 'warning'
    default:
      return 'success'
  }
}

export function statusLabel(status: ServiceStatus): string {
  return status
}

/** Tailwind background for a meter / gauge fill by scale bucket. */
export function severityFill(level: Severity): string {
  switch (level) {
    case 'bad':
      return 'bg-danger-solid'
    case 'warn':
      return 'bg-warning-solid'
    default:
      return 'bg-success-solid'
  }
}

/** Soft heat chip (bg + fg) for the Apdex cell. */
export function apdexHeatClass(tone: StatusTone): string {
  switch (tone) {
    case 'success':
      return 'bg-success-bg text-success-fg'
    case 'warning':
      return 'bg-warning-bg text-warning-fg'
    case 'danger':
      return 'bg-danger-bg text-danger-fg'
    default:
      return 'bg-muted text-muted-foreground'
  }
}

export function errorSeverityTone(severity: ErrorSeverity): StatusTone {
  return severity === 'warn' ? 'warning' : 'danger'
}

export function errorSeverityBorder(severity: ErrorSeverity): string {
  return severity === 'warn' ? 'border-l-warning-solid' : 'border-l-danger-solid'
}

// ---------------------------------------------------------------------------
// Time range
// ---------------------------------------------------------------------------

export const APM_TIME_RANGE_OPTIONS: ReadonlyArray<{value: ApmTimeRange; label: string}> = [
  {value: '1h', label: 'Last hour'},
  {value: '6h', label: 'Last 6h'},
  {value: '24h', label: 'Last 24h'},
  {value: '7d', label: 'Last 7d'},
  {value: '30d', label: 'Last 30d'},
  {value: '90d', label: 'Last 90d'},
]

const RANGE_LABEL: Record<ApmTimeRange, string> = {
  '1h': 'past 1h',
  '6h': 'past 6h',
  '24h': 'past 24h',
  '7d': 'past 7d',
  '30d': 'past 30d',
  '90d': 'past 90d',
}

/** Short caption used on chart cards, e.g. `past 24h`. */
export function apmRangeLabel(range: ApmTimeRange): string {
  return RANGE_LABEL[range] ?? `past ${range}`
}

// ---------------------------------------------------------------------------
// Facets (search bar typeahead schema)
// ---------------------------------------------------------------------------

/**
 * Facets the catalog search bar understands. The rail's selectable values +
 * counts are derived from the loaded services at render time, so only the
 * dimensions the `/v1/services` payload actually carries are listed here.
 */
export const SERVICE_FACET_SCHEMA: FacetSchema = [
  {key: 'env', label: 'Environment', aliases: ['environment'], color: 'bg-chart-1', singleSelect: true},
  {key: 'type', label: 'Service type', color: 'bg-chart-2'},
  {key: 'source', label: 'Telemetry source', aliases: ['telemetry'], color: 'bg-chart-6'},
]
