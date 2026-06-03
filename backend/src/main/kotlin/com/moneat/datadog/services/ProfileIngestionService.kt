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
import com.moneat.datadog.models.DdProfileEvent
import com.moneat.datadog.models.DdProfileListResponse
import com.moneat.datadog.models.DdProfileResponse
import com.moneat.datadog.models.DdProfileSeriesPoint
import com.moneat.datadog.models.DdProfileServiceSummary
import com.moneat.datadog.models.DdProfileServicesResponse
import com.moneat.datadog.models.DdProfileTimeseriesPoint
import com.moneat.datadog.models.DdProfileTimeseriesResponse
import com.moneat.datadog.models.DdProfileTypeCount
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.suspendRunCatching
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/** Server-side filters/paging for the profile list endpoint. */
data class DdProfileListQuery(
    val service: String? = null,
    val profileType: String? = null,
    val source: String? = null,
    val env: String? = null,
    val host: String? = null,
    val version: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

/** Shared dimensions used by profile dashboard queries. */
data class ProfileQueryFilters(
    val service: String? = null,
    val profileType: String? = null,
    val source: String? = null,
    val env: String? = null,
    val host: String? = null,
    val version: String? = null,
)

/** Optional lower/upper time bounds for ClickHouse profile filters. */
data class ProfileTimeFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
)

/** Required time window for dashboard aggregations. */
data class ProfileTimeWindow(
    val fromMs: Long,
    val toMs: Long,
) {
    fun toFilter(): ProfileTimeFilter = ProfileTimeFilter(fromMs, toMs)
}

/** Profile-volume time-series query shape. */
data class ProfileTimeseriesQuery(
    val organizationId: Int,
    val filters: ProfileQueryFilters,
    val window: ProfileTimeWindow,
    val buckets: Int,
)

/** Candidate sampling query shape for merged flamegraphs. */
data class ProfileMergeSelectionQuery(
    val organizationId: Int,
    val filters: ProfileQueryFilters,
    val window: ProfileTimeWindow,
    val maxProfiles: Int,
)

/** A profile selected for inclusion in a merged flamegraph. */
data class ProfileMergeCandidate(
    val profileId: String,
    val storageKey: String,
    val profileType: String,
    val source: String,
)

/** Sampled merge candidates plus the total profiles in the window. */
data class ProfileMergeSelection(
    val totalInWindow: Long,
    val candidates: List<ProfileMergeCandidate>,
)

private fun DdProfileListQuery.toFilters(): ProfileQueryFilters =
    ProfileQueryFilters(
        service = service,
        profileType = profileType,
        source = source,
        env = env,
        host = host,
        version = version,
    )

object ProfileIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracking = UsageTrackingService.instance

    private const val NANOS_PER_MILLI = 1_000_000L
    private const val MIN_PROFILE_PARTS = 3
    private const val DEFAULT_SOURCE = "datadog"
    private const val MILLIS_PER_SECOND_L = 1000L
    private const val DEFAULT_SERVICES_LIMIT = 200
    private const val SPARKLINE_LOOKBACK_SECONDS = 86_400L
    private const val SPARKLINE_BUCKETS = 24
    private const val MIN_BUCKET_SECONDS = 60L
    private const val MAX_TIMESERIES_BUCKETS = 500
    private const val MERGE_MAX_PROFILES = 50

    private data class LockEntry(val mutex: Mutex = Mutex(), val refs: AtomicInteger = AtomicInteger(0))
    private val sessionLocks = ConcurrentHashMap<String, LockEntry>()

    /**
     * Process a profiling upload: store the pprof data and
     * insert metadata into ClickHouse.
     *
     * Deduplicates by runtime_id + start + end: if a profile from the
     * same session already exists, new files are merged into it.
     */
    suspend fun ingestProfile(
        organizationId: Int,
        event: DdProfileEvent,
        fileParts: List<Pair<String, ByteArray>>,
        profileType: String = "cpu",
    ): String {
        val tags = extractProfileTags(event)
        val runtimeId = firstNonBlankTag(tags, "runtime_id", "runtime-id")
        val startMs = parseIsoToMs(event.start)
        val endMs = parseIsoToMs(event.end)

        // Per-session lock to prevent duplicate entries from concurrent uploads
        if (runtimeId.isNotBlank() && event.start.isNotBlank()) {
            val sessionKey = "$organizationId:$runtimeId:$startMs:$endMs"
            val entry = sessionLocks.compute(sessionKey) { _, existing ->
                (existing ?: LockEntry()).also { it.refs.incrementAndGet() }
            }!!
            try {
                return entry.mutex.withLock {
                    val existing = findExistingSession(
                        organizationId,
                        runtimeId,
                        startMs,
                        endMs
                    )
                    if (existing != null) {
                        ProfileStorageService.storeAdditional(
                            existing.storageKey,
                            fileParts
                        )
                        logger.debug {
                            "Merged ${fileParts.size} files into existing profile ${existing.profileId}"
                        }
                        existing.profileId
                    } else {
                        insertNewProfile(
                            organizationId,
                            fileParts,
                            profileType,
                            tags,
                            startMs,
                            endMs
                        )
                    }
                }
            } finally {
                sessionLocks.compute(sessionKey) { _, cur ->
                    if (cur != null && cur.refs.decrementAndGet() == 0) null else cur
                }
            }
        }

        return insertNewProfile(
            organizationId,
            fileParts,
            profileType,
            tags,
            startMs,
            endMs
        )
    }

    private suspend fun insertNewProfile(
        organizationId: Int,
        fileParts: List<Pair<String, ByteArray>>,
        profileType: String,
        tags: Map<String, String>,
        startMs: Long,
        endMs: Long,
    ): String {
        val profileId = UUID.randomUUID().toString()
        val storageKey = ProfileStorageService.storeMultiple(
            organizationId,
            profileId,
            fileParts
        )

        val host = firstNonBlankTag(tags, "host", "host.name")
        val service = firstNonBlankTag(
            tags,
            "service",
            "service.name",
            "service_name"
        )
        val env = firstNonBlankTag(
            tags,
            "env",
            "environment",
            "deployment.environment"
        )
        val version = firstNonBlankTag(
            tags,
            "version",
            "service.version"
        )
        val runtime = firstNonBlankTag(
            tags,
            "runtime",
            "runtime.name"
        )
        val language = firstNonBlankTag(
            tags,
            "language",
            "runtime.language"
        ).ifBlank {
            runtime.substringBefore(" ").trim()
        }

        val durationNs = (endMs - startMs) * NANOS_PER_MILLI

        val insert = """
            INSERT INTO `$clickhouseDb`.profiles (
                profile_id, organization_id,
                host, service, env, version,
                runtime, language, profile_type,
                start_time, end_time, duration_ns,
                storage_key, tags, size_bytes, source
            ) VALUES (
                toUUID('$profileId'),
                $organizationId,
                '${escapeSql(host)}',
                '${escapeSql(service)}',
                '${escapeSql(env)}',
                '${escapeSql(version)}',
                '${escapeSql(runtime)}',
                '${escapeSql(language)}',
                '${escapeSql(profileType)}',
                fromUnixTimestamp64Milli($startMs),
                fromUnixTimestamp64Milli($endMs),
                $durationNs,
                '${escapeSql(storageKey)}',
                ${mapToSqlMap(tags)},
                ${fileParts.sumOf { it.second.size.toLong() }},
                '$DEFAULT_SOURCE'
            )
        """.trimIndent()

        val response = ClickHouseClient.execute(insert)
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Failed to insert DD profile metadata into ClickHouse"
            )
        }

        usageTracking.recordOrgUsage(
            organizationId,
            "dd_profile",
            fileParts.sumOf { it.second.size }
        )

        return profileId
    }

    // --- Dashboard query methods ---

    suspend fun listProfiles(
        organizationId: Int,
        query: DdProfileListQuery,
    ): DdProfileListResponse {
        val whereClause = buildProfileFilters(
            organizationId = organizationId,
            filters = query.toFilters(),
            timeFilter = ProfileTimeFilter(query.fromMs, query.toMs),
        )
        val limit = query.limit
        val offset = query.offset

        val countQuery = """
            SELECT count()
            FROM `$clickhouseDb`.profiles
            WHERE $whereClause
        """.trimIndent()
        val countResult = ClickHouseClient.executeWithFormat(
            countQuery,
            "TabSeparated"
        )
        val totalCount = countResult.trim().toLongOrNull() ?: 0

        val listQuery = """
            SELECT
                toString(profile_id) as profile_id,
                host, service, env, version,
                runtime, language, profile_type,
                toString(start_time) as profile_start_time,
                toString(end_time) as profile_end_time,
                duration_ns, storage_key,
                tags, size_bytes, source
            FROM `$clickhouseDb`.profiles
            WHERE $whereClause
            ORDER BY start_time DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(listQuery, "")
        val profiles = if (result.isBlank()) {
            emptyList()
        } else {
            result.trim().lines()
                .filter { it.isNotBlank() }
                .map { parseProfileRow(it) }
        }

        return DdProfileListResponse(
            profiles = profiles,
            totalCount = totalCount
        )
    }

    /** Fetch a single profile's metadata by id (null when not found). */
    suspend fun getProfile(
        organizationId: Int,
        profileId: String,
    ): DdProfileResponse? {
        val query = """
            SELECT
                toString(profile_id) as profile_id,
                host, service, env, version,
                runtime, language, profile_type,
                toString(start_time) as start_time,
                toString(end_time) as end_time,
                duration_ns, storage_key,
                tags, size_bytes, source
            FROM `$clickhouseDb`.profiles
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND toString(profile_id) = '${escapeSql(profileId)}'
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()
        val result = ClickHouseClient.executeWithFormat(query, "")
        val line = result.trim().lines().firstOrNull { it.isNotBlank() }
            ?: return null
        return parseProfileRow(line)
    }

    /**
     * Per-service rollup powering the Profiles overview. Counts/sizes/hosts
     * are over the full window (or all retained data when [fromMs]/[toMs] are
     * null); each service carries a small activity sparkline.
     */
    suspend fun listServices(
        organizationId: Int,
        fromMs: Long?,
        toMs: Long?,
    ): DdProfileServicesResponse {
        val whereClause = buildProfileFilters(
            organizationId = organizationId,
            timeFilter = ProfileTimeFilter(fromMs, toMs),
        )

        val rollupQuery = """
            SELECT
                service,
                count() AS profileCount,
                uniqExact(host) AS hostCount,
                sum(size_bytes) AS totalSizeBytes,
                toString(min(start_time)) AS firstSeen,
                toString(max(start_time)) AS lastSeen,
                toUInt64(round(avg(duration_ns))) AS avgDurationNs,
                arrayFilter(x -> x != '', groupUniqArray(env)) AS environments,
                arrayFilter(x -> x != '', groupUniqArray(language)) AS languages,
                arrayFilter(x -> x != '', groupUniqArray(runtime)) AS runtimes
            FROM `$clickhouseDb`.profiles
            WHERE $whereClause
            GROUP BY service
            ORDER BY profileCount DESC
            LIMIT $DEFAULT_SERVICES_LIMIT
            FORMAT JSONEachRow
        """.trimIndent()

        val typesQuery = """
            SELECT service, profile_type AS profileType, count() AS count
            FROM `$clickhouseDb`.profiles
            WHERE $whereClause
            GROUP BY service, profile_type
            FORMAT JSONEachRow
        """.trimIndent()

        val sparkStep = sparklineStepSeconds(fromMs, toMs)
        val sparkWhere = if (fromMs != null && toMs != null) {
            whereClause
        } else {
            "$whereClause AND start_time >= now() - INTERVAL $SPARKLINE_LOOKBACK_SECONDS SECOND"
        }
        val sparkQuery = """
            SELECT
                service,
                ${bucketTimestampMsSql("start_time", sparkStep)} AS ts,
                count() AS count
            FROM `$clickhouseDb`.profiles
            WHERE $sparkWhere
            GROUP BY service, ts
            ORDER BY ts
            FORMAT JSONEachRow
        """.trimIndent()

        val typeCounts = mutableMapOf<String, MutableList<DdProfileTypeCount>>()
        parseJsonRows(ClickHouseClient.executeWithFormat(typesQuery, "")).forEach { obj ->
            val svc = obj["service"]?.jsonPrimitive?.content ?: ""
            typeCounts.getOrPut(svc) { mutableListOf() }.add(
                DdProfileTypeCount(
                    profileType = obj["profileType"]?.jsonPrimitive?.content ?: "",
                    count = obj["count"]?.jsonPrimitive?.long ?: 0,
                )
            )
        }

        val series = mutableMapOf<String, MutableList<DdProfileSeriesPoint>>()
        parseJsonRows(ClickHouseClient.executeWithFormat(sparkQuery, "")).forEach { obj ->
            val svc = obj["service"]?.jsonPrimitive?.content ?: ""
            series.getOrPut(svc) { mutableListOf() }.add(
                DdProfileSeriesPoint(
                    ts = obj["ts"]?.jsonPrimitive?.long ?: 0,
                    count = obj["count"]?.jsonPrimitive?.long ?: 0,
                )
            )
        }

        val services = parseJsonRows(
            ClickHouseClient.executeWithFormat(rollupQuery, "")
        ).map { obj ->
            val svc = obj["service"]?.jsonPrimitive?.content ?: ""
            DdProfileServiceSummary(
                service = svc,
                languages = stringArray(obj["languages"]),
                runtimes = stringArray(obj["runtimes"]),
                environments = stringArray(obj["environments"]),
                types = typeCounts[svc]?.sortedByDescending { it.count } ?: emptyList(),
                hostCount = obj["hostCount"]?.jsonPrimitive?.long ?: 0,
                profileCount = obj["profileCount"]?.jsonPrimitive?.long ?: 0,
                totalSizeBytes = obj["totalSizeBytes"]?.jsonPrimitive?.long ?: 0,
                firstSeen = obj["firstSeen"]?.jsonPrimitive?.content ?: "",
                lastSeen = obj["lastSeen"]?.jsonPrimitive?.content ?: "",
                avgDurationNs = obj["avgDurationNs"]?.jsonPrimitive?.long ?: 0,
                series = series[svc] ?: emptyList(),
            )
        }

        return DdProfileServicesResponse(
            services = services,
            totalProfiles = services.sumOf { it.profileCount },
            totalSizeBytes = services.sumOf { it.totalSizeBytes },
            serviceCount = services.size,
            hostCount = services.sumOf { it.hostCount },
            typeCount = services.flatMap { s -> s.types.map { it.profileType } }
                .toSet().size,
        )
    }

    /** Profile-volume time series for a service over a window. */
    suspend fun timeseries(query: ProfileTimeseriesQuery): DdProfileTimeseriesResponse {
        val safeBuckets = query.buckets.coerceIn(1, MAX_TIMESERIES_BUCKETS)
        val fromMs = query.window.fromMs
        val toMs = query.window.toMs
        val windowSec = ((toMs - fromMs) / MILLIS_PER_SECOND_L).coerceAtLeast(1)
        val stepSeconds = (windowSec / safeBuckets).coerceAtLeast(MIN_BUCKET_SECONDS)
        val whereClause = buildProfileFilters(
            organizationId = query.organizationId,
            filters = query.filters,
            timeFilter = query.window.toFilter(),
        )
        val sql = """
            SELECT
                ${bucketTimestampMsSql("start_time", stepSeconds)} AS ts,
                count() AS count,
                sum(size_bytes) AS sizeBytes
            FROM `$clickhouseDb`.profiles
            WHERE $whereClause
            GROUP BY ts
            ORDER BY ts
            FORMAT JSONEachRow
        """.trimIndent()
        val points = parseJsonRows(ClickHouseClient.executeWithFormat(sql, "")).map { obj ->
            DdProfileTimeseriesPoint(
                ts = obj["ts"]?.jsonPrimitive?.long ?: 0,
                count = obj["count"]?.jsonPrimitive?.long ?: 0,
                sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.long ?: 0,
            )
        }
        return DdProfileTimeseriesResponse(points = points, bucketSeconds = stepSeconds)
    }

    /**
     * Evenly sample up to [maxProfiles] profiles across the window so a wide
     * window stays representative without reading every stored blob.
     */
    suspend fun selectProfilesForMerge(query: ProfileMergeSelectionQuery): ProfileMergeSelection {
        val cap = query.maxProfiles.coerceIn(1, MERGE_MAX_PROFILES)
        val whereClause = buildProfileFilters(
            organizationId = query.organizationId,
            filters = query.filters,
            timeFilter = query.window.toFilter(),
        )
        val sql = """
            WITH
                (SELECT count() FROM `$clickhouseDb`.profiles WHERE $whereClause) AS total,
                greatest(intDiv(total + $cap - 1, $cap), 1) AS stride
            SELECT toString(profile_id) AS profile_id, storage_key,
                   profile_type AS profileType, source, total
            FROM (
                SELECT profile_id, storage_key, profile_type, source, start_time,
                       row_number() OVER (ORDER BY start_time) AS rn
                FROM `$clickhouseDb`.profiles
                WHERE $whereClause
            )
            WHERE (rn - 1) % stride = 0
            ORDER BY start_time
            LIMIT $cap
            FORMAT JSONEachRow
        """.trimIndent()
        val rows = parseJsonRows(ClickHouseClient.executeWithFormat(sql, ""))
        val total = rows.firstOrNull()?.get("total")?.jsonPrimitive?.long ?: 0
        val candidates = rows.map { obj ->
            ProfileMergeCandidate(
                profileId = obj["profile_id"]?.jsonPrimitive?.content ?: "",
                storageKey = obj["storage_key"]?.jsonPrimitive?.content ?: "",
                profileType = obj["profileType"]?.jsonPrimitive?.content ?: "",
                source = obj["source"]?.jsonPrimitive?.content ?: DEFAULT_SOURCE,
            )
        }.filter { it.storageKey.isNotBlank() }
        return ProfileMergeSelection(totalInWindow = total, candidates = candidates)
    }

    suspend fun getProfileStorageKey(
        organizationId: Int,
        profileId: String,
    ): String? {
        val query = """
            SELECT storage_key
            FROM `$clickhouseDb`.profiles
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND toString(profile_id) = '${escapeSql(profileId)}'
            LIMIT 1
            FORMAT TabSeparated
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
        return result.trim().takeIf { it.isNotBlank() }
    }

    /**
     * Returns storage_key, profile_type, and source for a profile.
     */
    data class ProfileMeta(
        val storageKey: String,
        val profileType: String,
        val source: String,
    )

    suspend fun getProfileMeta(
        organizationId: Int,
        profileId: String,
    ): ProfileMeta? {
        val query = """
            SELECT storage_key, profile_type, source
            FROM `$clickhouseDb`.profiles
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND toString(profile_id) = '${escapeSql(profileId)}'
            LIMIT 1
            FORMAT TabSeparated
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
        val line = result.trim().takeIf { it.isNotBlank() } ?: return null
        val parts = line.split("\t")
        return if (parts.size >= MIN_PROFILE_PARTS) {
            ProfileMeta(parts[0], parts[1], parts[2])
        } else if (parts.size >= 2) {
            ProfileMeta(parts[0], parts[1], DEFAULT_SOURCE)
        } else {
            null
        }
    }

    // --- Internal helpers ---

    private fun buildProfileFilters(
        organizationId: Int,
        filters: ProfileQueryFilters = ProfileQueryFilters(),
        timeFilter: ProfileTimeFilter = ProfileTimeFilter(),
    ): String {
        val clauses = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        )
        filters.service?.takeIf { it.isNotBlank() }?.let {
            clauses.add("service = '${escapeSql(it)}'")
        }
        filters.profileType?.takeIf { it.isNotBlank() }?.let {
            clauses.add("profile_type = '${escapeSql(it)}'")
        }
        filters.source?.takeIf { it.isNotBlank() }?.let {
            clauses.add("source = '${escapeSql(it)}'")
        }
        filters.env?.takeIf { it.isNotBlank() }?.let {
            clauses.add("env = '${escapeSql(it)}'")
        }
        filters.host?.takeIf { it.isNotBlank() }?.let {
            clauses.add("host = '${escapeSql(it)}'")
        }
        filters.version?.takeIf { it.isNotBlank() }?.let {
            clauses.add("version = '${escapeSql(it)}'")
        }
        timeFilter.fromMs?.let { clauses.add("start_time >= fromUnixTimestamp64Milli($it)") }
        timeFilter.toMs?.let { clauses.add("start_time < fromUnixTimestamp64Milli($it)") }
        return clauses.joinToString(" AND ")
    }

    private fun parseProfileRow(line: String): DdProfileResponse {
        val obj = json.parseToJsonElement(line).jsonObject
        val tagsMap = parseJsonStringMap(obj["tags"])
        val serviceFromColumn = obj["service"]?.jsonPrimitive?.content ?: ""
        val service = serviceFromColumn.ifBlank {
            firstNonBlankTag(tagsMap, "service", "service.name", "service_name")
        }
        val startTime = obj["start_time"] ?: obj["profile_start_time"]
        val endTime = obj["end_time"] ?: obj["profile_end_time"]
        return DdProfileResponse(
            profileId = obj["profile_id"]!!.jsonPrimitive.content,
            host = obj["host"]?.jsonPrimitive?.content ?: "",
            service = service,
            env = obj["env"]?.jsonPrimitive?.content ?: "",
            version = obj["version"]?.jsonPrimitive?.content ?: "",
            runtime = obj["runtime"]?.jsonPrimitive?.content ?: "",
            language = obj["language"]?.jsonPrimitive?.content ?: "",
            profileType = obj["profile_type"]!!.jsonPrimitive.content,
            startTime = startTime!!.jsonPrimitive.content,
            endTime = endTime!!.jsonPrimitive.content,
            durationNs = obj["duration_ns"]!!.jsonPrimitive.long,
            sizeBytes = obj["size_bytes"]!!.jsonPrimitive.long,
            tags = tagsMap,
            source = obj["source"]?.jsonPrimitive?.content ?: DEFAULT_SOURCE,
        )
    }

    private fun parseJsonRows(raw: String): List<kotlinx.serialization.json.JsonObject> {
        if (raw.isBlank()) return emptyList()
        return raw.trim().lines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
    }

    private fun stringArray(
        element: kotlinx.serialization.json.JsonElement?,
    ): List<String> {
        return element?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf { c -> c.isNotBlank() } }
            ?: emptyList()
    }

    private fun sparklineStepSeconds(fromMs: Long?, toMs: Long?): Long {
        if (fromMs == null || toMs == null || toMs <= fromMs) {
            return SPARKLINE_LOOKBACK_SECONDS / SPARKLINE_BUCKETS
        }
        val windowSec = (toMs - fromMs) / MILLIS_PER_SECOND_L
        return (windowSec / SPARKLINE_BUCKETS).coerceAtLeast(MIN_BUCKET_SECONDS)
    }

    private fun bucketTimestampMsSql(column: String, stepSeconds: Long): String =
        "toInt64(toUnixTimestamp(toStartOfInterval($column, INTERVAL $stepSeconds SECOND))) * $MILLIS_PER_SECOND_L"

    private data class ExistingSession(
        val profileId: String,
        val storageKey: String,
    )

    /**
     * Find an existing profile entry matching the same profiling session
     * (same runtime_id and time window). Returns null if none found.
     */
    private suspend fun findExistingSession(
        organizationId: Int,
        runtimeId: String,
        startMs: Long,
        endMs: Long,
    ): ExistingSession? {
        if (runtimeId.isBlank()) return null
        val query = """
            SELECT toString(profile_id), storage_key
            FROM `$clickhouseDb`.profiles
            WHERE ${ClickHouseQueryUtils.orgIdClause(organizationId.toLong())}
              AND (tags['runtime_id'] = '${escapeSql(runtimeId)}'
                OR tags['runtime-id'] = '${escapeSql(runtimeId)}')
              AND start_time = fromUnixTimestamp64Milli($startMs)
              AND end_time = fromUnixTimestamp64Milli($endMs)
            LIMIT 1
            FORMAT TabSeparated
        """.trimIndent()
        val result = ClickHouseClient.executeWithFormat(query, "")
        val line = result.trim().takeIf { it.isNotBlank() } ?: return null
        val parts = line.split("\t")
        return if (parts.size >= 2) {
            ExistingSession(parts[0], parts[1])
        } else {
            null
        }
    }

    private fun extractProfileTags(event: DdProfileEvent): Map<String, String> {
        val eventTags = parseDdTags(event.tags)
        val profilerTags = parseDdTags(event.tagsProfiler)
        return mergeTagMaps(eventTags, profilerTags)
    }

    private fun mergeTagMaps(
        primary: Map<String, String>,
        secondary: Map<String, String>,
    ): Map<String, String> {
        if (secondary.isEmpty()) return primary
        val merged = primary.toMutableMap()
        secondary.forEach { (key, value) ->
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) {
                merged[key] = trimmed
            }
        }
        return merged
    }

    private fun firstNonBlankTag(
        tags: Map<String, String>,
        vararg keys: String,
    ): String {
        keys.forEach { key ->
            val value = tags[key]?.trim()
            if (!value.isNullOrEmpty()) {
                return value
            }
        }
        return ""
    }

    private fun parseDdTags(tags: String): Map<String, String> {
        if (tags.isBlank()) return emptyMap()
        return tags.split(",")
            .mapNotNull { part ->
                val colonIdx = part.indexOf(':')
                if (colonIdx > 0) {
                    part.substring(0, colonIdx).trim() to
                        part.substring(colonIdx + 1).trim()
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun parseIsoToMs(iso: String): Long {
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: DateTimeParseException) {
            logger.warn { "Failed to parse ISO timestamp: $iso" }
            System.currentTimeMillis()
        }
    }

    private fun parseJsonStringMap(
        element: kotlinx.serialization.json.JsonElement?,
    ): Map<String, String> {
        if (element == null) return emptyMap()
        return suspendRunCatching {
            element.jsonObject.mapValues {
                it.value.jsonPrimitive.content
            }
        }.getOrElse { _ ->
            emptyMap()
        }
    }

    private fun mapToSqlMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "map()"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "'${escapeSql(k)}', '${escapeSql(v)}'"
        }
        return "map($entries)"
    }
}
