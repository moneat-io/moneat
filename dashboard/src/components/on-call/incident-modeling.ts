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

// Shared presentation metadata for the incident-modeling surfaces: labels,
// badge variants, and small formatting helpers used by the declaration form,
// configuration editors, responders panel, and canonical timeline. Kept free of
// JSX so it can be imported by both components and tests.

import type {LucideIcon} from 'lucide-react'
import {
  Archive,
  Ban,
  Bell,
  CheckCircle2,
  ClipboardCheck,
  Eye,
  FileText,
  GitBranch,
  ListChecks,
  Link as LinkIcon,
  MessageSquare,
  Pencil,
  Send,
  Unlink,
  UserMinus,
  UserPlus,
  XCircle,
  Zap,
} from 'lucide-react'

import type {BadgeProps} from '@/components/ui/badge'
import type {
  CreateIncidentFormFieldInput,
  EditIncidentTimelineInput,
  IncidentCustomFieldDefinition,
  IncidentCustomFieldValueType,
  IncidentFieldValue,
  IncidentFormStage,
  IncidentSourceType,
  IncidentTimelineProvenance,
  IncidentTimelineVisibility,
  OnCallIncidentMode,
  OnCallIncidentVisibility,
} from '@/lib/api/types'

export const INCIDENT_MODES: OnCallIncidentMode[] = ['LIVE', 'RETROSPECTIVE', 'TEST']

export const INCIDENT_MODE_META: Record<
  OnCallIncidentMode,
  {label: string; description: string; variant: BadgeProps['variant']}
> = {
  LIVE: {label: 'Live', description: 'A real, ongoing incident', variant: 'danger'},
  RETROSPECTIVE: {
    label: 'Retrospective',
    description: 'Recording an incident after the fact',
    variant: 'info',
  },
  TEST: {label: 'Test', description: 'A drill or exercise — not a real incident', variant: 'neutral'},
}

export const INCIDENT_VISIBILITIES: OnCallIncidentVisibility[] = [
  'ORGANIZATION',
  'PRIVATE',
  'PUBLIC',
]

// Visibility is a classification of the intended audience. It records intent —
// enforcement of private-incident access and any public share surface are not
// built yet, so the copy stays about intent, not a promised mechanism.
export const INCIDENT_VISIBILITY_META: Record<
  OnCallIncidentVisibility,
  {label: string; description: string}
> = {
  ORGANIZATION: {label: 'Organization', description: 'Classified for an organization-wide audience'},
  PRIVATE: {label: 'Private', description: 'Classified as sensitive — intended for responders'},
  PUBLIC: {label: 'Public', description: 'Classified as shareable beyond your organization'},
}

const MODE_FALLBACK = {label: 'Unknown', description: '', variant: 'neutral' as const}
const VISIBILITY_FALLBACK = {label: 'Unknown', description: ''}

/** Mode presentation with a safe fallback for values a newer backend may add. */
export function incidentModeMeta(mode: string): {label: string; description: string; variant: BadgeProps['variant']} {
  return INCIDENT_MODE_META[mode as OnCallIncidentMode] ?? {...MODE_FALLBACK, label: humanizeKey(mode)}
}

/** Visibility presentation with a safe fallback for future enum values. */
export function incidentVisibilityMeta(visibility: string): {label: string; description: string} {
  return INCIDENT_VISIBILITY_META[visibility as OnCallIncidentVisibility] ?? {
    ...VISIBILITY_FALLBACK,
    label: humanizeKey(visibility),
  }
}

// Initial statuses offered at declaration. The backend accepts the full native
// lifecycle, but only these make sense as a starting point.
export const INITIAL_INCIDENT_STATUSES: {value: string; label: string; description: string}[] = [
  {value: 'TRIAGE', label: 'Triage', description: 'Assessing whether this is a real incident'},
  {value: 'ACTIVE', label: 'Active', description: 'Confirmed and actively being worked'},
]

export const FIELD_VALUE_TYPES: IncidentCustomFieldValueType[] = [
  'SELECT',
  'MULTI_SELECT',
  'TEXT',
  'NUMBER',
  'LINK',
  'USER',
  'TEAM',
  'SERVICE',
  'CATALOG_RESOURCE',
]

export const FIELD_VALUE_TYPE_LABELS: Record<IncidentCustomFieldValueType, string> = {
  SELECT: 'Select (single)',
  MULTI_SELECT: 'Multi-select',
  TEXT: 'Text',
  NUMBER: 'Number',
  LINK: 'Link',
  USER: 'User',
  TEAM: 'Team',
  SERVICE: 'Service',
  CATALOG_RESOURCE: 'Catalog resource',
}

