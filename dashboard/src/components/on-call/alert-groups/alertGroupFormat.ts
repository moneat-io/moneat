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

// Source-neutral labels and status tones for Alert Groups, shared by the list
// and detail surfaces so both speak one language.

import type {BadgeProps} from '@/components/ui/badge'
import type {
  AlertGroup,
  AlertGroupDecisionType,
  AlertGroupMember,
  AlertGroupMemberState,
  AlertGroupPagingState,
  AlertGroupState,
  AlertRouteGroupingBehavior,
  AlertRouteGroupingWindowKind,
} from '@/lib/api/types'

export interface BadgeMeta {
  variant: BadgeProps['variant']
  label: string
}

const GROUP_STATE_META: Record<AlertGroupState, BadgeMeta> = {
  OPEN: {variant: 'info', label: 'Open'},
  CLOSED: {variant: 'neutral', label: 'Closed'},
}

export function groupStateMeta(state: string): BadgeMeta {
  return GROUP_STATE_META[state as AlertGroupState] ?? {variant: 'neutral', label: state}
}

const MEMBER_STATE_META: Record<AlertGroupMemberState, BadgeMeta> = {
  ACTIVE: {variant: 'success', label: 'Active'},
  REMOVED: {variant: 'neutral', label: 'Removed'},
  UNRELATED: {variant: 'warning', label: 'Unrelated'},
}

export function memberStateMeta(state: string): BadgeMeta {
  return MEMBER_STATE_META[state as AlertGroupMemberState] ?? {variant: 'neutral', label: state}
}

const PAGING_STATE_META: Record<AlertGroupPagingState, BadgeMeta> = {
  READY: {variant: 'warning', label: 'Paging ready'},
  CLAIMED: {variant: 'info', label: 'Paging claimed'},
  DELIVERED: {variant: 'success', label: 'Paged'},
  FAILED: {variant: 'danger', label: 'Paging failed'},
  NOT_REQUIRED: {variant: 'neutral', label: 'No paging'},
}

export function pagingStateMeta(state: string): BadgeMeta {
  return PAGING_STATE_META[state as AlertGroupPagingState] ?? {variant: 'neutral', label: state}
}

const BEHAVIOR_LABELS: Record<AlertRouteGroupingBehavior, string> = {
  SUGGESTED: 'Suggested',
  AUTOMATIC: 'Automatic',
}

export function behaviorLabel(behavior: string): string {
  return BEHAVIOR_LABELS[behavior as AlertRouteGroupingBehavior] ?? behavior
}

const WINDOW_KIND_LABELS: Record<AlertRouteGroupingWindowKind, string> = {
  FIXED: 'Fixed',
  ROLLING: 'Rolling',
}

export function windowKindLabel(kind: string): string {
  return WINDOW_KIND_LABELS[kind as AlertRouteGroupingWindowKind] ?? kind
}

const DECISION_TYPE_LABELS: Record<AlertGroupDecisionType, string> = {
  GROUP_CREATED: 'Group opened',
  SUGGESTED: 'Grouping suggested',
  MARKED_UNRELATED: 'Alert marked unrelated',
  REMOVED: 'Alert removed',
  ATTACHED: 'Attached to incident',
  AUTOMATICALLY_ATTACHED: 'Automatically attached to incident',
  TRIAGE_CREATED: 'Triage incident created',
  RECOVERY_COMPLETED: 'Recovery completed',
}

export function decisionTypeLabel(type: string): string {
  return DECISION_TYPE_LABELS[type as AlertGroupDecisionType] ?? type
}

// Members that still count toward the group (excludes removed / unrelated).
export function activeMembers(group: Pick<AlertGroup, 'members'>): AlertGroupMember[] {
  return group.members.filter((member) => member.state === 'ACTIVE')
}

export interface GroupLinkage {
  /** 'incident' when firmly linked, 'candidate' when a suggestion is pending, else 'none'. */
  kind: 'incident' | 'candidate' | 'none'
  incidentId: string | null
}

export function groupIncidentLinkage(group: Pick<AlertGroup, 'incidentId' | 'candidateIncidentId'>): GroupLinkage {
  if (group.incidentId) return {kind: 'incident', incidentId: group.incidentId}
  if (group.candidateIncidentId) return {kind: 'candidate', incidentId: group.candidateIncidentId}
  return {kind: 'none', incidentId: null}
}

// Human "closes in 4m" / "closed 2m ago" relative to the group window boundary.
export function relativeWindow(closesAtIso: string, now: number = Date.now()): string {
  const closesAt = new Date(closesAtIso).getTime()
  if (Number.isNaN(closesAt)) return closesAtIso
  const deltaSeconds = Math.round((closesAt - now) / 1000)
  const magnitude = Math.abs(deltaSeconds)
  const unit = formatRelativeMagnitude(magnitude)
  return deltaSeconds >= 0 ? `closes in ${unit}` : `closed ${unit} ago`
}

function formatRelativeMagnitude(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h`
  return `${Math.floor(hours / 24)}d`
}
