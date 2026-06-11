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

package com.moneat.dashboards.services

import com.moneat.dashboards.models.CreateCustomDataSourceRequest
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.CustomDataSources
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.UpdateCustomDataSourceRequest
import com.moneat.dashboards.services.handlers.ConnectionOptions
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

class CustomDataSourceService {
    companion object {
        private val TEMPLATE_DATA_SOURCE_MARKERS = mapOf(
            "__prometheus" to "prometheus",
            "__cloudwatch" to "cloudwatch",
            "__elasticsearch" to "elasticsearch",
            "__graphite" to "graphite",
            "__influxdb" to "influxdb",
            "__loki" to "loki",
            "__postgresql" to "postgresql",
            "__postgres" to "postgresql",
            "__redis" to "redis"
        )
    }

    private fun parseUuid(value: String): Uuid? =
        runCatching { Uuid.parse(value) }.getOrNull()

    fun isValidResourceId(value: String?): Boolean =
        value?.let(::parseUuid) != null

    fun resolveDataSourceId(resourceId: String, orgId: Long): Long? =
        parseUuid(resourceId)?.let { parsed ->
            transaction {
                CustomDataSources.selectAll()
                    .where { (CustomDataSources.resourceId eq parsed) and (CustomDataSources.orgId eq orgId) }
                    .firstOrNull()
                    ?.get(CustomDataSources.id)
            }
        }

    fun listDataSources(orgId: Long): List<CustomDataSourceResponse> = transaction {
        val sources = CustomDataSources.selectAll()
            .where { CustomDataSources.orgId eq orgId }
            .orderBy(CustomDataSources.name)
            .map { it.toResponse() }
        val usageCounts = dataSourceUsageCounts(orgId, sources)
        sources.map { source ->
            source.copy(usedByDashboardCount = usageCounts[source.id] ?: 0)
        }
    }

