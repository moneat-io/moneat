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

// Pure, source-neutral helpers for the Alert Route editor: option lists,
// defaults, form <-> request mapping, client-side validation, and preview
// interpretation. Kept free of JSX so the rules can be unit-tested in isolation
// and mirror the backend `AlertRouteValidator`.

import type {
  AlertRoute,
  AlertRouteCondition,
  AlertRouteConditionField,
  AlertRouteConditionOperator,
  AlertRouteEvaluation,
  AlertRouteGroupingBehavior,
  AlertRouteGroupingWindowKind,
  AlertRouteIncidentMode,
  AlertRoutePagingMode,
  AlertRoutePreviewResponse,
  AlertRouteRequest,
  AlertRouteTarget,
  AlertRouteTargetKind,
  AlertRouteOwnershipSource,
  AlertRouteEvaluationReason,
} from '@/lib/api/types'

export interface Option<T extends string> {
  value: T
  label: string
}

interface EditorRow {
  clientKey: string
}

export type AlertRouteFormCondition = AlertRouteCondition & EditorRow
export interface AlertRouteFormConditionGroup extends EditorRow {
  conditions: AlertRouteFormCondition[]
}
export type AlertRouteFormTarget = AlertRouteTarget & EditorRow
export interface AlertRouteFormPaging extends Omit<AlertRouteRequest['paging'], 'targets'> {
  targets: AlertRouteFormTarget[]
}

// Editor-only keys keep dynamic React rows stable and are deliberately omitted
// by formToRequest so the wire contract remains identical to AlertRouteRequest.
export interface AlertRouteFormState extends Omit<AlertRouteRequest, 'expectedRevision' | 'conditionGroups' | 'paging'> {
  conditionGroups: AlertRouteFormConditionGroup[]
  paging: AlertRouteFormPaging
}

const editorKeySequence = {current: 0}

export function createEditorKey(prefix: string): string {
  editorKeySequence.current += 1
  return `${prefix}-${editorKeySequence.current}`
}

export const ALERT_ROUTE_KEY_PATTERN = /^[a-z0-9][a-z0-9_-]*$/
export const MAX_WINDOW_SECONDS = 86_400
export const MAX_GRACE_SECONDS = 86_400
export const MAX_GROUPING_KEYS = 8

export const CONDITION_FIELD_OPTIONS: ReadonlyArray<Option<AlertRouteConditionField>> = [
  { value: 'source', label: 'Alert source' },
  { value: 'status', label: 'Alert status' },
  { value: 'priority', label: 'Priority' },
  { value: 'title', label: 'Title' },
  { value: 'description', label: 'Description' },
  { value: 'deduplication_key', label: 'Deduplication key' },
  { value: 'url', label: 'Alert URL' },
  { value: 'episode_key', label: 'Episode key' },
  { value: 'episode_sequence', label: 'Episode sequence' },
  { value: 'episode_status', label: 'Episode status' },
  { value: 'service', label: 'Service' },
  { value: 'team', label: 'Team' },
  { value: 'catalog_resource', label: 'Catalog resource' },
  { value: 'metadata', label: 'Metadata attribute' },
]

export function fieldSupportsMetadataKey(field: AlertRouteConditionField): boolean {
  return field === 'metadata'
}

export const CONDITION_OPERATOR_OPTIONS: ReadonlyArray<Option<AlertRouteConditionOperator>> = [
  { value: 'EQUALS', label: 'equals' },
  { value: 'NOT_EQUALS', label: 'does not equal' },
  { value: 'ONE_OF', label: 'is one of' },
  { value: 'NOT_ONE_OF', label: 'is not one of' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'NOT_CONTAINS', label: 'does not contain' },
  { value: 'STARTS_WITH', label: 'starts with' },
  { value: 'ENDS_WITH', label: 'ends with' },
  { value: 'IS_SET', label: 'is set' },
  { value: 'IS_NOT_SET', label: 'is not set' },
  { value: 'AT_LEAST_AS_SEVERE_AS', label: 'is at least as severe as' },
]

const VALUELESS_OPERATORS: ReadonlySet<AlertRouteConditionOperator> = new Set([
  'IS_SET',
  'IS_NOT_SET',
])

