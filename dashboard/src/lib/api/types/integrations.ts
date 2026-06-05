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

export interface OrganizationIntegration {
  id: number
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
  id: number
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
  id: number
  alertSource: string
  alertType?: string | null
  alertPriority: string
}

export interface UpsertRoutingRuleRequest {
  alertSource: string
  alertType?: string | null
  alertPriority: string
}

export interface IncidentEventLogEntry {
  id: number
  alertSource: string
  deduplicationKey: string
  alertPriority: string
  incidentStatus: string
  title: string
  description?: string | null
  providerIncidentId?: string | null
  success: boolean
  errorMessage?: string | null
  createdAt: number
}
