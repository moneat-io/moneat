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

package com.moneat.connectors.routes

import com.moneat.auth.CurrentOrgContext
import com.moneat.auth.currentOrgIdOrNull
import com.moneat.auth.requireCurrentOrg
import com.moneat.connectors.ConnectorAvailability
import com.moneat.connectors.ConnectorCatalog
import com.moneat.connectors.ConnectorConnectionState
import com.moneat.connectors.ConnectorConnectionSummary
import com.moneat.connectors.ConnectorProviderState
import com.moneat.connectors.ConnectorProviderStateDetail
import com.moneat.connectors.ConnectorProvidersResponse
import com.moneat.connectors.ConnectorService
import com.moneat.connectors.ConnectorServiceException
import com.moneat.connectors.ConnectorStateResponse
import com.moneat.connectors.ConnectorSyncRequest
import com.moneat.connectors.ConnectorUseDefinition
import com.moneat.connectors.ConnectorUseState
import com.moneat.connectors.ConnectorWebhookAcceptedResponse
import com.moneat.connectors.CreateConnectorInstallationRequest
import com.moneat.connectors.RotateConnectorApiCredentialRequest
import com.moneat.connectors.UpsertConnectorBindingRequest
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val MAX_CONNECTOR_WEBHOOK_BODY_BYTES = 512 * 1024L

fun Route.connectorRoutes(connectorService: ConnectorService = ConnectorService()) {
    route("/v1/connectors") {
        authenticate("auth-jwt") {
            get("/providers") {
                call.respond(HttpStatusCode.OK, ConnectorProvidersResponse(ConnectorCatalog.providers))
            }

            get("/state") {
                call.respondConnectorState(connectorService)
            }

            connectorInstallationRoutes(connectorService)
        }
    }
}

private suspend fun ApplicationCall.respondConnectorState(connectorService: ConnectorService) {
    val organizationId =
        principal<JWTPrincipal>()?.currentOrgIdOrNull()
            ?: return respond(HttpStatusCode.NotFound, ErrorResponse("No organization found"))
    val legacyIntegrations = legacyIntegrationStates(organizationId)
    val connectorStates = connectorService.connectorInstallationStates(organizationId)
    val response =
        ConnectorStateResponse(
            connections = ConnectorCatalog.providers.map { provider ->
                val connectorSummary = connectorStates[provider.id]?.first
                provider.providerConnectionSummary(
                    integration = legacyIntegrations[provider.id],
                    connectorSummary = connectorSummary,
                )
            },
            providers = ConnectorCatalog.providers.map { provider ->
                val detail = connectorStates[provider.id]?.second
                ConnectorProviderState(
                    providerId = provider.id,
                    uses = provider.uses.map { use ->
                        use.connectorUseState(legacyIntegrations[provider.id], detail)
                    }
                )
            }
        )
    respond(HttpStatusCode.OK, response)
}

private fun Route.connectorInstallationRoutes(connectorService: ConnectorService) {
    route("/installations") {
        get {
            val context = call.requireCurrentOrg() ?: return@get
            val providerId = call.request.queryParameters["providerId"]
            call.respondConnector {
                connectorService.listInstallations(context.orgId, providerId)
            }
        }

        post {
            val context = call.requireConnectorAdmin() ?: return@post
            val request = call.receive<CreateConnectorInstallationRequest>()
            call.respondConnector(HttpStatusCode.Created) {
                connectorService.createInstallation(context.orgId, context.userId, request)
            }
        }

        connectorInstallationDetailRoutes(connectorService)
    }
}

private fun Route.connectorInstallationDetailRoutes(connectorService: ConnectorService) {
    route("/{installationId}") {
        get {
            val context = call.requireCurrentOrg() ?: return@get
            val installationId = call.requireInstallationId() ?: return@get
            call.respondConnector {
                connectorService.getInstallation(context.orgId, installationId)
            }
        }

        delete {
            val context = call.requireConnectorAdmin() ?: return@delete
            val installationId = call.requireInstallationId() ?: return@delete
            call.respondConnector {
                connectorService.deleteInstallation(context.orgId, installationId)
                mapOf("deleted" to true)
            }
        }

        connectorInstallationActionRoutes(connectorService)
        connectorInstallationResourceRoutes(connectorService)
    }
}