export const OPTION_FIELD_TYPES: IncidentCustomFieldValueType[] = ['SELECT', 'MULTI_SELECT']

export const FORM_STAGES: IncidentFormStage[] = [
  'DECLARATION',
  'ACCEPTANCE',
  'UPDATE',
  'RESOLUTION',
  'ESCALATION',
]

export const FORM_STAGE_LABELS: Record<IncidentFormStage, string> = {
  DECLARATION: 'Declaration',
  ACCEPTANCE: 'Acceptance',
  UPDATE: 'Update',
  RESOLUTION: 'Resolution',
  ESCALATION: 'Escalation',
}

export const TIMELINE_PROVENANCES: IncidentTimelineProvenance[] = [
  'INTERNAL',
  'REST',
  'SLACK',
  'INTEGRATION',
  'IMPORT',
  'WORKFLOW',
]

// Provenance is metadata, not an interactive/selected affordance, so it never
// takes the cyan accent — the text label carries the distinction, tinted only
// with the neutral/info/warning status language.
export const PROVENANCE_META: Record<
  IncidentTimelineProvenance,
  {label: string; variant: BadgeProps['variant']}
> = {
  INTERNAL: {label: 'System', variant: 'neutral'},
  REST: {label: 'Dashboard', variant: 'info'},
  SLACK: {label: 'Slack', variant: 'neutral'},
  INTEGRATION: {label: 'Integration', variant: 'info'},
  IMPORT: {label: 'Imported', variant: 'warning'},
  WORKFLOW: {label: 'Workflow', variant: 'info'},
}

export const TIMELINE_VISIBILITIES: IncidentTimelineVisibility[] = [
  'ORGANIZATION',
  'PARTICIPANTS',
  'PRIVATE',
  'PUBLIC',
]

export const TIMELINE_VISIBILITY_META: Record<
  IncidentTimelineVisibility,
  {label: string; variant: BadgeProps['variant']}
> = {
  ORGANIZATION: {label: 'Organization', variant: 'neutral'},
  PARTICIPANTS: {label: 'Participants', variant: 'info'},
  PRIVATE: {label: 'Private', variant: 'warning'},
  PUBLIC: {label: 'Public', variant: 'success'},
}

// Source types the dashboard can collect a valid payload for. Alert and alert
// episode links are created through the alert linking flow, not here.
export const LINKABLE_SOURCE_TYPES: IncidentSourceType[] = ['URL', 'SLACK_MESSAGE', 'SOURCE_MESSAGE']

export const SOURCE_TYPE_LABELS: Record<IncidentSourceType, string> = {
  ON_CALL_ALERT: 'On-call alert',
  ALERT_EPISODE: 'Alert episode',
  SLACK_MESSAGE: 'Slack message',
  SOURCE_MESSAGE: 'Message',
  URL: 'Link',
}

/** Source-type label with a safe fallback for future enum values. */
export function sourceTypeLabel(sourceType: string): string {
  return SOURCE_TYPE_LABELS[sourceType as IncidentSourceType] ?? humanizeKey(sourceType)
}

interface TimelineEventStyle {
  icon: LucideIcon
  label: string
  color: string
  bgColor: string
}

// Semantic status/neutral tints only — never the charts-only ramp. Lifecycle and
// escalation events carry the status language; structural events stay neutral so
// color reads as meaning, not decoration. Keys mirror the backend event types.
const STATUS_STYLE = {
  danger: {color: 'text-danger-fg', bgColor: 'bg-danger-bg'},
  warning: {color: 'text-warning-fg', bgColor: 'bg-warning-bg'},
  success: {color: 'text-success-fg', bgColor: 'bg-success-bg'},
  info: {color: 'text-info-fg', bgColor: 'bg-info-bg'},
  neutral: {color: 'text-muted-foreground', bgColor: 'bg-muted'},
} as const