    fun getDataSource(id: Long, orgId: Long): CustomDataSourceResponse? = transaction {
        CustomDataSources.selectAll()
            .where { (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }
            .firstOrNull()
            ?.toResponse()
    }

    fun getDataSource(resourceId: String, orgId: Long): CustomDataSourceResponse? =
        resolveDataSourceId(resourceId, orgId)?.let { getDataSource(it, orgId) }

    fun createDataSource(orgId: Long, userId: Long, request: CreateCustomDataSourceRequest): CustomDataSourceResponse {
        val sourceType = CustomDataSourceType.fromString(request.sourceType)
            ?: throw IllegalArgumentException(
                "Unsupported source type: ${request.sourceType}"
            )

        val creds = DataSourceCredentials(
            username = request.username,
            password = request.password,
            apiKey = request.apiKey,
            headerName = request.extraConfig["header_name"],
            headerValue = request.headerValue,
            accessKeyId = request.accessKeyId,
            secretAccessKey = request.secretAccessKey,
            serviceAccountJson = request.serviceAccountJson,
            accountIdentifier = request.accountIdentifier,
            connectionString = request.connectionString,
            projectId = request.projectId,
            region = request.region,
        )
        val encryptedCreds = encryptCredentials(creds)
        val now = Clock.System.now()

        return transaction {
            val id = CustomDataSources.insert {
                it[CustomDataSources.orgId] = orgId
                it[name] = request.name
                it[description] = request.description
                it[CustomDataSources.sourceType] = sourceType.name.lowercase()
                it[host] = request.host
                it[port] = request.port
                it[databaseName] = request.databaseName
                it[CustomDataSources.encryptedCredentials] = encryptedCreds
                it[extraConfig] = json.encodeToString(request.extraConfig)
                it[enabled] = true
                it[createdBy] = userId
                it[createdAt] = now
                it[updatedAt] = now
            } get CustomDataSources.id

            CustomDataSources.selectAll()
                .where { CustomDataSources.id eq id }
                .first()
                .toResponse()
        }
    }

    fun updateDataSource(id: Long, orgId: Long, request: UpdateCustomDataSourceRequest): CustomDataSourceResponse? {
        val now = Clock.System.now()
        return transaction {
            val existing = CustomDataSources.selectAll()
                .where { (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }
                .firstOrNull() ?: return@transaction null

            // Only re-encrypt credentials if new values are provided
            val hasNewCreds = request.username != null || request.password != null || request.apiKey != null ||
                request.headerValue != null || request.extraConfig?.containsKey("header_name") == true ||
                request.accessKeyId != null || request.secretAccessKey != null || request.serviceAccountJson != null ||
                request.accountIdentifier != null || request.connectionString != null || request.projectId != null ||
                request.region != null
            val newEncryptedCreds = if (hasNewCreds) {
                val existingCreds = suspendRunCatching {
                    val dec = CredentialEncryption.decrypt(existing[CustomDataSources.encryptedCredentials])
                    json.decodeFromString<DataSourceCredentials>(dec)
                }.getOrElse { e ->
                    throw IllegalStateException(
                        "Failed to decrypt existing credentials for data source $id; " +
                            "cannot safely merge new credentials",
                        e
                    )
                }
                val merged = DataSourceCredentials(
                    username = request.username ?: existingCreds.username,
                    password = request.password ?: existingCreds.password,
                    apiKey = request.apiKey ?: existingCreds.apiKey,
                    headerName = request.extraConfig?.get("header_name") ?: existingCreds.headerName,
                    headerValue = request.headerValue ?: existingCreds.headerValue,
                    accessKeyId = request.accessKeyId ?: existingCreds.accessKeyId,
                    secretAccessKey = request.secretAccessKey ?: existingCreds.secretAccessKey,
                    serviceAccountJson = request.serviceAccountJson ?: existingCreds.serviceAccountJson,
                    accountIdentifier = request.accountIdentifier ?: existingCreds.accountIdentifier,
                    connectionString = request.connectionString ?: existingCreds.connectionString,
                    projectId = request.projectId ?: existingCreds.projectId,
                    region = request.region ?: existingCreds.region,
                )
                encryptCredentials(merged)
            } else {
                null
            }

            CustomDataSources.update({ (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }) {
                request.name?.let { v -> it[name] = v }
                request.description?.let { v -> it[description] = v }
                request.host?.let { v -> it[host] = v }
                request.port?.let { v -> it[port] = v }
                request.databaseName?.let { v -> it[databaseName] = v }
                newEncryptedCreds?.let { v -> it[encryptedCredentials] = v }
                request.extraConfig?.let { v ->
                    it[extraConfig] = json.encodeToString(v)
                }
                request.enabled?.let { v -> it[enabled] = v }
                it[updatedAt] = now
            }

            CustomDataSources.selectAll()
                .where { CustomDataSources.id eq id }
                .firstOrNull()
                ?.toResponse()
        }
    }

    fun deleteDataSource(id: Long, orgId: Long): Boolean = transaction {
        CustomDataSources.deleteWhere {
            (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId)
        } > 0
    }

    /**
     * Returns the decrypted credentials for a data source (used internally by the executor).
     * Never expose this to API responses.
     */
    fun getDecryptedCredentials(id: Long, orgId: Long): DataSourceCredentials? = transaction {
        val row = CustomDataSources.selectAll()
            .where { (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }
            .firstOrNull() ?: return@transaction null

        suspendRunCatching {
            val decrypted = CredentialEncryption.decrypt(row[CustomDataSources.encryptedCredentials])
            json.decodeFromString<DataSourceCredentials>(decrypted)
        }.getOrElse { e ->
            logger.error(e) { "Failed to decrypt credentials for data source $id" }
            null
        }
    }

    private fun encryptCredentials(creds: DataSourceCredentials): String {
        val credsJson = json.encodeToString(DataSourceCredentials.serializer(), creds)
        return CredentialEncryption.encrypt(credsJson)
    }

    private fun dataSourceUsageCounts(
        orgId: Long,
        sources: List<CustomDataSourceResponse>,
    ): Map<String, Int> {
        if (sources.isEmpty()) return emptyMap()

        val sourceIds = sources.map { it.id }.toSet()
        val firstEnabledSourceByType = sources
            .filter { it.enabled }
            .groupBy { it.sourceType.lowercase() }
            .mapValues { (_, matchingSources) -> matchingSources.first().id }
        val dashboardIdsBySource = sourceIds.associateWith { mutableSetOf<Long>() }.toMutableMap()

        DashboardWidgets.innerJoin(Dashboards)
            .select(DashboardWidgets.dashboardId, DashboardWidgets.queryConfig, DashboardWidgets.queryConfigs)
            .where { Dashboards.orgId eq orgId }
            .forEach { row ->
                val dashboardId = row[DashboardWidgets.dashboardId]
                val referencedSourceIds = referencedSourceIds(
                    queryConfig = row[DashboardWidgets.queryConfig],
                    queryConfigs = row[DashboardWidgets.queryConfigs],
                    sourceIds = sourceIds,
                    firstEnabledSourceByType = firstEnabledSourceByType,
                )
                referencedSourceIds.forEach { sourceId ->
                    dashboardIdsBySource[sourceId]?.add(dashboardId)
                }
            }

        return dashboardIdsBySource.mapValues { (_, dashboardIds) -> dashboardIds.size }
    }

    private fun referencedSourceIds(
        queryConfig: String,
        queryConfigs: String,
        sourceIds: Set<String>,
        firstEnabledSourceByType: Map<String, String>,
    ): Set<String> {
        val queries = decodeQueryConfigs(queryConfigs).ifEmpty {
            decodeQueryConfig(queryConfig)?.let(::listOf).orEmpty()
        }
        return queries.mapNotNullTo(mutableSetOf()) { query ->
            sourceIdForDataSource(query.dataSource, sourceIds, firstEnabledSourceByType)
        }
    }

    private fun sourceIdForDataSource(
        dataSource: String,
        sourceIds: Set<String>,
        firstEnabledSourceByType: Map<String, String>,
    ): String? {
        if (dataSource.startsWith("custom:")) {
            val sourceId = dataSource.removePrefix("custom:")
            return sourceId.takeIf(sourceIds::contains)
        }
        val sourceType = TEMPLATE_DATA_SOURCE_MARKERS[dataSource] ?: return null
        return firstEnabledSourceByType[sourceType]
    }

    private fun decodeQueryConfigs(value: String): List<QueryDsl> =
        try {
            json.decodeFromString<List<QueryDsl>>(value)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    private fun decodeQueryConfig(value: String): QueryDsl? =
        try {
            json.decodeFromString<QueryDsl>(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun ResultRow.toResponse() = CustomDataSourceResponse(
        id = this[CustomDataSources.resourceId].toString(),
        orgId = this[CustomDataSources.orgId],
        name = this[CustomDataSources.name],
        description = this[CustomDataSources.description],
        sourceType = this[CustomDataSources.sourceType],
        host = this[CustomDataSources.host],
        port = this[CustomDataSources.port],
        databaseName = this[CustomDataSources.databaseName],
        extraConfig = suspendRunCatching {
            json.decodeFromString<Map<String, String>>(this[CustomDataSources.extraConfig])
        }.getOrElse { _ ->
            emptyMap()
        },
        enabled = this[CustomDataSources.enabled],
        createdBy = this[CustomDataSources.createdBy],
        createdAt = this[CustomDataSources.createdAt].toString(),
        updatedAt = this[CustomDataSources.updatedAt].toString(),
        hasCredentials = this[CustomDataSources.encryptedCredentials].isNotBlank(),
        numericId = this[CustomDataSources.id]
    )
}

@kotlinx.serialization.Serializable
data class DataSourceCredentials(
    val username: String? = null,
    val password: String? = null,
    @kotlinx.serialization.SerialName("api_key") val apiKey: String? = null,
    // Custom-header auth (e.g. Loki multi-tenancy): name is non-secret config but
    // stored alongside the value so handlers get everything they need at query time.
    @kotlinx.serialization.SerialName("header_name") val headerName: String? = null,
    @kotlinx.serialization.SerialName("header_value") val headerValue: String? = null,
    @kotlinx.serialization.SerialName("access_key_id") val accessKeyId: String? = null,
    @kotlinx.serialization.SerialName("secret_access_key") val secretAccessKey: String? = null,
    @kotlinx.serialization.SerialName("service_account_json") val serviceAccountJson: String? = null,
    @kotlinx.serialization.SerialName("account_identifier") val accountIdentifier: String? = null,
    @kotlinx.serialization.SerialName("connection_string") val connectionString: String? = null,
    @kotlinx.serialization.SerialName("project_id") val projectId: String? = null,
    val region: String? = null,
    // Non-secret connection options (auth method, TLS mode, org/bucket, warehouse,
    // use_role, …) parsed from extra_config and injected by the executor at query
    // time. @Transient so it never touches the encrypted-at-rest credential blob.
    @kotlinx.serialization.Transient val options: ConnectionOptions = ConnectionOptions(),
)
