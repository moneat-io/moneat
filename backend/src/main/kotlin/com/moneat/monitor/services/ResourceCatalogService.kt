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

package com.moneat.monitor.services

import com.moneat.config.ClickHouseClient
import com.moneat.monitor.models.CatalogMetaItem
import com.moneat.monitor.models.CatalogRelationship
import com.moneat.monitor.models.CatalogResource
import com.moneat.monitor.models.CatalogResourceTelemetry
import com.moneat.monitor.models.CatalogVulnerabilityCounts
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import kotlin.math.roundToInt
import kotlin.time.Instant

private val resourceCatalogLogger = KotlinLogging.logger {}
private val resourceCatalogJson = Json { ignoreUnknownKeys = true }

private const val DEFAULT_CATALOG_LIMIT = 200
private const val MAX_CATALOG_LIMIT = 500
private const val RECENT_WINDOW_HOURS = 24
private const val SERVICE_WARN_ERROR_RATE = 1.0
private const val SERVICE_CRITICAL_ERROR_RATE = 5.0
private const val PERCENT_SCALE = 100.0
private const val ZERO_PERCENT = 0
private const val FULL_PERCENT = 100
private const val BYTES_PER_KIB = 1024.0
private const val NANOS_PER_MILLISECOND = 1_000_000
private const val CATALOG_UNKNOWN_TIMESTAMP = "1970-01-01T00:00:00.000Z"

interface ResourceCatalogQueryClient {
    suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listNetworkDevices(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject>
}

class ClickHouseResourceCatalogQueryClient : ResourceCatalogQueryClient {
    override suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject> =
        executeRows(apmServicesSql(organizationIds, limit))

    override suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject> =
        executeRows(containersSql(organizationIds, limit))

    override suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject> =
        executeRows(kubernetesPodsSql(organizationIds, limit))

    override suspend fun listNetworkDevices(organizationIds: List<Int>, limit: Int): List<JsonObject> =
        executeRows(networkDevicesSql(organizationIds, limit))

    override suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject> =
        executeRows(cloudResourcesSql(organizationIds, limit))

    private suspend fun executeRows(sql: String): List<JsonObject> =
        suspendRunCatching {
            val response = ClickHouseClient.execute(sql)
            if (!response.status.isSuccess()) {
                resourceCatalogLogger.warn { "Resource catalog ClickHouse query failed: ${response.status}" }
                return@suspendRunCatching emptyList()
            }
            parseJsonEachRow(response.bodyAsText())
        }.getOrElse { error ->
            resourceCatalogLogger.warn(error) { "Resource catalog ClickHouse query failed" }
            emptyList()
        }

