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

package com.moneat.events.services

import com.moneat.config.ClickHouseClient
import com.moneat.events.models.ProjectStatsResponse
import com.moneat.events.models.ReleaseMarker
import com.moneat.shared.models.IssueStatuses
import com.moneat.shared.services.CacheService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.sentry.ISpan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

class ProjectStatsService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    suspend fun getProjectStats(
        projectId: Long,
        period: String = "7d",
        parentSpan: ISpan? = null,
        demoEpochMs: Long? = null
    ): ProjectStatsResponse =
        CacheService.cached("cache:project_stats:$projectId:$period:${demoEpochMs ?: ""}", 60, parentSpan) {
            val retentionDays = queryHelper.getProjectRetentionDays(projectId)
            val hoursBack =
                when (period) {
                    "24h" -> 24
                    "7d" -> 168
                    "30d" -> 720
                    "90d" -> 2160
                    else -> 168
                }

            val intervalMinutes =
                when (period) {
                    "24h" -> 60
                    "7d" -> 360
                    "30d" -> 1440
                    "90d" -> 4320
                    else -> 360
                }

            val nowSql = queryHelper.demoNowClause(demoEpochMs)
            val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)

            val totalEventsQuery =
                """
            SELECT count() as total
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
                """.trimIndent()

            val totalIssuesQuery =
                """
            SELECT count(DISTINCT issue_id) as total
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
                """.trimIndent()

            val unresolvedIssuesQuery =
                """
            SELECT count() as total
            FROM (
                SELECT issue_id
                FROM `$clickhouseDb`.issues FINAL
                WHERE $projectIdClause
                    AND status = 'unresolved'
                    AND ${queryHelper.timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            )
            FORMAT JSONEachRow
                """.trimIndent()

            val affectedUsersQuery =
                """
            SELECT uniq(user_id) as total
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND user_id != ''
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
                """.trimIndent()

            val eventsTimelineQuery =
                """
            SELECT 
                formatDateTime(toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE), '%Y-%m-%dT%H:%i:%SZ', 'UTC') as time,
                count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
                """.trimIndent()

            val eventsByLevelQuery =
                """
            SELECT level, count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY level
            FORMAT JSONEachRow
                """.trimIndent()

            val eventsByPlatformQuery =
                """
            SELECT platform, count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND platform != ''
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY platform
            ORDER BY count DESC
            LIMIT 10
            FORMAT JSONEachRow
                """.trimIndent()

            val eventsByBrowserQuery =
                """
            SELECT browser_name, count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND browser_name != ''
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY browser_name
            ORDER BY count DESC
            LIMIT 10
            FORMAT JSONEachRow
                """.trimIndent()

            val eventsByEnvironmentQuery =
                """
            SELECT environment, count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND event_type = 'error'
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND environment != ''
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY environment
            FORMAT JSONEachRow
                """.trimIndent()

            val issuesByStatusQuery =
                """
            SELECT status, count() as count
            FROM `$clickhouseDb`.issues FINAL
            WHERE $projectIdClause
                AND ${queryHelper.timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            GROUP BY status
            FORMAT JSONEachRow
                """.trimIndent()

            val topIssuesQuery =
                """
            SELECT issue_id, any(message) as title, count() as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND timestamp >= $nowSql - INTERVAL $hoursBack HOUR
                AND event_type = 'error'
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays, demoEpochMs)}
            GROUP BY issue_id
            ORDER BY count DESC
            LIMIT 10
            FORMAT JSONEachRow
                """.trimIndent()

            val usersTimelineQuery =
                """
            SELECT 
                formatDateTime(toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE), '%Y-%m-%dT%H:%i:%SZ', 'UTC') as time,
                uniq(user_id) as count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause
                AND timestamp >= now() - INTERVAL $hoursBack HOUR
                AND user_id != ''
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
            GROUP BY time
            ORDER BY time
            FORMAT JSONEachRow
                """.trimIndent()

            try {
                coroutineScope {
                    val totalEventsDeferred = async { queryHelper.executeScalarQuery(totalEventsQuery, parentSpan) }
                    val totalIssuesDeferred = async { queryHelper.executeScalarQuery(totalIssuesQuery, parentSpan) }
                    val unresolvedIssuesDeferred =
                        async { queryHelper.executeScalarQuery(unresolvedIssuesQuery, parentSpan) }
                    val affectedUsersDeferred = async { queryHelper.executeScalarQuery(affectedUsersQuery, parentSpan) }
                    val eventsTimelineDeferred =
                        async { queryHelper.executeTimelineQuery(eventsTimelineQuery, parentSpan) }
                    val eventsByLevelDeferred =
                        async { queryHelper.executeMapQuery(eventsByLevelQuery, "level", parentSpan) }
                    val eventsByPlatformDeferred =
                        async { queryHelper.executeMapQuery(eventsByPlatformQuery, "platform", parentSpan) }
                    val eventsByBrowserDeferred =
                        async { queryHelper.executeMapQuery(eventsByBrowserQuery, "browser_name", parentSpan) }
                    val eventsByEnvironmentDeferred =
                        async { queryHelper.executeMapQuery(eventsByEnvironmentQuery, "environment", parentSpan) }
                    val issuesByStatusDeferred =
                        async { queryHelper.executeMapQuery(issuesByStatusQuery, "status", parentSpan) }
                    val topIssuesDeferred = async { queryHelper.executeTopIssuesQuery(topIssuesQuery, parentSpan) }
                    val usersTimelineDeferred =
                        async { queryHelper.executeTimelineQuery(usersTimelineQuery, parentSpan) }
                    val releaseMarkersDeferred =
                        async { executeReleaseMarkersQuery(projectId, hoursBack, retentionDays, parentSpan) }

                    val chUnresolved = unresolvedIssuesDeferred.await()
                    val chIssuesByStatus = issuesByStatusDeferred.await()
                    val pgOverrides = lookupProjectIssueStatuses(projectId)
                    val filteredOverrides =
                        filterToRetainedIssues(pgOverrides, projectId, retentionDays, demoEpochMs, parentSpan)
                    val adjusted = adjustStatsCounts(chUnresolved, chIssuesByStatus, filteredOverrides)

                    ProjectStatsResponse(
                        totalEvents = totalEventsDeferred.await(),
                        totalIssues = totalIssuesDeferred.await(),
                        unresolvedIssues = adjusted.first,
                        affectedUsers = affectedUsersDeferred.await(),
                        eventsTimeline = eventsTimelineDeferred.await(),
                        eventsByLevel = eventsByLevelDeferred.await(),
                        eventsByPlatform = eventsByPlatformDeferred.await(),
                        eventsByBrowser = eventsByBrowserDeferred.await(),
                        eventsByEnvironment = eventsByEnvironmentDeferred.await(),
                        issuesByStatus = adjusted.second,
                        topIssues = topIssuesDeferred.await(),
                        usersTimeline = usersTimelineDeferred.await(),
                        releaseMarkers = releaseMarkersDeferred.await()
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch project stats" }
                ProjectStatsResponse(
                    totalEvents = 0,
                    totalIssues = 0,
                    unresolvedIssues = 0,
                    affectedUsers = 0,
                    eventsTimeline = emptyList(),
                    eventsByLevel = emptyMap(),
                    eventsByPlatform = emptyMap(),
                    eventsByBrowser = emptyMap(),
                    eventsByEnvironment = emptyMap(),
                    issuesByStatus = emptyMap(),
                    topIssues = emptyList(),
                    usersTimeline = emptyList(),
                    releaseMarkers = emptyList()
                )
            }
        }

    private suspend fun executeReleaseMarkersQuery(
        projectId: Long,
        hoursBack: Int,
        retentionDays: Int,
        parentSpan: ISpan? = null
    ): List<ReleaseMarker> {
        val query =
            """
            SELECT version, formatDateTime(first_seen, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as timestamp
            FROM (
                SELECT release as version, min(timestamp) as first_seen
                FROM `$clickhouseDb`.events
                WHERE project_id = $projectId AND release != ''
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY release
            )
            WHERE first_seen >= now() - INTERVAL $hoursBack HOUR
            ORDER BY first_seen
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to execute release markers query: ${response.status} ${body.take(400)}" }
                return emptyList()
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    ReleaseMarker(
                        version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "",
                        timestamp = obj["timestamp"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch release markers" }
            emptyList()
        }
    }

    private fun lookupProjectIssueStatuses(projectId: Long): Map<String, String> {
        return transaction {
            IssueStatuses
                .selectAll()
                .where { IssueStatuses.project_id eq projectId }
                .associate { row ->
                    row[IssueStatuses.issue_id] to row[IssueStatuses.status]
                }
        }
    }

    private suspend fun filterToRetainedIssues(
        pgOverrides: Map<String, String>,
        projectId: Long,
        retentionDays: Int,
        demoEpochMs: Long?,
        parentSpan: ISpan?
    ): Map<String, String> {
        if (pgOverrides.isEmpty()) return pgOverrides
        val idList = pgOverrides.keys.joinToString(",") { "'${escapeSql(it)}'" }
        val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
        val query = """
            SELECT DISTINCT issue_id
            FROM `$clickhouseDb`.issues FINAL
            WHERE $projectIdClause
                AND issue_id IN ($idList)
                AND ${queryHelper.timestampRetentionClause("last_seen", retentionDays, demoEpochMs)}
            FORMAT JSONEachRow
        """.trimIndent()
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            return pgOverrides
        }
        val retainedIds = body.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                json.parseToJsonElement(line).jsonObject["issue_id"]?.jsonPrimitive?.contentOrNull
            }
            .toSet()
        return pgOverrides.filterKeys { it in retainedIds }
    }

    private fun adjustStatsCounts(
        chUnresolved: Long,
        chIssuesByStatus: Map<String, Long>,
        pgOverrides: Map<String, String>
    ): Pair<Long, Map<String, Long>> {
        if (pgOverrides.isEmpty()) return chUnresolved to chIssuesByStatus
        val statusCounts = chIssuesByStatus.toMutableMap()
        var unresolvedDelta = 0L
        for ((_, pgStatus) in pgOverrides) {
            if (pgStatus != "unresolved") {
                unresolvedDelta--
                statusCounts["unresolved"] = (statusCounts["unresolved"] ?: 0) - 1
                statusCounts[pgStatus] = (statusCounts[pgStatus] ?: 0) + 1
            }
        }
        val adjustedUnresolved = maxOf(0L, chUnresolved + unresolvedDelta)
        val adjustedByStatus = statusCounts.filter { it.value > 0 }
        return adjustedUnresolved to adjustedByStatus
    }
}
