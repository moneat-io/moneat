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

import type {SyntheticLocationResponse} from '@/lib/api'

export interface LocationMeta {
  abbr: string
  color: string
  name: string
  isPrivate: boolean
}

// Managed fleet codes → abbreviation + hue (mirrors the mockup's location pins).
const MANAGED_META: Record<string, {abbr: string; color: string; name: string}> = {
  'aws-us-east-1': {abbr: 'VA', color: '#2ba56f', name: 'US East'},
  'aws-us-west-2': {abbr: 'OR', color: '#3b82f6', name: 'US West'},
  'aws-eu-central-1': {abbr: 'FR', color: '#e0a100', name: 'EU · Frankfurt'},
  'aws-eu-west-1': {abbr: 'IE', color: '#8454e0', name: 'EU · Ireland'},
  'aws-ap-southeast-1': {abbr: 'SG', color: '#0e9da8', name: 'Asia · Singapore'},
  'aws-ap-northeast-1': {abbr: 'TK', color: '#e1567c', name: 'Asia · Tokyo'},
  'aws-sa-east-1': {abbr: 'SP', color: '#f27537', name: 'São Paulo'},
  'aws-us-east-2': {abbr: 'OH', color: '#0369a1', name: 'US Central'},
  moneat: {abbr: 'MN', color: '#6b7280', name: 'Default probe'},
}

export function locationMeta(
  code: string,
  locations?: readonly SyntheticLocationResponse[]
): LocationMeta {
  const managed = MANAGED_META[code]
  if (managed) return {...managed, isPrivate: false}
  const loc = locations?.find((l) => l.code === code)
  const name = loc?.name ?? code
  return {
    abbr: name.replace(/[^a-zA-Z]/g, '').slice(0, 2).toUpperCase() || 'PR',
    color: '#6b7280',
    name,
    isPrivate: loc?.type === 'private',
  }
}

export const PHASE_COLORS: Record<string, string> = {
  dns: '#8454e0',
  tcp: '#3b82f6',
  tls: '#0e9da8',
  ttfb: '#e0a100',
  waiting: '#e0a100',
  download: '#2ba56f',
  total: '#6b7280',
  udp: '#3b82f6',
}

export const PHASE_ORDER = ['dns', 'tcp', 'tls', 'ttfb', 'waiting', 'download', 'udp']

/** p95 of an array of numbers. */
export function p95(values: number[]): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const idx = Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))
  return sorted[idx]
}