const PRIORITY_ONLY_OPERATORS: ReadonlySet<AlertRouteConditionOperator> = new Set([
  'AT_LEAST_AS_SEVERE_AS',
])

export function operatorTakesValues(operator: AlertRouteConditionOperator): boolean {
  return !VALUELESS_OPERATORS.has(operator)
}

export function operatorIsPriorityOnly(operator: AlertRouteConditionOperator): boolean {
  return PRIORITY_ONLY_OPERATORS.has(operator)
}

// Metadata keys the backend approves for routing. Mirrors
// AlertRouteMetadataPolicy.APPROVED_KEYS; anything else is dropped from context.
export const APPROVED_METADATA_KEYS: readonly string[] = [
  'alert.condition',
  'alert.current_value',
  'alert.dashboard.title',
  'alert.display_title',
  'alert.threshold',
  'alert.widget.title',
  'catalog_resource_id',
  'cluster',
  'environment',
  'host_id',
  'monitor_id',
  'namespace',
  'region',
  'service',
  'service_name',
  'team',
  'triggered_by',
]

export const PAGING_MODE_OPTIONS: ReadonlyArray<Option<AlertRoutePagingMode>> = [
  { value: 'NONE', label: 'Do not page' },
  { value: 'FIRST_EPISODE_PER_GROUP', label: 'Page once per group' },
  { value: 'EVERY_EPISODE', label: 'Page every alert' },
]

export const TARGET_KIND_OPTIONS: ReadonlyArray<Option<AlertRouteTargetKind>> = [
  { value: 'ESCALATION_POLICY', label: 'Escalation policy' },
  { value: 'TEAM', label: 'Team' },
  { value: 'OWNERSHIP_DERIVED', label: 'Derived from ownership' },
]

export const OWNERSHIP_SOURCE_OPTIONS: ReadonlyArray<Option<AlertRouteOwnershipSource>> = [
  { value: 'SERVICE', label: 'Service owner' },
  { value: 'TEAM', label: 'Team owner' },
  { value: 'CATALOG', label: 'Catalog owner' },
]

export const GROUPING_BEHAVIOR_OPTIONS: ReadonlyArray<Option<AlertRouteGroupingBehavior>> = [
  { value: 'SUGGESTED', label: 'Suggested — group only after a responder confirms' },
  { value: 'AUTOMATIC', label: 'Automatic — group without waiting for confirmation' },
]

export const WINDOW_KIND_OPTIONS: ReadonlyArray<Option<AlertRouteGroupingWindowKind>> = [
  { value: 'ROLLING', label: 'Rolling — extends on each new alert' },
  { value: 'FIXED', label: 'Fixed — anchored on the first alert' },
]

export const INCIDENT_MODE_OPTIONS: ReadonlyArray<Option<AlertRouteIncidentMode>> = [
  { value: 'TRIAGE', label: 'Open in triage — a responder classifies it' },
  { value: 'ACTIVE', label: 'Open as active — requires a severity' },
]

export const PRIORITY_OPTIONS: readonly string[] = ['P0', 'P1', 'P2', 'P3', 'P4', 'P5']
export const SEVERITY_OPTIONS: readonly string[] = ['SEV-0', 'SEV-1', 'SEV-2', 'SEV-3', 'SEV-4']
export const ALERT_STATUS_OPTIONS: readonly string[] = ['FIRING', 'RESOLVED']
export const ALERT_SOURCE_OPTIONS: readonly string[] = [
  'HOST_ALERT',
  'HOST_DOWN',
  'UPTIME_MONITOR',
  'SYNTHETIC_TEST',
  'ERROR_ALERT',
  'DASHBOARD_ALERT',
]

const DEFAULT_WINDOW_SECONDS = 300

const GROUPING_REFERENCE_LEAVES: Readonly<Record<string, ReadonlySet<string>>> = {
  alert: new Set(CONDITION_FIELD_OPTIONS
    .map((option) => option.value)
    .filter((field) => field !== 'metadata')),
  metadata: new Set(APPROVED_METADATA_KEYS),
  ownership: new Set(['service', 'team', 'catalog', 'catalog_resource']),
  episode: new Set(['id', 'resource_id', 'key', 'sequence', 'status']),
}

