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

package com.moneat.datadog.services

import com.moneat.datadog.models.AgentDebuggerProbeConfig
import com.moneat.datadog.models.CreateDebuggerProbeRequest
import com.moneat.datadog.models.DebuggerProbe
import com.moneat.datadog.models.DebuggerProbes
import com.moneat.datadog.models.UpdateDebuggerProbeRequest
import com.moneat.shared.services.organizationResourceId
import com.moneat.shared.services.organizationResourceIds
import com.moneat.shared.services.userResourceId
import com.moneat.shared.services.userResourceIds
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock

private val supportedProbeTypes = setOf(
    "log_probe",
    "snapshot",
    "span_decoration",
    "metric_probe"
)
private val supportedWhereTypes = setOf("method", "line")
private val supportedMetricKinds = setOf("count", "gauge", "histogram")

object DebuggerProbeService {
    fun listProbes(organizationIds: List<Int>): List<DebuggerProbe> {
        if (organizationIds.isEmpty()) return emptyList()

        return transaction {
            val rows = DebuggerProbes
                .selectAll()
                .where { DebuggerProbes.organizationId inList organizationIds }
                .orderBy(DebuggerProbes.updatedAt to SortOrder.DESC)
                .toList()
            val organizationResources = organizationResourceIds(rows.map { it[DebuggerProbes.organizationId] })
            val userResources = userResourceIds(rows.mapNotNull { it[DebuggerProbes.createdBy] })
            rows.map { row -> row.toDebuggerProbe(organizationResources, userResources) }
        }
    }

    fun createProbe(
        organizationId: Int,
        createdBy: Int?,
        request: CreateDebuggerProbeRequest,
    ): DebuggerProbe =
        transaction {
            val normalized = normalizeCreateRequest(request)
            val now = Clock.System.now()

            val probeId =
                DebuggerProbes.insert {
                    it[id] = UUID.randomUUID()
                    it[DebuggerProbes.organizationId] = organizationId
                    it[probeType] = normalized.probeType
                    it[service] = normalized.service
                    it[environment] = normalized.environment
                    it[language] = normalized.language
                    it[active] = normalized.active
                    it[whereType] = normalized.whereType
                    it[typeName] = normalized.typeName
                    it[methodName] = normalized.methodName
                    it[sourceFile] = normalized.sourceFile
                    it[sourceLines] = normalized.sourceLines
                    it[template] = normalized.template
                    it[metricName] = normalized.metricName
                    it[metricKind] = normalized.metricKind
                    it[tags] = normalized.tags
                    it[captureConfig] = normalized.captureConfig
                    it[DebuggerProbes.createdBy] = createdBy
                    it[createdAt] = now
                    it[updatedAt] = now
                }[DebuggerProbes.id]

            DebuggerProbes
                .selectAll()
                .where { DebuggerProbes.id eq probeId }
                .single()
                .toDebuggerProbe()
        }

    fun updateProbe(
        probeId: UUID,
        organizationIds: List<Int>,
        request: UpdateDebuggerProbeRequest,
    ): DebuggerProbe? {
        if (organizationIds.isEmpty()) return null

        return transaction {
            val existing =
                DebuggerProbes
                    .selectAll()
                    .where {
                        (DebuggerProbes.id eq probeId) and
                            (DebuggerProbes.organizationId inList organizationIds)
                    }.singleOrNull() ?: return@transaction null

            val resolved = resolveUpdateRequest(existing, request)
            val now = Clock.System.now()

            DebuggerProbes.update({ DebuggerProbes.id eq probeId }) {
                it[probeType] = resolved.probeType
                it[service] = resolved.service
                it[environment] = resolved.environment
                it[language] = resolved.language
                it[active] = resolved.active
                it[whereType] = resolved.whereType
                it[typeName] = resolved.typeName
                it[methodName] = resolved.methodName
                it[sourceFile] = resolved.sourceFile
                it[sourceLines] = resolved.sourceLines
                it[template] = resolved.template
                it[metricName] = resolved.metricName
                it[metricKind] = resolved.metricKind
                it[tags] = resolved.tags
                it[captureConfig] = resolved.captureConfig
                it[updatedAt] = now
            }

            DebuggerProbes
                .selectAll()
                .where { DebuggerProbes.id eq probeId }
                .singleOrNull()
                ?.toDebuggerProbe()
        }
    }

    fun deleteProbe(probeId: UUID, organizationIds: List<Int>): Boolean {
        if (organizationIds.isEmpty()) return false

        return transaction {
            DebuggerProbes.deleteWhere {
                (id eq probeId) and (organizationId inList organizationIds)
            } > 0
        }
    }

