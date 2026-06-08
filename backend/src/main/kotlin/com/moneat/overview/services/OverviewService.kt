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

package com.moneat.overview.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.monitor.models.HostData
import com.moneat.monitor.models.LatestMetrics
import com.moneat.monitor.repositories.HostAlertRepositoryImpl
import com.moneat.monitor.repositories.HostRepositoryImpl
import com.moneat.monitor.services.MonitorService
import com.moneat.overview.models.OverviewActivityItem
import com.moneat.overview.models.OverviewAlertItem
import com.moneat.overview.models.OverviewCounts
import com.moneat.overview.models.OverviewDeployRow
import com.moneat.overview.models.OverviewInfraData
import com.moneat.overview.models.OverviewInfraGauge
import com.moneat.overview.models.OverviewIncidentItem
import com.moneat.overview.models.OverviewIssueItem
import com.moneat.overview.models.OverviewKpi
import com.moneat.overview.models.OverviewKpiDelta
import com.moneat.overview.models.OverviewResponse
import com.moneat.overview.models.OverviewSecurityItem
import com.moneat.overview.models.OverviewServiceDeploy
import com.moneat.overview.models.OverviewServiceRow
import com.moneat.overview.models.OverviewStatusAi
import com.moneat.overview.models.OverviewSystemStatus
import com.moneat.overview.models.OverviewTelemetryData
import com.moneat.overview.models.OverviewTriageData
import com.moneat.overview.models.OverviewUptimeData
import com.moneat.overview.models.OverviewUptimeMonitor
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Releases
import com.moneat.statuspage.models.StatusPages
import com.moneat.uptime.models.UptimeMonitorResponse
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

private const val CURRENT_WINDOW_HOURS = 24
private const val COMPARISON_WINDOW_HOURS = 48
private const val SERIES_BUCKET_COUNT = 24
private const val MAX_SERVICE_ROWS = 8
private const val MAX_DEPLOY_ROWS = 5
private const val MAX_ACTIVITY_ROWS = 8
private const val MAX_ATTENTION_ROWS = 4
private const val MINUTES_PER_DAY = 1_440.0
private const val PERCENT = 100.0
private const val MIN_PERCENT_INT = 0
private const val MAX_PERCENT_INT = 100
private const val GOOD_APDEX = 0.94
private const val WARN_APDEX = 0.85
private const val WARN_ERROR_PCT = 0.8
private const val BAD_ERROR_PCT = 2.0
private const val WARN_P95_MS = 400
private const val BAD_P95_MS = 700
private const val APDEX_SATISFIED_MS = 500
private const val APDEX_TOLERATED_MS = 2_000
private const val NANOS_PER_MILLI = 1_000_000
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_MONTH = 30L
private const val WARN_RESOURCE_PCT = 70
private const val BAD_RESOURCE_PCT = 90
private const val COUNT_SUFFIX_THOUSAND = 1_000L
private const val COUNT_SUFFIX_MILLION = 1_000_000L
private const val ONE_DECIMAL_SCALE = 10.0
private const val UPTIME_SLO_PCT = 99.9
private const val UPTIME_SLO_LABEL = "SLO 99.9"
private const val DEFAULT_DEPLOY_LABEL = "No deploys"
private const val LIVE_TRACE_WINDOW_HOURS = 2
private const val DATETIME64_MILLIS_PRECISION = 3