private fun Route.connectorInstallationActionRoutes(connectorService: ConnectorService) {
    post("/test") {
        val context = call.requireConnectorAdmin() ?: return@post
        val installationId = call.requireInstallationId() ?: return@post
        call.respondConnector {
            connectorService.testInstallation(context.orgId, installationId)
        }
    }

    post("/credentials/rotate") {
        val context = call.requireConnectorAdmin() ?: return@post
        val installationId = call.requireInstallationId() ?: return@post
        val request = call.receive<RotateConnectorApiCredentialRequest>()
        call.respondConnector {
            connectorService.rotateApiCredential(context.orgId, installationId, request)
        }
    }

    post("/webhook-token/rotate") {
        val context = call.requireConnectorAdmin() ?: return@post
        val installationId = call.requireInstallationId() ?: return@post
        call.respondConnector {
            connectorService.rotateWebhookToken(context.orgId, installationId)
        }
    }

    get("/webhook-setup") {
        val context = call.requireConnectorAdmin() ?: return@get
        val installationId = call.requireInstallationId() ?: return@get
        call.respondConnector {
            connectorService.webhookSetup(context.orgId, installationId)
        }
    }

    post("/sync") {
        val context = call.requireConnectorAdmin() ?: return@post
        val installationId = call.requireInstallationId() ?: return@post
        val request = call.receive<ConnectorSyncRequest>()
        call.respondConnector(HttpStatusCode.Accepted) {
            connectorService.enqueueSync(context.orgId, context.userId, installationId, request)
        }
    }
}

private fun Route.connectorInstallationResourceRoutes(connectorService: ConnectorService) {
    get("/resources") {
        val context = call.requireCurrentOrg() ?: return@get
        val installationId = call.requireInstallationId() ?: return@get
        call.respondConnector {
            connectorService.listExternalResources(context.orgId, installationId)
        }
    }

    post("/resources/refresh") {
        val context = call.requireConnectorAdmin() ?: return@post
        val installationId = call.requireInstallationId() ?: return@post
        call.respondConnector {
            connectorService.refreshExternalResources(context.orgId, installationId)
        }
    }

    get("/bindings") {
        val context = call.requireCurrentOrg() ?: return@get
        val installationId = call.requireInstallationId() ?: return@get
        call.respondConnector {
            connectorService.listBindings(context.orgId, installationId)
        }
    }

    get("/sync-runs") {
        val context = call.requireCurrentOrg() ?: return@get
        val installationId = call.requireInstallationId() ?: return@get
        call.respondConnector {
            connectorService.listImportRuns(context.orgId, installationId)
        }
    }

    put("/bindings") {
        val context = call.requireConnectorAdmin() ?: return@put
        val installationId = call.requireInstallationId() ?: return@put
        val request = call.receive<UpsertConnectorBindingRequest>()
        call.respondConnector {
            connectorService.upsertBindings(context.orgId, context.userId, installationId, request)
        }
    }
}

fun Route.connectorWebhookRoutes(connectorService: ConnectorService = ConnectorService()) {
    post("/api/connectors/revenuecat/{installationId}/webhook") {
        val installationId =
            call.parameters["installationId"]
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Connector installation not found"))
        val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_CONNECTOR_WEBHOOK_BODY_BYTES) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Webhook body is too large"))
        }
        val body = call.receive<ByteArray>()
        if (body.size > MAX_CONNECTOR_WEBHOOK_BODY_BYTES) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Webhook body is too large"))
        }
        call.respondConnector(HttpStatusCode.Accepted) {
            val accepted = connectorService.acceptRevenueCatWebhook(
                installationResourceId = installationId,
                authorizationHeader = call.request.header(HttpHeaders.Authorization),
                body = body,
                requestHeaders = safeRequestHeaders(call),
            )
            ConnectorWebhookAcceptedResponse(accepted = accepted.accepted, duplicate = accepted.duplicate)
        }
    }
}

