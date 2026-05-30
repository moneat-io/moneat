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

import type {WorkflowExportResponse, WorkflowImportRequest} from '@/lib/api'

export function jsonExport(exportData: WorkflowExportResponse): string {
  return JSON.stringify(exportData.resource, null, 2)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Parses pasted JSON (raw resource or a full export envelope) into an import request. */
export function parseImportJson(raw: string): WorkflowImportRequest | string {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return 'Invalid JSON.'
  }
  if (!isRecord(parsed)) return 'Expected a JSON object.'
  const resource = isRecord(parsed.resource) ? parsed.resource : parsed
  const {name, trigger_name: triggerName, graph} = resource
  if (typeof name !== 'string' || name.length === 0) return 'A workflow name is required.'
  if (typeof triggerName !== 'string' || triggerName.length === 0) {
    return 'A trigger name is required.'
  }
  if (!isRecord(graph)) return 'A graph object is required.'
  const request: WorkflowImportRequest = {
    name,
    trigger_name: triggerName,
    graph: graph as unknown as WorkflowImportRequest['graph'],
  }
  if (typeof resource.enabled === 'boolean') request.enabled = resource.enabled
  if (Array.isArray(resource.once_for_template)) {
    request.once_for_template = resource.once_for_template.filter(
      (item): item is string => typeof item === 'string'
    )
  }
  return request
}
