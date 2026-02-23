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

export interface ValueMapping {
  value: string
  text: string
  color?: string
}

export type UnitType =
  | 'none'
  | 'short'
  | 'bytes'
  | 'bytes/s'
  | 'percent'
  | 'ms'
  | 's'
  | 'reqps'
  | 'ops'
  | 'dateTimeAsIso'
  | 'locale'

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
const SHORT_SUFFIXES = ['', 'K', 'M', 'B', 'T']

function resolveDecimals(decimals: string | undefined, fallback: number): number {
  if (!decimals || decimals === 'auto') return fallback
  const n = parseInt(decimals, 10)
  return isNaN(n) ? fallback : n
}

function formatBytes(value: number, decimals: string | undefined): string {
  if (value === 0) return '0 B'
  const abs = Math.abs(value)
  const k = 1024
  let idx = Math.floor(Math.log(abs) / Math.log(k))
  idx = Math.min(idx, BYTE_UNITS.length - 1)
  const scaled = value / Math.pow(k, idx)
  const dec = resolveDecimals(decimals, idx === 0 ? 0 : 2)
  return `${scaled.toFixed(dec)} ${BYTE_UNITS[idx]}`
}

function formatBytesPerSec(value: number, decimals: string | undefined): string {
  return `${formatBytes(value, decimals)}/s`
}

function formatShort(value: number, decimals: string | undefined): string {
  if (value === 0) return '0'
  const abs = Math.abs(value)
  let idx = 0
  let scaled = abs
  while (scaled >= 1000 && idx < SHORT_SUFFIXES.length - 1) {
    scaled /= 1000
    idx++
  }
  const dec = resolveDecimals(decimals, idx === 0 ? 0 : 1)
  const sign = value < 0 ? '-' : ''
  return `${sign}${scaled.toFixed(dec)}${SHORT_SUFFIXES[idx]}`
}

function formatMs(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, 1)
  const abs = Math.abs(value)
  if (abs >= 604800000) return `${(value / 604800000).toFixed(dec)} weeks`
  if (abs >= 86400000) return `${(value / 86400000).toFixed(dec)} days`
  if (abs >= 3600000) return `${(value / 3600000).toFixed(dec)} h`
  if (abs >= 1000) return `${(value / 1000).toFixed(dec)} s`
  return `${value.toFixed(dec)} ms`
}

function formatSeconds(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, 1)
  const abs = Math.abs(value)
  if (abs >= 604800) return `${(value / 604800).toFixed(dec)} weeks`
  if (abs >= 86400) return `${(value / 86400).toFixed(dec)} days`
  if (abs >= 3600) return `${(value / 3600).toFixed(dec)} h`
  if (abs >= 60) return `${(value / 60).toFixed(dec)} m`
  return `${value.toFixed(dec)} s`
}

function formatPercent(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, 1)
  return `${value.toFixed(dec)}%`
}

function formatReqps(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, 1)
  return `${value.toFixed(dec)} req/s`
}

function formatOps(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, 1)
  return `${value.toFixed(dec)} ops/s`
}

function formatNone(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, Number.isInteger(value) ? 0 : 2)
  return value.toFixed(dec)
}

function formatDateTimeAsIso(value: number): string {
  // Grafana stores epoch seconds (or milliseconds); detect and convert
  const ts = value < 1e12 ? value * 1000 : value
  const d = new Date(ts)
  if (isNaN(d.getTime())) return String(value)
  return d.toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, '')
}

function formatLocale(value: number, decimals: string | undefined): string {
  const dec = resolveDecimals(decimals, Number.isInteger(value) ? 0 : 2)
  return value.toLocaleString(undefined, {
    minimumFractionDigits: dec,
    maximumFractionDigits: dec,
  })
}

/**
 * Format a numeric value with unit and decimal configuration.
 * Optionally applies value mappings (exact match by string comparison).
 */
export function formatValue(
  value: unknown,
  unit?: UnitType | string,
  decimals?: string,
  valueMappings?: ValueMapping[],
): string {
  // Value mappings take priority (exact match)
  if (valueMappings && valueMappings.length > 0) {
    const strVal = String(value)
    const mapping = valueMappings.find((m) => m.value === strVal)
    if (mapping) return mapping.text
  }

  if (typeof value !== 'number') return String(value ?? '')
  if (!isFinite(value)) return String(value)

  switch (unit) {
    case 'short':
      return formatShort(value, decimals)
    case 'bytes':
      return formatBytes(value, decimals)
    case 'bytes/s':
      return formatBytesPerSec(value, decimals)
    case 'percent':
      return formatPercent(value, decimals)
    case 'ms':
      return formatMs(value, decimals)
    case 's':
      return formatSeconds(value, decimals)
    case 'reqps':
      return formatReqps(value, decimals)
    case 'ops':
      return formatOps(value, decimals)
    case 'dateTimeAsIso':
      return formatDateTimeAsIso(value)
    case 'locale':
      return formatLocale(value, decimals)
    case 'none':
    default:
      return formatNone(value, decimals)
  }
}

/**
 * Find the matching value mapping (for color coding).
 */
export function findValueMapping(
  value: unknown,
  valueMappings?: ValueMapping[],
): ValueMapping | undefined {
  if (!valueMappings || valueMappings.length === 0) return undefined
  return valueMappings.find((m) => m.value === String(value))
}
