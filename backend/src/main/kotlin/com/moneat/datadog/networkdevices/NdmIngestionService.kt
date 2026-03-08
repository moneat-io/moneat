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

package com.moneat.datadog.networkdevices

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.datadog.models.DdNdmPayload
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private const val NDM_QUEUE_KEY = "moneat:dd:ndm:queue"

@Serializable
data class QueuedNdmBatch(
    @SerialName("organization_id") val organizationId: Int,
    @SerialName("batch_type") val batchType: String,
    val devices: List<QueuedNdmDeviceEntry> = emptyList(),
    val traps: List<QueuedNdmTrapEntry> = emptyList(),
    val flows: List<QueuedNdmFlowEntry> = emptyList(),
    val paths: List<QueuedNdmPathEntry> = emptyList(),
    val configs: List<QueuedNdmConfigEntry> = emptyList(),
)

@Serializable
data class QueuedNdmDeviceEntry(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("ip_address") val ipAddress: String = "",
    val hostname: String = "",
    val vendor: String = "",
    val model: String = "",
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("device_type") val deviceType: String = "",
    val status: String = "unknown",
    val reachability: String = "unknown",
    @SerialName("snmp_version") val snmpVersion: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedNdmTrapEntry(
    @SerialName("device_ip") val deviceIp: String = "",
    val oid: String = "",
    val severity: String = "info",
    val message: String = "",
    val variables: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedNdmFlowEntry(
    @SerialName("src_ip") val srcIp: String = "",
    @SerialName("dst_ip") val dstIp: String = "",
    @SerialName("src_port") val srcPort: Int = 0,
    @SerialName("dst_port") val dstPort: Int = 0,
    val protocol: String = "",
    val bytes: Long = 0,
    val packets: Long = 0,
    val direction: String = "",
    @SerialName("flow_type") val flowType: String = "netflow",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedNdmPathEntry(
    val source: String = "",
    val destination: String = "",
    val hops: List<String> = emptyList(),
    @SerialName("hop_rtts") val hopRtts: List<Double> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Serializable
data class QueuedNdmConfigEntry(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("config_type") val configType: String = "",
    val content: String = "",
    val tags: Map<String, String> = emptyMap(),
    @SerialName("timestamp_ms") val timestampMs: Long,
)

@Suppress("TooManyFunctions")
object NdmIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun enqueue(orgId: Int, payload: DdNdmPayload): Int {
        val now = System.currentTimeMillis()
        val batch = when (payload.type) {
            "ndm" -> {
                val entries = payload.devices.map { d ->
                    QueuedNdmDeviceEntry(
                        deviceId = d.deviceId, ipAddress = d.ipAddress,
                        hostname = d.hostname, vendor = d.vendor,
                        model = d.model, osVersion = d.osVersion,
                        deviceType = d.deviceType, status = d.status,
                        reachability = d.reachability,
                        snmpVersion = d.snmpVersion,
                        tags = parseDdTagList(d.tags), timestampMs = now,
                    )
                }
                QueuedNdmBatch(orgId, "devices", devices = entries)
            }
            "ndmtraps" -> {
                val entries = payload.traps.map { t ->
                    QueuedNdmTrapEntry(
                        deviceIp = t.deviceIp,
                        oid = t.oid,
                        severity = t.severity,
                        message = t.message,
                        variables = t.variables,
                        timestampMs = now,
                    )
                }
                QueuedNdmBatch(orgId, "traps", traps = entries)
            }
            "ndmflow" -> {
                val entries = payload.flows.map { f ->
                    QueuedNdmFlowEntry(
                        srcIp = f.srcIp, dstIp = f.dstIp,
                        srcPort = f.srcPort, dstPort = f.dstPort,
                        protocol = f.protocol, bytes = f.bytes,
                        packets = f.packets, direction = f.direction,
                        flowType = f.flowType,
                        tags = parseDdTagList(f.tags), timestampMs = now,
                    )
                }
                QueuedNdmBatch(orgId, "flows", flows = entries)
            }
            "netpath" -> {
                val entries = payload.paths.map { p ->
                    QueuedNdmPathEntry(
                        source = p.source,
                        destination = p.destination,
                        hops = p.hops,
                        hopRtts = p.hopRtts,
                        tags = parseDdTagList(p.tags),
                        timestampMs = now,
                    )
                }
                QueuedNdmBatch(orgId, "paths", paths = entries)
            }
            "ndmconfig" -> {
                val entries = payload.configs.map { c ->
                    QueuedNdmConfigEntry(
                        deviceId = c.deviceId,
                        configType = c.configType,
                        content = c.content,
                        tags = parseDdTagList(c.tags),
                        timestampMs = now,
                    )
                }
                QueuedNdmBatch(orgId, "configs", configs = entries)
            }
            else -> {
                logger.debug { "Unknown NDM type: ${payload.type}" }
                return 0
            }
        }

        val count = batch.devices.size + batch.traps.size +
            batch.flows.size + batch.paths.size + batch.configs.size
        if (count == 0) return 0
        RedisConfig.sync().lpush(NDM_QUEUE_KEY, json.encodeToString(batch))
        return count
    }

    suspend fun insertBatch(batch: QueuedNdmBatch) {
        when (batch.batchType) {
            "devices" -> insertDevices(batch)
            "traps" -> insertTraps(batch)
            "flows" -> insertFlows(batch)
            "paths" -> insertPaths(batch)
            "configs" -> insertConfigs(batch)
        }
    }

    private suspend fun insertDevices(batch: QueuedNdmBatch) {
        if (batch.devices.isEmpty()) return
        val rows = batch.devices.joinToString(",\n") { d ->
            """(
                ${batch.organizationId},
                '${escapeSql(d.deviceId)}', '${escapeSql(d.ipAddress)}',
                '${escapeSql(d.hostname)}', '${escapeSql(d.vendor)}',
                '${escapeSql(d.model)}', '${escapeSql(d.osVersion)}',
                '${escapeSql(d.deviceType)}', '${escapeSql(d.status)}',
                '${escapeSql(d.reachability)}',
                '${escapeSql(d.snmpVersion)}',
                ${mapToSqlMap(d.tags)},
                fromUnixTimestamp64Milli(${d.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.ndm_devices (
                organization_id, device_id, ip_address, hostname,
                vendor, model, os_version, device_type, status,
                reachability, snmp_version, tags, collected_at
            ) VALUES $rows""",
            "ndm_devices"
        )
    }

    private suspend fun insertTraps(batch: QueuedNdmBatch) {
        if (batch.traps.isEmpty()) return
        val rows = batch.traps.joinToString(",\n") { t ->
            """(
                ${batch.organizationId},
                '${escapeSql(t.deviceIp)}', '${escapeSql(t.oid)}',
                '${escapeSql(t.severity)}', '${escapeSql(t.message)}',
                ${mapToSqlMap(t.variables)},
                fromUnixTimestamp64Milli(${t.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.ndm_traps (
                organization_id, device_ip, oid, severity,
                message, variables, received_at
            ) VALUES $rows""",
            "ndm_traps"
        )
    }

    private suspend fun insertFlows(batch: QueuedNdmBatch) {
        if (batch.flows.isEmpty()) return
        val rows = batch.flows.joinToString(",\n") { f ->
            val ft = when (f.flowType) {
                "sflow" -> "sflow"
                "ipfix" -> "ipfix"
                else -> "netflow"
            }
            """(
                ${batch.organizationId},
                '${escapeSql(f.srcIp)}', '${escapeSql(f.dstIp)}',
                ${f.srcPort}, ${f.dstPort},
                '${escapeSql(f.protocol)}',
                ${f.bytes}, ${f.packets},
                '${escapeSql(f.direction)}', '$ft',
                ${mapToSqlMap(f.tags)},
                fromUnixTimestamp64Milli(${f.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.ndm_flows (
                organization_id, src_ip, dst_ip, src_port, dst_port,
                protocol, bytes, packets, direction, flow_type,
                tags, sampled_at
            ) VALUES $rows""",
            "ndm_flows"
        )
    }

    private suspend fun insertPaths(batch: QueuedNdmBatch) {
        if (batch.paths.isEmpty()) return
        val rows = batch.paths.joinToString(",\n") { p ->
            val hops = p.hops.joinToString(",") { "'${escapeSql(it)}'" }
            val rtts = p.hopRtts.joinToString(",")
            """(
                ${batch.organizationId},
                '${escapeSql(p.source)}', '${escapeSql(p.destination)}',
                [$hops], [$rtts],
                ${mapToSqlMap(p.tags)},
                fromUnixTimestamp64Milli(${p.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.network_paths (
                organization_id, source, destination,
                hops, hop_rtts, tags, collected_at
            ) VALUES $rows""",
            "network_paths"
        )
    }

    private suspend fun insertConfigs(batch: QueuedNdmBatch) {
        if (batch.configs.isEmpty()) return
        val rows = batch.configs.joinToString(",\n") { c ->
            """(
                ${batch.organizationId},
                '${escapeSql(c.deviceId)}', '${escapeSql(c.configType)}',
                '${escapeSql(c.content)}',
                ${mapToSqlMap(c.tags)},
                fromUnixTimestamp64Milli(${c.timestampMs})
            )"""
        }
        executeInsert(
            """INSERT INTO `$clickhouseDb`.ndm_configs (
                organization_id, device_id, config_type,
                content, tags, collected_at
            ) VALUES $rows""",
            "ndm_configs"
        )
    }

    fun decodeBatch(encoded: String): QueuedNdmBatch =
        json.decodeFromString(encoded)

    private suspend fun executeInsert(sql: String, label: String) {
        val response = ClickHouseClient.execute(sql)
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to insert DD $label")
        }
    }

    internal fun parseDdTagList(tags: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        tags.forEach { tag ->
            val colonIdx = tag.indexOf(':')
            if (colonIdx > 0) {
                result[tag.substring(0, colonIdx)] = tag.substring(colonIdx + 1)
            } else if (tag.isNotEmpty()) {
                result[tag] = ""
            }
        }
        return result
    }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
