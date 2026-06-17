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

package com.moneat.apm.services

import com.moneat.apm.models.ApmCatalogSummary
import com.moneat.apm.models.ApmDependencyRow
import com.moneat.apm.models.ApmDeploymentRow
import com.moneat.apm.models.ApmDistBar
import com.moneat.apm.models.ApmDistMarker
import com.moneat.apm.models.ApmEnvPill
import com.moneat.apm.models.ApmErrorBar
import com.moneat.apm.models.ApmErrorRow
import com.moneat.apm.models.ApmGaugeRow
import com.moneat.apm.models.ApmKpiSpec
import com.moneat.apm.models.ApmLatencyPoint
import com.moneat.apm.models.ApmPodRow
import com.moneat.apm.models.ApmResourceDetail
import com.moneat.apm.models.ApmResourceExemplar
import com.moneat.apm.models.ApmResourceRow
import com.moneat.apm.models.ApmServiceCatalogResponse
import com.moneat.apm.models.ApmServiceCatalogRow
import com.moneat.apm.models.ApmServiceDetail
import com.moneat.apm.models.ApmStatDeltaSpec
import com.moneat.apm.models.ApmThroughputPoint
import com.moneat.apm.models.ApmTraceRow
import com.moneat.apm.models.ApmWaterfallRow
import com.moneat.config.ClickHouseClient
import com.moneat.datadog.services.DdApmQueryTimeRange
import com.moneat.datadog.services.defaultApmQueryTimeRange
import com.moneat.utils.ClickHouseQueryParameters
import com.moneat.utils.ClickHouseQuerySpec
import com.moneat.utils.ClickHouseQueryUtils
import io.sentry.ISpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_SERVICE_LIMIT = 100
private const val MAX_SERVICE_LIMIT = 200
private const val EXACT_SERVICE_LIMIT = 1
private const val DEFAULT_SERIES_POINT_COUNT = 12
private const val DEFAULT_ERROR_BAR_COUNT = 16
private const val TOP_RESOURCE_LIMIT = 5
private const val WATERFALL_ROW_LIMIT = 20
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
private const val MILLISECONDS_PER_SECOND = 1_000L
private const val COMPACT_UNIT_THRESHOLD = 1_000.0
private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_DAY = 86_400
private const val ALERT_ERROR_RATE = 0.02
private const val WARN_ERROR_RATE = 0.005
private const val MIN_VISIBLE_ERROR_RATE = 0.001
private const val ALERT_LATENCY_MS = 750L
private const val WARN_LATENCY_MS = 300L
private const val WARN_RESOURCE_TIME_PCT = 18
private const val ERROR_BAR_SCALE = 4_000
private const val ERROR_BAR_MIN_PCT = 6
private const val ERROR_BAR_MAX_PCT = 92
private const val ERROR_RATE_MIN_BAR_PCT = 1
private const val PERCENT_MIN = 0
private const val PERCENT_MAX = 100
private const val GAUGE_MIN_PCT = 2
private const val PERCENT_SCALE = 100.0
private const val APDEX_MAX_SCORE = 1.0
private const val APDEX_TARGET_MS = 300L
private const val APDEX_TOLERATING_MULTIPLIER = 4
private const val APDEX_SUCCESS_SCORE = 0.9
private const val APDEX_WARNING_SCORE = 0.75
private const val LATENCY_DISTRIBUTION_BAR_COUNT = 24
private const val LATENCY_DISTRIBUTION_MIN = 6
private const val LATENCY_DISTRIBUTION_GOOD_END = 10
private const val LATENCY_DISTRIBUTION_WARN_END = 16
private const val LATENCY_MARKER_MIN_PCT = 4
private const val LATENCY_MARKER_MAX_PCT = 86
private const val LATENCY_AXIS_P50_DIVISOR = 2
private const val LATENCY_AXIS_P95_UPPER_NUMERATOR = 3
private const val LATENCY_AXIS_P95_UPPER_DENOMINATOR = 2
private const val LATENCY_AXIS_P99_UPPER_NUMERATOR = 13
private const val LATENCY_AXIS_P99_UPPER_DENOMINATOR = 10
private const val FALLBACK_P90_NUMERATOR = 9
private const val FALLBACK_P90_DENOMINATOR = 10
private const val MIN_HTTP_METHOD_LENGTH = 2
private const val SYNTHETIC_METHOD_MAX_LENGTH = 8
private const val ENV_CHART_BUCKET_COUNT = 10
private const val ENV_CHART_START = 1
private const val SPARK_MIN = 4
private const val SPARK_MAX = 20
private const val LATENCY_THRESHOLD_NUMERATOR = 6
private const val LATENCY_THRESHOLD_DENOMINATOR = 5
private const val P99_DURATION_MS = 1_000L
private const val P95_PLUS_DURATION_MS = 500L
private const val P95_DURATION_MS = 250L
private const val HTTP_SERVER_ERROR_STATUS = 500
private const val HEALTH_RESOURCE = "/health"
private const val UNKNOWN_VERSION = "unknown"
private const val UNASSIGNED_TEAM = "unassigned"
private const val DEFAULT_DEPLOY_AT = ""
private const val ROOT_PARENT_ID = ""
private const val WATERFALL_MIN_WIDTH = 2
private const val CHILD_SPAN_INDENT = 1
private const val DEPLOYMENT_ROW_LIMIT = 8
private const val CONTAINER_ROW_LIMIT = 20
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
private const val BYTES_PER_GIBIBYTE = 1_073_741_824.0
private const val INFRA_CPU_WARN_PCT = 70
private const val INFRA_CPU_ALERT_PCT = 85
private const val MEMORY_WARN_RATIO = 0.8
private const val DELTA_FLAT_THRESHOLD = 0.05
private const val FILTERS_PLACEHOLDER = "__APM_CLICKHOUSE_FILTERS__"

data class ApmServiceQuery(
    val env: String? = null,
    val source: String? = null,
    val search: String? = null,
    val limit: Int = DEFAULT_SERVICE_LIMIT,
    val offset: Int = 0,
    val timeRange: DdApmQueryTimeRange = defaultApmQueryTimeRange,
)

private data class ServiceAggregate(
    val name: String,
    val type: String,
    val envs: List<String>,
    val sources: List<String>,
    val spanCount: Long,
    val errorCount: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val version: String,
    val lastSeen: String,
    val apdexSatisfied: Long,
    val apdexTolerating: Long,
    val team: String,
    val language: String,
)

private data class ResourceAggregate(
    val resource: String,
    val name: String,
    val type: String,
    val spanCount: Long,
    val errorCount: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
)

private data class SpanAggregate(
    val spanId: String,
    val parentId: String,
    val name: String,
    val service: String,
    val resource: String,
    val type: String,
    val startNs: Long,
    val durationMs: Long,
    val error: Boolean,
    val statusCode: Int,
)

private data class PreviousWindowAggregate(
    val spanCount: Long,
    val errorCount: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val apdexSatisfied: Long,
    val apdexTolerating: Long,
)

private data class DeploymentAggregate(
    val version: String,
    val firstSeen: String,
    val lastSeen: String,
    val marker: String,
    val spanCount: Long,
    val errorCount: Long,
    val p95Ms: Long,
    val deployer: String,
)

private data class DeploymentSeries(
    val rows: List<ApmDeploymentRow>,
    val deployAt: String,
)

private data class ContainerAggregate(
    val pod: String,
    val node: String,
    val cpu: Int,
    val memUsage: Long,
    val memLimit: Long,
    val restarts: Int,
    val state: String,
)

private data class SpanFilterSet(
    val filters: MutableList<String>,
    val params: ClickHouseQueryParameters,
)

object ApmServiceCatalogService {
    private val json = Json { ignoreUnknownKeys = true }
    private val clickhouseDb: String
        get() = ClickHouseClient.getDatabase()

    suspend fun listServices(
        organizationId: Int,
        query: ApmServiceQuery = ApmServiceQuery(),
        parentSpan: ISpan? = null,
    ): ApmServiceCatalogResponse {
        val aggregates = serviceAggregates(organizationId, null, query, parentSpan)
        val sparklineByService = serviceSparklineSeries(
            organizationId = organizationId,
            services = aggregates.map { aggregate -> aggregate.name },
            query = query,
            parentSpan = parentSpan,
        )
        val services = aggregates.map { aggregate ->
            aggregate.toCatalogRow(query.timeRange, sparklineByService[aggregate.name])
        }
        return ApmServiceCatalogResponse(
            services = services,
            summary = catalogSummary(services),
        )
    }

