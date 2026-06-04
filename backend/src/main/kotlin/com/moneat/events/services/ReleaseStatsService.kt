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
import com.moneat.events.models.ReleaseDetailStats
import com.moneat.events.models.ReleaseListResponse
import com.moneat.shared.services.CacheService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import io.sentry.ISpan
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

private data class ReleaseListRow(
    val version: String,
    val firstSeen: String,
    val lastSeen: String,
    val eventCount: Long,
    val userCount: Long
)

private data class ReleaseQueryContext(
    val scope: ServiceQueryScope,
    val retentionDays: Int,
    val cacheKey: String,
    val logLabel: String
)

class ReleaseStatsService(private val queryHelper: DashboardQueryHelper) {
    private val clickhouseDb: String get() = queryHelper.clickhouseDb
    private val json get() = queryHelper.json

    companion object {
        private const val RELEASES_CACHE_TTL_SECONDS = 120L
        private const val RELEASE_STATS_INTERVAL_MINUTES = 360
    }

    suspend fun getReleases(
        projectId: Long,
        parentSpan: ISpan? = null
    ): List<ReleaseListResponse> {
        val context =
            ReleaseQueryContext(
                scope = ServiceQueryScope.service(projectId),
                retentionDays = queryHelper.getProjectRetentionDays(projectId),
                cacheKey = "project:$projectId",
                logLabel = "project $projectId"
            )
        return getReleases(context, parentSpan)
    }

