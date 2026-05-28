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

package com.moneat.otlp.services

import com.moneat.otlp.models.CreateOtlpServiceMappingRequest
import com.moneat.otlp.models.OtlpObservedServiceResponse
import com.moneat.otlp.models.OtlpServiceMappingResponse
import com.moneat.shared.models.OtelObservedServices
import com.moneat.shared.models.OtelServiceProjectMappings
import com.moneat.shared.models.Projects
import com.moneat.shared.services.ProjectIdResolver
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val UNKNOWN_SERVICE_PREFIX = "unknown_service"

data class OtlpServiceIdentity(
    val serviceNamespace: String,
    val serviceName: String,
)

data class OtlpServiceDescriptor(
    val serviceNamespace: String?,
    val serviceName: String?,
    val environment: String?,
)

enum class OtlpSignalType {
    LOGS,
    TRACES,
    METRICS,
}

class OtlpServiceRoutingService {
    private val projectIdResolver = ProjectIdResolver()

    fun resolveProjectIds(
        organizationId: Int,
        services: List<OtlpServiceDescriptor>,
        signalType: OtlpSignalType,
    ): Map<OtlpServiceIdentity, Long?> {
        val identities = services.mapNotNull { descriptor ->
            normalizeIdentity(descriptor.serviceNamespace, descriptor.serviceName)?.let { identity ->
                identity to descriptor.environment?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        if (identities.isEmpty()) return emptyMap()

        val environmentByIdentity = mergeEnvironmentsByIdentity(identities)
        return transaction {
            val mappings = getMappingsForOrg(organizationId)
            environmentByIdentity.forEach { (identity, environment) ->
                recordObservedService(organizationId, identity, environment, signalType)
            }
            environmentByIdentity.keys.associateWith { identity -> mappings[identity]?.projectId }
        }
    }

    fun listObservedServices(organizationId: Int): List<OtlpObservedServiceResponse> =
        transaction {
            val mappings = getMappingsForOrg(organizationId)
            val projectsById = Projects
                .selectAll()
                .where { Projects.organization_id eq organizationId }
                .associate { row -> row[Projects.id] to (row[Projects.name] to row[Projects.resource_id].toString()) }

            OtelObservedServices
                .selectAll()
                .where { OtelObservedServices.organization_id eq organizationId }
                .orderBy(OtelObservedServices.last_seen_at, SortOrder.DESC)
                .map { row ->
                    val identity = OtlpServiceIdentity(
                        serviceNamespace = row[OtelObservedServices.service_namespace],
                        serviceName = row[OtelObservedServices.service_name],
                    )
                    val mapping = mappings[identity]
                    OtlpObservedServiceResponse(
                        id = row[OtelObservedServices.id],
                        mappingId = mapping?.id,
                        serviceNamespace = identity.serviceNamespace,
                        serviceName = identity.serviceName,
                        projectId = mapping?.projectId,
                        projectResourceId = mapping?.projectId?.let { projectsById[it]?.second },
                        projectName = mapping?.projectId?.let { projectsById[it]?.first },
                        seenLogs = row[OtelObservedServices.seen_logs],
                        seenTraces = row[OtelObservedServices.seen_traces],
                        seenMetrics = row[OtelObservedServices.seen_metrics],
                        lastEnvironment = row[OtelObservedServices.last_environment],
                        firstSeenAt = row[OtelObservedServices.first_seen_at].toString(),
                        lastSeenAt = row[OtelObservedServices.last_seen_at].toString(),
                    )
                }
        }

    fun upsertMapping(
        organizationId: Int,
        request: CreateOtlpServiceMappingRequest,
    ): OtlpServiceMappingResponse? {
        val identity = normalizeIdentity(request.serviceNamespace, request.serviceName) ?: return null
        val now = Clock.System.now()
        val projectId = request.projectResourceId?.let(projectIdResolver::resolve) ?: request.projectId ?: return null
        return transaction {
            val projectRow = Projects
                .selectAll()
                .where {
                    (Projects.id eq projectId) and
                        (Projects.organization_id eq organizationId)
                }
                .firstOrNull()
                ?: return@transaction null
            val projectName = projectRow[Projects.name]
            val projectResourceId = projectRow[Projects.resource_id].toString()

            OtelServiceProjectMappings.insertIgnore {
                it[OtelServiceProjectMappings.organization_id] = organizationId
                it[OtelServiceProjectMappings.service_namespace] = identity.serviceNamespace
                it[OtelServiceProjectMappings.service_name] = identity.serviceName
                it[OtelServiceProjectMappings.project_id] = projectId
                it[OtelServiceProjectMappings.created_at] = now
                it[OtelServiceProjectMappings.updated_at] = now
            }
            OtelServiceProjectMappings.update({
                (OtelServiceProjectMappings.organization_id eq organizationId) and
                    (OtelServiceProjectMappings.service_namespace eq identity.serviceNamespace) and
                    (OtelServiceProjectMappings.service_name eq identity.serviceName)
            }) {
                it[OtelServiceProjectMappings.project_id] = projectId
                it[OtelServiceProjectMappings.updated_at] = now
            }
            val id = OtelServiceProjectMappings
                .selectAll()
                .where {
                    (OtelServiceProjectMappings.organization_id eq organizationId) and
                        (OtelServiceProjectMappings.service_namespace eq identity.serviceNamespace) and
                        (OtelServiceProjectMappings.service_name eq identity.serviceName)
                }
                .first()[OtelServiceProjectMappings.id]

            OtlpServiceMappingResponse(
                id = id,
                serviceNamespace = identity.serviceNamespace,
                serviceName = identity.serviceName,
                projectId = projectId,
                projectResourceId = projectResourceId,
                projectName = projectName,
                updatedAt = now.toString(),
            )
        }
    }

    fun deleteMapping(organizationId: Int, mappingId: Int): Boolean =
        transaction {
            val existing = OtelServiceProjectMappings
                .selectAll()
                .where {
                    (OtelServiceProjectMappings.id eq mappingId) and
                        (OtelServiceProjectMappings.organization_id eq organizationId)
                }
                .firstOrNull()
                ?: return@transaction false

            OtelServiceProjectMappings.deleteWhere {
                OtelServiceProjectMappings.id eq existing[OtelServiceProjectMappings.id]
            } > 0
        }

    fun normalizeIdentity(serviceNamespace: String?, serviceName: String?): OtlpServiceIdentity? {
        val normalizedName = serviceName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (normalizedName.lowercase().startsWith(UNKNOWN_SERVICE_PREFIX)) return null
        return OtlpServiceIdentity(
            serviceNamespace = serviceNamespace?.trim().orEmpty(),
            serviceName = normalizedName,
        )
    }

    private fun recordObservedService(
        organizationId: Int,
        identity: OtlpServiceIdentity,
        environment: String?,
        signalType: OtlpSignalType,
    ) {
        val now = Clock.System.now()
        OtelObservedServices.insertIgnore {
            it[OtelObservedServices.organization_id] = organizationId
            it[OtelObservedServices.service_namespace] = identity.serviceNamespace
            it[OtelObservedServices.service_name] = identity.serviceName
            it[OtelObservedServices.first_seen_at] = now
            it[OtelObservedServices.last_seen_at] = now
            it[OtelObservedServices.seen_logs] = signalType == OtlpSignalType.LOGS
            it[OtelObservedServices.seen_traces] = signalType == OtlpSignalType.TRACES
            it[OtelObservedServices.seen_metrics] = signalType == OtlpSignalType.METRICS
            it[OtelObservedServices.last_environment] = environment
        }
        OtelObservedServices.update({
            (OtelObservedServices.organization_id eq organizationId) and
                (OtelObservedServices.service_namespace eq identity.serviceNamespace) and
                (OtelObservedServices.service_name eq identity.serviceName)
        }) {
            it[OtelObservedServices.last_seen_at] = now
            if (environment != null) it[OtelObservedServices.last_environment] = environment
            when (signalType) {
                OtlpSignalType.LOGS -> it[OtelObservedServices.seen_logs] = true
                OtlpSignalType.TRACES -> it[OtelObservedServices.seen_traces] = true
                OtlpSignalType.METRICS -> it[OtelObservedServices.seen_metrics] = true
            }
        }
    }

    private fun mergeEnvironmentsByIdentity(
        identities: List<Pair<OtlpServiceIdentity, String?>>
    ): Map<OtlpServiceIdentity, String?> {
        val environmentByIdentity = linkedMapOf<OtlpServiceIdentity, String?>()
        identities.forEach { (identity, environment) ->
            if (!environmentByIdentity.containsKey(identity) || environmentByIdentity[identity] == null) {
                environmentByIdentity[identity] = environment
            }
        }
        return environmentByIdentity
    }

    private fun getMappingsForOrg(organizationId: Int): Map<OtlpServiceIdentity, MappingRow> =
        OtelServiceProjectMappings
            .selectAll()
            .where { OtelServiceProjectMappings.organization_id eq organizationId }
            .associate { row ->
                OtlpServiceIdentity(
                    serviceNamespace = row[OtelServiceProjectMappings.service_namespace],
                    serviceName = row[OtelServiceProjectMappings.service_name],
                ) to MappingRow(
                    id = row[OtelServiceProjectMappings.id],
                    projectId = row[OtelServiceProjectMappings.project_id],
                )
            }

    private data class MappingRow(
        val id: Int,
        val projectId: Long,
    )
}