    fun listAgentProbes(
        organizationId: Int,
        service: String?,
        environment: String?,
    ): List<AgentDebuggerProbeConfig> =
        transaction {
            var query =
                DebuggerProbes
                    .selectAll()
                    .where {
                        (DebuggerProbes.organizationId eq organizationId) and
                            (DebuggerProbes.active eq true)
                    }

            val normalizedService = service?.trim().takeUnless { it.isNullOrBlank() }
            val normalizedEnv = environment?.trim().takeUnless { it.isNullOrBlank() }

            if (normalizedService != null) {
                query = query.andWhere { DebuggerProbes.service eq normalizedService }
            }

            if (normalizedEnv != null) {
                query = query.andWhere {
                    (DebuggerProbes.environment eq normalizedEnv) or
                        (DebuggerProbes.environment eq "*")
                }
            }

            query
                .orderBy(DebuggerProbes.updatedAt to SortOrder.DESC)
                .map { it.toAgentDebuggerProbe() }
        }

    private fun normalizeCreateRequest(request: CreateDebuggerProbeRequest): ResolvedProbe {
        val service = request.service.trim()
        require(service.isNotEmpty()) { "service is required" }

        val whereType = normalizeWhereType(request.whereType)

        val location =
            if (whereType == "method") {
                val typeName = request.typeName?.trim().takeUnless { it.isNullOrBlank() }
                val methodName = request.methodName?.trim().takeUnless { it.isNullOrBlank() }
                require(typeName != null) { "typeName is required for method probes" }
                require(methodName != null) { "methodName is required for method probes" }
                ResolvedLocation(typeName, methodName, null, null)
            } else {
                val sourceFile = request.sourceFile?.trim().takeUnless { it.isNullOrBlank() }
                val sourceLines = request.sourceLines?.trim().takeUnless { it.isNullOrBlank() }
                require(sourceFile != null) { "sourceFile is required for line probes" }
                require(sourceLines != null) { "sourceLines is required for line probes" }
                ResolvedLocation(null, null, sourceFile, sourceLines)
            }

        return ResolvedProbe(
            probeType = normalizeProbeType(request.probeType),
            service = service,
            environment = request.environment.trim().ifEmpty { "*" },
            language = normalizeLanguage(request.language),
            active = request.active,
            whereType = whereType,
            typeName = location.typeName,
            methodName = location.methodName,
            sourceFile = location.sourceFile,
            sourceLines = location.sourceLines,
            template = request.template?.takeUnless { it.isBlank() },
            metricName = request.metricName?.trim().takeUnless { it.isNullOrBlank() },
            metricKind = normalizeMetricKind(request.metricKind),
            tags = request.tags?.takeUnless { it.isBlank() },
            captureConfig = request.captureConfig?.takeUnless { it.isBlank() },
        )
    }

    private fun resolveUpdateRequest(
        existing: ResultRow,
        request: UpdateDebuggerProbeRequest,
    ): ResolvedProbe {
        val whereType = request.whereType?.let { normalizeWhereType(it) } ?: existing[DebuggerProbes.whereType]

        val location =
            if (whereType == "method") {
                val existingTypeName = existing[DebuggerProbes.typeName]
                val existingMethodName = existing[DebuggerProbes.methodName]
                val typeName = request.typeName?.trim().takeUnless { it.isNullOrBlank() } ?: existingTypeName
                val methodName =
                    request.methodName?.trim().takeUnless { it.isNullOrBlank() } ?: existingMethodName
                require(typeName != null) { "typeName is required for method probes" }
                require(methodName != null) { "methodName is required for method probes" }
                ResolvedLocation(typeName, methodName, null, null)
            } else {
                val existingSourceFile = existing[DebuggerProbes.sourceFile]
                val existingSourceLines = existing[DebuggerProbes.sourceLines]
                val sourceFile =
                    request.sourceFile?.trim().takeUnless { it.isNullOrBlank() } ?: existingSourceFile
                val sourceLines =
                    request.sourceLines?.trim().takeUnless { it.isNullOrBlank() } ?: existingSourceLines
                require(sourceFile != null) { "sourceFile is required for line probes" }
                require(sourceLines != null) { "sourceLines is required for line probes" }
                ResolvedLocation(null, null, sourceFile, sourceLines)
            }

        val existingMetricKind = existing[DebuggerProbes.metricKind]
        val requestedMetricKind = request.metricKind?.let { normalizeMetricKind(it) }

        return ResolvedProbe(
            probeType = request.probeType?.let { normalizeProbeType(it) } ?: existing[DebuggerProbes.probeType],
            service = request.service?.trim()?.takeUnless { it.isBlank() } ?: existing[DebuggerProbes.service],
            environment =
            request.environment?.trim()?.ifEmpty { "*" } ?: existing[DebuggerProbes.environment],
            language = request.language?.let { normalizeLanguage(it) } ?: existing[DebuggerProbes.language],
            active = request.active ?: existing[DebuggerProbes.active],
            whereType = whereType,
            typeName = location.typeName,
            methodName = location.methodName,
            sourceFile = location.sourceFile,
            sourceLines = location.sourceLines,
            template = request.template?.takeUnless { it.isBlank() } ?: existing[DebuggerProbes.template],
            metricName =
            request.metricName?.trim()?.takeUnless { it.isBlank() } ?: existing[DebuggerProbes.metricName],
            metricKind = requestedMetricKind ?: existingMetricKind,
            tags = request.tags?.takeUnless { it.isBlank() } ?: existing[DebuggerProbes.tags],
            captureConfig =
            request.captureConfig?.takeUnless { it.isBlank() } ?: existing[DebuggerProbes.captureConfig],
        )
    }

