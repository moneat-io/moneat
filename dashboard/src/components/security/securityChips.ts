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

import type {ArchiveReason, SignalSeverity, SignalStatus} from '@/lib/api'

// Severity palette matches security.index.tsx so chips read consistently across the section.
export const severityColors: Record<string, string> = {
  critical: 'bg-red-500/15 text-red-500 border-red-500/30',
  high: 'bg-orange-500/15 text-orange-500 border-orange-500/30',
  medium: 'bg-amber-500/15 text-amber-500 border-amber-500/30',
  low: 'bg-blue-500/15 text-blue-500 border-blue-500/30',
  info: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
}

export const statusColors: Record<string, string> = {
  open: 'bg-blue-500/15 text-blue-500 border-blue-500/30',
  under_review: 'bg-amber-500/15 text-amber-500 border-amber-500/30',
  archived: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
}

export const SEVERITY_ORDER: SignalSeverity[] = ['critical', 'high', 'medium', 'low', 'info']
export const STATUS_ORDER: SignalStatus[] = ['open', 'under_review', 'archived']

export const STATUS_LABELS: Record<SignalStatus, string> = {
  open: 'Open',
  under_review: 'Under review',
  archived: 'Archived',
}

export const ARCHIVE_REASONS: {value: ArchiveReason; label: string}[] = [
  {value: 'true_positive', label: 'True positive'},
  {value: 'false_positive', label: 'False positive'},
  {value: 'benign', label: 'Benign'},
]

export const ARCHIVE_REASON_LABELS: Record<string, string> = Object.fromEntries(
  ARCHIVE_REASONS.map((r) => [r.value, r.label])
)

export function colorFor(map: Record<string, string>, key: string | null | undefined): string {
  return (key && map[key]) || ''
}

/** Formats a window in seconds as a compact human label (e.g. 300 -> "5m", 3600 -> "1h"). */
export function formatWindow(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return `${seconds}s`
  if (seconds % 86400 === 0) return `${seconds / 86400}d`
  if (seconds % 3600 === 0) return `${seconds / 3600}h`
  if (seconds % 60 === 0) return `${seconds / 60}m`
  return `${seconds}s`
}