class OverviewService(
    private val monitorService: MonitorService = MonitorService(
        HostRepositoryImpl(),
        HostAlertRepositoryImpl(),
    ),
    private val uptimeService: UptimeService = UptimeService(
        BillingQuotaService(),
        UptimeMonitorRepositoryImpl(),
    ),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getOverview(
        organizationId: Int,
        demoEpochMs: Long? = null,
    ): OverviewResponse = coroutineScope {
        val projects = loadProjects(organizationId)
        val projectIds = projects.map { project -> project.id }

        val hostsDeferred = async { monitorService.listHosts(organizationId) }
        val monitorsDeferred = async { uptimeService.listMonitors(organizationId) }
        val eventMetricsDeferred = async { loadEventMetrics(projectIds, demoEpochMs) }
        val issueItemsDeferred = async { loadIssueItems(projectIds, demoEpochMs) }
        val traceMetricsDeferred = async { loadTraceMetrics(organizationId, demoEpochMs) }
        val serviceRowsDeferred = async { loadServiceRows(organizationId, demoEpochMs) }
        val logMetricsDeferred = async { loadLogMetrics(organizationId, demoEpochMs) }
        val containersDeferred = async { loadContainerCounts(organizationId, demoEpochMs) }
        val deploysDeferred = async { loadDeploys(organizationId) }
        val alertsDeferred = async { loadAlertItems(organizationId) }
        val incidentsDeferred = async { loadIncidentItems(organizationId) }
        val statusPageCountDeferred = async { loadStatusPageCount(organizationId) }
        val syntheticFailingDeferred = async { loadSyntheticFailing(organizationId, demoEpochMs) }

        val hosts = hostsDeferred.await()
        val latestMetrics = loadLatestMetrics(organizationId, hosts, demoEpochMs)
        val monitors = monitorsDeferred.await()
        val eventMetrics = eventMetricsDeferred.await()
        val traceMetrics = traceMetricsDeferred.await()
        val logMetrics = logMetricsDeferred.await()
        val issueItems = issueItemsDeferred.await()
        val deploys = deploysDeferred.await()
        val alertItems = alertsDeferred.await()
        val incidents = incidentsDeferred.await()

        val counts = overviewCounts(
            incidents = incidents.size,
            alerts = alertItems.size,
            serviceRows = serviceRowsDeferred.await(),
            hosts = hosts,
        )

        OverviewResponse(
            systemStatus = systemStatus(counts),
            kpis = kpis(eventMetrics, traceMetrics, logMetrics, issueItems.size, monitors),
            serviceHealth = serviceRowsDeferred.await(),
            telemetry = telemetry(eventMetrics, traceMetrics, logMetrics, deploys),
            triage = OverviewTriageData(
                incidents = incidents,
                alerts = alertItems,
                issues = issueItems,
                security = emptyList<OverviewSecurityItem>(),
            ),
            infra = infra(hosts, latestMetrics, containersDeferred.await()),
            uptime = uptime(monitors, syntheticFailingDeferred.await(), statusPageCountDeferred.await()),
            deploys = deploys,
            activity = activity(incidents, deploys, issueItems),
        )
    }

    private fun overviewCounts(
        incidents: Int,
        alerts: Int,
        serviceRows: List<OverviewServiceRow>,
        hosts: List<HostData>,
    ): OverviewCounts =
        OverviewCounts(
            incidents = incidents,
            alerts = alerts,
            degraded = serviceRows.count { row -> row.status == "bad" || row.status == "warn" },
            hostsOffline = hosts.count { host -> host.status == "offline" },
        )

    private fun systemStatus(counts: OverviewCounts): OverviewSystemStatus {
        val severity = when {
            counts.incidents > 0 || counts.hostsOffline > 0 -> "bad"
            counts.alerts > 0 || counts.degraded > 0 -> "warn"
            else -> "good"
        }
        val state = when (severity) {
            "bad" -> "Action needed"
            "warn" -> "Degraded"
            else -> "Healthy"
        }
        val summary = when (severity) {
            "bad" -> "Active incidents or offline hosts need attention."
            "warn" -> "Some services or alerts are degraded in the current workspace."
            else -> "No active incidents, firing alerts, degraded services, or offline hosts."
        }
        return OverviewSystemStatus(
            state = state,
            severity = severity,
            counts = counts,
            ai = OverviewStatusAi(summary = summary, incidentId = null),
        )
    }

    private fun kpis(
        eventMetrics: EventMetrics,
        traceMetrics: TraceMetrics,
        logMetrics: LogMetrics,
        openIssues: Int,
        monitors: List<UptimeMonitorResponse>,
    ): List<OverviewKpi> {
        val uptimePct = uptimePercent(monitors)
        return listOf(
            metricKpi(
                MetricKpiSpec(
                    id = "errors",
                    label = "Errors · 24h",
                    current = eventMetrics.currentErrors + logMetrics.currentErrors,
                    previous = eventMetrics.previousErrors + logMetrics.previousErrors,
                    increaseIsBad = true,
                    status = toneForCount(eventMetrics.currentErrors + logMetrics.currentErrors),
                    spark = sumSeries(eventMetrics.errorSpark, logMetrics.errorSpark),
                ),
            ),
            metricKpi(
                MetricKpiSpec(
                    id = "latency",
                    label = "p95 latency",
                    current = traceMetrics.p95Ms.toLong(),
                    previous = traceMetrics.previousP95Ms.toLong(),
                    increaseIsBad = true,
                    status = p95Tone(traceMetrics.p95Ms),
                    unit = "ms",
                    spark = traceMetrics.latencySpark,
                ),
            ),
            metricKpi(
                MetricKpiSpec(
                    id = "throughput",
                    label = "Throughput",
                    current = traceMetrics.currentThroughputPerMinute.toLong(),
                    previous = traceMetrics.previousThroughputPerMinute.toLong(),
                    increaseIsBad = false,
                    status = "neutral",
                    unit = "req/min",
                    spark = traceMetrics.throughputSpark,
                ),
            ),
            apdexKpi(traceMetrics),
            metricKpi(
                MetricKpiSpec(
                    id = "issues",
                    label = "Open issues",
                    current = openIssues.toLong(),
                    previous = eventMetrics.previousOpenIssues.toLong(),
                    increaseIsBad = true,
                    status = toneForCount(openIssues.toLong()),
                    unit = "+${eventMetrics.newIssues} new",
                    spark = eventMetrics.issueSpark,
                ),
            ),
            OverviewKpi(
                id = "uptime",
                label = "Uptime · 24h",
                value = formatPercentValue(uptimePct),
                unit = "%",
                delta = OverviewKpiDelta(value = UPTIME_SLO_LABEL, tone = "neutral"),
                status = uptimeTone(uptimePct),
                spark = uptimeSpark(monitors),
            ),
        )
    }

    private fun metricKpi(spec: MetricKpiSpec): OverviewKpi =
        OverviewKpi(
            id = spec.id,
            label = spec.label,
            value = formatCount(spec.current),
            unit = spec.unit,
            delta = percentDelta(spec.current.toDouble(), spec.previous.toDouble(), spec.increaseIsBad),
            status = spec.status,
            spark = filledSeries(spec.spark),
        )

    private fun apdexKpi(traceMetrics: TraceMetrics): OverviewKpi =
        OverviewKpi(
            id = "apdex",
            label = "Apdex",
            value = String.format(Locale.US, "%.2f", traceMetrics.apdex),
            delta = decimalDelta(traceMetrics.apdex, traceMetrics.previousApdex),
            status = apdexTone(traceMetrics.apdex),
            spark = traceMetrics.apdexSpark,
        )

    private fun telemetry(
        eventMetrics: EventMetrics,
        traceMetrics: TraceMetrics,
        logMetrics: LogMetrics,
        deploys: List<OverviewDeployRow>,
    ): OverviewTelemetryData {
        val deployAtPct = if (deploys.isEmpty()) 0 else PERCENT.roundToInt()
        val deployLabel = deploys.firstOrNull()?.version ?: DEFAULT_DEPLOY_LABEL
        return OverviewTelemetryData(
            errors = filledSeries(sumSeries(eventMetrics.errorSpark, logMetrics.errorSpark)),
            latency = filledSeries(traceMetrics.latencySpark),
            throughput = filledSeries(traceMetrics.throughputSpark),
            logs = filledSeries(logMetrics.volumeSpark),
            deployAtPct = deployAtPct,
            deployLabel = deployLabel,
        )
    }

    private fun infra(
        hosts: List<HostData>,
        latestMetrics: Map<Int, LatestMetrics?>,
        containerCounts: ContainerCounts,
    ): OverviewInfraData {
        val metrics = latestMetrics.values.filterNotNull()
        val offline = hosts.firstOrNull { host -> host.status == "offline" }
        val up = hosts.count { host -> host.status == "online" }
        return OverviewInfraData(
            gauges = listOf(
                gauge("CPU", averageInt(metrics) { metric -> metric.cpuPercent.toDouble() }),
                gauge("Mem", averageInt(metrics) { metric -> metric.memPercent.toDouble() }),
                gauge("Disk", averageInt(metrics) { metric -> metric.diskPercent.toDouble() }),
                gauge(
                    "Net",
                    averageInt(metrics) { metric ->
                        (metric.netRecvMbps ?: 0f).toDouble() + (metric.netSentMbps ?: 0f).toDouble()
                    },
                ),
            ),
            containers = containerCounts.containers,
            pods = containerCounts.pods,
            upLabel = "$up/${hosts.size} up",
            offlineNode = offline?.displayName ?: offline?.hostname,
        )
    }

    private fun uptime(
        monitors: List<UptimeMonitorResponse>,
        syntheticFailing: String?,
        statusPageCount: Int,
    ): OverviewUptimeData {
        val up = monitors.count { monitor -> monitor.status == "up" }
        return OverviewUptimeData(
            monitors = monitors.take(MAX_ATTENTION_ROWS).map(::uptimeMonitor),
            upLabel = "$up/${monitors.size} up",
            syntheticFailing = syntheticFailing,
            statusPages = "$statusPageCount status pages",
        )
    }

    private fun uptimeMonitor(monitor: UptimeMonitorResponse): OverviewUptimeMonitor {
        val down = monitor.status == "down"
        val state = when (monitor.status) {
            "down" -> "down"
            "degraded", "pending" -> "warn"
            else -> "up"
        }
        val uptimeLabel = monitor.uptime24h?.let { pct -> "${formatPercentValue(pct.toDouble())}%" }
            ?: monitor.status.uppercase(Locale.US)
        return OverviewUptimeMonitor(
            name = monitor.name,
            bars = List(SERIES_BUCKET_COUNT) { state },
            uptimeLabel = uptimeLabel,
            down = down,
        )
    }

    private fun activity(
        incidents: List<OverviewIncidentItem>,
        deploys: List<OverviewDeployRow>,
        issues: List<OverviewIssueItem>,
    ): List<OverviewActivityItem> =
        (
            incidents.map { incident ->
                OverviewActivityItem("incident", "${incident.id} ${incident.title}", incident.ageLabel)
            } + deploys.map { deploy ->
                OverviewActivityItem("deploy", "${deploy.version} released to ${deploy.service}", deploy.ageLabel)
            } + issues.map { issue ->
                OverviewActivityItem("incident", issue.title, issue.ageLabel)
            }
            ).take(MAX_ACTIVITY_ROWS)

    private fun loadProjects(organizationId: Int): List<ProjectRef> =
        transaction {
            Projects
                .selectAll()
                .where { Projects.organization_id eq organizationId }
                .map { row -> ProjectRef(row[Projects.id], row[Projects.name]) }
        }

    private fun loadDeploys(organizationId: Int): List<OverviewDeployRow> =
        transaction {
            Releases
                .innerJoin(Projects)
                .selectAll()
                .where { Projects.organization_id eq organizationId }
                .orderBy(Releases.created_at to SortOrder.DESC)
                .limit(MAX_DEPLOY_ROWS)
                .map { row ->
                    val age = ageLabel(row[Releases.created_at])
                    OverviewDeployRow(
                        version = row[Releases.version],
                        service = row[Projects.name],
                        status = "neutral",
                        label = "released",
                        ageLabel = age,
                    )
                }
        }

    private fun loadStatusPageCount(organizationId: Int): Int =
        transaction {
            StatusPages
                .selectAll()
                .where { StatusPages.organizationId eq organizationId }
                .count()
                .toInt()
        }

    private fun loadIncidentItems(organizationId: Int): List<OverviewIncidentItem> =
        transaction {
            AlertEpisodes
                .selectAll()
                .where {
                    (AlertEpisodes.organizationId eq organizationId) and
                        (AlertEpisodes.status eq "FIRING")
                }
                .orderBy(AlertEpisodes.openedAt to SortOrder.DESC)
                .limit(MAX_ATTENTION_ROWS)
                .map { row ->
                    OverviewIncidentItem(
                        id = "INC-${row[AlertEpisodes.id].value}",
                        title = row[AlertEpisodes.sourceName],
                        priority = "P2",
                        status = row[AlertEpisodes.status],
                        owner = "On-call",
                        ageLabel = ageLabel(row[AlertEpisodes.openedAt].toEpochMilliseconds()),
                    )
                }
        }

    private fun loadAlertItems(organizationId: Int): List<OverviewAlertItem> =
        transaction {
            HostAlerts
                .innerJoin(Hosts)
                .selectAll()
                .where {
                    (HostAlerts.organization_id eq organizationId) and
                        (HostAlerts.enabled eq true) and
                        HostAlerts.last_triggered_at.isNotNull()
                }
                .orderBy(HostAlerts.last_triggered_at to SortOrder.DESC)
                .limit(MAX_ATTENTION_ROWS)
                .map { row ->
                    val host = row[Hosts.display_name] ?: row[Hosts.hostname]
                    OverviewAlertItem(
                        title = "${row[HostAlerts.metric]} ${row[HostAlerts.condition]} ${row[HostAlerts.threshold]}",
                        detail = host,
                        level = alertLevel(row[HostAlerts.alert_priority]),
                        ageLabel = row[HostAlerts.last_triggered_at]?.let { ageLabel(it.toEpochMilliseconds()) } ?: "",
                    )
                }
        }

    private suspend fun loadLatestMetrics(
        organizationId: Int,
        hosts: List<HostData>,
        demoEpochMs: Long?,
    ): Map<Int, LatestMetrics?> {
        if (hosts.isEmpty()) return emptyMap()
        return suspendRunCatching {
            monitorService.getLatestMetricsForHosts(hosts.map { host -> host.id }, organizationId, demoEpochMs)
        }.getOrElse { error ->
            logger.warn(error) { "Failed to load latest host metrics for overview" }
            hosts.associate { host -> host.id to null }
        }
    }

    private suspend fun loadEventMetrics(
        projectIds: List<Long>,
        demoEpochMs: Long?,
    ): EventMetrics {
        val clause = projectClause(projectIds) ?: return EventMetrics.EMPTY
        val now = nowClause(demoEpochMs)
        val countRow = firstRow(
            """
            SELECT
                countIf(timestamp >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR) AS currentErrors,
                countIf(
                    timestamp < $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
                    AND timestamp >= $now - INTERVAL $COMPARISON_WINDOW_HOURS HOUR
                ) AS previousErrors
            FROM events
            WHERE $clause
              AND event_type = 'error'
              AND timestamp >= $now - INTERVAL $COMPARISON_WINDOW_HOURS HOUR
              AND timestamp <= $now
            """.trimIndent(),
        )
        val issueRow = firstRow(
            """
            SELECT
                count() AS openIssues,
                countIf(first_seen >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR) AS newIssues
            FROM issues FINAL
            WHERE $clause
              AND status = 'unresolved'
              AND first_seen <= $now
            """.trimIndent(),
        )
        return EventMetrics(
            currentErrors = countRow.long("currentErrors"),
            previousErrors = countRow.long("previousErrors"),
            errorSpark = hourlySeries(
                """
                SELECT
                    toUInt32(dateDiff('hour', $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR, timestamp)) AS bucket,
                    count() AS value
                FROM events
                WHERE $clause
                  AND event_type = 'error'
                  AND timestamp >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
                  AND timestamp <= $now
                GROUP BY bucket
                """.trimIndent(),
            ),
            previousOpenIssues = issueRow.int("openIssues"),
            newIssues = issueRow.int("newIssues"),
            issueSpark = hourlySeries(
                """
                SELECT
                    toUInt32(dateDiff('hour', $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR, first_seen)) AS bucket,
                    count() AS value
                FROM issues FINAL
                WHERE $clause
                  AND first_seen >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
                  AND first_seen <= $now
                GROUP BY bucket
                """.trimIndent(),
            ),
        )
    }

    private suspend fun loadIssueItems(
        projectIds: List<Long>,
        demoEpochMs: Long?,
    ): List<OverviewIssueItem> {
        val clause = projectClause(projectIds) ?: return emptyList()
        val now = nowClause(demoEpochMs)
        return rows(
            """
            SELECT
                title,
                toString(level) AS level,
                event_count AS eventCount,
                dateDiff('second', last_seen, $now) AS ageSeconds
            FROM issues FINAL
            WHERE $clause
              AND status = 'unresolved'
              AND last_seen <= $now
            ORDER BY last_seen DESC
            LIMIT $MAX_ATTENTION_ROWS
            """.trimIndent(),
        ).map { row ->
            OverviewIssueItem(
                level = normalizeIssueLevel(row.string("level")),
                title = row.string("title").ifBlank { "Unresolved issue" },
                detail = "${row.long("eventCount")} events",
                ageLabel = durationLabel(row.long("ageSeconds") * MILLIS_PER_SECOND),
            )
        }
    }

    private suspend fun loadTraceMetrics(
        organizationId: Int,
        demoEpochMs: Long?,
    ): TraceMetrics {
        val now = nowClause(demoEpochMs)
        val currentSubquery = traceSummarySubquery(organizationId, demoEpochMs)
        val previousSubquery = traceSummarySubquery(organizationId, demoEpochMs, previousWindow = true)
        val currentRow = firstRow(
            """
            SELECT
                count() AS currentTraces,
                sum(has_error) AS currentErrors,
                quantileExact(0.95)(duration_ns / $NANOS_PER_MILLI) AS p95Ms,
                countIf(duration_ns <= $APDEX_SATISFIED_MS * $NANOS_PER_MILLI) AS satisfied,
                countIf(duration_ns <= $APDEX_TOLERATED_MS * $NANOS_PER_MILLI) AS tolerated
            FROM ($currentSubquery)
            """.trimIndent(),
        )
        val previousRow = firstRow(
            """
            SELECT
                count() AS previousTraces,
                quantileExact(0.95)(duration_ns / $NANOS_PER_MILLI) AS previousP95Ms,
                countIf(duration_ns <= $APDEX_SATISFIED_MS * $NANOS_PER_MILLI) AS previousSatisfied,
                countIf(duration_ns <= $APDEX_TOLERATED_MS * $NANOS_PER_MILLI) AS previousTolerated
            FROM ($previousSubquery)
            """.trimIndent(),
        )
        val currentTraces = currentRow.long("currentTraces")
        val previousTraces = previousRow.long("previousTraces")
        return TraceMetrics(
            currentThroughputPerMinute = (currentTraces / MINUTES_PER_DAY).roundToInt(),
            previousThroughputPerMinute = (previousTraces / MINUTES_PER_DAY).roundToInt(),
            currentErrors = currentRow.long("currentErrors"),
            p95Ms = currentRow.double("p95Ms").roundToInt(),
            previousP95Ms = previousRow.double("previousP95Ms").roundToInt(),
            apdex = apdex(currentRow.long("satisfied"), currentRow.long("tolerated"), currentTraces),
            previousApdex = apdex(
                previousRow.long("previousSatisfied"),
                previousRow.long("previousTolerated"),
                previousTraces,
            ),
            latencySpark = hourlyTraceSeries(
                currentSubquery,
                "quantileExact(0.95)(duration_ns / $NANOS_PER_MILLI)",
                now,
            ),
            throughputSpark = hourlyTraceSeries(currentSubquery, "count()", now),
            apdexSpark = hourlyTraceSeries(
                currentSubquery,
                "round(100 * countIf(duration_ns <= $APDEX_SATISFIED_MS * $NANOS_PER_MILLI) / greatest(count(), 1))",
                now,
            ),
        )
    }

    private suspend fun loadServiceRows(
        organizationId: Int,
        demoEpochMs: Long?,
    ): List<OverviewServiceRow> {
        val currentSubquery = traceSummarySubquery(organizationId, demoEpochMs)
        return rows(
            """
            SELECT
                root_service AS service,
                anyIf(env, env != '') AS env,
                count() AS traces,
                sum(has_error) AS errors,
                quantileExact(0.95)(duration_ns / $NANOS_PER_MILLI) AS p95Ms,
                countIf(duration_ns <= $APDEX_SATISFIED_MS * $NANOS_PER_MILLI) AS satisfied,
                countIf(duration_ns <= $APDEX_TOLERATED_MS * $NANOS_PER_MILLI) AS tolerated
            FROM ($currentSubquery)
            WHERE root_service != ''
            GROUP BY root_service
            ORDER BY errors DESC, traces DESC
            LIMIT $MAX_SERVICE_ROWS
            """.trimIndent(),
        ).map { row ->
            val traces = row.long("traces")
            val errors = row.long("errors")
            val p95Ms = row.double("p95Ms").roundToInt()
            val apdex = apdex(row.long("satisfied"), row.long("tolerated"), traces)
            val errorPct = percent(errors, traces)
            val status = serviceStatus(errorPct, p95Ms, apdex)
            OverviewServiceRow(
                name = row.string("service"),
                env = row.string("env").ifBlank { "prod" },
                status = status,
                reqPerMin = (traces / MINUTES_PER_DAY).roundToInt(),
                errorPct = roundOne(errorPct),
                p95Ms = p95Ms.takeIf { value -> value > 0 },
                apdex = roundTwo(apdex).takeIf { value -> value > 0.0 },
                trend = listOf(errors.toInt().coerceAtLeast(0)),
                issues = errors.toInt().coerceAtLeast(0),
                deploy = OverviewServiceDeploy(version = DEFAULT_DEPLOY_LABEL, ageLabel = "", tone = "neutral"),
            )
        }
    }

    private suspend fun loadLogMetrics(
        organizationId: Int,
        demoEpochMs: Long?,
    ): LogMetrics {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val now = nowClause(demoEpochMs)
        val row = firstRow(
            """
            SELECT
                countIf(
                    timestamp >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
                    AND level IN ('error', 'fatal')
                ) AS currentErrors,
                countIf(
                    timestamp < $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
                    AND timestamp >= $now - INTERVAL $COMPARISON_WINDOW_HOURS HOUR
                    AND level IN ('error', 'fatal')
                ) AS previousErrors
            FROM logs
            WHERE $orgClause
              AND timestamp >= $now - INTERVAL $COMPARISON_WINDOW_HOURS HOUR
              AND timestamp <= $now
            """.trimIndent(),
        )
        return LogMetrics(
            currentErrors = row.long("currentErrors"),
            previousErrors = row.long("previousErrors"),
            errorSpark = hourlyLogSeries(orgClause, "countIf(level IN ('error', 'fatal'))", now),
            volumeSpark = hourlyLogSeries(orgClause, "count()", now),
        )
    }

    private suspend fun loadContainerCounts(
        organizationId: Int,
        demoEpochMs: Long?,
    ): ContainerCounts {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val now = nowClause(demoEpochMs)
        val row = firstRow(
            """
            SELECT
                count() AS containers,
                uniqIf(tags['pod_name'], tags['pod_name'] != '') AS pods
            FROM containers_latest_by_host FINAL
            WHERE $orgClause
              AND timestamp >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
              AND timestamp <= $now
            """.trimIndent(),
        )
        return ContainerCounts(row.int("containers"), row.int("pods"))
    }

    private suspend fun loadSyntheticFailing(
        organizationId: Int,
        demoEpochMs: Long?,
    ): String? {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val now = nowClause(demoEpochMs)
        return firstRow(
            """
            SELECT test_name AS name
            FROM synthetic_results
            WHERE $orgClause
              AND timestamp >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
              AND timestamp <= $now
              AND status = 'failed'
            ORDER BY timestamp DESC
            LIMIT 1
            """.trimIndent(),
        ).string("name").ifBlank { null }
    }

    internal fun traceSummarySubquery(
        organizationId: Int,
        demoEpochMs: Long?,
        previousWindow: Boolean = false,
    ): String {
        val orgClause = ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        val now = nowClause(demoEpochMs)
        val currentStart = "$now - INTERVAL $CURRENT_WINDOW_HOURS HOUR"
        val previousStart = "$now - INTERVAL $COMPARISON_WINDOW_HOURS HOUR"
        val liveBoundary = "toStartOfHour($now - INTERVAL $LIVE_TRACE_WINDOW_HOURS HOUR)"
        val start = if (previousWindow) previousStart else currentStart
        val end = if (previousWindow) currentStart else now
        val finalizedFilters = mutableListOf(
            orgClause,
            "trace_bucket >= toStartOfHour($start)",
            "trace_bucket < toStartOfHour($end)",
        )
        if (!previousWindow) {
            finalizedFilters.add("trace_bucket < $liveBoundary")
        }

        val finalizedPart = """
            SELECT
                trace_id_canonical,
                root_service,
                root_resource,
                root_name,
                env,
                span_count,
                duration_ns,
                trace_start,
                toInt64(toUnixTimestamp64Nano(trace_start)) AS start_ns,
                has_error,
                error_count,
                source
            FROM apm_traces_final
            WHERE ${finalizedFilters.joinToString(" AND ")}
        """.trimIndent()

        if (previousWindow) return finalizedPart

        val livePart = """
            SELECT
                trace_id_canonical,
                argMinMerge(root_service_state) AS root_service,
                argMinMerge(root_resource_state) AS root_resource,
                argMinMerge(root_name_state) AS root_name,
                any(env) AS env,
                toUInt32(sumMerge(span_count_state)) AS span_count,
                toUInt64(greatest(
                    0,
                    toUnixTimestamp64Nano(maxMerge(trace_end_state)) -
                        toUnixTimestamp64Nano(minMerge(trace_start_state))
                )) AS duration_ns,
                minMerge(trace_start_state) AS trace_start,
                toInt64(toUnixTimestamp64Nano(minMerge(trace_start_state))) AS start_ns,
                toUInt8(sumMerge(error_count_state) > 0) AS has_error,
                toUInt32(sumMerge(error_count_state)) AS error_count,
                argMinMerge(source_state) AS source
            FROM apm_trace_summaries
            WHERE $orgClause
              AND bucket_start >= toStartOfHour($currentStart)
              AND bucket_start >= $liveBoundary
              AND bucket_start < toStartOfHour($now) + INTERVAL 1 HOUR
            GROUP BY organization_id, trace_id_canonical
        """.trimIndent()

        return "$finalizedPart\nUNION ALL\n$livePart"
    }

    private fun nowClause(demoEpochMs: Long?): String =
        if (demoEpochMs != null) {
            "toDateTime64(${formatEpochSeconds(demoEpochMs)}, $DATETIME64_MILLIS_PRECISION)"
        } else {
            "now()"
        }

    private fun formatEpochSeconds(epochMs: Long): String {
        val seconds = epochMs / MILLIS_PER_SECOND
        val millis = epochMs % MILLIS_PER_SECOND
        return "$seconds.${millis.toString().padStart(DATETIME64_MILLIS_PRECISION, '0')}"
    }

    private suspend fun hourlyTraceSeries(
        subquery: String,
        expression: String,
        now: String,
    ): List<Int> =
        hourlySeries(
            """
            SELECT
                toUInt32(dateDiff('hour', $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR, trace_start)) AS bucket,
                $expression AS value
            FROM ($subquery)
            GROUP BY bucket
            """.trimIndent(),
        )

    private suspend fun hourlyLogSeries(
        orgClause: String,
        expression: String,
        now: String,
    ): List<Int> =
        hourlySeries(
            """
            SELECT
                toUInt32(dateDiff('hour', $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR, timestamp)) AS bucket,
                $expression AS value
            FROM logs
            WHERE $orgClause
              AND timestamp >= $now - INTERVAL $CURRENT_WINDOW_HOURS HOUR
              AND timestamp <= $now
            GROUP BY bucket
            """.trimIndent(),
        )

    private suspend fun hourlySeries(query: String): List<Int> {
        val values = MutableList(SERIES_BUCKET_COUNT) { 0 }
        rows(query).forEach { row ->
            val bucket = row.int("bucket")
            if (bucket in values.indices) values[bucket] = row.double("value").roundToInt()
        }
        return values
    }

    private suspend fun firstRow(query: String): JsonObject =
        rows(query).firstOrNull() ?: JsonObject(emptyMap())

    private suspend fun rows(query: String): List<JsonObject> {
        if (!ClickHouseClient.isInitialized()) return emptyList()
        return suspendRunCatching {
            ClickHouseClient.executeWithFormat(query, "JSONEachRow")
        }.map { body ->
            body.lines()
                .filter { line -> line.isNotBlank() }
                .mapNotNull(::parseRow)
        }.getOrElse { error ->
            logger.warn(error) { "Overview ClickHouse query failed" }
            emptyList()
        }
    }

    private fun parseRow(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()

    private fun projectClause(projectIds: List<Long>): String? {
        if (projectIds.isEmpty()) return null
        if (projectIds.size == 1) return ClickHouseQueryUtils.projectIdClause(projectIds.first())
        return "toInt64(project_id) IN (${projectIds.joinToString(",")})"
    }

    private fun percent(value: Long, total: Long): Double =
        if (total > 0) value.toDouble() / total * PERCENT else 0.0

    private fun apdex(satisfied: Long, tolerated: Long, total: Long): Double {
        if (total <= 0) return 0.0
        val tolerating = (tolerated - satisfied).coerceAtLeast(0)
        return (satisfied + tolerating / 2.0) / total
    }

    private fun serviceStatus(errorPct: Double, p95Ms: Int, apdex: Double): String =
        when {
            errorPct >= BAD_ERROR_PCT || p95Ms >= BAD_P95_MS || apdex in 0.0..<WARN_APDEX -> "bad"
            errorPct >= WARN_ERROR_PCT || p95Ms >= WARN_P95_MS || apdex in WARN_APDEX..<GOOD_APDEX -> "warn"
            else -> "good"
        }

    private fun alertLevel(priority: String?): String =
        when (priority?.lowercase(Locale.US)) {
            "p1", "critical" -> "error"
            else -> "warn"
        }

    private fun normalizeIssueLevel(level: String): String =
        when (level.lowercase(Locale.US)) {
            "warning" -> "warn"
            "fatal", "error", "warn", "info" -> level.lowercase(Locale.US)
            else -> "info"
        }

    private fun gauge(label: String, pct: Int): OverviewInfraGauge =
        OverviewInfraGauge(label = label, pct = pct, tone = resourceTone(pct))

    private fun resourceTone(pct: Int): String =
        when {
            pct >= BAD_RESOURCE_PCT -> "bad"
            pct >= WARN_RESOURCE_PCT -> "warn"
            else -> "good"
        }

    private fun averageInt(metrics: List<LatestMetrics>, selector: (LatestMetrics) -> Double): Int =
        metrics.takeIf { rows -> rows.isNotEmpty() }
            ?.map(selector)
            ?.average()
            ?.roundToInt()
            ?.coerceIn(MIN_PERCENT_INT, MAX_PERCENT_INT)
            ?: 0

    private fun percentDelta(current: Double, previous: Double, increaseIsBad: Boolean): OverviewKpiDelta {
        val change = when {
            previous > 0.0 -> (current - previous) / previous * PERCENT
            current > 0.0 -> PERCENT
            else -> 0.0
        }
        val direction = direction(change)
        val tone = deltaTone(change, increaseIsBad)
        return OverviewKpiDelta(value = "${abs(change).roundToInt()}%", direction = direction, tone = tone)
    }

    private fun decimalDelta(current: Double, previous: Double): OverviewKpiDelta {
        val change = current - previous
        return OverviewKpiDelta(
            value = String.format(Locale.US, "%.2f", abs(change)),
            direction = direction(change),
            tone = deltaTone(change, increaseIsBad = false),
        )
    }

    private fun direction(change: Double): String? =
        when {
            change > 0.0 -> "up"
            change < 0.0 -> "down"
            else -> null
        }

    private fun deltaTone(change: Double, increaseIsBad: Boolean): String =
        when {
            change == 0.0 -> "neutral"
            increaseIsBad && change > 0.0 -> "bad"
            increaseIsBad -> "good"
            change > 0.0 -> "good"
            else -> "bad"
        }

    private fun formatCount(value: Long): String =
        when {
            value >= COUNT_SUFFIX_MILLION -> String.format(
                Locale.US,
                "%.1fm",
                value.toDouble() / COUNT_SUFFIX_MILLION,
            )
            value >= COUNT_SUFFIX_THOUSAND -> String.format(
                Locale.US,
                "%.1fk",
                value.toDouble() / COUNT_SUFFIX_THOUSAND,
            )
            else -> value.toString()
        }

    private fun formatPercentValue(value: Double): String =
        if (value == 0.0) "0" else String.format(Locale.US, "%.2f", value)

    private fun toneForCount(value: Long): String =
        if (value > 0L) "bad" else "good"

    private fun p95Tone(ms: Int): String =
        when {
            ms >= BAD_P95_MS -> "bad"
            ms >= WARN_P95_MS -> "warn"
            else -> "good"
        }

    private fun apdexTone(apdex: Double): String =
        when {
            apdex == 0.0 -> "neutral"
            apdex < WARN_APDEX -> "bad"
            apdex < GOOD_APDEX -> "warn"
            else -> "good"
        }

    private fun uptimeTone(uptime: Double): String =
        when {
            uptime == 0.0 -> "neutral"
            uptime < UPTIME_SLO_PCT -> "bad"
            else -> "good"
        }

    private fun uptimePercent(monitors: List<UptimeMonitorResponse>): Double {
        if (monitors.isEmpty()) return 0.0
        val explicit = monitors.mapNotNull { monitor -> monitor.uptime24h?.toDouble() }
        if (explicit.isNotEmpty()) return explicit.average()
        return monitors.count { monitor -> monitor.status == "up" }.toDouble() / monitors.size * PERCENT
    }

    private fun uptimeSpark(monitors: List<UptimeMonitorResponse>): List<Int> {
        val value = uptimePercent(monitors).roundToInt()
        return List(SERIES_BUCKET_COUNT) { value }
    }

    private fun sumSeries(left: List<Int>, right: List<Int>): List<Int> =
        filledSeries(left).zip(filledSeries(right)) { a, b -> a + b }

    private fun filledSeries(values: List<Int>): List<Int> =
        if (values.isEmpty()) {
            List(SERIES_BUCKET_COUNT) { 0 }
        } else {
            values.take(SERIES_BUCKET_COUNT).let { trimmed ->
                trimmed + List(SERIES_BUCKET_COUNT - trimmed.size) { trimmed.lastOrNull() ?: 0 }
            }
        }

    private fun roundOne(value: Double): Double =
        (value * ONE_DECIMAL_SCALE).roundToInt() / ONE_DECIMAL_SCALE

    private fun roundTwo(value: Double): Double =
        (value * PERCENT).roundToInt() / PERCENT

    private fun ageLabel(epochMillis: Long): String =
        durationLabel(Clock.System.now().toEpochMilliseconds() - epochMillis)

    private fun durationLabel(ageMillis: Long): String {
        val seconds = (ageMillis / MILLIS_PER_SECOND).coerceAtLeast(0)
        val minutes = seconds / SECONDS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        val days = hours / HOURS_PER_DAY
        return when {
            days >= DAYS_PER_MONTH -> "${days / DAYS_PER_MONTH}mo"
            days > 0L -> "${days}d"
            hours > 0L -> "${hours}h"
            minutes > 0L -> "${minutes}m"
            else -> "now"
        }
    }
}

private data class ProjectRef(
    val id: Long,
    val name: String,
)

private data class MetricKpiSpec(
    val id: String,
    val label: String,
    val current: Long,
    val previous: Long,
    val increaseIsBad: Boolean,
    val status: String,
    val spark: List<Int>,
    val unit: String? = null,
)

private data class EventMetrics(
    val currentErrors: Long,
    val previousErrors: Long,
    val errorSpark: List<Int>,
    val previousOpenIssues: Int,
    val newIssues: Int,
    val issueSpark: List<Int>,
) {
    companion object {
        val EMPTY = EventMetrics(0, 0, emptyList(), 0, 0, emptyList())
    }
}

private data class TraceMetrics(
    val currentThroughputPerMinute: Int,
    val previousThroughputPerMinute: Int,
    val currentErrors: Long,
    val p95Ms: Int,
    val previousP95Ms: Int,
    val apdex: Double,
    val previousApdex: Double,
    val latencySpark: List<Int>,
    val throughputSpark: List<Int>,
    val apdexSpark: List<Int>,
) {
    companion object {
        val EMPTY = TraceMetrics(0, 0, 0, 0, 0, 0.0, 0.0, emptyList(), emptyList(), emptyList())
    }
}

private data class LogMetrics(
    val currentErrors: Long,
    val previousErrors: Long,
    val errorSpark: List<Int>,
    val volumeSpark: List<Int>,
) {
    companion object {
        val EMPTY = LogMetrics(0, 0, emptyList(), emptyList())
    }
}

private data class ContainerCounts(
    val containers: Int,
    val pods: Int,
)

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: this[name]?.jsonPrimitive?.longOrNull?.toInt() ?: 0

private fun JsonObject.long(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: 0L

private fun JsonObject.double(name: String): Double =
    this[name]?.jsonPrimitive?.doubleOrNull ?: 0.0