private fun legacyIntegrationStates(organizationId: Int): Map<String, OrganizationIntegrationConnectorState> =
    transaction {
        OrganizationIntegrations
            .selectAll()
            .where { OrganizationIntegrations.organization_id eq organizationId }
            .associate { row ->
                row[OrganizationIntegrations.integration_type] to
                    OrganizationIntegrationConnectorState(
                        id = row[OrganizationIntegrations.resource_id].toString(),
                        enabled = row[OrganizationIntegrations.enabled],
                        configured = row[OrganizationIntegrations.access_token] != null,
                    )
            }
    }

private data class OrganizationIntegrationConnectorState(
    val id: String,
    val enabled: Boolean,
    val configured: Boolean,
)

private fun com.moneat.connectors.ConnectorProviderDefinition.providerConnectionSummary(
    integration: OrganizationIntegrationConnectorState?,
    connectorSummary: ConnectorConnectionSummary?,
): ConnectorConnectionSummary {
    if (connectorSummary != null) return connectorSummary
    val availableUses = uses.filter { it.availability == ConnectorAvailability.AVAILABLE }
    val connected = availableUses.isNotEmpty() && integration?.configured == true
    val enabled = integration?.enabled == true
    val health = when {
        connected && enabled -> "healthy"
        connected -> "degraded"
        else -> "unknown"
    }
    val detail = when {
        connected && enabled -> "Connected"
        connected -> "Connected but disabled"
        uses.any { it.availability == ConnectorAvailability.PLANNED } -> "Coming soon"
        uses.any { it.availability == ConnectorAvailability.ENTERPRISE } -> "Enterprise feature"
        else -> "Not connected"
    }
    return ConnectorConnectionSummary(
        providerId = id,
        connected = connected,
        health = health,
        detail = detail,
    )
}

private fun ConnectorUseDefinition.connectorUseState(
    integration: OrganizationIntegrationConnectorState?,
    connectorDetail: ConnectorProviderStateDetail?,
): ConnectorUseState =
    when (availability) {
        ConnectorAvailability.PLANNED ->
            ConnectorUseState(
                useId = id,
                availability = availability,
                stateSource = stateSource,
                state = ConnectorConnectionState.PLANNED,
                connected = false,
                enabled = false,
                message = "Coming soon",
            )

        ConnectorAvailability.ENTERPRISE ->
            ConnectorUseState(
                useId = id,
                availability = availability,
                stateSource = stateSource,
                state = ConnectorConnectionState.ENTERPRISE,
                connected = false,
                enabled = false,
                message = "Enterprise feature",
            )

        ConnectorAvailability.AVAILABLE -> {
            val connectorConnected = connectorDetail != null
            val connected = connectorConnected || integration?.configured == true
            ConnectorUseState(
                useId = id,
                availability = availability,
                stateSource = stateSource,
                state = if (connected) ConnectorConnectionState.CONNECTED else ConnectorConnectionState.NOT_CONNECTED,
                connected = connected,
                enabled = connectorConnected || integration?.enabled == true,
                integrationId = connectorDetail?.installationId ?: integration?.id,
                message = connectorDetail?.message ?: if (connected) null else "Not connected",
                detail = connectorDetail,
            )
        }
    }

private suspend fun ApplicationCall.requireConnectorAdmin(): CurrentOrgContext? {
    val context = requireCurrentOrg() ?: return null
    val role = context.orgRole?.lowercase()
    if (role !in setOf("owner", "admin")) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Only organization owners and admins can manage connectors"))
        return null
    }
    return context
}

private suspend fun ApplicationCall.requireInstallationId(): String? {
    val installationId = parameters["installationId"]
    if (installationId.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("installationId is required"))
        return null
    }
    return installationId
}

private suspend fun ApplicationCall.respondConnector(
    status: HttpStatusCode = HttpStatusCode.OK,
    block: suspend () -> Any,
) {
    try {
        respond(status, block())
    } catch (error: ConnectorServiceException) {
        respond(error.status, ErrorResponse(error.message))
    }
}

private fun safeRequestHeaders(call: ApplicationCall): Map<String, String> =
    call.request.headers.names()
        .filterNot { name -> name.equals(HttpHeaders.Authorization, ignoreCase = true) }
        .filterNot { name -> name.equals(HttpHeaders.Cookie, ignoreCase = true) }
        .associateWith { name -> call.request.header(name).orEmpty().take(HEADER_VALUE_MAX_CHARS) }

private const val HEADER_VALUE_MAX_CHARS = 512