function isValidGroupingReference(reference: string): boolean {
  const [scope, leaf, ...extra] = reference.trim().toLowerCase().split('.')
  return extra.length === 0 && leaf !== undefined && leaf.length > 0 &&
    GROUPING_REFERENCE_LEAVES[scope]?.has(leaf) === true
}

// A fresh condition for a newly added row. Defaults to the common "priority is at
// least as severe as P3" shape so a new row is valid without further edits.
export function createDefaultCondition(): AlertRouteFormCondition {
  return {
    clientKey: createEditorKey('condition'),
    field: 'priority',
    operator: 'AT_LEAST_AS_SEVERE_AS',
    values: ['P3'],
  }
}

// A fresh paging target of the given kind, carrying only the field that kind uses.
export function createDefaultTarget(kind: AlertRouteTargetKind): AlertRouteFormTarget {
  const clientKey = createEditorKey('target')
  switch (kind) {
    case 'TEAM':
      return {clientKey, kind, teamId: null}
    case 'OWNERSHIP_DERIVED':
      return {clientKey, kind, ownershipSource: 'SERVICE'}
    case 'ESCALATION_POLICY':
    default:
      return {clientKey, kind, escalationPolicyId: null}
  }
}

// Reduce a target to only the identifier its kind uses, so a target whose kind
// was changed in the editor never ships stale escalation-policy/team/ownership
// references to the backend.
function normalizeTarget(target: AlertRouteTarget): AlertRouteTarget {
  switch (target.kind) {
    case 'TEAM':
      return {kind: 'TEAM', teamId: target.teamId ?? null}
    case 'OWNERSHIP_DERIVED':
      return {kind: 'OWNERSHIP_DERIVED', ownershipSource: target.ownershipSource ?? null}
    case 'ESCALATION_POLICY':
    default:
      return {kind: 'ESCALATION_POLICY', escalationPolicyId: target.escalationPolicyId ?? null}
  }
}

export function createEmptyAlertRouteForm(): AlertRouteFormState {
  return {
    key: '',
    name: '',
    description: '',
    enabled: true,
    conditionGroups: [{
      clientKey: createEditorKey('condition-group'),
      conditions: [{
        clientKey: createEditorKey('condition'),
        field: 'priority',
        operator: 'AT_LEAST_AS_SEVERE_AS',
        values: ['P2'],
      }],
    }],
    paging: { mode: 'NONE', targets: [] },
    grouping: { behavior: 'SUGGESTED', keys: [], windowKind: 'ROLLING', windowSeconds: DEFAULT_WINDOW_SECONDS },
    incident: { create: false, mode: 'TRIAGE' },
    recovery: { cancelEscalations: false, declineUnacceptedTriage: false, graceSeconds: 0 },
  }
}

// Deep clone a saved route into an editable form. Structured clone keeps nested
// arrays independent so edits never mutate the cached query data.
export function alertRouteToForm(route: AlertRoute): AlertRouteFormState {
  return {
    key: route.key,
    name: route.name,
    description: route.description ?? '',
    enabled: route.enabled,
    conditionGroups: route.conditionGroups.map((group) => ({
      clientKey: createEditorKey('condition-group'),
      conditions: group.conditions.map((condition) => ({
        clientKey: createEditorKey('condition'),
        field: condition.field,
        operator: condition.operator,
        values: [...condition.values],
        metadataKey: condition.metadataKey ?? undefined,
      })),
    })),
    paging: {
      mode: route.paging.mode,
      targets: route.paging.targets.map((target) => ({
        ...target,
        clientKey: createEditorKey('target'),
      })),
    },
    grouping: { ...route.grouping, keys: [...route.grouping.keys] },
    incident: { ...route.incident },
    recovery: { ...route.recovery },
  }
}

function trimmedOrUndefined(value: string | null | undefined): string | undefined {
  const trimmed = value?.trim()
  return trimmed || undefined
}