    suspend fun getServiceDetail(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery = ApmServiceQuery(),
        parentSpan: ISpan? = null,
    ): ApmServiceDetail? = coroutineScope {
        val aggregate = serviceAggregates(organizationId, serviceName, query, parentSpan).firstOrNull()
            ?: return@coroutineScope null
        val resourcesDeferred = async(Dispatchers.IO) {
            resourceRows(organizationId, serviceName, query, parentSpan)
        }
        val latencyDeferred = async(Dispatchers.IO) {
            latencySeries(organizationId, serviceName, null, query, parentSpan)
        }
        val throughputDeferred = async(Dispatchers.IO) {
            throughputSeries(organizationId, serviceName, null, query, parentSpan)
        }
        val tracesDeferred = async(Dispatchers.IO) {
            traceRows(organizationId, serviceName, null, query, parentSpan)
        }
        val errorsDeferred = async(Dispatchers.IO) {
            errorRows(organizationId, serviceName, null, query, parentSpan)
        }
        val previousDeferred = async(Dispatchers.IO) {
            previousServiceAggregate(organizationId, serviceName, query, parentSpan)
        }
        val deploymentsDeferred = async(Dispatchers.IO) {
            deploymentSeries(organizationId, serviceName, query, parentSpan)
        }
        val containersDeferred = async(Dispatchers.IO) {
            containerRows(organizationId, serviceName, parentSpan)
        }
        val upstreamDeferred = async(Dispatchers.IO) {
            serviceEdges(organizationId, serviceName, incoming = true, query, parentSpan)
        }
        val downstreamDeferred = async(Dispatchers.IO) {
            serviceEdges(organizationId, serviceName, incoming = false, query, parentSpan)
        }
        val catalogRow = aggregate.toCatalogRow(query.timeRange)
        val resources = resourcesDeferred.await()
        val latency = latencyDeferred.await()
        val throughput = throughputDeferred.await()
        val upstream = upstreamDeferred.await()
        val downstream = downstreamDeferred.await()
        val deployments = deploymentsDeferred.await()
        val containers = containersDeferred.await()

        ApmServiceDetail(
            name = aggregate.name,
            type = catalogRow.type,
            status = catalogRow.status,
            runtime = runtimeLabel(catalogRow.type, aggregate.sources),
            team = catalogRow.team,
            version = aggregate.version,
            deployedAgo = aggregate.lastDeployLabel(),
            sources = catalogRow.sources,
            kpis = serviceKpis(catalogRow, previousDeferred.await(), query.timeRange),
            latency = latency.withFallback(aggregate),
            latencyThresholdMs = latencyThreshold(aggregate.p95Ms),
            latencyThresholdLabel = "p95 > ${formatMs(latencyThreshold(aggregate.p95Ms))}",
            deployAt = deployments.deployAt,
            throughput = throughput.withFallback(catalogRow.rps),
            errorBars = errorBars(throughput, aggregate),
            p95ByResource = p95ByResource(resources),
            resources = resources,
            upstream = upstream,
            downstream = downstream,
            depInsight = dependencyInsight(downstream),
            deployments = deployments.rows,
            podMemory = podMemoryRows(containers),
            pods = containers.map { container -> container.toPodRow() },
            errors = errorsDeferred.await(),
            traces = tracesDeferred.await(),
        )
    }

    suspend fun getResourceDetail(
        organizationId: Int,
        serviceName: String,
        resourceSlug: String,
        query: ApmServiceQuery = ApmServiceQuery(),
        parentSpan: ISpan? = null,
    ): ApmResourceDetail? = coroutineScope {
        val aggregateDeferred = async(Dispatchers.IO) {
            serviceAggregates(organizationId, serviceName, query, parentSpan).firstOrNull()
        }
        val resourcesDeferred = async(Dispatchers.IO) { resourceRows(organizationId, serviceName, query, parentSpan) }
        val aggregate = aggregateDeferred.await() ?: return@coroutineScope null
        val resource = resourcesDeferred.await().find { row -> row.slug == resourceSlug }
            ?: return@coroutineScope null
        val rawResource = rawResourceValue(resource)
        val latencyDeferred = async(Dispatchers.IO) {
            latencySeries(organizationId, serviceName, rawResource, query, parentSpan)
        }
        val throughputDeferred = async(Dispatchers.IO) {
            throughputSeries(organizationId, serviceName, rawResource, query, parentSpan)
        }
        val tracesDeferred = async(Dispatchers.IO) {
            traceRows(organizationId, serviceName, rawResource, query, parentSpan)
        }
        val errorsDeferred = async(Dispatchers.IO) {
            errorRows(organizationId, serviceName, rawResource, query, parentSpan)
        }
        val previousDeferred = async(Dispatchers.IO) {
            previousResourceAggregate(organizationId, serviceName, rawResource, query, parentSpan)
        }
        val deploymentDeferred = async(Dispatchers.IO) {
            deploymentSeries(organizationId, serviceName, query, parentSpan)
        }
        val distributionDeferred = async(Dispatchers.IO) {
            latencyDistribution(organizationId, serviceName, rawResource, resource.p99Ms, query, parentSpan)
        }
        val downstreamDeferred = async(Dispatchers.IO) {
            serviceEdges(organizationId, serviceName, incoming = false, query, parentSpan)
        }
        val traces = tracesDeferred.await()
        val spans = traces.firstOrNull()?.traceId
            ?.let { traceId -> exemplarSpans(organizationId, traceId, parentSpan) }
            .orEmpty()
        val downstream = downstreamDeferred.await()
        val exemplar = exemplar(resource, traces.firstOrNull(), spans)
        val deployments = deploymentDeferred.await()

        ApmResourceDetail(
            serviceName = aggregate.name,
            method = resource.method,
            path = resource.name,
            status = resource.status,
            kind = resourceKind(resource.method),
            topDependency = downstream.firstOrNull()?.name ?: aggregate.name,
            kpis = resourceKpis(resource, previousDeferred.await(), query.timeRange),
            latency = latencyDeferred.await().withFallback(resource),
            latencyThresholdMs = latencyThreshold(resource.p95Ms),
            latencyThresholdLabel = "p95 > ${formatMs(latencyThreshold(resource.p95Ms))}",
            deployAt = deployments.deployAt,
            throughput = throughputDeferred.await().withFallback(resource.rps),
            distribution = distributionDeferred.await(),
            distMarkers = latencyMarkers(resource),
            distAxis = latencyAxis(resource),
            whereTimeSpent = whereTimeSpent(resource, spans),
            whereInsight = whereInsight(resource, downstream.firstOrNull()),
            exemplar = exemplar,
            slowTraces = traces,
            errors = errorsDeferred.await(),
        )
    }

