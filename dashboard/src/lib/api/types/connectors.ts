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
export type ConnectorHealth = 'healthy' | 'degraded' | 'error' | 'unknown'

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
}

export interface ConnectorProviderState {
  providerId: string
  uses: ConnectorUseState[]
}

export interface ConnectorStateResponse {
  connections: ConnectorConnectionState[]
  providers?: ConnectorProviderState[]
}
