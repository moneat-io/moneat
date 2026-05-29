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

export type WorkflowJsonValue =
  | string
  | number
  | boolean
  | null
  | WorkflowJsonValue[]
  | {[key: string]: WorkflowJsonValue}

export interface WorkflowRetryConfig {
  max_attempts: number
  initial_interval: string
  backoff_coefficient: number
  maximum_interval?: string | null
  non_retryable_error_types: string[]
}

export interface WorkflowSwitchCaseConfig {
  name: string
  label?: string | null
  conditions: WorkflowConditionConfig[]
}

export interface WorkflowGraphNode {
  id: string
  type: 'trigger' | 'condition' | 'action' | 'control'
  trigger?: string | null
  kind?: string | null
  action?: string | null
  params?: Record<string, WorkflowJsonValue>
  conditions?: WorkflowConditionConfig[]
  cases?: WorkflowSwitchCaseConfig[]
  retry?: WorkflowRetryConfig | null
  continue_on_error?: boolean
}

export interface WorkflowGraphEdge {
  from: string
  to: string
  branch?: string | null
  on?: string | null
}

export interface WorkflowGraphConfig {
  nodes: WorkflowGraphNode[]
  edges: WorkflowGraphEdge[]
}

export interface WorkflowPreviewRequest {
  trigger_name: string
  steps?: WorkflowStepConfig[]
  scope?: Record<string, WorkflowJsonValue>
  graph?: WorkflowGraphConfig | null
  node_id?: string | null
}

export interface WorkflowPreviewField {
  label: string
  value: string
}

export interface WorkflowStepPreview {
  step: string
  channel: string
  title: string
  subject?: string | null
  body: string
  html_body?: string | null
  text_body: string
  fields: WorkflowPreviewField[]
  color: string
  cta_label?: string | null
  cta_url?: string | null
  footer?: string | null
  fallback_text: string
}

export interface WorkflowPreviewResponse {
  scope: Record<string, string>
  previews: WorkflowStepPreview[]
}

export interface WorkflowTestMessageResult {
  step: string
  channel: string
  status: string
  error_message?: string | null
}

export interface WorkflowTestMessageResponse {
  scope: Record<string, string>
  results: WorkflowTestMessageResult[]
}

export interface WorkflowRequest {
  name: string
  trigger_name: string
  enabled: boolean
  conditions: WorkflowConditionConfig[]
  steps: WorkflowStepConfig[]
  graph?: WorkflowGraphConfig | null
  once_for_template: string[]
}

export type WorkflowUpdateRequest = Partial<Omit<WorkflowRequest, 'trigger_name'>>

export interface WorkflowResponse {
  id: number
  name: string
  trigger_name: string
  enabled: boolean
  version: number
  published: boolean
  system_key?: string | null
  conditions: WorkflowConditionConfig[]
  steps: WorkflowStepConfig[]
  graph: WorkflowGraphConfig
  once_for_template: string[]
  created_at: string
  updated_at: string
  last_run_at?: string | null
  run_count: number
}

export interface WorkflowRunStepProgress {
  step: string
  status: string
  node_id?: string | null
  type?: string | null
  completed_at?: string | null
  output: Record<string, WorkflowJsonValue>
  error_message?: string | null
}

export interface WorkflowRunStepResponse {
  id: number
  run_id: number
  node_id: string
  type: string
  status: string
  started_at?: string | null
  completed_at?: string | null
  input: Record<string, WorkflowJsonValue>
  output: Record<string, WorkflowJsonValue>
  error_message?: string | null
  attempt: number
}

export interface WorkflowRunResponse {
  id: number
  workflow_id: number
  workflow_version_id: number
  trigger_name: string
  once_for: string
  status: string
  progress: WorkflowRunStepProgress[]
  steps: WorkflowRunStepResponse[]
  error_message?: string | null
  temporal_workflow_id?: string | null
  temporal_run_id?: string | null
  created_at: string
  completed_at?: string | null
  failed_at?: string | null
}

export interface WorkflowRunInstanceRequest {
  scope: Record<string, WorkflowJsonValue>
}

export interface WorkflowRunCancelResponse {
  id: number
  status: string
}

export interface WorkflowWebhookSigningResponse {
  workflow_id: number
  webhook_url: string
  signing_secret: string
  signature_header: string
  signature_format: string
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

export interface WorkflowNodeTypeDefinition {
  type: string
  kind?: string | null
  name: string
  label: string
  description: string
  params: WorkflowStepParamDefinition[]
  branch_labels: string[]
}

export interface WorkflowCatalogResponse {
  resources: WorkflowResourceDefinition[]
  triggers: WorkflowTriggerDefinition[]
  steps: WorkflowStepDefinition[]
  node_types: WorkflowNodeTypeDefinition[]
}