    private suspend fun serviceAggregates(
        organizationId: Int,
        serviceName: String?,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ServiceAggregate> {
        val querySpec = serviceAggregateSql(organizationId, serviceName, query)
        val rows = jsonRows(querySpec, parentSpan)
        if (rows.isEmpty()) return emptyList()
        return rows.map { row ->
            ServiceAggregate(
                name = row.stringValue("name"),
                type = inferServiceType(row.stringValue("name"), row.stringValue("type")),
                envs = row.stringList("envs"),
                sources = row.stringList("sources"),
                spanCount = row.longValue("span_count"),
                errorCount = row.longValue("error_count"),
                p50Ms = nanosToMillis(row.longValue("p50_ns")),
                p95Ms = nanosToMillis(row.longValue("p95_ns")),
                p99Ms = nanosToMillis(row.longValue("p99_ns")),
                version = row.stringValue("version").ifBlank { UNKNOWN_VERSION },
                lastSeen = row.stringValue("last_seen"),
                apdexSatisfied = row.longValue("apdex_satisfied"),
                apdexTolerating = row.longValue("apdex_tolerating"),
                team = row.stringValue("team"),
                language = row.stringValue("language"),
            )
        }
    }

    private fun serviceAggregateSql(
        organizationId: Int,
        serviceName: String?,
        query: ApmServiceQuery,
    ): ClickHouseQuerySpec {
        val filterSet = spanFilters(organizationId, query, serviceName, null)
        val filters = filterSet.filters
        val limit = query.limit.coerceIn(1, MAX_SERVICE_LIMIT)
        val apdexTargetNs = APDEX_TARGET_MS * NANOSECONDS_PER_MILLISECOND
        val limitClause = if (serviceName == null) {
            "LIMIT $limit OFFSET ${query.offset.coerceAtLeast(0)}"
        } else {
            "LIMIT $EXACT_SERVICE_LIMIT"
        }
        val sql = filteredQuery(
            """
            SELECT
                service as name,
                count() as span_count,
                sum(error) as error_count,
                toUInt64(quantile(0.50)(duration)) as p50_ns,
                toUInt64(quantile(0.95)(duration)) as p95_ns,
                toUInt64(quantile(0.99)(duration)) as p99_ns,
                countIf(error = 0 AND duration <= $apdexTargetNs) as apdex_satisfied,
                countIf(
                    error = 0 AND duration > $apdexTargetNs
                    AND duration <= ${apdexTargetNs * APDEX_TOLERATING_MULTIPLIER}
                ) as apdex_tolerating,
                groupUniqArray(8)(env) as envs,
                groupUniqArray(8)(source) as sources,
                argMax(version, start) as version,
                formatDateTime(max(start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                argMax(type, start) as type,
                coalesce(
                    nullIf(argMaxIf(meta['team'], start, meta['team'] != ''), ''),
                    nullIf(argMaxIf(resource_attributes['team'], start, resource_attributes['team'] != ''), ''),
                    nullIf(
                        argMaxIf(resource_attributes['service.team'], start, resource_attributes['service.team'] != ''),
                        ''
                    ),
                    ''
                ) as team,
                coalesce(
                    nullIf(
                        argMaxIf(
                            resource_attributes['telemetry.sdk.language'],
                            start,
                            resource_attributes['telemetry.sdk.language'] != ''
                        ),
                        ''
                    ),
                    nullIf(argMaxIf(meta['language'], start, meta['language'] != ''), ''),
                    nullIf(
                        argMaxIf(
                            resource_attributes['process.runtime.name'],
                            start,
                            resource_attributes['process.runtime.name'] != ''
                        ),
                        ''
                    ),
                    ''
                ) as language
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY service
            ORDER BY error_count DESC, p95_ns DESC, span_count DESC
            $limitClause
            FORMAT JSONEachRow
            """,
            filters,
        )
        return ClickHouseQuerySpec(sql, filterSet.params.asMap())
    }

    private suspend fun serviceSparklineSeries(
        organizationId: Int,
        services: List<String>,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): Map<String, List<Int>> {
        if (services.isEmpty()) return emptyMap()
        val filterSet = spanFilters(organizationId, query, null, null)
        val filters = filterSet.filters
        val serviceList = services.joinToString(", ") { service -> filterSet.params.string(service) }
        filters.add("service IN ($serviceList)")
        val bucketSeconds = (query.timeRange.seconds() / DEFAULT_SERIES_POINT_COUNT).coerceAtLeast(1)
        val sql = filteredQuery(
            """
            SELECT
                service,
                toStartOfInterval(start, INTERVAL $bucketSeconds SECOND) as bucket_start,
                count() as span_count
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY service, bucket_start
            ORDER BY service ASC, bucket_start ASC
            LIMIT ${services.size * DEFAULT_SERIES_POINT_COUNT}
            FORMAT JSONEachRow
            """,
            filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap())
            .groupBy { row -> row.stringValue("service") }
            .mapValues { (_, rows) ->
                rows.map { row -> row.longValue("span_count").toInt().coerceIn(SPARK_MIN, SPARK_MAX) }
            }
    }

    private suspend fun resourceRows(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmResourceRow> {
        val resources = resourceAggregates(organizationId, serviceName, query, parentSpan)
        val totalSpanCount = resources.sumOf { resource -> resource.spanCount }.coerceAtLeast(1)
        return resources.map { resource ->
            val errorRate = ratio(resource.errorCount, resource.spanCount)
            val method = resource.method()
            val displayName = resource.displayName()
            ApmResourceRow(
                slug = resourceSlug(method, displayName),
                method = method,
                name = displayName,
                rps = requestsPerSecond(resource.spanCount, query.timeRange),
                p50Ms = resource.p50Ms,
                p95Ms = resource.p95Ms,
                p99Ms = resource.p99Ms,
                errorRateLabel = formatPercent(errorRate),
                errorBarPct = errorBarPct(errorRate),
                errorLevel = severity(errorRate, resource.p95Ms),
                timePct = ((resource.spanCount.toDouble() / totalSpanCount.toDouble()) * PERCENT_SCALE).toInt(),
                status = status(errorRate, resource.p95Ms),
            )
        }
    }

    private suspend fun resourceAggregates(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ResourceAggregate> {
        val querySpec = resourceAggregateSql(organizationId, serviceName, query)
        return jsonRows(querySpec, parentSpan).map { row ->
            ResourceAggregate(
                resource = row.stringValue("resource").ifBlank { row.stringValue("name") },
                name = row.stringValue("name"),
                type = row.stringValue("type"),
                spanCount = row.longValue("span_count"),
                errorCount = row.longValue("error_count"),
                p50Ms = nanosToMillis(row.longValue("p50_ns")),
                p95Ms = nanosToMillis(row.longValue("p95_ns")),
                p99Ms = nanosToMillis(row.longValue("p99_ns")),
            )
        }
    }

    private fun resourceAggregateSql(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
    ): ClickHouseQuerySpec {
        val filterSet = spanFilters(organizationId, query, serviceName, null)
        val sql = filteredQuery(
            """
            SELECT
                resource,
                argMax(name, start) as name,
                argMax(type, start) as type,
                count() as span_count,
                sum(error) as error_count,
                toUInt64(quantile(0.50)(duration)) as p50_ns,
                toUInt64(quantile(0.95)(duration)) as p95_ns,
                toUInt64(quantile(0.99)(duration)) as p99_ns
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
              AND resource != ''
            GROUP BY resource
            ORDER BY error_count DESC, p95_ns DESC, span_count DESC
            LIMIT 50
            FORMAT JSONEachRow
            """,
            filterSet.filters,
        )
        return ClickHouseQuerySpec(sql, filterSet.params.asMap())
    }

    private suspend fun latencySeries(
        organizationId: Int,
        serviceName: String,
        resource: String?,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmLatencyPoint> {
        val filterSet = spanFilters(organizationId, query, serviceName, resource)
        val sql = filteredQuery(
            """
            SELECT
                toStartOfHour(start) as bucket_start,
                formatDateTime(bucket_start, '%H:%i') as t,
                toUInt64(quantile(0.50)(duration)) as p50_ns,
                toUInt64(quantile(0.90)(duration)) as p90_ns,
                toUInt64(quantile(0.95)(duration)) as p95_ns,
                toUInt64(quantile(0.99)(duration)) as p99_ns
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY bucket_start
            ORDER BY bucket_start ASC
            LIMIT 48
            FORMAT JSONEachRow
            """,
            filterSet.filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap()).map { row ->
            ApmLatencyPoint(
                t = row.stringValue("t"),
                p50 = row.millisValue("p50", "p50_ns"),
                p90 = row.millisValue("p90", "p90_ns"),
                p95 = row.millisValue("p95", "p95_ns"),
                p99 = row.millisValue("p99", "p99_ns"),
            )
        }
    }

    private suspend fun throughputSeries(
        organizationId: Int,
        serviceName: String,
        resource: String?,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmThroughputPoint> {
        val filterSet = spanFilters(organizationId, query, serviceName, resource)
        val sql = filteredQuery(
            """
            SELECT
                toStartOfHour(start) as bucket_start,
                formatDateTime(bucket_start, '%H:%i') as t,
                count() / $SECONDS_PER_HOUR as rps,
                sum(error) / $SECONDS_PER_HOUR as errors
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY bucket_start
            ORDER BY bucket_start ASC
            LIMIT 48
            FORMAT JSONEachRow
            """,
            filterSet.filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap()).map { row ->
            ApmThroughputPoint(
                t = row.stringValue("t"),
                rps = row.doubleValue("rps"),
                errors = row.doubleValue("errors"),
            )
        }
    }

    private suspend fun previousServiceAggregate(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): PreviousWindowAggregate? =
        previousWindowAggregate(organizationId, serviceName, null, query, parentSpan)

    private suspend fun previousResourceAggregate(
        organizationId: Int,
        serviceName: String,
        resource: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): PreviousWindowAggregate? =
        previousWindowAggregate(organizationId, serviceName, resource, query, parentSpan)

    private suspend fun previousWindowAggregate(
        organizationId: Int,
        serviceName: String,
        resource: String?,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): PreviousWindowAggregate? {
        val filterSet = previousSpanFilters(organizationId, query, serviceName, resource)
        val apdexTargetNs = APDEX_TARGET_MS * NANOSECONDS_PER_MILLISECOND
        val sql = filteredQuery(
            """
            SELECT
                count() as previous_span_count,
                sum(error) as previous_error_count,
                toUInt64(quantile(0.95)(duration)) as previous_p95_ns,
                toUInt64(quantile(0.99)(duration)) as previous_p99_ns,
                countIf(error = 0 AND duration <= $apdexTargetNs) as previous_apdex_satisfied,
                countIf(
                    error = 0 AND duration > $apdexTargetNs
                    AND duration <= ${apdexTargetNs * APDEX_TOLERATING_MULTIPLIER}
                ) as previous_apdex_tolerating
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            FORMAT JSONEachRow
            """,
            filterSet.filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap()).firstOrNull()?.let { row ->
            PreviousWindowAggregate(
                spanCount = row.longValue("previous_span_count"),
                errorCount = row.longValue("previous_error_count"),
                p95Ms = nanosToMillis(row.longValue("previous_p95_ns")),
                p99Ms = nanosToMillis(row.longValue("previous_p99_ns")),
                apdexSatisfied = row.longValue("previous_apdex_satisfied"),
                apdexTolerating = row.longValue("previous_apdex_tolerating"),
            )
        }
    }

    private suspend fun deploymentSeries(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): DeploymentSeries {
        val deployments = deploymentAggregates(organizationId, serviceName, query, parentSpan)
        if (deployments.isEmpty()) return DeploymentSeries(emptyList(), DEFAULT_DEPLOY_AT)
        return DeploymentSeries(
            rows = deployments.mapIndexed { index, deployment ->
                deployment.toRow(query.timeRange, current = index == 0)
            },
            deployAt = deployments.first().marker,
        )
    }

    private suspend fun deploymentAggregates(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<DeploymentAggregate> {
        val filterSet = spanFilters(organizationId, query, serviceName, null)
        val filters = filterSet.filters
        filters.add("version != ''")
        val sql = filteredQuery(
            """
            SELECT
                version,
                formatDateTime(min(start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                formatDateTime(toStartOfHour(min(start)), '%H:%i') as deploy_t,
                count() as span_count,
                sum(error) as error_count,
                toUInt64(quantile(0.95)(duration)) as p95_ns,
                coalesce(
                    nullIf(argMaxIf(meta['deployment.user'], start, meta['deployment.user'] != ''), ''),
                    nullIf(argMaxIf(meta['git.commit.author.name'], start, meta['git.commit.author.name'] != ''), ''),
                    nullIf(
                        argMaxIf(
                            resource_attributes['deployment.user'],
                            start,
                            resource_attributes['deployment.user'] != ''
                        ),
                        ''
                    ),
                    ''
                ) as deployer
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY version
            ORDER BY max(start) DESC
            LIMIT $DEPLOYMENT_ROW_LIMIT
            FORMAT JSONEachRow
            """,
            filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap()).map { row ->
            DeploymentAggregate(
                version = row.stringValue("version"),
                firstSeen = row.stringValue("first_seen"),
                lastSeen = row.stringValue("last_seen"),
                marker = row.stringValue("deploy_t"),
                spanCount = row.longValue("span_count"),
                errorCount = row.longValue("error_count"),
                p95Ms = nanosToMillis(row.longValue("p95_ns")),
                deployer = row.stringValue("deployer"),
            )
        }
    }

    private suspend fun containerRows(
        organizationId: Int,
        serviceName: String,
        parentSpan: ISpan?,
    ): List<ContainerAggregate> {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val params = ClickHouseQueryParameters()
        val service = params.string(serviceName)
        val sql = """
            SELECT
                argMax(name, timestamp) as pod,
                argMax(host, timestamp) as node,
                toInt32(round(argMax(cpu_percent, timestamp))) as cpu,
                argMax(mem_usage, timestamp) as mem_usage,
                argMax(mem_limit, timestamp) as mem_limit,
                toInt32OrZero(argMax(tags['restart_count'], timestamp)) as restarts,
                argMax(state, timestamp) as state
            FROM `$clickhouseDb`.containers_latest_by_host
            WHERE $orgClause
              AND timestamp >= now64(3) - INTERVAL 30 DAY
              AND (
                  tags['service'] = $service
                  OR positionCaseInsensitive(name, $service) > 0
                  OR positionCaseInsensitive(image, $service) > 0
              )
            GROUP BY host, container_id
            ORDER BY pod ASC
            LIMIT $CONTAINER_ROW_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()
        return jsonRows(sql, parentSpan, params.asMap()).map { row ->
            ContainerAggregate(
                pod = row.stringValue("pod"),
                node = row.stringValue("node"),
                cpu = row.longValue("cpu").toInt().coerceIn(PERCENT_MIN, PERCENT_MAX),
                memUsage = row.longValue("mem_usage"),
                memLimit = row.longValue("mem_limit"),
                restarts = row.longValue("restarts").toInt().coerceAtLeast(0),
                state = row.stringValue("state").ifBlank { "unknown" },
            )
        }
    }

    private suspend fun latencyDistribution(
        organizationId: Int,
        serviceName: String,
        resource: String,
        maxDurationMs: Long,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmDistBar> {
        val filterSet = spanFilters(organizationId, query, serviceName, resource)
        val bucketSizeNs = (
            (maxDurationMs.coerceAtLeast(1) * NANOSECONDS_PER_MILLISECOND) / LATENCY_DISTRIBUTION_BAR_COUNT
            ).coerceAtLeast(1)
        val sql = filteredQuery(
            """
            SELECT
                least(intDiv(duration, $bucketSizeNs), ${LATENCY_DISTRIBUTION_BAR_COUNT - 1}) as bucket_idx,
                count() as span_count
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY bucket_idx
            ORDER BY bucket_idx ASC
            FORMAT JSONEachRow
            """,
            filterSet.filters,
        )
        val counts = jsonRows(sql, parentSpan, filterSet.params.asMap()).associate { row ->
            row.longValue("bucket_idx").toInt() to row.longValue("span_count")
        }
        return latencyDistribution(counts)
    }

    private suspend fun dependencyRows(
        organizationId: Int,
        serviceName: String,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): Pair<List<ApmDependencyRow>, List<ApmDependencyRow>> {
        val upstream = serviceEdges(organizationId, serviceName, incoming = true, query, parentSpan)
        val downstream = serviceEdges(organizationId, serviceName, incoming = false, query, parentSpan)
        return upstream to downstream
    }

    private suspend fun serviceEdges(
        organizationId: Int,
        serviceName: String,
        incoming: Boolean,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmDependencyRow> {
        val serviceColumn = if (incoming) "to_service" else "from_service"
        val peerColumn = if (incoming) "from_service" else "to_service"
        val params = ClickHouseQueryParameters()
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            query.timeRange.bucketStartClause(),
            "$serviceColumn = ${params.string(serviceName)}",
        )
        query.env?.let { env -> filters.add("env = ${params.string(env)}") }
        query.source?.let { source -> filters.add("source = ${params.string(source)}") }
        val sql = filteredQuery(
            """
            SELECT
                $peerColumn as peer_service,
                sum(call_count) as call_count,
                sum(error_count) as error_count,
                if(sum(duration_count) = 0, 0, sum(duration_sum) / sum(duration_count)) as avg_duration_ns
            FROM `$clickhouseDb`.apm_service_edges_hourly
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY peer_service
            ORDER BY call_count DESC
            LIMIT 10
            FORMAT JSONEachRow
            """,
            filters,
        )
        return jsonRows(sql, parentSpan, params.asMap()).map { row ->
            val errorRate = ratio(row.longValue("error_count"), row.longValue("call_count"))
            ApmDependencyRow(
                name = row.stringValue("peer_service"),
                type = inferServiceType(row.stringValue("peer_service"), ""),
                tone = statusTone(status(errorRate, nanosToMillis(row.longValue("avg_duration_ns")))),
                rps = formatCompact(requestsPerSecond(row.longValue("call_count"), query.timeRange)),
                p95 = formatMs(nanosToMillis(row.longValue("avg_duration_ns"))),
                err = formatPercent(errorRate),
                errWarn = errorRate >= WARN_ERROR_RATE,
            )
        }
    }

    private suspend fun traceRows(
        organizationId: Int,
        serviceName: String,
        resource: String?,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmTraceRow> {
        val filterSet = spanFilters(organizationId, query, serviceName, resource)
        val sql = filteredQuery(
            """
            SELECT
                if(trace_id_hex != '', trace_id_hex, toString(trace_id)) as trace_id_out,
                argMax(resource, duration) as resource,
                argMax(name, duration) as name,
                max(status_code) as http_status,
                toUInt64(max(duration) / $NANOSECONDS_PER_MILLISECOND) as duration_ms,
                count() as span_count,
                formatDateTime(max(start), '%H:%i:%S') as time
            FROM `$clickhouseDb`.apm_spans
            WHERE __APM_CLICKHOUSE_FILTERS__
            GROUP BY trace_id_out
            ORDER BY duration_ms DESC
            LIMIT 20
            FORMAT JSONEachRow
            """,
            filterSet.filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap()).map { row ->
            ApmTraceRow(
                time = row.stringValue("time"),
                traceId = row.stringValue("trace_id_out"),
                resource = row.stringValue("resource"),
                method = methodFromResource(row.stringValue("resource")),
                httpStatus = row.longValue("http_status").toInt().takeIf { it > 0 } ?: 0,
                durationMs = row.longValue("duration_ms"),
                spans = row.longValue("span_count").toInt(),
                bucket = durationBucket(row.longValue("duration_ms")),
            )
        }
    }

    private suspend fun errorRows(
        organizationId: Int,
        serviceName: String,
        resource: String?,
        query: ApmServiceQuery,
        parentSpan: ISpan?,
    ): List<ApmErrorRow> {
        val filterSet = spanFilters(organizationId, query, serviceName, resource)
        val filters = filterSet.filters
        filters.add("error = 1")
        val sql = filteredQuery(
            """
            SELECT
                resource,
                error_type,
                error_message,
                count() as error_count,
                uniqExactIf(user_key, user_key != '') as user_count,
                max(status_code) as status_code,
                max(unhandled_flag) as unhandled,
                argMax(version, start) as version,
                formatDateTime(min(start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(start), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen
            FROM (
                SELECT
                    resource,
                    start,
                    version,
                    status_code,
                    multiIf(
                        meta['error.type'] != '', meta['error.type'],
                        meta['exception.type'] != '', meta['exception.type'],
                        status_message != '', status_message,
                        status_code >= $HTTP_SERVER_ERROR_STATUS, concat('HTTP ', toString(status_code)),
                        'Trace error'
                    ) as error_type,
                    multiIf(
                        meta['error.msg'] != '', meta['error.msg'],
                        meta['exception.message'] != '', meta['exception.message'],
                        status_message != '', status_message,
                        'Trace contains errored spans'
                    ) as error_message,
                    multiIf(
                        meta['user.id'] != '', meta['user.id'],
                        meta['user.email'] != '', meta['user.email'],
                        meta['user.ip_address'] != '', meta['user.ip_address'],
                        ''
                    ) as user_key,
                    if(
                        meta['handled'] = 'false'
                        OR meta['error.handled'] = 'false'
                        OR meta['exception.escaped'] = 'true',
                        1,
                        0
                    ) as unhandled_flag
                FROM `$clickhouseDb`.apm_spans
                WHERE __APM_CLICKHOUSE_FILTERS__
            )
            GROUP BY resource, error_type, error_message
            ORDER BY error_count DESC, last_seen DESC
            LIMIT 20
            FORMAT JSONEachRow
            """,
            filters,
        )
        return jsonRows(sql, parentSpan, filterSet.params.asMap()).map { row ->
            val errorType = row.stringValue("error_type").ifBlank { "Trace error" }
            val message = row.stringValue("error_message").ifBlank { "Trace contains errored spans" }
            val version = row.stringValue("version")
            val firstSeen = row.stringValue("first_seen")
            ApmErrorRow(
                severity = errorSeverity(errorType, message, row.longValue("status_code").toInt()),
                title = "$errorType: $message",
                sub = row.stringValue("resource"),
                chips = listOf(row.stringValue("resource"), version, firstSeen)
                    .filter { chip -> chip.isNotBlank() },
                events = row.longValue("error_count").toString(),
                users = row.longValue("user_count").takeIf { count -> count > 0 }?.toString(),
                unhandled = row.longValue("unhandled") > 0,
            )
        }
    }

    private suspend fun exemplarSpans(
        organizationId: Int,
        traceId: String,
        parentSpan: ISpan?,
    ): List<SpanAggregate> {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val params = ClickHouseQueryParameters()
        val traceIdParam = params.string(traceId)
        val sql = """
            SELECT
                if(span_id_hex != '', span_id_hex, toString(span_id)) as span_id_out,
                if(parent_id_hex != '', parent_id_hex, toString(parent_id)) as parent_id_out,
                name,
                service,
                resource,
                type,
                toInt64(toUnixTimestamp64Nano(start)) as start_ns,
                toUInt64(duration / $NANOSECONDS_PER_MILLISECOND) as duration_ms,
                error,
                status_code
            FROM `$clickhouseDb`.apm_spans
            WHERE $orgClause
              AND (trace_id_hex = $traceIdParam OR toString(trace_id) = $traceIdParam)
            ORDER BY start_ns ASC
            LIMIT 200
            FORMAT JSONEachRow
        """.trimIndent()
        return jsonRows(sql, parentSpan, params.asMap()).map { row ->
            SpanAggregate(
                spanId = row.stringValue("span_id_out"),
                parentId = row.stringValue("parent_id_out").takeUnless { it == "0" } ?: ROOT_PARENT_ID,
                name = row.stringValue("name"),
                service = row.stringValue("service"),
                resource = row.stringValue("resource"),
                type = row.stringValue("type"),
                startNs = row.longValue("start_ns"),
                durationMs = row.longValue("duration_ms"),
                error = row.longValue("error") > 0,
                statusCode = row.longValue("status_code").toInt(),
            )
        }
    }

    private fun spanFilters(
        organizationId: Int,
        query: ApmServiceQuery,
        serviceName: String?,
        resource: String?,
    ): SpanFilterSet {
        return spanFiltersForTimeClause(organizationId, query, serviceName, resource, query.timeRange.startClause())
    }

    private fun previousSpanFilters(
        organizationId: Int,
        query: ApmServiceQuery,
        serviceName: String?,
        resource: String?,
    ): SpanFilterSet {
        return spanFiltersForTimeClause(
            organizationId,
            query,
            serviceName,
            resource,
            query.timeRange.previousStartClause(),
        )
    }

    private fun spanFiltersForTimeClause(
        organizationId: Int,
        query: ApmServiceQuery,
        serviceName: String?,
        resource: String?,
        timeClause: String,
    ): SpanFilterSet {
        val params = ClickHouseQueryParameters()
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong()),
            timeClause,
            "service != ''",
        )
        serviceName?.let { service -> filters.add("service = ${params.string(service)}") }
        resource?.let { resourceName -> filters.add("resource = ${params.string(resourceName)}") }
        query.env?.let { env -> filters.add("env = ${params.string(env)}") }
        query.source?.let { source -> filters.add("source = ${params.string(source)}") }
        query.search?.let { search -> filters.add(searchClause(search, params)) }
        return SpanFilterSet(filters, params)
    }

    private fun searchClause(search: String, params: ClickHouseQueryParameters): String {
        val searchParam = params.string(search)
        return """
            (
                positionCaseInsensitive(service, $searchParam) > 0 OR
                positionCaseInsensitive(resource, $searchParam) > 0 OR
                positionCaseInsensitive(name, $searchParam) > 0
            )
        """.trimIndent()
    }

    private suspend fun jsonRows(query: ClickHouseQuerySpec, parentSpan: ISpan?): List<JsonObject> =
        jsonRows(query.sql, parentSpan, query.parameters)

    private suspend fun jsonRows(
        query: String,
        parentSpan: ISpan?,
        queryParameters: Map<String, String> = emptyMap(),
    ): List<JsonObject> {
        val result = if (parentSpan == null) {
            ClickHouseClient.executeWithFormat(query, "", queryParameters)
        } else {
            ClickHouseClient.executeWithFormat(query, "", parentSpan, queryParameters)
        }
        return result.lines()
            .filter { line -> line.isNotBlank() }
            .mapNotNull { line -> parseRowOrNull(line) }
    }

    private fun parseRowOrNull(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject

    private fun filteredQuery(template: String, filters: List<String>): String =
        template.trimIndent().replace(FILTERS_PLACEHOLDER, filters.joinToString(" AND "))
}

private fun ServiceAggregate.toCatalogRow(
    timeRange: DdApmQueryTimeRange,
    sparkline: List<Int>? = null,
): ApmServiceCatalogRow {
    val errorRate = ratio(errorCount, spanCount)
    val serviceStatus = status(errorRate, p95Ms)
    val apdex = apdexScore(apdexSatisfied, apdexTolerating, spanCount)
    return ApmServiceCatalogRow(
        name = name,
        type = type,
        status = serviceStatus,
        env = envPills(envs),
        rps = requestsPerSecond(spanCount, timeRange),
        spark = sparkline.orEmpty(),
        p95Ms = p95Ms,
        p99Ms = p99Ms,
        errorRateLabel = formatPercent(errorRate),
        errorBarPct = errorBarPct(errorRate),
        errorLevel = severity(errorRate, p95Ms),
        apdex = formatApdex(apdex),
        apdexTone = apdexTone(apdex),
        lastDeploy = version.takeUnless { it == UNKNOWN_VERSION } ?: lastDeployLabel(),
        team = team.ifBlank { UNASSIGNED_TEAM },
        language = language.takeIf { value -> value.isNotBlank() },
        sources = sources.map(::sourceLabel).distinct(),
    )
}

private fun serviceKpis(
    service: ApmServiceCatalogRow,
    previous: PreviousWindowAggregate?,
    timeRange: DdApmQueryTimeRange,
): List<ApmKpiSpec> =
    listOf(
        ApmKpiSpec(
            "Requests / sec",
            formatCompact(service.rps),
            delta = previous?.let { prev ->
                metricDelta(service.rps, requestsPerSecond(prev.spanCount, timeRange), lowerIsBetter = false)
            },
        ),
        ApmKpiSpec(
            "p95 latency",
            formatMs(service.p95Ms),
            valueTone = latencyTone(service.p95Ms),
            delta = previous?.let { prev -> metricDelta(service.p95Ms.toDouble(), prev.p95Ms.toDouble()) },
        ),
        ApmKpiSpec(
            "Error rate",
            service.errorRateLabel,
            valueTone = errorTone(service.errorLevel),
            delta = previous?.let { prev ->
                metricDelta(
                    ratioLabelToDouble(service.errorRateLabel),
                    ratio(prev.errorCount, prev.spanCount),
                )
            },
        ),
        ApmKpiSpec(
            "Apdex",
            service.apdex,
            valueTone = service.apdexTone,
            delta = previous?.let { prev ->
                metricDelta(
                    service.apdex.toDoubleOrNull() ?: 0.0,
                    apdexScore(prev.apdexSatisfied, prev.apdexTolerating, prev.spanCount),
                    lowerIsBetter = false,
                )
            },
        ),
    )

private fun resourceKpis(
    resource: ApmResourceRow,
    previous: PreviousWindowAggregate?,
    timeRange: DdApmQueryTimeRange,
): List<ApmKpiSpec> =
    listOf(
        ApmKpiSpec(
            "Requests / sec",
            formatCompact(resource.rps),
            delta = previous?.let { prev ->
                metricDelta(resource.rps, requestsPerSecond(prev.spanCount, timeRange), lowerIsBetter = false)
            },
        ),
        ApmKpiSpec(
            "p95 latency",
            formatMs(resource.p95Ms),
            valueTone = latencyTone(resource.p95Ms),
            delta = previous?.let { prev -> metricDelta(resource.p95Ms.toDouble(), prev.p95Ms.toDouble()) },
        ),
        ApmKpiSpec(
            "Error rate",
            resource.errorRateLabel,
            valueTone = errorTone(resource.errorLevel),
            delta = previous?.let { prev ->
                metricDelta(ratioLabelToDouble(resource.errorRateLabel), ratio(prev.errorCount, prev.spanCount))
            },
        ),
        ApmKpiSpec(
            "p99 latency",
            formatMs(resource.p99Ms),
            delta = previous?.let { prev -> metricDelta(resource.p99Ms.toDouble(), prev.p99Ms.toDouble()) },
        ),
    )

private fun catalogSummary(services: List<ApmServiceCatalogRow>): ApmCatalogSummary =
    ApmCatalogSummary(
        total = services.size,
        alerting = services.count { service -> service.status == "alerting" },
        degraded = services.count { service -> service.status == "degraded" },
    )

private fun p95ByResource(resources: List<ApmResourceRow>): List<ApmGaugeRow> {
    val maxP95 = resources.maxOfOrNull { resource -> resource.p95Ms }?.coerceAtLeast(1) ?: 1
    return resources.take(TOP_RESOURCE_LIMIT).map { resource ->
        ApmGaugeRow(
            label = "${resource.method} ${resource.name}",
            valueText = formatMs(resource.p95Ms),
            pct = ((resource.p95Ms.toDouble() / maxP95.toDouble()) * PERCENT_SCALE)
                .toInt()
                .coerceIn(GAUGE_MIN_PCT, PERCENT_MAX),
            level = severity(0.0, resource.p95Ms),
        )
    }
}

private fun DeploymentAggregate.toRow(timeRange: DdApmQueryTimeRange, current: Boolean): ApmDeploymentRow {
    val errorRate = ratio(errorCount, spanCount)
    val serviceStatus = status(errorRate, p95Ms)
    return ApmDeploymentRow(
        version = version,
        `when` = firstSeen.ifBlank { lastSeen },
        initials = initials(deployer),
        rps = formatCompact(requestsPerSecond(spanCount, timeRange)),
        errorRate = formatPercent(errorRate),
        p95 = formatMs(p95Ms),
        status = if (current) serviceStatus else "retired",
        current = current,
        trendBad = serviceStatus != "healthy",
    )
}

private fun dependencyInsight(downstream: List<ApmDependencyRow>): String =
    downstream.firstOrNull()
        ?.let { dependency -> "${dependency.name} is the busiest downstream dependency in this window." }
        ?: "No downstream dependencies were observed in this window."

private fun whereTimeSpent(
    resource: ApmResourceRow,
    spans: List<SpanAggregate>,
): List<ApmGaugeRow> {
    if (spans.isEmpty()) {
        return listOf(
            ApmGaugeRow(
                label = "${resource.method} ${resource.name}",
                valueText = formatMs(resource.p95Ms),
                pct = 100,
                level = severity(0.0, resource.p95Ms),
            )
        )
    }
    val maxDuration = spans.maxOf { span -> span.durationMs }.coerceAtLeast(1)
    return spans.sortedByDescending { span -> span.durationMs }.take(TOP_RESOURCE_LIMIT).map { span ->
        ApmGaugeRow(
            label = span.resource.ifBlank { span.name },
            valueText = formatMs(span.durationMs),
            pct = ((span.durationMs.toDouble() / maxDuration.toDouble()) * PERCENT_SCALE)
                .toInt()
                .coerceIn(GAUGE_MIN_PCT, PERCENT_MAX),
            level = severity(if (span.error) ALERT_ERROR_RATE else 0.0, span.durationMs),
        )
    }
}

private fun whereInsight(resource: ApmResourceRow, dependency: ApmDependencyRow?): String =
    dependency
        ?.let { dep -> "${dep.name} contributes to the slow path for ${resource.method} ${resource.name}." }
        ?: "${resource.method} ${resource.name} spent most observed time inside its own service spans."

private fun exemplar(
    resource: ApmResourceRow,
    trace: ApmTraceRow?,
    spans: List<SpanAggregate>,
): ApmResourceExemplar {
    val traceId = trace?.traceId ?: ""
    val durationMs = trace?.durationMs ?: resource.p99Ms
    return ApmResourceExemplar(
        traceId = traceId,
        httpStatus = trace?.httpStatus ?: 0,
        durationLabel = formatMs(durationMs),
        rows = waterfallRows(resource, durationMs, spans),
    )
}

private fun waterfallRows(
    resource: ApmResourceRow,
    durationMs: Long,
    spans: List<SpanAggregate>,
): List<ApmWaterfallRow> {
    if (spans.isEmpty()) {
        return listOf(
            ApmWaterfallRow(
                op = resource.method,
                desc = resource.name,
                left = PERCENT_MIN,
                width = PERCENT_MAX,
                label = formatMs(durationMs),
                tone = "root",
                selected = true,
            )
        )
    }
    val start = spans.minOf { span -> span.startNs }
    val end = spans.maxOf { span -> span.startNs + span.durationMs * NANOSECONDS_PER_MILLISECOND }
    val total = (end - start).coerceAtLeast(1)
    return spans.take(WATERFALL_ROW_LIMIT).mapIndexed { index, span ->
        ApmWaterfallRow(
            op = span.name.ifBlank { span.type.ifBlank { "span" } },
            desc = span.resource.ifBlank { span.service },
            left = (((span.startNs - start).toDouble() / total.toDouble()) * PERCENT_SCALE)
                .toInt()
                .coerceIn(PERCENT_MIN, PERCENT_MAX),
            width = ((span.durationMs * NANOSECONDS_PER_MILLISECOND).toDouble() / total.toDouble() * PERCENT_SCALE)
                .toInt()
                .coerceIn(WATERFALL_MIN_WIDTH, PERCENT_MAX),
            label = formatMs(span.durationMs),
            tone = waterfallTone(span),
            indent = if (span.parentId == ROOT_PARENT_ID) null else CHILD_SPAN_INDENT,
            selected = index == 0,
        )
    }
}

private fun latencyDistribution(counts: Map<Int, Long>): List<ApmDistBar> {
    val maxCount = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    return List(LATENCY_DISTRIBUTION_BAR_COUNT) { index ->
        val count = counts[index] ?: 0
        val height = if (count == 0L) {
            LATENCY_DISTRIBUTION_MIN
        } else {
            ((count.toDouble() / maxCount.toDouble()) * PERCENT_SCALE)
                .toInt()
                .coerceIn(LATENCY_DISTRIBUTION_MIN, PERCENT_MAX)
        }
        ApmDistBar(
            h = height,
            band = when {
                index < LATENCY_DISTRIBUTION_GOOD_END -> "good"
                index < LATENCY_DISTRIBUTION_WARN_END -> "warn"
                else -> "bad"
            },
        )
    }
}

private fun latencyMarkers(resource: ApmResourceRow): List<ApmDistMarker> =
    listOf(
        ApmDistMarker(label = "p50", left = markerPosition(resource.p50Ms, resource.p99Ms)),
        ApmDistMarker(label = "p95", left = markerPosition(resource.p95Ms, resource.p99Ms)),
        ApmDistMarker(label = "p99", left = LATENCY_MARKER_MAX_PCT, p99 = true),
    )

private fun latencyAxis(resource: ApmResourceRow): List<String> =
    listOf(
        "0",
        formatMs((resource.p95Ms / LATENCY_AXIS_P50_DIVISOR).coerceAtLeast(1)),
        formatMs(resource.p95Ms),
        formatMs(
            ((resource.p95Ms * LATENCY_AXIS_P95_UPPER_NUMERATOR) / LATENCY_AXIS_P95_UPPER_DENOMINATOR)
                .coerceAtLeast(resource.p95Ms + 1)
        ),
        formatMs(resource.p99Ms),
        "${formatMs(
            ((resource.p99Ms * LATENCY_AXIS_P99_UPPER_NUMERATOR) / LATENCY_AXIS_P99_UPPER_DENOMINATOR)
                .coerceAtLeast(resource.p99Ms + 1)
        )}+",
    )

private fun markerPosition(valueMs: Long, maxMs: Long): Int =
    ((valueMs.toDouble() / maxMs.coerceAtLeast(1).toDouble()) * LATENCY_MARKER_MAX_PCT.toDouble())
        .toInt()
        .coerceIn(LATENCY_MARKER_MIN_PCT, LATENCY_MARKER_MAX_PCT)

private fun errorBars(
    throughput: List<ApmThroughputPoint>,
    aggregate: ServiceAggregate,
): List<ApmErrorBar> {
    if (throughput.isEmpty()) {
        val errorRate = ratio(aggregate.errorCount, aggregate.spanCount)
        return List(DEFAULT_ERROR_BAR_COUNT) {
            ApmErrorBar(
                h = errorBarPct(errorRate).coerceIn(ERROR_BAR_MIN_PCT, ERROR_BAR_MAX_PCT),
                level = severity(errorRate, aggregate.p95Ms),
            )
        }
    }
    val maxErrors = throughput.maxOf { point -> point.errors ?: 0.0 }.coerceAtLeast(1.0)
    return throughput.map { point ->
        val errors = point.errors ?: 0.0
        val errorRate = if (point.rps > 0.0) errors / point.rps else 0.0
        val height = ((errors / maxErrors) * ERROR_BAR_MAX_PCT.toDouble())
            .toInt()
            .coerceIn(ERROR_BAR_MIN_PCT, ERROR_BAR_MAX_PCT)
        ApmErrorBar(h = height, level = if (errorRate >= WARN_ERROR_RATE) "bad" else "warn")
    }
}

private fun List<ApmLatencyPoint>.withFallback(aggregate: ServiceAggregate): List<ApmLatencyPoint> =
    if (isNotEmpty()) {
        this
    } else {
        List(DEFAULT_SERIES_POINT_COUNT) { index ->
            val label = index.toString().padStart(2, '0') + ":00"
            ApmLatencyPoint(
                label,
                aggregate.p50Ms,
                (aggregate.p95Ms * FALLBACK_P90_NUMERATOR) / FALLBACK_P90_DENOMINATOR,
                aggregate.p95Ms,
                aggregate.p99Ms,
            )
        }
    }

private fun List<ApmLatencyPoint>.withFallback(resource: ApmResourceRow): List<ApmLatencyPoint> =
    if (isNotEmpty()) {
        this
    } else {
        List(DEFAULT_SERIES_POINT_COUNT) { index ->
            val label = index.toString().padStart(2, '0') + ":00"
            ApmLatencyPoint(
                label,
                resource.p50Ms,
                (resource.p95Ms * FALLBACK_P90_NUMERATOR) / FALLBACK_P90_DENOMINATOR,
                resource.p95Ms,
                resource.p99Ms,
            )
        }
    }

private fun List<ApmThroughputPoint>.withFallback(rps: Double): List<ApmThroughputPoint> =
    if (isNotEmpty()) {
        this
    } else {
        List(DEFAULT_SERIES_POINT_COUNT) { index ->
            ApmThroughputPoint(t = index.toString().padStart(2, '0') + ":00", rps = rps)
        }
    }

private fun JsonObject.millisValue(msKey: String, nanosKey: String): Long {
    val explicitMs = longValue(msKey)
    return if (explicitMs > 0) explicitMs else nanosToMillis(longValue(nanosKey))
}

private fun JsonObject.longValue(key: String): Long =
    this[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L

private fun JsonObject.doubleValue(key: String): Double =
    this[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.content ?: ""

private fun JsonObject.stringList(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    return if (element is JsonArray) {
        element.mapNotNull { item -> item.jsonPrimitive.content.takeIf { value -> value.isNotBlank() } }
    } else {
        element.jsonPrimitive.content.split(",").map { value -> value.trim() }.filter { value -> value.isNotBlank() }
    }
}

private fun ResourceAggregate.method(): String =
    methodFromResource(resource).ifBlank {
        name.substringBefore('.').uppercase().takeIf { it.length <= SYNTHETIC_METHOD_MAX_LENGTH } ?: "SPAN"
    }

private fun ResourceAggregate.displayName(): String {
    val method = methodFromResource(resource)
    return if (method.isBlank()) {
        resource
    } else {
        resource.removePrefix(method).trim()
    }
}

private fun rawResourceValue(resource: ApmResourceRow): String =
    if (methodFromResource(resource.name).isBlank() && resource.method != "SPAN") {
        "${resource.method} ${resource.name}"
    } else {
        resource.name
    }

fun resourceSlug(method: String, name: String): String =
    slugify("$method-$name")

private fun slugify(value: String): String {
    val builder = StringBuilder()
    var lastWasSeparator = false
    value.lowercase().forEach { char ->
        if (char.isAsciiLetterOrDigit()) {
            builder.append(char)
            lastWasSeparator = false
        } else if (!lastWasSeparator && builder.isNotEmpty()) {
            builder.append('-')
            lastWasSeparator = true
        }
    }
    return builder.toString().trim('-')
}

private fun envPills(envs: List<String>): List<ApmEnvPill> {
    val values = envs.filter { env -> env.isNotBlank() }.ifEmpty { listOf("default") }
    return values.mapIndexed { index, env ->
        ApmEnvPill(label = envLabel(env), chart = (index % ENV_CHART_BUCKET_COUNT) + ENV_CHART_START)
    }
}

private fun envLabel(env: String): String =
    when (env.lowercase()) {
        "production" -> "prod"
        else -> env
    }

private fun sourceLabel(source: String): String =
    when (source.lowercase()) {
        "otlp", "otel", "opentelemetry" -> "OTLP"
        "datadog" -> "Datadog Agent"
        "sentry" -> "Sentry"
        else -> source.ifBlank { "Telemetry" }
    }

private fun inferServiceType(serviceName: String, spanType: String): String {
    val combined = "$serviceName $spanType".lowercase()
    return when {
        listOf("postgres", "mysql", "mongo", "db", "sql").any { marker -> marker in combined } -> "db"
        listOf("redis", "cache", "memcached").any { marker -> marker in combined } -> "cache"
        listOf("worker", "queue", "consumer", "job").any { marker -> marker in combined } -> "worker"
        else -> "web"
    }
}

private fun runtimeLabel(type: String, sources: List<String>): String =
    when {
        sources.any { source -> source.equals("otlp", ignoreCase = true) } -> "OpenTelemetry service"
        type == "db" -> "Database"
        type == "cache" -> "Cache"
        type == "worker" -> "Worker"
        else -> "Web service"
    }

private fun resourceKind(method: String): String =
    when (method) {
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> "http endpoint"
        else -> "span resource"
    }

private fun methodFromResource(resource: String): String =
    resource.substringBefore(' ', missingDelimiterValue = "")
        .takeIf { method -> method.isHttpMethodToken() }
        ?: ""

private fun String.isHttpMethodToken(): Boolean =
    length in MIN_HTTP_METHOD_LENGTH..SYNTHETIC_METHOD_MAX_LENGTH && all { char -> char in 'A'..'Z' }

private fun status(errorRate: Double, p95Ms: Long): String =
    when {
        errorRate >= ALERT_ERROR_RATE || p95Ms >= ALERT_LATENCY_MS -> "alerting"
        errorRate >= WARN_ERROR_RATE || p95Ms >= WARN_LATENCY_MS -> "degraded"
        else -> "healthy"
    }

private fun severity(errorRate: Double, p95Ms: Long): String =
    when (status(errorRate, p95Ms)) {
        "alerting" -> "bad"
        "degraded" -> "warn"
        else -> "good"
    }

private fun statusTone(status: String): String =
    when (status) {
        "alerting" -> "danger"
        "degraded" -> "warning"
        else -> "success"
    }

private fun latencyTone(p95Ms: Long): String? =
    when {
        p95Ms >= ALERT_LATENCY_MS -> "danger"
        p95Ms >= WARN_LATENCY_MS -> "warning"
        else -> null
    }

private fun errorTone(errorLevel: String): String? =
    when (errorLevel) {
        "bad" -> "danger"
        "warn" -> "warning"
        else -> null
    }

private fun apdexScore(satisfied: Long, tolerating: Long, total: Long): Double =
    if (total > 0) {
        ((satisfied.toDouble() + tolerating.toDouble() / 2.0) / total.toDouble()).coerceIn(0.0, APDEX_MAX_SCORE)
    } else {
        APDEX_MAX_SCORE
    }

private fun formatApdex(score: Double): String =
    "%.2f".format(score.coerceIn(0.0, APDEX_MAX_SCORE))

private fun apdexTone(score: Double): String =
    when {
        score >= APDEX_SUCCESS_SCORE -> "success"
        score >= APDEX_WARNING_SCORE -> "warning"
        else -> "danger"
    }

private fun metricDelta(
    current: Double,
    previous: Double,
    lowerIsBetter: Boolean = true,
): ApmStatDeltaSpec? {
    if (previous <= 0.0) return null
    val delta = ((current - previous) / previous) * PERCENT_SCALE
    val direction = when {
        delta > DELTA_FLAT_THRESHOLD -> "up"
        delta < -DELTA_FLAT_THRESHOLD -> "down"
        else -> "flat"
    }
    val tone = when {
        direction == "flat" -> "neutral"
        lowerIsBetter && direction == "up" -> "danger"
        lowerIsBetter && direction == "down" -> "success"
        !lowerIsBetter && direction == "up" -> "success"
        else -> "warning"
    }
    val prefix = if (delta > 0.0) "+" else ""
    return ApmStatDeltaSpec(
        value = "$prefix${"%.1f".format(delta)}%",
        direction = direction,
        tone = tone,
    )
}

private fun ratioLabelToDouble(label: String): Double =
    label.removeSuffix("%").toDoubleOrNull()?.div(PERCENT_SCALE) ?: 0.0

private fun errorSeverity(errorType: String, message: String, statusCode: Int): String {
    val combined = "$errorType $message".lowercase()
    val hasFatalText = listOf("fatal", "panic", "crash").any { marker -> marker in combined }
    return when {
        statusCode >= HTTP_SERVER_ERROR_STATUS || hasFatalText -> "fatal"
        statusCode in 400 until HTTP_SERVER_ERROR_STATUS || "warn" in combined -> "warn"
        else -> "error"
    }
}

private fun ContainerAggregate.toPodRow(): ApmPodRow =
    ApmPodRow(
        pod = pod,
        node = node,
        cpu = cpu,
        mem = formatBytes(memUsage),
        restarts = restarts,
        tone = when {
            state.lowercase() !in setOf("running", "healthy", "up") -> "danger"
            cpu >= INFRA_CPU_ALERT_PCT -> "danger"
            cpu >= INFRA_CPU_WARN_PCT -> "warning"
            else -> "success"
        },
        state = state,
    )

private fun podMemoryRows(containers: List<ContainerAggregate>): List<ApmGaugeRow> {
    val maxMem = containers.maxOfOrNull { container -> container.memUsage }?.coerceAtLeast(1) ?: 1
    return containers.map { container ->
        ApmGaugeRow(
            label = container.pod,
            valueText = formatBytes(container.memUsage),
            pct = ((container.memUsage.toDouble() / maxMem.toDouble()) * PERCENT_SCALE)
                .toInt()
                .coerceIn(GAUGE_MIN_PCT, PERCENT_MAX),
            level = when {
                container.memLimit > 0 && ratio(container.memUsage, container.memLimit) >= MEMORY_WARN_RATIO -> "warn"
                else -> "good"
            },
        )
    }
}

private fun initials(name: String): String =
    name.splitToSequence(' ', '\t', '\n', '\r', '\u000C')
        .mapNotNull { part -> part.firstOrNull()?.uppercaseChar()?.toString() }
        .take(2)
        .joinToString("")
        .ifBlank { "TM" }

private fun formatBytes(bytes: Long): String =
    if (bytes >= BYTES_PER_GIBIBYTE) {
        "%.1fGB".format(bytes.toDouble() / BYTES_PER_GIBIBYTE)
    } else {
        "%.0fMB".format(bytes.toDouble() / BYTES_PER_MEBIBYTE)
    }

private fun errorBarPct(errorRate: Double): Int =
    (errorRate * ERROR_BAR_SCALE).toInt().coerceIn(ERROR_RATE_MIN_BAR_PCT, PERCENT_MAX)

private fun formatPercent(value: Double): String =
    if (value < MIN_VISIBLE_ERROR_RATE && value > 0.0) {
        "<0.1%"
    } else {
        "%.1f%%".format(value * PERCENT_SCALE)
    }

private fun requestsPerSecond(count: Long, timeRange: DdApmQueryTimeRange): Double =
    count.toDouble() / timeRange.seconds().toDouble()

private fun DdApmQueryTimeRange.seconds(): Int =
    amount * when (unit.sql) {
        "DAY" -> SECONDS_PER_DAY
        else -> SECONDS_PER_HOUR
    }

private fun DdApmQueryTimeRange.previousStartClause(column: String = "start"): String =
    "$column >= now() - INTERVAL ${amount * 2} ${unit.sql} AND $column < now() - INTERVAL $amount ${unit.sql}"

private fun ratio(part: Long, total: Long): Double =
    if (total > 0) part.toDouble() / total.toDouble() else 0.0

private fun nanosToMillis(value: Long): Long =
    value / NANOSECONDS_PER_MILLISECOND

private fun formatMs(ms: Long): String =
    if (ms >= MILLISECONDS_PER_SECOND) {
        "%.2f".format(ms.toDouble() / MILLISECONDS_PER_SECOND.toDouble())
            .trimEnd('0')
            .trimEnd('.') + "s"
    } else {
        "${ms}ms"
    }

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in '0'..'9'

private fun formatCompact(value: Double): String =
    if (value >= COMPACT_UNIT_THRESHOLD) {
        "%.1fk".format(value / COMPACT_UNIT_THRESHOLD)
    } else {
        "%.1f".format(value).trimEnd('0').trimEnd('.')
    }

private fun ServiceAggregate.lastDeployLabel(): String =
    version.takeUnless { it == UNKNOWN_VERSION } ?: lastSeen.takeIf { it.isNotBlank() } ?: "observed"

private fun latencyThreshold(p95Ms: Long): Long =
    maxOf(
        WARN_LATENCY_MS,
        ((p95Ms * LATENCY_THRESHOLD_NUMERATOR) / LATENCY_THRESHOLD_DENOMINATOR).coerceAtLeast(p95Ms + 1)
    )

private fun durationBucket(durationMs: Long): String =
    when {
        durationMs >= P99_DURATION_MS -> "p99"
        durationMs >= P95_PLUS_DURATION_MS -> "p95+"
        durationMs >= P95_DURATION_MS -> "p95"
        else -> "p50"
    }

private fun waterfallTone(span: SpanAggregate): String =
    when {
        span.error || span.statusCode >= HTTP_SERVER_ERROR_STATUS -> "error"
        "db" in span.type.lowercase() || "sql" in span.resource.lowercase() -> "db"
        "redis" in span.resource.lowercase() || "cache" in span.type.lowercase() -> "cache"
        "http" in span.type.lowercase() || methodFromResource(span.resource).isNotBlank() -> "http"
        span.parentId == ROOT_PARENT_ID -> "root"
        else -> "app"
    }