    suspend fun getReleasesForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        parentSpan: ISpan? = null
    ): List<ReleaseListResponse> {
        val scope = ServiceQueryScope.services(serviceIds)
        val context =
            ReleaseQueryContext(
                scope = scope,
                retentionDays = queryHelper.getOrganizationRetentionDays(organizationId),
                cacheKey = "org:$organizationId:${scope.cacheKeyPart()}",
                logLabel = "organization $organizationId"
            )
        return getReleases(context, parentSpan)
    }

    private suspend fun getReleases(
        context: ReleaseQueryContext,
        parentSpan: ISpan? = null
    ): List<ReleaseListResponse> =
        CacheService.cached("cache:releases:${context.cacheKey}", RELEASES_CACHE_TTL_SECONDS, parentSpan) {
            if (context.scope.serviceIds.isEmpty()) return@cached emptyList()
            val projectIdClause = context.scope.projectIdClause()
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
                AND ${queryHelper.timestampRetentionClause("timestamp", context.retentionDays)}
            GROUP BY release
            ORDER BY first_seen DESC
            FORMAT JSONEachRow
                """.trimIndent()

            suspendRunCatching {
                val releases = executeReleasesListQuery(releasesQuery, parentSpan)
                val versions = releases.map { it.version }
                val newIssueCountByVersion =
                    getNewIssueCountForReleases(context.scope, versions, context.retentionDays, parentSpan)
                val crashFreeRateByVersion =
                    getCrashFreeRateForReleases(context.scope, versions, context.retentionDays, parentSpan)
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
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch releases for ${context.logLabel}" }
                emptyList()
            }
        }

    suspend fun getReleaseStats(
        projectId: Long,
        version: String
    ): ReleaseDetailStats? {
        val context =
            ReleaseQueryContext(
                scope = ServiceQueryScope.service(projectId),
                retentionDays = queryHelper.getProjectRetentionDays(projectId),
                cacheKey = "project:$projectId",
                logLabel = "project $projectId"
            )
        return getReleaseStats(context, version)
    }

    suspend fun getReleaseStatsForServices(
        organizationId: Int,
        serviceIds: List<Long>,
        version: String
    ): ReleaseDetailStats? {
        val scope = ServiceQueryScope.services(serviceIds)
        val context =
            ReleaseQueryContext(
                scope = scope,
                retentionDays = queryHelper.getOrganizationRetentionDays(organizationId),
                cacheKey = "org:$organizationId:${scope.cacheKeyPart()}",
                logLabel = "organization $organizationId"
            )
        return getReleaseStats(context, version)
    }

    private suspend fun getReleaseStats(
        context: ReleaseQueryContext,
        version: String
    ): ReleaseDetailStats? {
        if (context.scope.serviceIds.isEmpty()) return null
        val escapedVersion = escapeSql(version)
        val projectIdClause = context.scope.projectIdClause()
        val releasesQuery =
            """
            SELECT
                formatDateTime(min(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as first_seen,
                formatDateTime(max(timestamp), '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as last_seen,
                count() as total_events,
                uniq(user_id) as user_count
            FROM `$clickhouseDb`.events
            WHERE $projectIdClause AND release = '$escapedVersion'
                AND ${queryHelper.timestampRetentionClause("timestamp", context.retentionDays)}
            FORMAT JSONEachRow
            """.trimIndent()

        return suspendRunCatching {
            val response = ClickHouseClient.execute(releasesQuery)
            val body = response.bodyAsText()
            if (body.isBlank()) return null

            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            val firstSeen = obj["first_seen"]?.jsonPrimitive?.contentOrNull ?: return null
            val lastSeen = obj["last_seen"]?.jsonPrimitive?.contentOrNull ?: return null
            val totalEvents = obj["total_events"]?.jsonPrimitive?.long ?: 0
            val userCount = obj["user_count"]?.jsonPrimitive?.long ?: 0

            val newIssues = getNewIssueCountForRelease(context.scope, version, context.retentionDays)
            val resolvedIssues = 0L
            val crashFreeSessionRate = getCrashFreeRateForRelease(context.scope, version, context.retentionDays)
            val crashFreeUserRate = getCrashFreeUserRateForRelease(context.scope, version, context.retentionDays)

            val intervalMinutes = RELEASE_STATS_INTERVAL_MINUTES
            val eventsTimelineQuery =
                """
                SELECT
                    formatDateTime(
                        toStartOfInterval(timestamp, INTERVAL $intervalMinutes MINUTE),
                        '%Y-%m-%dT%H:%i:%S.000Z',
                        'UTC'
                    ) as time,
                    count() as count
                FROM `$clickhouseDb`.events
                WHERE $projectIdClause AND release = '$escapedVersion'
                    AND ${queryHelper.timestampRetentionClause("timestamp", context.retentionDays)}
                GROUP BY time
                ORDER BY time
                FORMAT JSONEachRow
                """.trimIndent()

            val eventsByLevelQuery =
                """
                SELECT level, count() as count
                FROM `$clickhouseDb`.events
                WHERE $projectIdClause AND release = '$escapedVersion'
                    AND ${queryHelper.timestampRetentionClause("timestamp", context.retentionDays)}
                GROUP BY level
                FORMAT JSONEachRow
                """.trimIndent()

            val topIssuesQuery =
                """
                SELECT issue_id, any(message) as title, count() as count
                FROM `$clickhouseDb`.events
                WHERE $projectIdClause AND release = '$escapedVersion' AND event_type = 'error'
                    AND ${queryHelper.timestampRetentionClause("timestamp", context.retentionDays)}
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
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch release stats for ${context.logLabel} release $version" }
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
        scope: ServiceQueryScope,
        versions: List<String>,
        retentionDays: Int,
        parentSpan: ISpan? = null
    ): Map<String, Long> {
        if (versions.isEmpty()) return emptyMap()
        val escapedVersions = versions.distinct().map { "'${escapeSql(it)}'" }.joinToString(",")
        val projectIdClause = scope.projectIdClause()
        val query =
            """
            SELECT first_release as version, count() as total
            FROM (
                SELECT issue_id, argMin(release, timestamp) as first_release
                FROM `$clickhouseDb`.events
                WHERE $projectIdClause AND event_type = 'error' AND issue_id != ''
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
        scope: ServiceQueryScope,
        versions: List<String>,
        retentionDays: Int,
        parentSpan: ISpan? = null
    ): Map<String, Double> {
        if (versions.isEmpty()) return emptyMap()
        val escapedVersions = versions.distinct().map { "'${escapeSql(it)}'" }.joinToString(",")
        val projectIdClause = scope.projectIdClause()
        val query =
            """
            SELECT release as version, countIf(errors = 0) * 100.0 / count() as rate
            FROM `$clickhouseDb`.sessions
            WHERE $projectIdClause AND release IN ($escapedVersions)
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
        scope: ServiceQueryScope,
        version: String,
        retentionDays: Int
    ): Long {
        val escapedVersion = escapeSql(version)
        val projectIdClause = scope.projectIdClause()
        val query =
            """
            SELECT count() as total FROM (
                SELECT issue_id, argMin(release, timestamp) as first_release
                FROM `$clickhouseDb`.events
                WHERE $projectIdClause AND event_type = 'error' AND issue_id != ''
                    AND ${queryHelper.timestampRetentionClause("timestamp", retentionDays)}
                GROUP BY issue_id
                HAVING first_release = '$escapedVersion'
            )
            FORMAT JSONEachRow
            """.trimIndent()
        return queryHelper.executeScalarQuery(query)
    }

    private suspend fun getCrashFreeRateForRelease(
        scope: ServiceQueryScope,
        version: String,
        retentionDays: Int
    ): Double? {
        val escapedVersion = escapeSql(version)
        val projectIdClause = scope.projectIdClause()
        val query =
            """
            SELECT countIf(errors = 0) * 100.0 / count() as rate
            FROM `$clickhouseDb`.sessions
            WHERE $projectIdClause AND release = '$escapedVersion'
                AND ${queryHelper.timestampRetentionClause("started", retentionDays)}
            FORMAT JSONEachRow
            """.trimIndent()
        return executeNullableRateQuery(query, "crash-free session rate for release $version")
    }

    private suspend fun getCrashFreeUserRateForRelease(
        scope: ServiceQueryScope,
        version: String,
        retentionDays: Int
    ): Double? {
        val escapedVersion = escapeSql(version)
        val projectIdClause = scope.projectIdClause()
        val query =
            """
            SELECT countIf(errors = 0) * 100.0 / count() as rate
            FROM (
                SELECT user_id, sum(errors) as errors
                FROM `$clickhouseDb`.sessions
                WHERE $projectIdClause AND release = '$escapedVersion' AND user_id != ''
                    AND ${queryHelper.timestampRetentionClause("started", retentionDays)}
                GROUP BY user_id
            )
            FORMAT JSONEachRow
            """.trimIndent()
        return executeNullableRateQuery(query, "crash-free user rate for release $version")
    }

    private suspend fun executeNullableRateQuery(
        query: String,
        errorContext: String
    ): Double? {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (body.isBlank()) {
                null
            } else {
                val obj = json.parseToJsonElement(body.lines().first()).jsonObject
                val rate = obj["rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                rate?.takeUnless { it.isNaN() || it.isInfinite() }
            }
        }.getOrElse { e ->
            logger.warn(e) { "Failed to fetch $errorContext" }
            null
        }
    }
}
