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

package com.moneat.connectors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectorExternalAccountRequest(
    val projectId: String = "",
    val customerId: String? = null,
    val managerCustomerId: String? = null,
)

@Serializable
data class CreateConnectorInstallationRequest(
    val providerId: String,
    val authProfileId: String,
    val name: String,
    val externalAccount: ConnectorExternalAccountRequest,
    val secret: String,
)

@Serializable
data class RotateConnectorApiCredentialRequest(
    val secret: String,
)

@Serializable
data class ConnectorInstallationResponse(
    val id: String,
    val providerId: String,
    val name: String,
    val credentialType: String,
    val authProfileId: String,
    val externalProjectId: String?,
    val externalProjectName: String?,
    val status: String,
    val statusReason: String?,
    val enabled: Boolean,
    val apiSecretLastFour: String?,
    val webhookTokenPrefix: String?,
    val webhookToken: String? = null,
    val lastTestedAt: String?,
    val lastTestResult: String?,
    val lastSuccessfulProviderCallAt: String?,
    val lastError: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ConnectorExternalResourceResponse(
    val id: String,
    val installationId: String,
    val externalProjectId: String?,
    val externalResourceType: String,
    val externalResourceId: String,
    val displayName: String?,
    val providerMetadata: Map<String, String> = emptyMap(),
    val lastSeenAt: String,
)

@Serializable
data class UpsertConnectorBindingRequest(
    val bindings: List<ConnectorBindingInput>,
)

@Serializable
data class ConnectorBindingInput(
    val externalResourceType: String,
    val externalResourceId: String,
    val localResourceType: String,
    val localResourceId: String,
)

@Serializable
data class ConnectorBindingResponse(
    val id: String,
    val installationId: String,
    val externalProjectId: String?,
    val externalResourceType: String,
    val externalResourceId: String,
    val localResourceType: String,
    val localResourceId: String,
    val status: String,
    val bindingVersion: Int,
    val effectiveFrom: String,
    val effectiveTo: String?,
)

@Serializable
data class ConnectorWebhookSetupResponse(
    val installationId: String,
    val providerId: String,
    val webhookUrl: String,
    val authorizationHeaderName: String,
    val authorizationHeaderValue: String?,
    val authorizationHeaderPrefix: String?,
    val recommendedEventTypes: List<String>,
    val recommendedEnvironment: String,
    val setupMode: String = "manual",
    val observedIntegrations: List<ConnectorObservedWebhookIntegration> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ConnectorObservedWebhookIntegration(
    val id: String,
    val name: String?,
    val enabled: Boolean?,
    val appIds: List<String> = emptyList(),
    val environments: List<String> = emptyList(),
    val eventTypes: List<String> = emptyList(),
    val matchesExpectedUrl: Boolean = false,
)

@Serializable
data class ConnectorInstallationsResponse(
    val installations: List<ConnectorInstallationResponse>,
)

@Serializable
data class ConnectorExternalResourcesResponse(
    val resources: List<ConnectorExternalResourceResponse>,
)

@Serializable
data class ConnectorBindingsResponse(
    val bindings: List<ConnectorBindingResponse>,
)

@Serializable
data class ConnectorWebhookAcceptedResponse(
    val accepted: Boolean,
    val duplicate: Boolean = false,
)

@Serializable
data class ConnectorProviderStateDetail(
    val installationId: String,
    val status: String,
    val health: String,
    val message: String?,
    val mappedResources: Int,
    val unmappedEvents: Int,
    val failedReceipts: Int,
    val sandboxEvents: Long,
    val productionEvents: Long,
    val lastAcceptedWebhookAt: String?,
    val lastAppliedAt: String?,
    val processingLagSeconds: Long?,
)

@Serializable
data class RevenueCatWebhookEventEnvelope(
    val event: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("api_version") val apiVersion: String? = null,
)
