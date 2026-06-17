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

// Severity is a semantic ramp, mapped onto the shared status language so chips
// read consistently across the section.
export const severityColors: Record<string, string> = {
  critical: 'bg-danger-solid text-white border-transparent',
  high: 'bg-danger-bg text-danger-fg border-danger-border',
  medium: 'bg-warning-bg text-warning-fg border-warning-border',
  low: 'bg-info-bg text-info-fg border-info-border',
  info: 'bg-muted text-muted-foreground border-border',
}

export const statusColors: Record<string, string> = {
  open: 'bg-info-bg text-info-fg border-info-border',
  under_review: 'bg-warning-bg text-warning-fg border-warning-border',
  archived: 'bg-muted text-muted-foreground border-border',
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
