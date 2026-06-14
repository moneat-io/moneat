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

import com.moneat.auth.currentOrgIdOrNull
import com.moneat.connectors.ConnectorAvailability
import com.moneat.connectors.ConnectorCatalog
import com.moneat.connectors.ConnectorConnectionSummary
import com.moneat.connectors.ConnectorConnectionState
import com.moneat.connectors.ConnectorProviderState
import com.moneat.connectors.ConnectorProvidersResponse
import com.moneat.connectors.ConnectorStateResponse
import com.moneat.connectors.ConnectorUseDefinition
import com.moneat.connectors.ConnectorUseState
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

fun Route.connectorRoutes() {
    route("/v1/connectors") {
        authenticate("auth-jwt") {
            get("/providers") {
                call.respond(HttpStatusCode.OK, ConnectorProvidersResponse(ConnectorCatalog.providers))
            }

            get("/state") {
                val organizationId =
                    call.principal<JWTPrincipal>()?.currentOrgIdOrNull()
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No organization found"))
                val integrations =
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
                val response =
                    ConnectorStateResponse(
                        connections = ConnectorCatalog.providers.map { provider ->
                            provider.providerConnectionSummary(integrations[provider.id])
                        },
                        providers = ConnectorCatalog.providers.map { provider ->
                            ConnectorProviderState(
                                providerId = provider.id,
                                uses = provider.uses.map { use -> use.connectorUseState(integrations[provider.id]) }
                            )
                        }
                    )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}

private data class OrganizationIntegrationConnectorState(
    val id: String,
    val enabled: Boolean,
    val configured: Boolean,
)

private fun com.moneat.connectors.ConnectorProviderDefinition.providerConnectionSummary(
    integration: OrganizationIntegrationConnectorState?,
): ConnectorConnectionSummary {
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
            val connected = integration?.configured == true
            ConnectorUseState(
                useId = id,
                availability = availability,
                stateSource = stateSource,
                state = if (connected) ConnectorConnectionState.CONNECTED else ConnectorConnectionState.NOT_CONNECTED,
                connected = connected,
                enabled = integration?.enabled == true,
                integrationId = integration?.id,
                message = if (connected) null else "Not connected",
            )
        }
    }
