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

import type {AlertPriority} from './dashboards'

type RoutingAlertPriority = Exclude<AlertPriority, null>

export interface OrganizationIntegration {
  id: string
  integrationType: string
  teamName: string | null
  channelId: string | null
  channelName: string | null
  enabled: boolean
  isConfigured: boolean
}

export interface SlackOAuthStartResponse {
  authUrl: string
}

export interface SlackChannel {
  id: string
  name: string
}

export interface SlackChannelList {
  channels: SlackChannel[]
}

export interface SlackChannelSelection {
  channelId: string
  channelName: string
}

// ── Slack installations ──────────────────────────────────────────────────────
// Mirrors com.moneat.notifications.services.SlackInstallation* (GET/POST/PUT/DELETE
// under /v1/integrations/slack). Each installation tracks its own granted scopes,
// health, default channel, and enabled state, and takes one of two shapes:
//   • A workspace install has a team (`teamId`) and may optionally belong to a
//     Grid enterprise (`enterpriseId` set, `isEnterpriseInstall` false).
//   • An Enterprise Grid organization-wide install is authorized once for a Grid
//     enterprise with no team at OAuth time (`isEnterpriseInstall` true,
//     `teamId` null). A Slack admin attaches it to individual workspaces later.
// `workspaceBindings` enumerates the explicit workspace routing contexts. An
// Enterprise Grid install with zero bindings is healthy but cannot route messages.

export type SlackInstallationHealth =
  | 'HEALTHY'
  | 'MISSING_SCOPES'
  | 'TOKEN_REVOKED'
  | 'BOT_REMOVED'
  | 'WORKSPACE_MISMATCH'
  | 'REAUTHORIZATION_REQUIRED'
  | 'DEGRADED'
  | 'DISABLED'

// A capability bundles the least-privilege scopes needed for one product surface.
// `optional` marks capabilities the workspace can run without (the Slack Assistant);
// everything else is required for core alert and incident delivery.
export interface SlackCapabilityDefinition {
  id: string
  label: string
  description: string
  scopes: string[]
  botScopes: string[]
  userScopes: string[]
  optional: boolean
}

export interface SlackWorkspaceBindingSummary {
  id: string
  teamId: string
  teamName: string | null
  enterpriseId: string | null
  isPrimary: boolean
  enabled: boolean
  health: SlackInstallationHealth
  healthDetail: string | null
  lastVerifiedAt: string | null
}

export type SlackGrantType = 'BOT' | 'USER'

export interface SlackGrantSummary {
  id: string
  grantType: SlackGrantType
  slackUserId: string | null
  tokenType: string | null
  grantedScopes: string[]
  expiresAt: string | null
  rotatedAt: string | null
  revokedAt: string | null
  health: SlackInstallationHealth
  healthDetail: string | null
  lastVerifiedAt: string | null
}

export interface SlackCapabilityHealth {
  capabilityId: string
  missingBotScopes: string[]
  missingUserScopes: string[]
  healthy: boolean
}

// Why a single scope is requested, and which capabilities pull it in.
export interface SlackScopeExplanation {
  scope: string
  reason: string
  capabilities: string[]
}

export interface SlackCapabilitiesResponse {
  capabilities: SlackCapabilityDefinition[]
  scopes: SlackScopeExplanation[]
}

export interface SlackInstallationSummary {
  id: string
  // Present for workspace installs; null for an org-wide Grid install.
  teamId: string | null
  teamName: string | null
  // Present for org-wide installs and for workspaces that belong to a Grid.
  enterpriseId: string | null
  enterpriseName: string | null
  // True only for an org-wide Grid install (enterprise context, no team).
  isEnterpriseInstall: boolean
  appId: string | null
  botUserId: string | null
  grantedScopes: string[]
  grantedUserScopes: string[]
  enabledCapabilities: string[]
  missingScopes: string[]
  workspaceBindings: SlackWorkspaceBindingSummary[]
  grants: SlackGrantSummary[]
  capabilityHealth: SlackCapabilityHealth[]
  defaultChannelId: string | null
  defaultChannelName: string | null
  isDefault: boolean
  enabled: boolean
  health: SlackInstallationHealth
  healthDetail: string | null
  lastVerifiedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface SlackUsergroup {
  id: string
  handle: string
  name: string
  description?: string
}

export interface UpdateSlackIntegrationRequest {
  webhookUrl: string
  channelName?: string
  enabled?: boolean
}

export interface TestIntegrationResponse {
  success: boolean
  message: string
}

export interface IncidentProviderConfig {
  id: string
  providerType: string
  name: string
  configJson: Record<string, string>
  enabled: boolean
  createdAt: number
  updatedAt: number
}

export interface CreateIncidentProviderRequest {
  providerType: string
  name: string
  apiKey: string
  configJson: Record<string, string>
}

export interface UpdateIncidentProviderRequest {
  name?: string
  apiKey?: string
  configJson?: Record<string, string>
  enabled?: boolean
}

export interface IncidentRoutingRule {
  id: string
  alertSource: string
  alertType?: string | null
  alertPriority: RoutingAlertPriority
}

export interface UpsertRoutingRuleRequest {
  alertSource: string
  alertType?: string | null
  alertPriority: RoutingAlertPriority
}

export interface IncidentEventLogEntry {
  id: string
  alertSource: string
  deduplicationKey: string
  alertPriority: RoutingAlertPriority
  incidentStatus: string
  title: string
  description?: string | null
  providerIncidentId?: string | null
  success: boolean
  errorMessage?: string | null
  createdAt: number
}
