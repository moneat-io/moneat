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
import com.moneat.shared.services.UsageTrackingService
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mu.KotlinLogging
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

object ProfileIngestionService {
    private val clickhouseDb by lazy { ClickHouseClient.getDatabase() }
    private val json = Json { ignoreUnknownKeys = true }
    private val usageTracking = UsageTrackingService.instance

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

        val durationNs = (endMs - startMs) * 1_000_000

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
                'datadog'
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
        service: String?,
        profileType: String?,
        source: String?,
        limit: Int,
        offset: Int,
    ): DdProfileListResponse {
        val filters = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(organizationId.toLong())
        )
        service?.let {
            filters.add("service = '${escapeSql(it)}'")
        }
        profileType?.let {
            filters.add("profile_type = '${escapeSql(it)}'")
        }
        source?.let {
            filters.add("source = '${escapeSql(it)}'")
        }
        val whereClause = filters.joinToString(" AND ")

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
            WHERE $whereClause
            ORDER BY start_time DESC
            LIMIT $limit OFFSET $offset
            FORMAT JSONEachRow
        """.trimIndent()

        val result = ClickHouseClient.executeWithFormat(query, "")
        val profiles = if (result.isBlank()) {
            emptyList()
        } else {
            result.trim().lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val tagsMap = parseJsonStringMap(obj["tags"])
                    val serviceFromColumn =
                        obj["service"]?.jsonPrimitive?.content ?: ""
                    val service = if (serviceFromColumn.isNotBlank()) {
                        serviceFromColumn
                    } else {
                        firstNonBlankTag(
                            tagsMap,
                            "service",
                            "service.name",
                            "service_name"
                        )
                    }
                    DdProfileResponse(
                        profileId = obj["profile_id"]!!
                            .jsonPrimitive.content,
                        host = obj["host"]?.jsonPrimitive?.content
                            ?: "",
                        service = service,
                        env = obj["env"]?.jsonPrimitive?.content
                            ?: "",
                        version = obj["version"]?.jsonPrimitive?.content
                            ?: "",
                        runtime = obj["runtime"]?.jsonPrimitive?.content
                            ?: "",
                        language = obj["language"]?.jsonPrimitive?.content
                            ?: "",
                        profileType = obj["profile_type"]!!
                            .jsonPrimitive.content,
                        startTime = obj["start_time"]!!
                            .jsonPrimitive.content,
                        endTime = obj["end_time"]!!
                            .jsonPrimitive.content,
                        durationNs = obj["duration_ns"]!!
                            .jsonPrimitive.long,
                        sizeBytes = obj["size_bytes"]!!
                            .jsonPrimitive.long,
                        tags = tagsMap,
                        source = obj["source"]?.jsonPrimitive?.content
                            ?: "datadog",
                    )
                }
        }

        return DdProfileListResponse(
            profiles = profiles,
            totalCount = totalCount
        )
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
        return if (parts.size >= 3) {
            ProfileMeta(parts[0], parts[1], parts[2])
        } else if (parts.size >= 2) {
            ProfileMeta(parts[0], parts[1], "datadog")
        } else {
            null
        }
    }

    // --- Internal helpers ---

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
