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

import type { ApiClientCore } from '../client'
import { urlWithQuery } from '../utils'
import type {
  ConnectorBindingsResponse,
  ConnectorExternalResourcesResponse,
  ConnectorInstallationResponse,
  ConnectorInstallationsResponse,
  ConnectorProvidersResponse,
  ConnectorStateResponse,
  ConnectorWebhookSetupResponse,
  CreateConnectorInstallationRequest,
  RotateConnectorApiCredentialRequest,
  UpsertConnectorBindingRequest,
} from '../types'

export function connectorsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    // Shared connector catalog (provider + per-use availability metadata).
    getConnectorProviders: () =>
      core.request<ConnectorProvidersResponse>(`${base}/connectors/providers`),

    // Optional live connection state used to enrich already-configured
    // connectors with health/last-checked. Availability gating still governs
    // which providers can be set up.
    getConnectorState: () =>
      core.request<ConnectorStateResponse>(`${base}/connectors/state`),

    getConnectorInstallations: (providerId?: string | null) => {
      const query = providerId ? new URLSearchParams({ providerId }).toString() : ''
      return core.request<ConnectorInstallationsResponse>(
        urlWithQuery(`${base}/connectors/installations`, query)
      )
    },

    createConnectorInstallation: (request: CreateConnectorInstallationRequest) =>
      core.request<ConnectorInstallationResponse>(`${base}/connectors/installations`, {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    getConnectorInstallation: (installationId: string) =>
      core.request<ConnectorInstallationResponse>(
        `${base}/connectors/installations/${installationId}`
      ),

    deleteConnectorInstallation: (installationId: string) =>
      core.request<{ deleted: boolean }>(
        `${base}/connectors/installations/${installationId}`,
        { method: 'DELETE' }
      ),

    testConnectorInstallation: (installationId: string) =>
      core.request<ConnectorInstallationResponse>(
        `${base}/connectors/installations/${installationId}/test`,
        { method: 'POST' }
      ),

    rotateConnectorApiCredential: (
      installationId: string,
      request: RotateConnectorApiCredentialRequest
    ) =>
      core.request<ConnectorInstallationResponse>(
        `${base}/connectors/installations/${installationId}/credentials/rotate`,
        {
          method: 'POST',
          body: JSON.stringify(request),
        }
      ),

    rotateConnectorWebhookToken: (installationId: string) =>
      core.request<ConnectorInstallationResponse>(
        `${base}/connectors/installations/${installationId}/webhook-token/rotate`,
        { method: 'POST' }
      ),

    getConnectorExternalResources: (installationId: string) =>
      core.request<ConnectorExternalResourcesResponse>(
        `${base}/connectors/installations/${installationId}/resources`
      ),

    refreshConnectorExternalResources: (installationId: string) =>
      core.request<ConnectorExternalResourcesResponse>(
        `${base}/connectors/installations/${installationId}/resources/refresh`,
        { method: 'POST' }
      ),

    getConnectorBindings: (installationId: string) =>
      core.request<ConnectorBindingsResponse>(
        `${base}/connectors/installations/${installationId}/bindings`
      ),

    updateConnectorBindings: (
      installationId: string,
      request: UpsertConnectorBindingRequest
    ) =>
      core.request<ConnectorBindingsResponse>(
        `${base}/connectors/installations/${installationId}/bindings`,
        {
          method: 'PUT',
          body: JSON.stringify(request),
        }
      ),

    getConnectorWebhookSetup: (installationId: string) =>
      core.request<ConnectorWebhookSetupResponse>(
        `${base}/connectors/installations/${installationId}/webhook-setup`
      ),
  }
}
