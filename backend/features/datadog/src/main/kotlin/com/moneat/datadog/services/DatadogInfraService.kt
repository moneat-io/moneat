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
import com.moneat.datadog.models.DatadogConnectionsPayload
import com.moneat.datadog.models.DatadogContainerPayload
import com.moneat.datadog.models.DatadogProcessPayload
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.monitor.services.InfraContainerRollupRow
import com.moneat.monitor.services.InfraTelemetryRollups
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val INFRA_QUEUE_KEY = "moneat:infra:queue"
private const val ERROR_BODY_MAX_LEN = 600

@Serializable
data class QueuedInfraBatch(
    @SerialName("organization_id") val organizationId: Long,
    val type: String,
    val processes: List<QueuedProcessEntry> = emptyList(),
    val containers: List<QueuedContainerEntry> = emptyList(),
    val connections: List<QueuedConnectionEntry> = emptyList()
)

@Serializable
data class QueuedProcessEntry(
    val host: String,
    val pid: Int,
    val name: String = "",
    val command: String = "",
    val user: String = "",
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("mem_rss") val memRss: Long = 0,
    @SerialName("mem_vms") val memVms: Long = 0,
    val state: String = "",
    @SerialName("thread_count") val threadCount: Int = 0,
    @SerialName("open_fd_count") val openFdCount: Int = 0,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long
)

@Serializable
data class QueuedContainerEntry(
    val host: String,
    @SerialName("container_id") val containerId: String,
    val name: String = "",
    val image: String = "",
    val state: String = "running",
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("mem_usage") val memUsage: Long = 0,
    @SerialName("mem_limit") val memLimit: Long = 0,
    @SerialName("net_rx_bytes") val netRxBytes: Long = 0,
    @SerialName("net_tx_bytes") val netTxBytes: Long = 0,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long
)

@Serializable
data class QueuedConnectionEntry(
    val host: String,
    val pid: Int = 0,
    @SerialName("local_addr") val localAddr: String = "",
    @SerialName("local_port") val localPort: Int = 0,
    @SerialName("remote_addr") val remoteAddr: String = "",
    @SerialName("remote_port") val remotePort: Int = 0,
    val protocol: String = "tcp",
    val family: String = "IPv4",
    val direction: String = "",
    @SerialName("bytes_sent") val bytesSent: Long = 0,
    @SerialName("bytes_recv") val bytesRecv: Long = 0,
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long
)