// Normalize a form into the wire request: drop blank values, trim strings, and
// only attach expectedRevision when updating an existing route.
export function formToRequest(
  form: AlertRouteFormState,
  expectedRevision?: number
): AlertRouteRequest {
  const request: AlertRouteRequest = {
    key: form.key.trim(),
    name: form.name.trim(),
    description: trimmedOrUndefined(form.description) ?? null,
    enabled: form.enabled,
    conditionGroups: form.conditionGroups.map((group) => ({
      conditions: group.conditions.map((condition) => ({
        field: condition.field,
        operator: condition.operator,
        values: operatorTakesValues(condition.operator)
          ? condition.values.map((value) => value.trim()).filter((value) => value.length > 0)
          : [],
        metadataKey: fieldSupportsMetadataKey(condition.field)
          ? trimmedOrUndefined(condition.metadataKey)
          : undefined,
      })),
    })),
    paging: {
      mode: form.paging.mode,
      targets: form.paging.mode === 'NONE' ? [] : form.paging.targets.map(normalizeTarget),
    },
    grouping: {
      behavior: form.grouping.behavior,
      keys: form.grouping.keys.map((key) => key.trim().toLowerCase()).filter((key) => key.length > 0),
      windowKind: form.grouping.windowKind,
      windowSeconds: form.grouping.windowSeconds,
    },
    incident: {
      create: form.incident.create,
      mode: form.incident.mode,
      titleTemplate: trimmedOrUndefined(form.incident.titleTemplate),
      summaryTemplate: trimmedOrUndefined(form.incident.summaryTemplate),
      severity: trimmedOrUndefined(form.incident.severity),
      incidentType: trimmedOrUndefined(form.incident.incidentType),
    },
    recovery: { ...form.recovery },
  }
  if (expectedRevision !== undefined) request.expectedRevision = expectedRevision
  return request
}

// Client-side validation mirroring the backend validator. Returns human,
// source-neutral messages; the backend remains the source of truth and its
// errors are surfaced verbatim on save.
export function validateAlertRouteForm(form: AlertRouteFormState): string[] {
  const errors: string[] = []
  const key = form.key.trim()
  if (!key) {
    errors.push('A route key is required.')
  } else if (!ALERT_ROUTE_KEY_PATTERN.test(key)) {
    errors.push('The route key may only use lowercase letters, digits, hyphens, and underscores.')
  }
  if (!form.name.trim()) errors.push('A route name is required.')

  form.conditionGroups.forEach((group, groupIndex) => {
    if (group.conditions.length === 0) {
      errors.push(`Condition group ${groupIndex + 1} needs at least one condition.`)
    }
    group.conditions.forEach((condition, conditionIndex) => {
      const label = `Group ${groupIndex + 1}, condition ${conditionIndex + 1}`
      if (fieldSupportsMetadataKey(condition.field) && !trimmedOrUndefined(condition.metadataKey)) {
        errors.push(`${label}: choose a metadata attribute.`)
      }
      if (operatorIsPriorityOnly(condition.operator) && condition.field !== 'priority') {
        errors.push(`${label}: "is at least as severe as" only applies to the priority field.`)
      }
      if (operatorTakesValues(condition.operator)) {
        const nonBlank = condition.values.map((value) => value.trim()).filter((value) => value.length > 0)
        if (nonBlank.length === 0) errors.push(`${label}: add at least one value.`)
      }
    })
  })

  if (form.paging.mode !== 'NONE' && form.paging.targets.length === 0) {
    errors.push('Choose at least one paging target, or set paging to "Do not page".')
  }
  form.paging.targets.forEach((target, index) => {
    const label = `Paging target ${index + 1}`
    if (target.kind === 'ESCALATION_POLICY' && !target.escalationPolicyId) {
      errors.push(`${label}: select an escalation policy.`)
    }
    if (target.kind === 'TEAM' && !target.teamId) errors.push(`${label}: select a team.`)
    if (target.kind === 'OWNERSHIP_DERIVED' && !target.ownershipSource) {
      errors.push(`${label}: choose an ownership source.`)
    }
  })

  if (form.grouping.keys.length > MAX_GROUPING_KEYS) {
    errors.push(`A route can group on at most ${MAX_GROUPING_KEYS} keys.`)
  }
  form.grouping.keys.forEach((key, index) => {
    if (!isValidGroupingReference(key)) {
      errors.push(
        `Grouping key ${index + 1} must be a supported scoped field, such as ` +
        'ownership.service, metadata.environment, or alert.deduplication_key.'
      )
    }
  })
  if (form.grouping.windowSeconds < 1 || form.grouping.windowSeconds > MAX_WINDOW_SECONDS) {
    errors.push(`The grouping window must be between 1 second and ${formatWindow(MAX_WINDOW_SECONDS)}.`)
  }

  if (form.incident.create && form.incident.mode === 'ACTIVE' && !trimmedOrUndefined(form.incident.severity)) {
    errors.push('Opening an incident as active requires a severity.')
  }

  if (form.recovery.graceSeconds < 0 || form.recovery.graceSeconds > MAX_GRACE_SECONDS) {
    errors.push(`The recovery grace period must be between 0 and ${formatWindow(MAX_GRACE_SECONDS)}.`)
  }
  return errors
}

