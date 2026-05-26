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

export interface WorkflowConditionConfig {
  reference: string
  operation: string
  value?: string | null
}

export interface WorkflowStepConfig {
  name: string
  params: Record<string, string>
}

export interface WorkflowRequest {
  name: string
  trigger_name: string
  enabled: boolean
  conditions: WorkflowConditionConfig[]
  steps: WorkflowStepConfig[]
  once_for_template: string[]
}

export type WorkflowUpdateRequest = Partial<Omit<WorkflowRequest, 'trigger_name'>>

export interface WorkflowResponse {
  id: number
  name: string
  trigger_name: string
  enabled: boolean
  version: number
  system_key?: string | null
  conditions: WorkflowConditionConfig[]
  steps: WorkflowStepConfig[]
  once_for_template: string[]
  created_at: string
  updated_at: string
  last_run_at?: string | null
  run_count: number
}

export interface WorkflowRunStepProgress {
  step: string
  status: string
  completed_at?: string | null
  error_message?: string | null
}

export interface WorkflowRunResponse {
  id: number
  workflow_id: number
  workflow_version_id: number
  trigger_name: string
  once_for: string
  status: string
  progress: WorkflowRunStepProgress[]
  error_message?: string | null
  created_at: string
  completed_at?: string | null
  failed_at?: string | null
}

export interface WorkflowFieldConfig {
  type: string
  placeholder?: string | null
  multiline: boolean
}

export interface WorkflowOperationDefinition {
  name: string
  label: string
  value_type?: string | null
}

export interface WorkflowResourceDefinition {
  type: string
  label: string
  field_config: WorkflowFieldConfig
  operations: WorkflowOperationDefinition[]
}

export interface WorkflowScopeReferenceDefinition {
  name: string
  label: string
  type: string
  description?: string | null
}

export interface WorkflowTriggerDefinition {
  name: string
  label: string
  description: string
  scope: WorkflowScopeReferenceDefinition[]
  default_once_for_template: string[]
}

export interface WorkflowStepParamDefinition {
  name: string
  label: string
  type: string
  description?: string | null
  required: boolean
}

export interface WorkflowStepDefinition {
  name: string
  label: string
  description: string
  params: WorkflowStepParamDefinition[]
}

export interface WorkflowCatalogResponse {
  resources: WorkflowResourceDefinition[]
  triggers: WorkflowTriggerDefinition[]
  steps: WorkflowStepDefinition[]
}