const TIMELINE_EVENT_STYLES: Record<string, TimelineEventStyle> = {
  DECLARED: {icon: Zap, label: 'Incident declared', ...STATUS_STYLE.danger},
  TRIAGED: {icon: ClipboardCheck, label: 'Triaged', ...STATUS_STYLE.warning},
  ACCEPTED: {icon: CheckCircle2, label: 'Accepted', ...STATUS_STYLE.info},
  RESOLVED: {icon: CheckCircle2, label: 'Resolved', ...STATUS_STYLE.success},
  POST_INCIDENT_STARTED: {icon: ClipboardCheck, label: 'Post-incident started', ...STATUS_STYLE.info},
  CLOSED: {icon: Archive, label: 'Closed', ...STATUS_STYLE.neutral},
  CANCELLED: {icon: Ban, label: 'Cancelled', ...STATUS_STYLE.neutral},
  DECLINED: {icon: XCircle, label: 'Declined', ...STATUS_STYLE.neutral},
  UPDATED: {icon: Pencil, label: 'Details updated', ...STATUS_STYLE.neutral},
  NOTE_ADDED: {icon: MessageSquare, label: 'Note added', ...STATUS_STYLE.neutral},
  ACTION_ADDED: {icon: ListChecks, label: 'Action added', ...STATUS_STYLE.info},
  ALERT_LINKED: {icon: Bell, label: 'Alert linked', ...STATUS_STYLE.info},
  SOURCE_LINKED: {icon: LinkIcon, label: 'Source linked', ...STATUS_STYLE.neutral},
  SOURCE_UNLINKED: {icon: Unlink, label: 'Source unlinked', ...STATUS_STYLE.neutral},
  ROLE_ASSIGNED: {icon: UserPlus, label: 'Role assigned', ...STATUS_STYLE.info},
  ROLE_UNASSIGNED: {icon: UserMinus, label: 'Role unassigned', ...STATUS_STYLE.neutral},
  ROLE_HANDED_OVER: {icon: GitBranch, label: 'Role handed over', ...STATUS_STYLE.info},
  PARTICIPANT_JOINED: {icon: UserPlus, label: 'Participant joined', ...STATUS_STYLE.info},
  OBSERVER_JOINED: {icon: Eye, label: 'Observer joined', ...STATUS_STYLE.neutral},
  PARTICIPANT_LEFT: {icon: UserMinus, label: 'Participant left', ...STATUS_STYLE.neutral},
  ESCALATED: {icon: Bell, label: 'Escalated', ...STATUS_STYLE.warning},
  NOTIFICATION_SENT: {icon: Send, label: 'Notification sent', ...STATUS_STYLE.neutral},
}