    private fun apmServicesSql(organizationIds: List<Int>, limit: Int): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                toString(organization_id) AS organization_id,
                service,
                env,
                span_count,
                error_count,
                latency_ms,
                round(if(span_count = 0, 0, error_count / span_count * 100), 2) AS error_rate_pct,
                formatDateTime(last_bucket_start, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS last_seen
            FROM (
                SELECT
                    organization_id,
                    service,
                    argMax(env, bucket_start) AS env,
                    sum(span_count) AS span_count,
                    sum(error_count) AS error_count,
                    round(
                        if(sum(duration_count) = 0, 0, sum(duration_sum) / sum(duration_count) / $NANOS_PER_MILLISECOND)
                    ) AS latency_ms,
                    max(bucket_start) AS last_bucket_start
                FROM `$db`.apm_service_stats_hourly
                WHERE organization_id IN ($orgClause)
                  AND bucket_start >= now() - INTERVAL $RECENT_WINDOW_HOURS HOUR
                  AND service != ''
                GROUP BY organization_id, service
            )
            ORDER BY last_bucket_start DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun containersSql(organizationIds: List<Int>, limit: Int): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                toString(organization_id) AS organization_id,
                toString(cityHash64(organization_id, host, container_id)) AS id,
                host,
                container_id,
                argMax(name, timestamp) AS name,
                argMax(image, timestamp) AS image,
                argMax(state, timestamp) AS state,
                argMax(cpu_percent, timestamp) AS cpu_percent,
                argMax(mem_usage, timestamp) AS mem_usage,
                argMax(mem_limit, timestamp) AS mem_limit,
                argMax(tags, timestamp) AS tags,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS last_seen
            FROM `$db`.containers_latest_by_host
            WHERE organization_id IN ($orgClause)
              AND timestamp >= now64(3) - INTERVAL $RECENT_WINDOW_HOURS HOUR
              AND container_id != ''
            GROUP BY organization_id, host, container_id
            ORDER BY max(timestamp) DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun kubernetesPodsSql(organizationIds: List<Int>, limit: Int): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                toString(organization_id) AS organization_id,
                uid AS id,
                argMax(namespace, collected_at) AS namespace,
                argMax(name, collected_at) AS name,
                argMax(cluster_name, collected_at) AS cluster_name,
                argMax(status, collected_at) AS status,
                argMax(tags, collected_at) AS tags,
                argMax(labels, collected_at) AS labels,
                formatDateTime(min(creation_timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS first_seen,
                formatDateTime(max(collected_at), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS last_seen
            FROM `$db`.k8s_resources
            WHERE organization_id IN ($orgClause)
              AND resource_type = 'Pod'
            GROUP BY organization_id, uid
            ORDER BY max(collected_at) DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun networkDevicesSql(organizationIds: List<Int>, limit: Int): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                toString(organization_id) AS organization_id,
                device_id AS id,
                argMax(hostname, collected_at) AS hostname,
                argMax(ip_address, collected_at) AS ip_address,
                argMax(vendor, collected_at) AS vendor,
                argMax(model, collected_at) AS model,
                argMax(os_version, collected_at) AS os_version,
                argMax(device_type, collected_at) AS device_type,
                argMax(status, collected_at) AS status,
                argMax(reachability, collected_at) AS reachability,
                argMax(tags, collected_at) AS tags,
                formatDateTime(max(collected_at), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS last_seen
            FROM `$db`.ndm_devices
            WHERE organization_id IN ($orgClause)
              AND device_id != ''
            GROUP BY organization_id, device_id
            ORDER BY max(collected_at) DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun cloudResourcesSql(organizationIds: List<Int>, limit: Int): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                toString(organization_id) AS organization_id,
                resource_id AS id,
                argMax(name, collected_at) AS name,
                argMax(resource_type, collected_at) AS resource_type,
                argMax(provider, collected_at) AS provider,
                argMax(account, collected_at) AS account,
                argMax(region, collected_at) AS region,
                argMax(health, collected_at) AS health,
                argMax(tags, collected_at) AS tags,
                argMax(metadata, collected_at) AS metadata,
                argMax(cpu_percent, collected_at) AS cpu_percent,
                argMax(mem_percent, collected_at) AS mem_percent,
                argMax(monthly_usd, collected_at) AS monthly_usd,
                argMax(cost_trend_pct, collected_at) AS cost_trend_pct,
                formatDateTime(min(first_seen), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS first_seen,
                formatDateTime(max(last_seen), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS last_seen
            FROM `$db`.cloud_resources_latest
            WHERE organization_id IN ($orgClause)
              AND resource_id != ''
            GROUP BY organization_id, cloud_source_id, resource_id
            ORDER BY max(collected_at) DESC
            LIMIT $limit
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun orgIdClause(organizationIds: List<Int>): String =
        organizationIds.joinToString(", ") { "toUInt64($it)" }
}

class ResourceCatalogService(
    private val monitorService: MonitorService,
    private val queryClient: ResourceCatalogQueryClient = ClickHouseResourceCatalogQueryClient(),
) {
    suspend fun listResources(
        organizationIds: List<Int>,
        limit: Int = DEFAULT_CATALOG_LIMIT,
    ): List<CatalogResource> {
        if (organizationIds.isEmpty()) return emptyList()

        val boundedLimit = limit.coerceIn(1, MAX_CATALOG_LIMIT)
        val hosts = organizationIds.flatMap { monitorService.listHosts(it) }
        val metricsByHost = latestMetricsByHost(organizationIds, hosts)

        return buildList {
            addAll(queryClient.listApmServices(organizationIds, boundedLimit).mapNotNull(::serviceResource))
            addAll(hosts.map { host -> hostResource(host, metricsByHost[host.id]) })
            addAll(queryClient.listContainers(organizationIds, boundedLimit).mapNotNull(::containerResource))
            addAll(queryClient.listKubernetesPods(organizationIds, boundedLimit).mapNotNull(::podResource))
            addAll(queryClient.listNetworkDevices(organizationIds, boundedLimit).mapNotNull(::networkDeviceResource))
            addAll(queryClient.listCloudResources(organizationIds, boundedLimit).mapNotNull(::cloudResource))
        }.take(boundedLimit)
    }

    private suspend fun latestMetricsByHost(
        organizationIds: List<Int>,
        hosts: List<HostData>,
    ): Map<Int, LatestMetrics?> =
        organizationIds
            .flatMap { organizationId ->
                val orgHosts = hosts.filter { it.organizationId == organizationId }
                if (orgHosts.isEmpty()) {
                    emptyList()
                } else {
                    monitorService
                        .getLatestMetricsForHosts(orgHosts.map { it.id }, organizationId)
                        .entries
                        .map { it.toPair() }
                }
            }
            .toMap()

    private fun hostResource(host: HostData, metrics: LatestMetrics?): CatalogResource {
        val tags = listOf("source:host-agent")
        return CatalogResource(
            id = stableId("host", "${host.organizationId}:${host.id}"),
            name = host.displayName ?: host.hostname,
            kind = "host",
            health = host.status.toCatalogHealth(),
            environment = "prod",
            region = "unknown",
            cloud = "on-prem",
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(
                cpuPct = metrics?.cpuPercent.toPct(),
                memPct = metrics?.memPercent.toPct()
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = hostMetadata(host),
            firstSeen = host.firstSeenAt.toCatalogIso(),
            lastChange = (host.lastSeenAt ?: host.createdAt).toCatalogIso()
        )
    }

    private fun serviceResource(row: JsonObject): CatalogResource? {
        val name = row.s("service") ?: return null
        val errorRate = row.d("error_rate_pct") ?: 0.0
        val spanCount = row.i("span_count")
        return CatalogResource(
            id = organizationScopedId("service", row.s("organization_id"), name),
            name = name,
            kind = "service",
            health = serviceHealth(spanCount, errorRate),
            environment = row.s("env").toEnvironment(),
            region = "unknown",
            cloud = "on-prem",
            owner = null,
            tags = listOf("source:apm"),
            telemetry = CatalogResourceTelemetry(
                cpuPct = 0,
                memPct = 0,
                latencyMs = row.i("latency_ms"),
                errorRatePct = errorRate,
                throughput = row.s("span_count")?.let { "$it spans/24h" }
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("Spans", row.s("span_count")),
                meta("Errors", row.s("error_count"))
            ),
            firstSeen = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun containerResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: row.s("container_id") ?: return null
        val tags = row.tags() + "source:container"
        val host = row.s("host")
        val memLimit = row.d("mem_limit") ?: 0.0
        val memUsage = row.d("mem_usage") ?: 0.0
        val memPct = if (memLimit > 0) (memUsage / memLimit * PERCENT_SCALE).roundToInt() else 0
        return CatalogResource(
            id = organizationScopedId("container", row.s("organization_id"), id),
            name = row.s("name") ?: row.s("image") ?: id,
            kind = "container",
            health = row.s("state").containerHealth(),
            environment = tags.envFromTags(),
            region = "unknown",
            cloud = tags.cloudFromTags(),
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(
                cpuPct = row.d("cpu_percent").toPct(),
                memPct = memPct.coerceIn(ZERO_PERCENT, FULL_PERCENT)
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = host?.let {
                listOf(CatalogRelationship("Runs on", it, "host", "unknown"))
            } ?: emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("Image", row.s("image")),
                meta("State", row.s("state")),
                meta("Host", host)
            ),
            firstSeen = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun podResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: return null
        val tags = row.tags() + row.labels().map { "label:$it" } + "source:kubernetes"
        return CatalogResource(
            id = organizationScopedId("pod", row.s("organization_id"), id),
            name = row.s("name") ?: id,
            kind = "pod",
            health = row.s("status").podHealth(),
            environment = tags.envFromTags(),
            region = "unknown",
            cloud = tags.cloudFromTags(),
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(cpuPct = 0, memPct = 0),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("Namespace", row.s("namespace")),
                meta("Cluster", row.s("cluster_name")),
                meta("Status", row.s("status"))
            ),
            firstSeen = row.s("first_seen") ?: row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun networkDeviceResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: return null
        val tags = row.tags() + "source:network-device"
        return CatalogResource(
            id = organizationScopedId("network", row.s("organization_id"), id),
            name = row.s("hostname") ?: row.s("ip_address") ?: id,
            kind = "network-device",
            health = row.s("reachability")?.toCatalogHealth() ?: row.s("status").toCatalogHealth(),
            environment = tags.envFromTags(),
            region = "unknown",
            cloud = "on-prem",
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(cpuPct = 0, memPct = 0),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = 0.0,
            costTrendPct = 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = listOfNotNull(
                meta("IP", row.s("ip_address")),
                meta("Vendor", row.s("vendor")),
                meta("Model", row.s("model")),
                meta("OS", row.s("os_version")),
                meta("Type", row.s("device_type"))
            ),
            firstSeen = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun cloudResource(row: JsonObject): CatalogResource? {
        val id = row.s("id") ?: return null
        val cloud = row.s("provider").toCloudProvider()
        val tags = row.tags() + listOf("source:cloud", "cloud:$cloud")
        return CatalogResource(
            id = organizationScopedId("cloud", row.s("organization_id"), id),
            name = row.s("name") ?: id,
            kind = "cloud",
            health = row.s("health").toCatalogHealth(),
            environment = tags.envFromTags(),
            region = row.s("region") ?: "global",
            cloud = cloud,
            owner = null,
            tags = tags,
            telemetry = CatalogResourceTelemetry(
                cpuPct = row.d("cpu_percent").toPct(),
                memPct = row.d("mem_percent").toPct()
            ),
            vulns = CatalogVulnerabilityCounts(),
            sbomComponents = 0,
            posture = emptyList(),
            monthlyUsd = row.d("monthly_usd") ?: 0.0,
            costTrendPct = row.d("cost_trend_pct") ?: 0.0,
            costBreakdown = emptyList(),
            relationships = emptyList(),
            changes = emptyList(),
            metadata = cloudMetadata(row),
            firstSeen = row.s("first_seen") ?: row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP,
            lastChange = row.s("last_seen") ?: CATALOG_UNKNOWN_TIMESTAMP
        )
    }

    private fun hostMetadata(host: HostData): List<CatalogMetaItem> =
        listOfNotNull(
            meta("Hostname", host.hostname),
            meta("OS", host.os),
            meta("Platform", host.platform),
            meta("Architecture", host.arch),
            meta("Processor", host.processor),
            meta("CPU cores", host.cpuCores?.toString()),
            meta("Memory", host.memoryTotalKb?.let { formatKiB(it) }),
            meta("Agent", host.agentVersion)
        )

    private fun cloudMetadata(row: JsonObject): List<CatalogMetaItem> =
        listOfNotNull(
            meta("Account", row.s("account")),
            meta("Type", row.s("resource_type"))
        ) + row.metadataItems()

    private fun serviceHealth(spanCount: Int, errorRatePct: Double): String =
        when {
            spanCount <= 0 -> "unknown"
            errorRatePct >= SERVICE_CRITICAL_ERROR_RATE -> "critical"
            errorRatePct >= SERVICE_WARN_ERROR_RATE -> "warn"
            else -> "healthy"
        }
}

private fun parseJsonEachRow(body: String): List<JsonObject> =
    body
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            try {
                resourceCatalogJson.parseToJsonElement(line).jsonObject
            } catch (error: SerializationException) {
                resourceCatalogLogger.warn(error) { "Skipping invalid resource catalog ClickHouse row" }
                null
            } catch (error: IllegalArgumentException) {
                resourceCatalogLogger.warn(error) { "Skipping invalid resource catalog ClickHouse row" }
                null
            }
        }
        .toList()

private fun JsonObject.s(key: String): String? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.d(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.i(key: String): Int =
    d(key)?.roundToInt() ?: s(key)?.toIntOrNull() ?: 0

private fun JsonObject.tags(): List<String> =
    mapEntries("tags")

private fun JsonObject.labels(): List<String> =
    mapEntries("labels")

private fun JsonObject.metadataItems(): List<CatalogMetaItem> {
    val obj = this["metadata"]?.jsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (entryKey, value) ->
        val entryValue = value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        entryValue?.let { CatalogMetaItem(entryKey, it) }
    }
}

private fun JsonObject.mapEntries(key: String): List<String> {
    val obj = this[key]?.jsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (entryKey, value) ->
        val entryValue = value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        entryValue?.let { "$entryKey:$it" }
    }
}

private fun String?.toCatalogHealth(): String =
    when (this?.lowercase()) {
        "online", "up", "ok", "healthy", "reachable", "success" -> "healthy"
        "warning", "warn", "degraded" -> "warn"
        "offline", "down", "critical", "error", "failed", "unreachable" -> "critical"
        else -> "unknown"
    }

private fun String?.containerHealth(): String =
    when (this?.lowercase()) {
        "running", "healthy", "up" -> "healthy"
        "restarting", "paused", "created" -> "warn"
        "exited", "dead", "error", "failed" -> "critical"
        else -> "unknown"
    }

private fun String?.podHealth(): String =
    when (this?.lowercase()) {
        "running", "succeeded" -> "healthy"
        "pending", "unknown" -> "warn"
        "failed" -> "critical"
        else -> "unknown"
    }

private fun String?.toEnvironment(): String =
    when (this?.lowercase()) {
        "prod", "production" -> "prod"
        "stage", "staging" -> "staging"
        "dev", "development", "test", "testing", "local" -> "dev"
        else -> "prod"
    }

private fun List<String>.envFromTags(): String {
    val raw = firstTagValue("env")
        ?: firstTagValue("environment")
        ?: firstTagValue("deployment.environment")
    return raw.toEnvironment()
}

private fun List<String>.cloudFromTags(): String =
    when ((firstTagValue("cloud") ?: firstTagValue("cloud.provider"))?.lowercase()) {
        "aws" -> "aws"
        "gcp", "google_cloud", "google-cloud" -> "gcp"
        "azure" -> "azure"
        else -> "on-prem"
    }

private fun String?.toCloudProvider(): String =
    when (this?.lowercase()) {
        "aws" -> "aws"
        "gcp", "google_cloud", "google-cloud" -> "gcp"
        "azure" -> "azure"
        else -> "on-prem"
    }

private fun List<String>.firstTagValue(key: String): String? =
    firstOrNull { it.startsWith("$key:") }?.substringAfter(":")?.takeIf { it.isNotBlank() }

private fun Float?.toPct(): Int =
    this?.roundToInt()?.coerceIn(ZERO_PERCENT, FULL_PERCENT) ?: ZERO_PERCENT

private fun Double?.toPct(): Int =
    this?.roundToInt()?.coerceIn(ZERO_PERCENT, FULL_PERCENT) ?: ZERO_PERCENT

private fun Instant.toCatalogIso(): String {
    val text = toString()
    return if (text.endsWith("Z") && !text.contains(".")) {
        text.removeSuffix("Z") + ".000Z"
    } else {
        text
    }
}

private fun stableId(prefix: String, value: String): String {
    val slug = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_.:-]+"), "-")
        .trim('-')
        .ifBlank { "unknown" }
    return "$prefix:$slug"
}

private fun organizationScopedId(prefix: String, organizationId: String?, value: String): String =
    stableId(prefix, "${organizationId ?: "unknown-org"}:$value")

private fun meta(label: String, value: String?): CatalogMetaItem? =
    value?.takeIf { it.isNotBlank() }?.let { CatalogMetaItem(label, it) }

private fun formatKiB(value: Long): String {
    val mib = value / BYTES_PER_KIB
    return if (mib >= BYTES_PER_KIB) {
        "%.1f GiB".format(mib / BYTES_PER_KIB)
    } else {
        "%.0f MiB".format(mib)
    }
}