    private fun normalizeProbeType(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in supportedProbeTypes) { "Unsupported probeType: $raw" }
        return normalized
    }

    private fun normalizeWhereType(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized in supportedWhereTypes) { "Unsupported whereType: $raw" }
        return normalized
    }

    private fun normalizeMetricKind(raw: String?): String? {
        if (raw == null) return null
        val normalized = raw.trim().lowercase()
        if (normalized.isEmpty()) return null
        require(normalized in supportedMetricKinds) { "Unsupported metricKind: $raw" }
        return normalized
    }

    private fun normalizeLanguage(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized.isNotEmpty()) { "language is required" }
        return when (normalized) {
            "node.js" -> "nodejs"
            ".net" -> "dotnet"
            else -> normalized
        }
    }

    private fun ResultRow.toDebuggerProbe(
        organizationResources: Map<Int, String> = emptyMap(),
        userResources: Map<Int, String> = emptyMap(),
    ): DebuggerProbe {
        val organizationId = this[DebuggerProbes.organizationId]
        val createdBy = this[DebuggerProbes.createdBy]
        return DebuggerProbe(
            id = this[DebuggerProbes.id].toString(),
            organizationId = organizationResources[organizationId] ?: organizationResourceId(organizationId),
            probeType = this[DebuggerProbes.probeType],
            service = this[DebuggerProbes.service],
            environment = this[DebuggerProbes.environment],
            language = this[DebuggerProbes.language],
            active = this[DebuggerProbes.active],
            whereType = this[DebuggerProbes.whereType],
            typeName = this[DebuggerProbes.typeName],
            methodName = this[DebuggerProbes.methodName],
            sourceFile = this[DebuggerProbes.sourceFile],
            sourceLines = this[DebuggerProbes.sourceLines],
            template = this[DebuggerProbes.template],
            metricName = this[DebuggerProbes.metricName],
            metricKind = this[DebuggerProbes.metricKind],
            tags = this[DebuggerProbes.tags],
            captureConfig = this[DebuggerProbes.captureConfig],
            createdBy = createdBy?.let { userResources[it] ?: userResourceId(it) },
            createdAt = this[DebuggerProbes.createdAt].toString(),
            updatedAt = this[DebuggerProbes.updatedAt].toString(),
        )
    }

    private fun ResultRow.toAgentDebuggerProbe(): AgentDebuggerProbeConfig =
        AgentDebuggerProbeConfig(
            id = this[DebuggerProbes.id].toString(),
            probeType = this[DebuggerProbes.probeType],
            service = this[DebuggerProbes.service],
            environment = this[DebuggerProbes.environment],
            language = this[DebuggerProbes.language],
            active = this[DebuggerProbes.active],
            whereType = this[DebuggerProbes.whereType],
            typeName = this[DebuggerProbes.typeName],
            methodName = this[DebuggerProbes.methodName],
            sourceFile = this[DebuggerProbes.sourceFile],
            sourceLines = this[DebuggerProbes.sourceLines],
            template = this[DebuggerProbes.template],
            metricName = this[DebuggerProbes.metricName],
            metricKind = this[DebuggerProbes.metricKind],
            tags = this[DebuggerProbes.tags],
            captureConfig = this[DebuggerProbes.captureConfig],
        )
}

private data class ResolvedLocation(
    val typeName: String?,
    val methodName: String?,
    val sourceFile: String?,
    val sourceLines: String?,
)

private data class ResolvedProbe(
    val probeType: String,
    val service: String,
    val environment: String,
    val language: String,
    val active: Boolean,
    val whereType: String,
    val typeName: String?,
    val methodName: String?,
    val sourceFile: String?,
    val sourceLines: String?,
    val template: String?,
    val metricName: String?,
    val metricKind: String?,
    val tags: String?,
    val captureConfig: String?,
)
