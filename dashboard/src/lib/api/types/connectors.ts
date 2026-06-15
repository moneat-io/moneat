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

// Mirrors com.moneat.connectors.ConnectorCatalog (GET /v1/connectors/providers).
// The catalog describes which external systems Moneat can connect to and what
// each connection is used for; live connection state comes from other endpoints
// (e.g. /v1/integrations).

export type ConnectorFamily =
  | 'notification'
  | 'workflow_egress'
  | 'data_import'
  | 'dashboard_query'
  | 'webhook_ingress'

export type ConnectorAvailability = 'available' | 'planned' | 'enterprise'

export type ConnectorSetupMode = 'oauth' | 'api_key' | 'app_installation' | 'webhook' | 'built_in'

export type ConnectorDirection = 'read' | 'write' | 'read_write' | 'ingress' | 'egress'

export type ConnectorUiGroup =
  | 'import_data'
  | 'send_actions'
  | 'receive_webhooks'
  | 'send_notifications'
  | 'query_data'

export interface ConnectorUseDefinition {
  id: string
  name: string
  family: ConnectorFamily
  availability: ConnectorAvailability
  setupMode: ConnectorSetupMode
  capabilities: string[]
  stateSource: string
  secretPurpose: string | null
  setupRoute?: string | null
  statusRoute?: string | null
  description: string
  direction: ConnectorDirection
  allowedAuthProfileIds: string[]
  requiredScopes?: string[]
  resourceTypes?: string[]
  uiGroup: ConnectorUiGroup
}

export interface ConnectorAuthProfileDefinition {
  id: string
  name: string
  authKind: string
  subjectKinds: string[]
  tokenLifecycle: string
  defaultScopes: string[]
  separateReconsentRequired: boolean
  secretPurpose: string | null
}

export interface ConnectorProviderDefinition {
  id: string
  name: string
  description: string
  authProfiles: ConnectorAuthProfileDefinition[]
  uses: ConnectorUseDefinition[]
}

export interface ConnectorProvidersResponse {
  providers: ConnectorProviderDefinition[]
}

// Optional live connection state (GET /v1/connectors/state). Used to enrich the
// catalog with health/last-checked for connectors that are already set up.
export type ConnectorHealth =
  | 'healthy'
  | 'degraded'
  | 'error'
  | 'unknown'
  | 'needs_mapping'
  | 'awaiting_traffic'

export interface ConnectorConnectionState {
  providerId: string
  connected: boolean
  health?: ConnectorHealth | null
  detail?: string | null
  lastCheckedAt?: string | null
}

export type ConnectorUseStateValue = 'connected' | 'not_connected' | 'planned' | 'enterprise'

export interface ConnectorUseState {
  useId: string
  availability: ConnectorAvailability
  stateSource: string
  state: ConnectorUseStateValue
  connected: boolean
  enabled: boolean
  integrationId?: string | null
  message?: string | null
  detail?: ConnectorProviderStateDetail | null
}

export interface ConnectorProviderState {
  providerId: string
  uses: ConnectorUseState[]
}

export interface ConnectorStateResponse {
  connections: ConnectorConnectionState[]
  providers?: ConnectorProviderState[]
}

export interface ConnectorExternalAccountRequest {
  projectId: string
}

export interface CreateConnectorInstallationRequest {
  providerId: string
  authProfileId: string
  name: string
  externalAccount: ConnectorExternalAccountRequest
  secret: string
}

export interface RotateConnectorApiCredentialRequest {
  secret: string
}

export interface ConnectorInstallationResponse {
  id: string
  providerId: string
  name: string
  credentialType: string
  authProfileId: string
  externalProjectId?: string | null
  externalProjectName?: string | null
  status: string
  statusReason?: string | null
  enabled: boolean
  apiSecretLastFour?: string | null
  webhookTokenPrefix?: string | null
  webhookToken?: string | null
  lastTestedAt?: string | null
  lastTestResult?: string | null
  lastSuccessfulProviderCallAt?: string | null
  lastError?: string | null
  createdAt: string
  updatedAt: string
}

export interface ConnectorInstallationsResponse {
  installations: ConnectorInstallationResponse[]
}

export interface ConnectorExternalResourceResponse {
  id: string
  installationId: string
  externalProjectId?: string | null
  externalResourceType: string
  externalResourceId: string
  displayName?: string | null
  providerMetadata?: Record<string, string>
  lastSeenAt: string
}

export interface ConnectorExternalResourcesResponse {
  resources: ConnectorExternalResourceResponse[]
}

export interface ConnectorBindingInput {
  externalResourceType: string
  externalResourceId: string
  localResourceType: string
  localResourceId: string
}

export interface UpsertConnectorBindingRequest {
  bindings: ConnectorBindingInput[]
}

export interface ConnectorBindingResponse {
  id: string
  installationId: string
  externalProjectId?: string | null
  externalResourceType: string
  externalResourceId: string
  localResourceType: string
  localResourceId: string
  status: string
  bindingVersion: number
  effectiveFrom: string
  effectiveTo?: string | null
}

export interface ConnectorBindingsResponse {
  bindings: ConnectorBindingResponse[]
}

export interface ConnectorObservedWebhookIntegration {
  id: string
  name?: string | null
  enabled?: boolean | null
  appIds?: string[]
  environments?: string[]
  eventTypes?: string[]
  matchesExpectedUrl?: boolean
}

export interface ConnectorWebhookSetupResponse {
  installationId: string
  providerId: string
  webhookUrl: string
  authorizationHeaderName: string
  authorizationHeaderValue?: string | null
  authorizationHeaderPrefix?: string | null
  recommendedEventTypes: string[]
  recommendedEnvironment: string
  setupMode: string
  observedIntegrations?: ConnectorObservedWebhookIntegration[]
  warnings?: string[]
}

export interface ConnectorProviderStateDetail {
  installationId: string
  status: string
  health: string
  message?: string | null
  mappedResources: number
  unmappedEvents: number
  failedReceipts: number
  sandboxEvents: number
  productionEvents: number
  lastAcceptedWebhookAt?: string | null
  lastAppliedAt?: string | null
  processingLagSeconds?: number | null
}
