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

package com.moneat.routes

import com.moneat.models.IncidentEventLog
import com.moneat.models.IncidentProviderConfigs
import com.moneat.models.IncidentRoutingRules
import com.moneat.models.Memberships
import com.moneat.models.ProviderConfig
import com.moneat.services.incident.IncidentProviderRegistry
import com.moneat.utils.BooleanResponse
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock

fun Route.incidentProviderRoutes() {
    val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    route("/api/incident-providers") {
        authenticate("auth-jwt") {
            // List provider configs for organization
            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@get call.respond(HttpStatusCode.Forbidden)

                val configs =
                    transaction {
                        IncidentProviderConfigs
                            .selectAll()
                            .where {
                                IncidentProviderConfigs.organizationId eq organizationId
                            }.map { row ->
                                ProviderConfigResponse(
                                    id = row[IncidentProviderConfigs.id].value,
                                    providerType = row[IncidentProviderConfigs.providerType],
                                    name = row[IncidentProviderConfigs.name],
                                    configJson =
                                    try {
                                        val jsonStr = row[IncidentProviderConfigs.configJson]
                                        json.parseToJsonElement(jsonStr).jsonObject.toMap().mapValues {
                                            it.value.toString().trim('"')
                                        }
                                    } catch (e: Exception) {
                                        emptyMap()
                                    },
                                    enabled = row[IncidentProviderConfigs.enabled],
                                    createdAt = row[IncidentProviderConfigs.createdAt].toEpochMilliseconds(),
                                    updatedAt = row[IncidentProviderConfigs.updatedAt].toEpochMilliseconds()
                                )
                            }
                    }

                call.respond(configs)
            }

            // Create provider config
            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@post call.respond(HttpStatusCode.Forbidden)

                val request = call.receive<CreateProviderConfigRequest>()

                val configId =
                    transaction {
                        // Custom SQL for JSONB insertion
                        val jsonString = request.configJson.toString()
                        val sql =
                            """
                        INSERT INTO incident_provider_configs 
                        (organization_id, provider_type, name, api_key, config_json, enabled, created_at, updated_at)
                        VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP))
                        RETURNING id
                            """.trimIndent()

                        val now = Clock.System.now()
                        val nowStr = now.toString()

                        var resultId: Int? = null
                        TransactionManager.current().exec(
                            sql,
                            listOf(
                                IntegerColumnType() to organizationId,
                                VarCharColumnType(50) to request.providerType,
                                VarCharColumnType(255) to request.name,
                                TextColumnType() to request.apiKey,
                                TextColumnType() to jsonString,
                                BooleanColumnType() to true,
                                TextColumnType() to nowStr,
                                TextColumnType() to nowStr
                            )
                        ) { rs ->
                            if (rs.next()) {
                                resultId = rs.getInt(1)
                            }
                        }

                        resultId ?: throw Exception("Failed to insert provider config")
                    }

                call.respond(HttpStatusCode.Created, mapOf("id" to configId))
            }

            // Update provider config
            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val configId =
                    call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest)

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@put call.respond(HttpStatusCode.Forbidden)

                val request = call.receive<UpdateProviderConfigRequest>()

                val updated =
                    transaction {
                        val exists =
                            IncidentProviderConfigs
                                .selectAll()
                                .where {
                                    (IncidentProviderConfigs.id eq configId) and
                                        (IncidentProviderConfigs.organizationId eq organizationId)
                                }.count() > 0

                        if (!exists) return@transaction false

                        // Build update SQL dynamically based on what's provided
                        val setClauses = mutableListOf<String>()
                        val params = mutableListOf<Pair<IColumnType<*>, Any?>>()

                        request.name?.let {
                            setClauses.add("name = ?")
                            params.add(VarCharColumnType(255) to it)
                        }
                        request.apiKey?.let {
                            setClauses.add("api_key = ?")
                            params.add(TextColumnType() to it)
                        }
                        request.configJson?.let {
                            setClauses.add("config_json = CAST(? AS JSONB)")
                            params.add(TextColumnType() to it.toString())
                        }
                        request.enabled?.let {
                            setClauses.add("enabled = ?")
                            params.add(BooleanColumnType() to it)
                        }

                        if (setClauses.isNotEmpty()) {
                            setClauses.add("updated_at = CAST(? AS TIMESTAMP)")
                            params.add(TextColumnType() to Clock.System.now().toString())
                            params.add(IntegerColumnType() to configId)

                            val updateSql =
                                """
                            UPDATE incident_provider_configs
                            SET ${setClauses.joinToString(", ")}
                            WHERE id = ?
                                """.trimIndent()

                            TransactionManager.current().exec(updateSql, params) {}
                        }

                        true
                    }

                if (updated) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // Delete provider config
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val configId =
                    call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@delete call.respond(HttpStatusCode.Forbidden)

                val deleted =
                    transaction {
                        IncidentProviderConfigs.deleteWhere {
                            (id eq configId) and (IncidentProviderConfigs.organizationId eq organizationId)
                        } > 0
                    }

                if (deleted) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // Test connection
            post("/{id}/test") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val configId =
                    call.parameters["id"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@post call.respond(HttpStatusCode.Forbidden)

                val config =
                    transaction {
                        IncidentProviderConfigs
                            .selectAll()
                            .where {
                                (IncidentProviderConfigs.id eq configId) and
                                    (IncidentProviderConfigs.organizationId eq organizationId)
                            }.firstOrNull()
                            ?.let { row ->
                                ProviderConfig(
                                    id = row[IncidentProviderConfigs.id].value,
                                    organizationId = row[IncidentProviderConfigs.organizationId],
                                    providerType = row[IncidentProviderConfigs.providerType],
                                    name = row[IncidentProviderConfigs.name],
                                    apiKey = row[IncidentProviderConfigs.apiKey],
                                    configJson =
                                    try {
                                        val jsonStr = row[IncidentProviderConfigs.configJson]
                                        json.parseToJsonElement(jsonStr).jsonObject
                                    } catch (e: Exception) {
                                        buildJsonObject {}
                                    },
                                    enabled = row[IncidentProviderConfigs.enabled]
                                )
                            }
                    } ?: return@post call.respond(HttpStatusCode.NotFound)

                val provider =
                    IncidentProviderRegistry.getProvider(config.providerType)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Provider not registered"))

                val result = provider.testConnection(config)

                result.fold(
                    onSuccess = { success ->
                        call.respond(HttpStatusCode.OK, BooleanResponse(success))
                    },
                    onFailure = { error ->
                        call.respond(HttpStatusCode.OK, ErrorResponse(error.message))
                    }
                )
            }

            // Get routing rules
            get("/{id}/rules") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val configId =
                    call.parameters["id"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@get call.respond(HttpStatusCode.Forbidden)

                val hasAccess =
                    transaction {
                        IncidentProviderConfigs
                            .selectAll()
                            .where {
                                (IncidentProviderConfigs.id eq configId) and
                                    (IncidentProviderConfigs.organizationId eq organizationId)
                            }.count() > 0
                    }

                if (!hasAccess) return@get call.respond(HttpStatusCode.NotFound)

                val rules =
                    transaction {
                        IncidentRoutingRules
                            .selectAll()
                            .where {
                                IncidentRoutingRules.providerConfigId eq configId
                            }.map { row ->
                                RoutingRuleResponse(
                                    id = row[IncidentRoutingRules.id].value,
                                    alertSource = row[IncidentRoutingRules.alertSource],
                                    alertType = row[IncidentRoutingRules.alertType],
                                    incidentSeverity = row[IncidentRoutingRules.incidentSeverity]
                                )
                            }
                    }

                call.respond(rules)
            }

            // Bulk upsert routing rules
            put("/{id}/rules") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val configId =
                    call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest)

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@put call.respond(HttpStatusCode.Forbidden)

                val hasAccess =
                    transaction {
                        IncidentProviderConfigs
                            .selectAll()
                            .where {
                                (IncidentProviderConfigs.id eq configId) and
                                    (IncidentProviderConfigs.organizationId eq organizationId)
                            }.count() > 0
                    }

                if (!hasAccess) return@put call.respond(HttpStatusCode.NotFound)

                val request = call.receive<List<UpsertRoutingRuleRequest>>()

                transaction {
                    // Delete existing rules for this provider
                    IncidentRoutingRules.deleteWhere {
                        providerConfigId eq configId
                    }

                    // Insert new rules
                    request.forEach { rule ->
                        IncidentRoutingRules.insert {
                            it[IncidentRoutingRules.providerConfigId] = configId
                            it[IncidentRoutingRules.alertSource] = rule.alertSource
                            it[IncidentRoutingRules.alertType] = rule.alertType
                            it[IncidentRoutingRules.incidentSeverity] = rule.incidentSeverity
                            it[IncidentRoutingRules.createdAt] = Clock.System.now()
                            it[IncidentRoutingRules.updatedAt] = Clock.System.now()
                        }
                    }
                }

                call.respond(HttpStatusCode.OK)
            }

            // Get event log
            get("/{id}/events") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("userId").asInt()
                val configId =
                    call.parameters["id"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val organizationId =
                    transaction {
                        Memberships
                            .selectAll()
                            .where { Memberships.user_id eq userId }
                            .firstOrNull()
                            ?.get(Memberships.organization_id)
                    } ?: return@get call.respond(HttpStatusCode.Forbidden)

                val hasAccess =
                    transaction {
                        IncidentProviderConfigs
                            .selectAll()
                            .where {
                                (IncidentProviderConfigs.id eq configId) and
                                    (IncidentProviderConfigs.organizationId eq organizationId)
                            }.count() > 0
                    }

                if (!hasAccess) return@get call.respond(HttpStatusCode.NotFound)

                val events =
                    transaction {
                        IncidentEventLog
                            .selectAll()
                            .where { IncidentEventLog.providerConfigId eq configId }
                            .orderBy(IncidentEventLog.createdAt to SortOrder.DESC)
                            .limit(limit)
                            .map { row ->
                                EventLogResponse(
                                    id = row[IncidentEventLog.id].value,
                                    alertSource = row[IncidentEventLog.alertSource],
                                    deduplicationKey = row[IncidentEventLog.deduplicationKey],
                                    incidentSeverity = row[IncidentEventLog.incidentSeverity],
                                    incidentStatus = row[IncidentEventLog.incidentStatus],
                                    title = row[IncidentEventLog.title],
                                    description = row[IncidentEventLog.description],
                                    providerIncidentId = row[IncidentEventLog.providerIncidentId],
                                    success = row[IncidentEventLog.success],
                                    errorMessage = row[IncidentEventLog.errorMessage],
                                    createdAt = row[IncidentEventLog.createdAt].toEpochMilliseconds()
                                )
                            }
                    }

                call.respond(events)
            }
        }
    }
}

@Serializable
data class ProviderConfigResponse(
    val id: Int,
    val providerType: String,
    val name: String,
    val configJson: Map<String, String>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CreateProviderConfigRequest(
    val providerType: String,
    val name: String,
    val apiKey: String,
    val configJson: Map<String, String>
)

@Serializable
data class UpdateProviderConfigRequest(
    val name: String? = null,
    val apiKey: String? = null,
    val configJson: Map<String, String>? = null,
    val enabled: Boolean? = null
)

@Serializable
data class RoutingRuleResponse(
    val id: Int,
    val alertSource: String,
    val alertType: String?,
    val incidentSeverity: String
)

@Serializable
data class UpsertRoutingRuleRequest(
    val alertSource: String,
    val alertType: String? = null,
    val incidentSeverity: String
)

@Serializable
data class EventLogResponse(
    val id: Int,
    val alertSource: String,
    val deduplicationKey: String,
    val incidentSeverity: String,
    val incidentStatus: String,
    val title: String,
    val description: String?,
    val providerIncidentId: String?,
    val success: Boolean,
    val errorMessage: String?,
    val createdAt: Long
)
