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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdManifestPayload
import com.moneat.datadog.models.DdOrchestratorPayload
import com.moneat.shared.services.UsageTrackingService
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val ORCH_QUEUE_KEY = "moneat:dd:orchestrator:queue"

@Serializable
data class QueuedK8sResourceBatch(
    @SerialName("organization_id") val organizationId: Int,
    @SerialName("cluster_name") val clusterName: String,
    @SerialName("cluster_id") val clusterId: String,
    @SerialName("batch_type") val batchType: String = "resources",
    val resources: List<QueuedK8sResourceEntry> = emptyList(),
    val manifests: List<QueuedK8sManifestEntry> = emptyList(),
)

@Serializable
data class QueuedK8sResourceEntry(
    val uid: String,
    @SerialName("resource_type") val resourceType: String,
    val namespace: String = "",
    val name: String,
    val status: String = "",
    val tags: Map<String, String> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    @SerialName("resource_version") val resourceVersion: String = "",
    @SerialName("creation_timestamp_ms") val creationTimestampMs: Long? = null,
)

@Serializable
data class QueuedK8sManifestEntry(
    val uid: String,
    @SerialName("resource_type") val resourceType: String,
    val namespace: String = "",
    val name: String,
    val content: String,
    @SerialName("content_type") val contentType: String = "application/json",
)

object OrchestratorIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val usageTracking = UsageTrackingService.instance

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mapResources(
        organizationId: Int,
        payload: DdOrchestratorPayload,
    ): QueuedK8sResourceBatch {
        val resources = payload.resources.map { r ->
            QueuedK8sResourceEntry(
                uid = r.uid,
                resourceType = r.type,
                namespace = r.namespace,
                name = r.name,
                status = r.status,
                tags = r.tags,
                labels = r.labels,
                annotations = r.annotations,
                resourceVersion = r.resourceVersion,
                creationTimestampMs = r.creationTimestamp,
            )
        }
        return QueuedK8sResourceBatch(
            organizationId = organizationId,
            clusterName = payload.clusterName,
            clusterId = payload.clusterId,
            batchType = "resources",
            resources = resources,
        )
    }

    fun mapManifests(
        organizationId: Int,
        payload: DdManifestPayload,
    ): QueuedK8sResourceBatch {
        val manifests = payload.manifests.map { m ->
            QueuedK8sManifestEntry(
                uid = m.uid,
                resourceType = m.type,
                namespace = m.namespace,
                name = m.name,
                content = m.content,
                contentType = m.contentType,
            )
        }
        return QueuedK8sResourceBatch(
            organizationId = organizationId,
            clusterName = payload.clusterName,
            clusterId = "",
            batchType = "manifests",
            manifests = manifests,
        )
    }

    fun enqueueResources(
        organizationId: Int,
        payload: DdOrchestratorPayload,
        queueKey: String = ORCH_QUEUE_KEY,
    ): Int {
        val batch = mapResources(organizationId, payload)
        if (batch.resources.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.resources.size
    }

    fun enqueueManifests(
        organizationId: Int,
        payload: DdManifestPayload,
        queueKey: String = ORCH_QUEUE_KEY,
    ): Int {
        val batch = mapManifests(organizationId, payload)
        if (batch.manifests.isEmpty()) return 0
        RedisConfig.sync().lpush(queueKey, json.encodeToString(batch))
        return batch.manifests.size
    }

    suspend fun insertBatch(batch: QueuedK8sResourceBatch) {
        when (batch.batchType) {
            "resources" -> insertResources(batch)
            "manifests" -> insertManifests(batch)
        }
    }

    private suspend fun insertResources(batch: QueuedK8sResourceBatch) {
        if (batch.resources.isEmpty()) return

        val rows = batch.resources.joinToString(",\n") { r ->
            val creationTs = r.creationTimestampMs?.let {
                "fromUnixTimestamp64Milli($it)"
            } ?: "now()"
            """(
                ${batch.organizationId},
                '${escapeSql(r.uid)}',
                '${escapeSql(r.resourceType)}',
                '${escapeSql(r.namespace)}',
                '${escapeSql(r.name)}',
                '${escapeSql(batch.clusterName)}',
                '${escapeSql(batch.clusterId)}',
                '${escapeSql(r.status)}',
                ${mapToSqlMap(r.tags)},
                ${mapToSqlMap(r.labels)},
                ${mapToSqlMap(r.annotations)},
                '${escapeSql(r.resourceVersion)}',
                $creationTs,
                now()
            )"""
        }

        val insert = """
            INSERT INTO $clickhouseDb.k8s_resources (
                organization_id, uid, resource_type, namespace, name,
                cluster_name, cluster_id, status, tags, labels,
                annotations, resource_version, creation_timestamp,
                collected_at
            ) VALUES $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Failed to insert DD K8s resources into ClickHouse"
            )
        }

        val totalBytes = batch.resources.sumOf { it.name.length + it.uid.length }
        usageTracking.recordOrgUsage(batch.organizationId, "dd_k8s", totalBytes)
    }

    private suspend fun insertManifests(batch: QueuedK8sResourceBatch) {
        if (batch.manifests.isEmpty()) return

        val rows = batch.manifests.joinToString(",\n") { m ->
            """(
                ${batch.organizationId},
                '${escapeSql(m.uid)}',
                '${escapeSql(m.resourceType)}',
                '${escapeSql(m.namespace)}',
                '${escapeSql(m.name)}',
                '${escapeSql(batch.clusterName)}',
                '${escapeSql(m.content)}',
                '${escapeSql(m.contentType)}',
                now()
            )"""
        }

        val insert = """
            INSERT INTO $clickhouseDb.k8s_manifests (
                organization_id, uid, resource_type, namespace, name,
                cluster_name, manifest, content_type, collected_at
            ) VALUES $rows
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Failed to insert DD K8s manifests into ClickHouse"
            )
        }

        val totalBytes = batch.manifests.sumOf { it.content.length }
        usageTracking.recordOrgUsage(batch.organizationId, "dd_k8s", totalBytes)
    }

    fun decodeBatch(encoded: String): QueuedK8sResourceBatch =
        json.decodeFromString(encoded)

    internal fun escapeSql(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    internal fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