object DatadogInfraService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun mapProcesses(
        organizationId: Long,
        payload: DatadogProcessPayload
    ): QueuedInfraBatch {
        val now = System.currentTimeMillis()
        val entries = payload.processes.map { p ->
            val tags = DatadogMetricService.parseDdTagList(p.tags)
            QueuedProcessEntry(
                host = payload.host,
                pid = p.pid,
                name = p.name,
                command = p.command,
                user = p.user,
                cpuPercent = p.cpuPercent,
                memRss = p.memRss,
                memVms = p.memVms,
                state = p.state,
                threadCount = p.threadCount,
                openFdCount = p.openFdCount,
                tags = tags,
                timestampMs = now
            )
        }
        return QueuedInfraBatch(
            organizationId = organizationId,
            type = "processes",
            processes = entries
        )
    }

    fun mapContainers(
        organizationId: Long,
        payload: DatadogContainerPayload
    ): QueuedInfraBatch {
        val now = System.currentTimeMillis()
        val entries = payload.containers.map { c ->
            val tags = DatadogMetricService.parseDdTagList(c.tags)
            val resolvedName = c.name.ifBlank { tags["container_name"] ?: "" }
            val resolvedImage = c.image.ifBlank {
                val imgName = tags["image_name"] ?: ""
                val imgTag = tags["image_tag"]
                if (imgName.isNotEmpty() && imgTag != null) "$imgName:$imgTag" else imgName
            }
            QueuedContainerEntry(
                host = payload.host,
                containerId = c.containerId,
                name = resolvedName,
                image = resolvedImage,
                state = c.state,
                cpuPercent = c.cpuPercent,
                memUsage = c.memUsage,
                memLimit = c.memLimit,
                netRxBytes = c.netRxBytes,
                netTxBytes = c.netTxBytes,
                tags = tags,
                timestampMs = now
            )
        }
        return QueuedInfraBatch(
            organizationId = organizationId,
            type = "containers",
            containers = entries
        )
    }

    fun mapConnections(
        organizationId: Long,
        payload: DatadogConnectionsPayload
    ): QueuedInfraBatch {
        val now = System.currentTimeMillis()
        val entries = payload.connections.map { c ->
            val tags = DatadogMetricService.parseDdTagList(c.tags)
            QueuedConnectionEntry(
                host = payload.host,
                pid = c.pid,
                localAddr = c.localAddr,
                localPort = c.localPort,
                remoteAddr = c.remoteAddr,
                remotePort = c.remotePort,
                protocol = c.protocol,
                family = c.family,
                direction = c.direction,
                bytesSent = c.bytesSent,
                bytesRecv = c.bytesRecv,
                tags = tags,
                timestampMs = now
            )
        }
        return QueuedInfraBatch(
            organizationId = organizationId,
            type = "connections",
            connections = entries
        )
    }

    suspend fun enqueueInfra(
        batch: QueuedInfraBatch,
        queueKey: String = INFRA_QUEUE_KEY
    ): Int {
        val count = batch.processes.size +
            batch.containers.size + batch.connections.size
        if (count == 0) return 0
        val message = json.encodeToString(batch)
        IngestionQueueClient.enqueue(IngestionPipeline.DD_INFRA, queueKey, message)
        logger.debug {
            "Enqueued $count DD infra entries (${batch.type}) " +
                "for org ${batch.organizationId}"
        }
        return count
    }

    suspend fun insertInfraBatch(batch: QueuedInfraBatch) {
        when (batch.type) {
            "processes" -> insertProcesses(batch)
            "containers" -> insertContainers(batch)
            "connections" -> insertConnections(batch)
        }
    }

    private suspend fun insertProcesses(batch: QueuedInfraBatch) {
        if (batch.processes.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val rows = batch.processes.joinToString(",\n") { p ->
            val tagsMap = p.tags.entries.joinToString(",") { (k, v) ->
                "'${escapeSql(k)}','${escapeSql(v)}'"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(p.host)}',
                ${p.pid},
                '${escapeSql(p.name)}',
                '${escapeSql(p.command)}',
                '${escapeSql(p.user)}',
                ${p.cpuPercent},
                ${p.memRss},
                ${p.memVms},
                '${escapeSql(p.state)}',
                ${p.threadCount},
                ${p.openFdCount},
                map($tagsMap),
                fromUnixTimestamp64Milli(${p.timestampMs})
            )"""
        }

        val insert = """
            INSERT INTO `$db`.processes (
                organization_id, host, pid, name, command,
                user, cpu_percent, mem_rss, mem_vms, state,
                thread_count, open_fd_count, tags, timestamp
            ) VALUES $rows
        """.trimIndent()

        executeInsert(insert, "DD processes")
    }

    private suspend fun insertContainers(batch: QueuedInfraBatch) {
        if (batch.containers.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val rows = batch.containers.joinToString(",\n") { c ->
            val tagsMap = c.tags.entries.joinToString(",") { (k, v) ->
                "'${escapeSql(k)}','${escapeSql(v)}'"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(c.host)}',
                '${escapeSql(c.containerId)}',
                '${escapeSql(c.name)}',
                '${escapeSql(c.image)}',
                '${escapeSql(c.state)}',
                ${c.cpuPercent},
                ${c.memUsage},
                ${c.memLimit},
                ${c.netRxBytes},
                ${c.netTxBytes},
                map($tagsMap),
                fromUnixTimestamp64Milli(${c.timestampMs})
            )"""
        }

        val insert = """
            INSERT INTO `$db`.containers (
                organization_id, host, container_id, name,
                image, state, cpu_percent, mem_usage, mem_limit,
                net_rx_bytes, net_tx_bytes, tags, timestamp
            ) VALUES $rows
        """.trimIndent()

        executeInsert(insert, "DD containers")
        insertContainerRollupsBestEffort(batch)
    }

    private suspend fun insertConnections(batch: QueuedInfraBatch) {
        if (batch.connections.isEmpty()) return
        val db = ClickHouseClient.getDatabase()

        val rows = batch.connections.joinToString(",\n") { c ->
            val tagsMap = c.tags.entries.joinToString(",") { (k, v) ->
                "'${escapeSql(k)}','${escapeSql(v)}'"
            }
            val proto = when (c.protocol.lowercase()) {
                "udp" -> "udp"
                "tcp6" -> "tcp6"
                "udp6" -> "udp6"
                else -> "tcp"
            }
            val fam = if (c.family == "IPv6") "IPv6" else "IPv4"
            """(
                ${batch.organizationId},
                '${escapeSql(c.host)}',
                ${c.pid},
                '${escapeSql(c.localAddr)}',
                ${c.localPort},
                '${escapeSql(c.remoteAddr)}',
                ${c.remotePort},
                '$proto',
                '$fam',
                '${escapeSql(c.direction)}',
                ${c.bytesSent},
                ${c.bytesRecv},
                map($tagsMap),
                fromUnixTimestamp64Milli(${c.timestampMs})
            )"""
        }

        val insert = """
            INSERT INTO `$db`.network_connections (
                organization_id, host, pid, local_addr,
                local_port, remote_addr, remote_port,
                protocol, family, direction,
                bytes_sent, bytes_recv, tags, timestamp
            ) VALUES $rows
        """.trimIndent()

        executeInsert(insert, "DD connections")
    }

    private suspend fun executeInsert(
        insert: String,
        label: String
    ) {
        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw IllegalStateException(
                "Failed to insert $label: ${errorBody.take(ERROR_BODY_MAX_LEN)}"
            )
        }
    }

    fun decodeInfraBatch(encoded: String): QueuedInfraBatch {
        return json.decodeFromString(encoded)
    }

    private suspend fun insertContainerRollupsBestEffort(batch: QueuedInfraBatch) {
        try {
            InfraTelemetryRollups.insertContainerRollups(
                batch.containers.map { container ->
                    InfraContainerRollupRow(
                        organizationId = batch.organizationId,
                        host = container.host,
                        containerId = container.containerId,
                        name = container.name,
                        image = container.image,
                        state = container.state,
                        cpuPercent = container.cpuPercent,
                        memUsage = container.memUsage,
                        memLimit = container.memLimit,
                        netRxBytes = container.netRxBytes,
                        netTxBytes = container.netTxBytes,
                        tags = container.tags,
                        timestampMs = container.timestampMs,
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Failed to write Datadog container rollups for org ${batch.organizationId} " +
                    "(${batch.containers.size} containers); raw containers were already written"
            }
        }
    }
}