/** Humanize an UPPER_SNAKE event type or field key into a readable label. */
export function humanizeKey(value: string): string {
  const spaced = value.trim().replaceAll('_', ' ').toLowerCase()
  if (!spaced) return ''
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** Presentation style for a canonical timeline event type. */
export function timelineEventStyle(eventType: string): TimelineEventStyle {
  return (
    TIMELINE_EVENT_STYLES[eventType] ?? {
      icon: FileText,
      label: humanizeKey(eventType),
      color: 'text-muted-foreground',
      bgColor: 'bg-muted',
    }
  )
}

/** Incident severity mapped onto the shared status badge language. */
export function severityBadgeVariant(severity: string): BadgeProps['variant'] {
  const severityMatch = /^SEV-?(\d)/i.exec(severity)
  const severityLevel = severityMatch?.[1]
  if (severityLevel === '0' || severityLevel === '1') return 'danger'
  if (severityLevel === '2') return 'warning'
  if (severityLevel === '3') return 'info'
  return 'neutral'
}

export function timeAgo(date: string): string {
  const seconds = Math.floor((Date.now() - new Date(date).getTime()) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

export function formatDateTime(date: string): string {
  const parsed = new Date(date)
  return Number.isNaN(parsed.getTime()) ? date : parsed.toLocaleString()
}

// ──── Configured-field conditions (typed, backend-aligned) ────

/**
 * Evaluate a configured field's show-condition against current values, matching
 * the backend: an empty condition always shows; a non-empty condition that lacks
 * a string `fieldKey` or an `equals` value fails closed (hides the field) rather
 * than defaulting to visible. Equality is typed (a NUMBER equals compares as a
 * number, a MULTI_SELECT equals as an array), mirroring the backend Map DTO.
 */
export function evaluateFieldCondition(
  condition: Record<string, IncidentFieldValue> | undefined,
  values: Record<string, IncidentFieldValue>,
): boolean {
  if (!condition || Object.keys(condition).length === 0) return true
  const fieldKey = condition.fieldKey
  if (typeof fieldKey !== 'string' || fieldKey.length === 0) return false
  if (!('equals' in condition)) return false
  return JSON.stringify(values[fieldKey] ?? null) === JSON.stringify(condition.equals ?? null)
}

/** Field types whose stored value is a scalar, so a typed equals-condition can match. */
export function isScalarConditionField(field: IncidentCustomFieldDefinition): boolean {
  return field.valueType !== 'MULTI_SELECT'
}

/**
 * Parse a raw NUMBER-field text value into a finite number, accepting negatives
 * and decimals. Returns undefined for empty or not-yet-numeric input (e.g. a
 * lone "-") so partial keystrokes are stored as "no value" rather than blocked;
 * server-side validation remains authoritative for the final value.
 */
export function parseNumericInput(raw: string): number | undefined {
  const trimmed = raw.trim()
  if (trimmed === '') return undefined
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : undefined
}

/** Assemble a create-form field input, preserving typed defaults/conditions. */
export function buildFormFieldInput(row: {
  fieldId: string
  position: number
  visible: boolean
  required: boolean
  helpText: string
  defaultValue: IncidentFieldValue | undefined
  conditionFieldKey: string
  conditionEquals: IncidentFieldValue | undefined
}): CreateIncidentFormFieldInput {
  const equals = row.conditionEquals
  return {
    fieldId: row.fieldId,
    position: row.position,
    visible: row.visible,
    required: row.required,
    helpText: row.helpText.trim() || undefined,
    defaultValue:
      row.defaultValue === undefined || row.defaultValue === '' ? undefined : row.defaultValue,
    condition:
      row.conditionFieldKey.length > 0 && equals !== undefined && equals !== ''
        ? {fieldKey: row.conditionFieldKey, equals}
        : undefined,
  }
}

// ──── Timeline edit payload (finding: only send changed occurredAt; object details) ────

export interface TimelineEditOriginal {
  eventType: string
  visibility: IncidentTimelineVisibility
  occurredAtLocal: string
  details: Record<string, IncidentFieldValue>
}

export interface TimelineEditDraft {
  eventType: string
  visibility: IncidentTimelineVisibility
  occurredAtLocal: string
  detailsText: string
  reason: string
}

/**
 * Build the timeline edit payload, sending each field only when it actually
 * changed. `originalOccurredAt` is emitted solely when the local datetime input
 * differs from its initialized value (so an untouched minute-precision control
 * never truncates the stored seconds). Details must parse to a non-null,
 * non-array JSON object to match the backend `Map<String, JsonElement>` DTO.
 */
export function buildTimelineEditPayload(
  original: TimelineEditOriginal,
  draft: TimelineEditDraft,
): {payload: EditIncidentTimelineInput} | {error: string} {
  let details: Record<string, IncidentFieldValue> | undefined
  const trimmed = draft.detailsText.trim()
  const originalDetailsText = JSON.stringify(original.details ?? {}, null, 2).trim()
  if (trimmed && trimmed !== originalDetailsText) {
    let parsed: unknown
    try {
      parsed = JSON.parse(trimmed)
    } catch {
      return {error: 'Details must be valid JSON.'}
    }
    const isObject = parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
    if (!isObject) {
      return {error: 'Details must be a JSON object of key/value pairs.'}
    }
    details = parsed as Record<string, IncidentFieldValue>
  }

  const eventType = draft.eventType.trim()
  const occurredChanged =
    draft.occurredAtLocal.length > 0 && draft.occurredAtLocal !== original.occurredAtLocal

  // A changed occurred-at must parse to a real instant. Invalid input (a cleared
  // or malformed datetime-local value) is surfaced through the same error
  // contract rather than producing an Invalid Date / throwing from toISOString().
  let originalOccurredAt: string | undefined
  if (occurredChanged) {
    const parsedOccurred = new Date(draft.occurredAtLocal)
    if (Number.isNaN(parsedOccurred.getTime())) {
      return {error: 'Occurred-at must be a valid date and time.'}
    }
    originalOccurredAt = parsedOccurred.toISOString()
  }

  return {
    payload: {
      eventType: eventType && eventType !== original.eventType ? eventType : undefined,
      visibility: draft.visibility === original.visibility ? undefined : draft.visibility,
      originalOccurredAt,
      details,
      reason: draft.reason.trim() || undefined,
    },
  }
}

// ──── Timeline detail summaries ────

export interface TimelineDetailSummary {
  summary: string | null
  fallback: Array<[string, string]>
}

// Keys that are plumbing (opaque ids, origin), rendered elsewhere (note), or
// private (role instructions). They are never surfaced in the generic fallback.
const HIDDEN_DETAIL_KEYS = new Set([
  'origin',
  'commandtype',
  'actoruserid',
  'version',
  'incidentid',
  'roleid',
  'alertid',
  'note',
  'annotation',
])

/** Private role instructions must never be surfaced, at any nesting depth. */
function isPrivateDetailKey(key: string): boolean {
  const lower = key.toLowerCase()
  return lower.includes('instruction') || lower.includes('private')
}

function isHiddenDetailKey(key: string): boolean {
  return HIDDEN_DETAIL_KEYS.has(key.toLowerCase()) || isPrivateDetailKey(key)
}

/**
 * Recursively remove private-instruction keys from a detail value so that a
 * nested object/array can never leak instructions through a stringified render,
 * while keeping every other (legitimate) key intact.
 */
function stripPrivateDetailKeys(value: IncidentFieldValue): IncidentFieldValue {
  if (Array.isArray(value)) return value.map(stripPrivateDetailKeys)
  if (value !== null && typeof value === 'object') {
    const cleaned: Record<string, IncidentFieldValue> = {}
    for (const [key, nested] of Object.entries(value)) {
      if (isPrivateDetailKey(key)) continue
      cleaned[key] = stripPrivateDetailKeys(nested)
    }
    return cleaned
  }
  return value
}

function detailToString(value: IncidentFieldValue | undefined): string {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map(detailToString).filter(Boolean).join(', ')
  return JSON.stringify(stripPrivateDetailKeys(value))
}

/** Short, safe fallback for an opaque id when no display name resolves. */
export function shortResourceId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id
}

/**
 * Produce a calm, scannable summary of a canonical timeline event from its real
 * `details`, plus a key/value fallback for events we do not specially format.
 * Private role instructions are never surfaced. `resolveUser` maps an opaque
 * user id to a display name.
 */
export function summarizeTimelineDetails(
  eventType: string,
  details: Record<string, IncidentFieldValue> | undefined,
  resolveUser: (id: string | undefined) => string,
): TimelineDetailSummary {
  if (!details) return {summary: null, fallback: []}
  const str = (key: string) => detailToString(details[key])
  const known = (summary: string | null): TimelineDetailSummary => ({summary, fallback: []})

  switch (eventType) {
    case 'DECLARED':
    case 'UPDATED':
    case 'NOTE_ADDED':
      return known(null)
    case 'TRIAGED':
    case 'ACCEPTED':
    case 'RESOLVED':
    case 'POST_INCIDENT_STARTED':
    case 'CLOSED':
    case 'CANCELLED':
    case 'DECLINED': {
      const previous = str('previousStatus')
      return known(previous ? `From ${humanizeKey(previous)}` : null)
    }
    case 'ROLE_ASSIGNED':
      return known(`${str('role')} → ${resolveUser(str('assigneeUserId') || undefined)}`.trim())
    case 'ROLE_UNASSIGNED':
      return known(`${str('role')} · ${resolveUser(str('assigneeUserId') || undefined)}`.trim())
    case 'ROLE_HANDED_OVER':
      return known(
        `${str('role')}: ${resolveUser(str('fromUserId') || undefined)} → ${resolveUser(
          str('toUserId') || undefined,
        )}`.trim(),
      )
    case 'PARTICIPANT_JOINED':
    case 'OBSERVER_JOINED':
      return known(`${resolveUser(str('userId') || undefined)} · ${humanizeKey(str('participationType'))}`.trim())
    case 'PARTICIPANT_LEFT':
      return known(`${resolveUser(str('userId') || undefined)} left`.trim())
    case 'ALERT_LINKED':
      return known(str('alertTitle') || null)
    case 'SOURCE_LINKED':
    case 'SOURCE_UNLINKED': {
      const type = sourceTypeLabel(str('sourceType'))
      const key = str('sourceKey')
      return known(key ? `${type}: ${key}` : type || null)
    }
    case 'ACTION_ADDED': {
      const title = str('title')
      const assignee = str('assigneeUserId')
      return known(assignee ? `${title} → ${resolveUser(assignee)}` : title || null)
    }
    default: {
      const fallback = Object.entries(details)
        .filter(([key]) => !isHiddenDetailKey(key))
        .map(([key, value]): [string, string] => [humanizeKey(key), detailToString(value)])
        .filter(([, value]) => value.length > 0)
      return {summary: null, fallback}
    }
  }
}
