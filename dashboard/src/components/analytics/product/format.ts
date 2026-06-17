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

export type DeltaDirection = 'up' | 'down' | 'flat'

export interface MetricDelta {
  readonly value: string
  readonly direction: DeltaDirection
}

/** Compact number, e.g. 24600 → "24.6k", 128400 → "128.4k". */
export function formatCompact(value: number): string {
  if (Math.abs(value) >= 1000) {
    return new Intl.NumberFormat('en', {
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(value)
  }
  return value.toLocaleString()
}

export function formatPercent(value: number, digits = 0): string {
  return `${value.toFixed(digits)}%`
}

function directionOf(diff: number, epsilon = 0.05): DeltaDirection {
  if (diff > epsilon) return 'up'
  if (diff < -epsilon) return 'down'
  return 'flat'
}

/** Period-over-period change as a relative percentage — for count metrics. */
export function relativeDelta(current: number, previous?: number): MetricDelta | undefined {
  if (previous == null || previous === 0) return undefined
  const pct = ((current - previous) / previous) * 100
  return {value: `${Math.abs(pct).toFixed(1)}%`, direction: directionOf(pct)}
}

/** Period-over-period change in percentage points — for rate metrics (0–100). */
export function pointsDelta(current: number, previous?: number): MetricDelta | undefined {
  if (previous == null) return undefined
  const diff = current - previous
  return {value: `${Math.abs(diff).toFixed(1)}pp`, direction: directionOf(diff)}
}

/** Signed relative change from a ratio, e.g. 0.12 → "+12%", -0.03 → "−3%". */
export function formatSignedRatio(ratio: number): string {
  const pct = Math.round(ratio * 100)
  let sign = ''
  if (pct > 0) {
    sign = '+'
  } else if (pct < 0) {
    sign = '−'
  }
  return `${sign}${Math.abs(pct)}%`
}
