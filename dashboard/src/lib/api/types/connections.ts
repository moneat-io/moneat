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

// Workflow connection vault (Enterprise: workflows_advanced). Secrets are write-only:
// they are entered once on create/rotate and never returned by the API.

export interface WorkflowConnection {
  id: number
  type: string
  name: string
  identifier_tags: Record<string, string>
  last_four?: string | null
  created_at: string
  updated_at: string
}

export interface CreateWorkflowConnectionRequest {
  type: string
  name: string
  identifier_tags?: Record<string, string>
  secret: string
}

export interface RotateWorkflowConnectionRequest {
  secret: string
}

export interface WorkflowConnectionGroup {
  id: number
  name: string
  connection_type: string
  member_connection_ids: number[]
  selection_strategy: string
  created_at: string
  updated_at: string
}

export interface CreateWorkflowConnectionGroupRequest {
  name: string
  connection_type: string
  member_connection_ids?: number[]
  selection_strategy?: string
}
