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

// Types for the native incident modeling surfaces: standalone declaration,
// configuration (types / custom fields / stage forms / responder roles),
// responders (roles, participants, observers, handover), the canonical
// evidence-preserving timeline, and generic source references. IDs are always
// opaque resource UUIDs, never internal surrogate ids.

import type { OnCallIncidentMode, OnCallIncidentVisibility } from './on-call'

/** A JSON-serialisable configured-field value carried in incident form payloads. */
export type IncidentFieldValue =
  | string
  | number
  | boolean
  | null
  | IncidentFieldValue[]
  | { [key: string]: IncidentFieldValue }

// ──── Declaration ────

/**
 * Payload for declaring an incident, shared by the standalone declaration flow
 * and the from-alert flow (which posts the same body to the alert declare path).
 * The backend applies defaults for mode/visibility/initialStatus, so callers may
 * omit them, but the declaration form always sends them explicitly.
 */
export interface DeclareIncidentInput {
  title: string
  description?: string
  summary?: string
  severity?: string
  mode?: OnCallIncidentMode
  visibility?: OnCallIncidentVisibility
  initialStatus?: string
  incidentTypeId?: string
  fields?: Record<string, IncidentFieldValue>
}

// ──── Configuration: incident types ────

export interface IncidentTypeDefinition {
  id: string
  key: string
  version: number
  name: string
  description?: string
  enabled: boolean
}

export interface CreateIncidentTypeInput {
  key: string
  name: string
  description?: string
  enabled?: boolean
}

// ──── Configuration: custom fields ────

export type IncidentCustomFieldValueType =
  | 'SELECT'
  | 'MULTI_SELECT'
  | 'TEXT'
  | 'NUMBER'
  | 'LINK'
  | 'USER'
  | 'TEAM'
  | 'SERVICE'
  | 'CATALOG_RESOURCE'

export interface IncidentCustomFieldOption {
  id: string
  value: string
  label: string
  position: number
  color?: string
}

export interface IncidentCustomFieldDefinition {
  id: string
  key: string
  version: number
  name: string
  description?: string
  valueType: IncidentCustomFieldValueType
  catalogResourceType?: string
  options: IncidentCustomFieldOption[]
}

export interface CreateIncidentCustomFieldOptionInput {
  value: string
  label: string
  position: number
  color?: string
}

export interface CreateIncidentCustomFieldInput {
  key: string
  name: string
  description?: string
  valueType: IncidentCustomFieldValueType
  catalogResourceType?: string
  options?: CreateIncidentCustomFieldOptionInput[]
}

// ──── Configuration: stage forms ────

export type IncidentFormStage =
  | 'DECLARATION'
  | 'ACCEPTANCE'
  | 'UPDATE'
  | 'RESOLUTION'
  | 'ESCALATION'

export interface IncidentFormFieldDefinition {
  id: string
  field: IncidentCustomFieldDefinition
  position: number
  visible: boolean
  required: boolean
  defaultValue?: IncidentFieldValue
  helpText?: string
  condition: Record<string, IncidentFieldValue>
}

export interface IncidentFormDefinition {
  id: string
  incidentTypeId?: string
  stage: IncidentFormStage
  version: number
  name: string
  fields: IncidentFormFieldDefinition[]
}

export interface CreateIncidentFormFieldInput {
  fieldId: string
  position: number
  visible?: boolean
  required?: boolean
  defaultValue?: IncidentFieldValue
  helpText?: string
  condition?: Record<string, IncidentFieldValue>
}

export interface CreateIncidentFormInput {
  incidentTypeId?: string
  stage: IncidentFormStage
  name: string
  fields: CreateIncidentFormFieldInput[]
}

// ──── Configuration & responders: roles ────

export interface IncidentRoleDefinition {
  id: string
  key: string
  version: number
  name: string
  description?: string
  responsibilities: string[]
  // Private responder instructions are authored here but MUST NOT be surfaced to
  // general incident viewers on the incident detail responders panel.
  privateInstructions?: string
  required: boolean
  default: boolean
}

export interface CreateIncidentRoleInput {
  key: string
  name: string
  description?: string
  responsibilities: string[]
  privateInstructions?: string
  required?: boolean
  default?: boolean
}

export interface IncidentRoleAssignment {
  id: string
  role: IncidentRoleDefinition
  assigneeUserId: string
  assignedByUserId: string
  assignedAt: string
}

// ──── Responders: participants & observers ────

export type IncidentParticipationType = 'PARTICIPANT' | 'OBSERVER'

export interface IncidentParticipant {
  id: string
  userId: string
  type: IncidentParticipationType
  joinedByUserId: string
  joinedAt: string
}

export interface SetIncidentParticipantInput {
  userId?: string
  expectedVersion?: number
}

export interface HandoverIncidentRoleInput {
  userId: string
  note?: string
  expectedVersion?: number
}

// ──── Canonical timeline ────

export type IncidentTimelineProvenance =
  | 'INTERNAL'
  | 'REST'
  | 'SLACK'
  | 'INTEGRATION'
  | 'IMPORT'
  | 'WORKFLOW'

export type IncidentTimelineVisibility =
  | 'ORGANIZATION'
  | 'PARTICIPANTS'
  | 'PRIVATE'
  | 'PUBLIC'

export interface IncidentTimelineEntry {
  id: string
  eventKey: string
  eventType: string
  actorUserId?: string
  details: Record<string, IncidentFieldValue>
  sourceType?: string
  sourceReference?: string
  sourceUrl?: string
  provenance: IncidentTimelineProvenance
  visibility: IncidentTimelineVisibility
  originalOccurredAt: string
  observedAt: string
  displayOrder: number
  annotation?: string
  editedAt?: string
  deletedAt?: string
  createdAt: string
}

export interface IncidentTimelineRevision {
  id: string
  revision: number
  action: string
  previous: Record<string, IncidentFieldValue>
  next: Record<string, IncidentFieldValue>
  reason?: string
  editedByUserId: string
  createdAt: string
}

export interface IncidentTimelineExport {
  incidentId: string
  exportedAt: string
  events: IncidentTimelineEntry[]
}

export interface IncidentTimelineFilterInput {
  eventType?: string[]
  provenance?: IncidentTimelineProvenance[]
  visibility?: IncidentTimelineVisibility[]
  includeDeleted?: boolean
}

export interface EditIncidentTimelineInput {
  eventType?: string
  details?: Record<string, IncidentFieldValue>
  originalOccurredAt?: string
  visibility?: IncidentTimelineVisibility
  reason?: string
}

// ──── Source references ────

export type IncidentSourceType =
  | 'ON_CALL_ALERT'
  | 'ALERT_EPISODE'
  | 'SLACK_MESSAGE'
  | 'SOURCE_MESSAGE'
  | 'URL'

export interface IncidentSourceLink {
  id: string
  sourceType: IncidentSourceType
  sourceKey: string
  label?: string
  sourceUrl?: string
  metadata: Record<string, IncidentFieldValue>
  createdAt: string
}

export interface LinkIncidentSourceInput {
  sourceType: IncidentSourceType
  sourceId?: string
  sourceKey?: string
  label?: string
  sourceUrl?: string
  metadata?: Record<string, IncidentFieldValue>
}
