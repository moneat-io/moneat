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

import kotlinx.serialization.SerializationException
import java.io.IOException

import com.moneat.config.ClickHouseClient
import com.moneat.events.models.ReleaseDetailStats
import com.moneat.events.models.ReleaseListResponse
import com.moneat.shared.services.CacheService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.sentry.ISpan
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private data class ReleaseListRow(
    val version: String,
    val firstSeen: String,
    val lastSeen: String,
    val eventCount: Long,
    val userCount: Long
)

class ReleaseStatsService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    suspend fun getReleases(
        projectId: Long,
        parentSpan: ISpan? = null
    ): List<ReleaseListResponse> =
        CacheService.cached("cache:releases:$projectId", 120, parentSpan) {
            val retentionDays = queryHelper.getProjectRetentionDays(projectId)
            val projectIdClause = ClickHouseQueryUtils.projectIdClause(projectId)
            val releasesQuery =
                """
            SELECT
                release as version,
                formatDateTime(min(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                count() as event_count,
                uniq(user_id) as user_count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause AND release != ''
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
            GROUP BY release
            ORDER BY first_seen DESC
            FORMAT JSONEachRow
                """.trimIndent()

            try {
                val releases = executeReleasesListQuery(releasesQuery, parentSpan)
                val versions = releases.map { it.version }
                val newIssueCountByVersion = getNewIssueCountForReleases(projectId, versions, retentionDays, parentSpan)
                val crashFreeRateByVersion = getCrashFreeRateForReleases(projectId, versions, retentionDays, parentSpan)
                releases.map { r ->
                    ReleaseListResponse(
                        version = r.version,
                        firstSeen = r.firstSeen,
                        lastSeen = r.lastSeen,
                        eventCount = r.eventCount,
                        newIssueCount = newIssueCountByVersion[r.version] ?: 0L,
                        crashFreeRate = crashFreeRateByVersion[r.version],
                        userCount = r.userCount
                    )
                }
            } catch (e: SerializationException) {
                logger.error(e) { "Failed to fetch releases for project $projectId" }
                emptyList()
            } catch (e: IOException) {
                logger.error(e) { "Failed to fetch releases for project $projectId" }
                emptyList()
            } catch (e: IllegalStateException) {
                logger.error(e) { "Failed to fetch releases for project $projectId" }
                emptyList()
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "Failed to fetch releases for project $projectId" }
                emptyList()
            }
        }

    suspend fun getReleaseStats(
        projectId: Long,
        version: String
    ): ReleaseDetailStats? {
        val retentionDays = queryHelper.getProjectRetentionDays(projectId)
        val escapedVersion = escapeSql(version)
        val releasesQuery =
            """
            SELECT
                formatDateTime(min(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                count() as total_events,
                uniq(user_id) as user_count
            FROM `$clickhouseDb`.events
            WHERE project_id = $projectId AND release = '$escapedVersion'
                AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
            FORMAT JSONEachRow
            """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(releasesQuery)
            val body = response.bodyAsText()
            if (body.isBlank()) return null

            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val firstSeen = obj["first_seen"]?.jsonPrimitive?.contentOrNull ?: return null
            val lastSeen = obj["last_seen"]?.jsonPrimitive?.contentOrNull ?: return null
            val totalEvents = obj["total_events"]?.jsonPrimitive?.long ?: 0
            val userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0

            val newIssues = getNewIssueCountForRelease(projectId, version, retentionDays)
            val resolvedIssues = 0L
            val crashFreeSessionRate = getCrashFreeRateForRelease(projectId, version, retentionDays)
            // TODO: implement getCrashFreeUserRateForRelease when user-based query exists
            val crashFreeUserRate: Double? = null

            val intervalMinutes = 360
            val eventsTimelineQuery =
                """
                SELECT
                    formatDateTime(toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as time,
                    count() as count
                FROM `$clickhouseDb`.events
                WHERE project_id = $projectId AND release = '$escapedVersion'
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY time
                ORDER BY time
                FORMAT JSONEachRow
                """.trimIndent()

            val eventsByLevelQuery =
                """
                SELECT level, count() as count
                FROM `$clickhouseDb`.events
                WHERE project_id = $projectId AND release = '$escapedVersion'
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY level
                FORMAT JSONEachRow
                """.trimIndent()

            val topIssuesQuery =
                """
                SELECT issue_id, any(message) as title, count() as count
                FROM `$clickhouseDb`.events
                WHERE project_id = $projectId AND release = '$escapedVersion' AND event_type = 'error'
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY issue_id
                ORDER BY count DESC
                LIMIT 10
                FORMAT JSONEachRow
                """.trimIndent()

            ReleaseDetailStats(
                version = version,
                firstSeen = firstSeen,
                lastSeen = lastSeen,
                totalEvents = totalEvents,
                newIssues = newIssues,
                resolvedIssues = resolvedIssues,
                crashFreeSessionRate = crashFreeSessionRate,
                crashFreeUserRate = crashFreeUserRate,
                userCount = userCount,
                eventsTimeline = queryHelper.executeTimelineQuery(eventsTimelineQuery),
                eventsByLevel = queryHelper.executeMapQuery(eventsByLevelQuery, "level"),
                topIssues = queryHelper.executeTopIssuesQuery(topIssuesQuery)
            )
        } catch (e: SerializationException) {
            logger.error(e) { "Failed to fetch release stats for $version" }
            null
        } catch (e: IOException) {
            logger.error(e) { "Failed to fetch release stats for $version" }
            null
        } catch (e: IllegalStateException) {
            logger.error(e) { "Failed to fetch release stats for $version" }
            null
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Failed to fetch release stats for $version" }
            null
        }
    }

    private suspend fun executeReleasesListQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<ReleaseListRow> {
        val rows = queryHelper.executeJsonEachRowQuery(query, "releases list", parentSpan)
            ?: return emptyList()
        return rows.map { obj ->
            ReleaseListRow(
                version = obj["version"]?.jsonPrimitive?.content ?: "",
                firstSeen = obj["first_seen"]?.jsonPrimitive?.content ?: "",
                lastSeen = obj["last_seen"]?.jsonPrimitive?.content ?: "",
                eventCount = obj["event_count"]?.jsonPrimitive?.long ?: 0,
                userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0
            )
        }
    }

    private suspend fun getNewIssueCountForReleases(
        projectId: Long,
        versions: List<String>,
        retentionDays: Int,
        parentSpan: ISpan? = null
    ): Map<String, Long> {
        if (versions.isEmpty()) return emptyMap()
        val escapedVersions = versions.distinct().map { "'${escapeSql(it)}'" }.joinToString(",")
        val query =
            """
            SELECT first_release as version, count() as total
            FROM (
                SELECT issue_id, argMin(release, timestamp) as first_release
                FROM `$clickhouseDb`.events
                WHERE project_id = $projectId AND event_type = 'error' AND issue_id != ''
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY issue_id
                HAVING first_release IN ($escapedVersions)
            )
            GROUP BY first_release
            FORMAT JSONEachRow
            """.trimIndent()
        return queryHelper.executeQueryToMap(
            query = query,
            errorContext = "new issue counts for releases",
            parentSpan = parentSpan
        ) { obj ->
            val v = obj["version"]?.jsonPrimitive?.content ?: return@executeQueryToMap null
            val total = obj["total"]?.jsonPrimitive?.long ?: 0L
            v to total
        }
    }

    private suspend fun getCrashFreeRateForReleases(
        projectId: Long,
        versions: List<String>,
        retentionDays: Int,
        parentSpan: ISpan? = null
    ): Map<String, Double> {
        if (versions.isEmpty()) return emptyMap()
        val escapedVersions = versions.distinct().map { "'${escapeSql(it)}'" }.joinToString(",")
        val query =
            """
            SELECT release as version, countIf(errors = 0) * 100.0 / count() as rate
            FROM `$clickhouseDb`.sessions
            WHERE project_id = $projectId AND release IN ($escapedVersions)
                AND ${queryHelper.timestampRetentionClause("started", retentionDays)}
            GROUP BY release
            FORMAT JSONEachRow
            """.trimIndent()
        return queryHelper.executeQueryToMap(
            query = query,
            errorContext = "crash-free rates for releases",
            parentSpan = parentSpan
        ) { obj ->
            val v = obj["version"]?.jsonPrimitive?.content ?: return@executeQueryToMap null
            val rate = obj["rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            if (rate == null || rate.isNaN() || rate.isInfinite()) return@executeQueryToMap null
            v to rate
        }
    }

    private suspend fun getNewIssueCountForRelease(
        projectId: Long,
        version: String,
        retentionDays: Int
    ): Long {
        val escapedVersion = escapeSql(version)
        val query =
            """
            SELECT count() as total FROM (
                SELECT issue_id, argMin(release, timestamp) as first_release
                FROM `$clickhouseDb`.events
                WHERE project_id = $projectId AND event_type = 'error' AND issue_id != ''
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY issue_id
                HAVING first_release = '$escapedVersion'
            )
            FORMAT JSONEachRow
            """.trimIndent()
        return queryHelper.executeScalarQuery(query)
    }

    private suspend fun getCrashFreeRateForRelease(
        projectId: Long,
        version: String,
        retentionDays: Int
    ): Double? {
        val escapedVersion = escapeSql(version)
        val query =
            """
            SELECT countIf(errors = 0) * 100.0 / count() as rate
            FROM `$clickhouseDb`.sessions
            WHERE project_id = $projectId AND release = '$escapedVersion'
                AND ${queryHelper.timestampRetentionClause("started", retentionDays)}
            FORMAT JSONEachRow
            """.trimIndent()
        return try {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val rate = obj["rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
            if (rate.isNaN() || rate.isInfinite()) null else rate
        } catch (e: SerializationException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
