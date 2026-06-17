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
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging

private val resourceCatalogQueryLogger = KotlinLogging.logger {}
private val resourceCatalogQueryJson = Json { ignoreUnknownKeys = true }

private const val RECENT_WINDOW_HOURS = 24
private const val NANOS_PER_MILLISECOND = 1_000_000
private const val SECONDS_PER_HOUR = 3600L
private const val MAX_TELEMETRY_HOURS = 24L * 31
private const val MAX_TELEMETRY_BUCKETS = 800
private const val MAX_EDGE_ROWS = 2000
private const val CHANGES_WINDOW_DAYS = 30
private const val MAX_CHANGE_ROWS = 2000

interface ResourceCatalogQueryClient {
    suspend fun listApmServices(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listContainers(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listKubernetesPods(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listNetworkDevices(organizationIds: List<Int>, limit: Int): List<JsonObject>
    suspend fun listCloudResources(organizationIds: List<Int>, limit: Int): List<JsonObject>

    /** Hourly latency (avg ms), span count, and error count for one APM service over a range. */
    suspend fun serviceTelemetrySeries(
        organizationIds: List<Int>,
        service: String,
        rangeSeconds: Long,
    ): List<JsonObject>

    /** Caller -> callee service-map edges over the recent window. */
    suspend fun serviceEdges(organizationIds: List<Int>): List<JsonObject>

    /** Deploy events (service, version, deploy_at, deployer) derived from versioned spans over the recent window. */
    suspend fun serviceDeployments(organizationIds: List<Int>): List<JsonObject>
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

    override suspend fun serviceTelemetrySeries(
        organizationIds: List<Int>,
        service: String,
        rangeSeconds: Long,
    ): List<JsonObject> {
        if (organizationIds.isEmpty() || service.isBlank()) return emptyList()
        return executeRows(serviceTelemetrySeriesSql(organizationIds, service, rangeSeconds))
    }

    override suspend fun serviceEdges(organizationIds: List<Int>): List<JsonObject> {
        if (organizationIds.isEmpty()) return emptyList()
        return executeRows(serviceEdgesSql(organizationIds))
    }

    override suspend fun serviceDeployments(organizationIds: List<Int>): List<JsonObject> {
        if (organizationIds.isEmpty()) return emptyList()
        return executeRows(serviceDeploymentsSql(organizationIds))
    }

    private suspend fun executeRows(sql: String): List<JsonObject> =
        suspendRunCatching {
            val response = ClickHouseClient.execute(sql)
            if (!response.status.isSuccess()) {
                resourceCatalogQueryLogger.warn { "Resource catalog ClickHouse query failed: ${response.status}" }
                return@suspendRunCatching emptyList()
            }
            parseJsonEachRow(response.bodyAsText())
        }.getOrElse { error ->
            resourceCatalogQueryLogger.warn(error) { "Resource catalog ClickHouse query failed" }
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

    private fun serviceTelemetrySeriesSql(
        organizationIds: List<Int>,
        service: String,
        rangeSeconds: Long,
    ): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        val hours = ((rangeSeconds + SECONDS_PER_HOUR - 1) / SECONDS_PER_HOUR).coerceIn(1, MAX_TELEMETRY_HOURS)
        return """
            SELECT
                toUnixTimestamp(bucket_start) AS ts,
                round(
                    if(sum(duration_count) = 0, 0, sum(duration_sum) / sum(duration_count) / $NANOS_PER_MILLISECOND),
                    3
                ) AS latency_ms,
                sum(span_count) AS span_count,
                sum(error_count) AS error_count
            FROM `$db`.apm_service_stats_hourly
            WHERE organization_id IN ($orgClause)
              AND service = '${escapeClickHouseLiteral(service)}'
              AND bucket_start >= now() - INTERVAL $hours HOUR
            GROUP BY bucket_start
            ORDER BY bucket_start ASC
            LIMIT $MAX_TELEMETRY_BUCKETS
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun serviceEdgesSql(organizationIds: List<Int>): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                from_service,
                to_service,
                sum(call_count) AS call_count,
                sum(error_count) AS error_count
            FROM `$db`.apm_service_edges_hourly
            WHERE organization_id IN ($orgClause)
              AND bucket_start >= now() - INTERVAL $RECENT_WINDOW_HOURS HOUR
              AND from_service != ''
              AND to_service != ''
            GROUP BY from_service, to_service
            ORDER BY call_count DESC
            LIMIT $MAX_EDGE_ROWS
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun serviceDeploymentsSql(organizationIds: List<Int>): String {
        val db = ClickHouseClient.getDatabase()
        val orgClause = orgIdClause(organizationIds)
        return """
            SELECT
                service,
                version,
                formatDateTime(min(start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') AS deploy_at,
                coalesce(
                    nullIf(argMaxIf(meta['deployment.user'], start, meta['deployment.user'] != ''), ''),
                    nullIf(argMaxIf(meta['git.commit.author.name'], start, meta['git.commit.author.name'] != ''), ''),
                    ''
                ) AS deployer
            FROM `$db`.apm_spans
            WHERE organization_id IN ($orgClause)
              AND start >= now() - INTERVAL $CHANGES_WINDOW_DAYS DAY
              AND service != ''
              AND version != ''
            GROUP BY service, version
            ORDER BY min(start) DESC
            LIMIT $MAX_CHANGE_ROWS
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun orgIdClause(organizationIds: List<Int>): String =
        organizationIds.joinToString(", ") { "toUInt64($it)" }

    private fun escapeClickHouseLiteral(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}

private fun parseJsonEachRow(body: String): List<JsonObject> =
    body
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            try {
                resourceCatalogQueryJson.parseToJsonElement(line).jsonObject
            } catch (error: SerializationException) {
                resourceCatalogQueryLogger.warn(error) { "Skipping invalid resource catalog ClickHouse row" }
                null
            } catch (error: IllegalArgumentException) {
                resourceCatalogQueryLogger.warn(error) { "Skipping invalid resource catalog ClickHouse row" }
                null
            }
        }
        .toList()
