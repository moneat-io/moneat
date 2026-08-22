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

import type {LucideIcon} from 'lucide-react'
import {Archive, Ban, CheckCircle2, ClipboardCheck, Clock, XCircle, Zap} from 'lucide-react'

import type {BadgeProps} from '@/components/ui/badge'
import type {StatusTone} from '@/components/ui/status-dot'
import type {OnCallIncidentStatus} from '@/lib/api/types'

// Single source of truth for mapping a native declared-incident status to the
// shared status language, so the incident list and detail views stay in sync
// instead of each carrying its own switch. The wire status OPEN was migrated to
// ACTIVE; unknown values fall back to a neutral badge.
export interface IncidentStatusConfig {
  variant: BadgeProps['variant']
  icon: LucideIcon
  label: string
  tone: StatusTone
}

const INCIDENT_STATUS_CONFIG: Record<OnCallIncidentStatus, IncidentStatusConfig> = {
  TRIAGE: {variant: 'warning', icon: Clock, label: 'Triage', tone: 'warning'},
  ACTIVE: {variant: 'danger', icon: Zap, label: 'Active', tone: 'danger'},
  RESOLVED: {variant: 'success', icon: CheckCircle2, label: 'Resolved', tone: 'success'},
  POST_INCIDENT: {variant: 'info', icon: ClipboardCheck, label: 'Post-incident', tone: 'info'},
  CLOSED: {variant: 'neutral', icon: Archive, label: 'Closed', tone: 'neutral'},
  CANCELLED: {variant: 'neutral', icon: Ban, label: 'Cancelled', tone: 'neutral'},
  DECLINED: {variant: 'neutral', icon: XCircle, label: 'Declined', tone: 'neutral'},
}

function fallbackLabel(status: string): string {
  const normalized = status.trim()
  if (!normalized) return 'Unknown'
  const spaced = normalized.replaceAll('_', ' ').toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** Map a native incident status to its badge variant, icon, label, and tone. */
export function incidentStatusConfig(status: string): IncidentStatusConfig {
  return (
    INCIDENT_STATUS_CONFIG[status as OnCallIncidentStatus] ?? {
      variant: 'neutral',
      icon: Clock,
      label: fallbackLabel(status),
      tone: 'neutral',
    }
  )
}

// A native incident can only be resolved from the ACTIVE state, mirroring the
// backend's allowed lifecycle transitions. Used to decide when the detail view
// offers the resolve action.
export function isResolvableIncidentStatus(status: string): boolean {
  return status === 'ACTIVE'
}