const SECONDS_PER_MINUTE = 60
const SECONDS_PER_HOUR = 3_600
const SECONDS_PER_DAY = 86_400

// Compact human duration, e.g. 300 -> "5m", 3600 -> "1h", 90 -> "1m 30s".
export function formatWindow(totalSeconds: number): string {
  if (!Number.isFinite(totalSeconds) || totalSeconds <= 0) return '0s'
  const days = Math.floor(totalSeconds / SECONDS_PER_DAY)
  const hours = Math.floor((totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR)
  const minutes = Math.floor((totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
  const seconds = totalSeconds % SECONDS_PER_MINUTE
  const parts: string[] = []
  if (days) parts.push(`${days}d`)
  if (hours) parts.push(`${hours}h`)
  if (minutes) parts.push(`${minutes}m`)
  if (seconds) parts.push(`${seconds}s`)
  return parts.join(' ')
}

const CONFLICT_STATUS = 409

// The API client attaches the HTTP status to thrown errors; a 409 means our
// expected revision/version was stale.
export function isConflictError(error: unknown): boolean {
  return typeof error === 'object' && error !== null && (error as { status?: number }).status === CONFLICT_STATUS
}

const EVALUATION_REASON_COPY: Record<AlertRouteEvaluationReason, string> = {
  SELECTED: 'Selected — this route handles the alert',
  NO_GROUP_MATCHED: 'No condition group matched',
  ROUTE_DISABLED: 'Route is disabled',
  EARLIER_ROUTE_SELECTED: 'An earlier route was selected first',
}

export function describeEvaluationReason(reason: AlertRouteEvaluationReason): string {
  return EVALUATION_REASON_COPY[reason] ?? reason
}

// Routes that matched the previewed alert but were shadowed because an earlier
// route was selected. These are the overlaps an admin needs to see when a new or
// reordered route would otherwise never fire.
export function overlappingEarlierRoutes(
  preview: AlertRoutePreviewResponse
): AlertRouteEvaluation[] {
  return preview.evaluations.filter(
    (evaluation) => evaluation.matched && !evaluation.selected && evaluation.reason === 'EARLIER_ROUTE_SELECTED'
  )
}

const FIELD_LABELS: Record<AlertRouteConditionField, string> = Object.fromEntries(
  CONDITION_FIELD_OPTIONS.map((option) => [option.value, option.label])
) as Record<AlertRouteConditionField, string>

const OPERATOR_LABELS: Record<AlertRouteConditionOperator, string> = Object.fromEntries(
  CONDITION_OPERATOR_OPTIONS.map((option) => [option.value, option.label])
) as Record<AlertRouteConditionOperator, string>

export function conditionFieldLabel(field: AlertRouteConditionField): string {
  return FIELD_LABELS[field] ?? field
}

export function conditionOperatorLabel(operator: AlertRouteConditionOperator): string {
  return OPERATOR_LABELS[operator] ?? operator
}

// One-line, source-neutral description of a single condition, e.g.
// "Metadata environment is one of production, staging" or "Priority is set".
export function describeCondition(condition: AlertRouteCondition): string {
  const fieldLabel = fieldSupportsMetadataKey(condition.field)
    ? `Metadata ${condition.metadataKey ?? '(unset)'}`
    : conditionFieldLabel(condition.field)
  const operatorLabel = conditionOperatorLabel(condition.operator)
  if (!operatorTakesValues(condition.operator)) return `${fieldLabel} ${operatorLabel}`
  const values = condition.values.filter((value) => value.trim().length > 0)
  return `${fieldLabel} ${operatorLabel} ${values.join(', ')}`.trim()
}

export function pagingModeLabel(mode: AlertRoutePagingMode): string {
  return PAGING_MODE_OPTIONS.find((option) => option.value === mode)?.label ?? mode
}
