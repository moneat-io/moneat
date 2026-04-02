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

import com.moneat.billing.models.PricingTier
import com.moneat.billing.services.PricingTierService
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.events.models.EventResponse
import com.moneat.events.models.SlowTransactionResponse
import com.moneat.events.models.TimelinePoint
import com.moneat.events.models.TopIssue
import com.moneat.events.models.UserInfo
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.sentry.ISpan
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

data class PeriodConfig(
    val hoursBack: Int,
    val intervalMinutes: Int,
    val periodMinutes: Int
)

private enum class PeriodWindow(
    val hoursBack: Int,
    val intervalMinutes: Int,
    val periodMinutes: Int
) {
    H24(24, 60, 24 * 60),
    D30(720, 1440, 30 * 24 * 60),
    D90(2160, 4320, 90 * 24 * 60),
    DEFAULT(168, 360, 7 * 24 * 60)
}

class DashboardQueryHelper(
    private val retentionPolicyService: RetentionPolicyService = RetentionPolicyService(),
    private val pricingTierService: PricingTierService = PricingTierService(),
) {
    val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    val backendUrl: String get() = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
    val json = Json { ignoreUnknownKeys = true }

    /**
     * Normalizes a JSONEachRow field to the string form stored in API models.
     * ClickHouse may emit nested JSON for object/array columns; older rows use string primitives.
     */
    fun jsonFieldAsStoredString(obj: JsonObject, key: String, default: String = "{}"): String =
        when (val el = obj[key]) {
            null -> default
            is JsonPrimitive -> el.contentOrNull ?: default
            else -> json.encodeToString(JsonElement.serializer(), el)
        }

    fun jsonFieldAsStoredStringOrNull(obj: JsonObject, key: String): String? =
        when (val el = obj[key]) {
            null -> null
            is JsonPrimitive -> el.contentOrNull
            else -> json.encodeToString(JsonElement.serializer(), el)
        }

    /**
     * Executes a query that returns a single row with project_id, e.g. for entity lookup.
     * Returns null on HTTP/ClickHouse error or when no valid row is returned.
     */
    suspend fun executeProjectIdQuery(
        query: String,
        context: String,
        entityId: String
    ): Long? {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "$context failed for $entityId: ${response.status} ${body.take(LOG_BODY_CHARS)}" }
                return null
            }
            if (body.isBlank()) return null
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["project_id"]?.jsonPrimitive?.longOrNull
        }.getOrElse { e ->
            logger.error(e) { "Failed to get project ID for $context" }
            null
        }
    }

    /**
     * Executes a JSONEachRow query and returns parsed JsonObjects, or null on error.
     */
    suspend fun executeJsonEachRowQuery(
        query: String,
        errorContext: String = "Query",
        parentSpan: ISpan? = null
    ): List<JsonObject>? {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "$errorContext failed: ${response.status} ${body.take(LOG_BODY_CHARS)}" }
                return null
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                }
        }.getOrElse { e ->
            logger.error(e) { "Failed to execute $errorContext" }
            null
        }
    }

    /**
     * Safely extracts body text from ClickHouse response, checking for error messages.
     * Returns null if the response contains a ClickHouse error instead of valid data.
     */
    suspend fun extractClickHouseBody(response: HttpResponse): String? {
        if (response.status != HttpStatusCode.OK) {
            return null
        }
        val body = response.bodyAsText()
        // ClickHouse returns error messages as plain text starting with "Code:"
        if (body.startsWith("Code:") && body.contains("DB::Exception")) {
            logger.warn { "ClickHouse error: ${body.take(LOG_SNIPPET_CHARS)}" }
            return null
        }
        return body
    }

    fun normalizeUuid(value: String): String? {
        val trimmed = value.trim().lowercase()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        if (uuidRegex.matches(trimmed)) return trimmed

        val hexRegex = Regex("^[0-9a-f]{32}$")
        if (hexRegex.matches(trimmed)) {
            return "${trimmed.substring(0, UUID_HEX_BLOCK1_END)}-" +
                "${trimmed.substring(UUID_HEX_BLOCK1_END, UUID_HEX_BLOCK2_END)}-" +
                "${trimmed.substring(UUID_HEX_BLOCK2_END, UUID_HEX_BLOCK3_END)}-" +
                "${trimmed.substring(UUID_HEX_BLOCK3_END, UUID_HEX_BLOCK4_END)}-" +
                trimmed.substring(UUID_HEX_BLOCK4_END)
        }

        return null
    }

    fun extractUserInfo(obj: JsonObject): UserInfo? {
        val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull
        val userEmail = obj["user_email"]?.jsonPrimitive?.contentOrNull
        val userUsername = obj["user_username"]?.jsonPrimitive?.contentOrNull
        return if (userId != null || userEmail != null || userUsername != null) {
            UserInfo(id = userId, email = userEmail, username = userUsername, ip_address = null)
        } else {
            null
        }
    }

    fun mapEventRow(obj: JsonObject, timestampKey: String = "timestamp"): EventResponse {
        val tagsMap = (obj["tags"] as? JsonObject)?.entries?.associate { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyMap()
        return EventResponse(
            eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
            timestamp = obj[timestampKey]?.jsonPrimitive?.content ?: "",
            message = obj["message"]?.jsonPrimitive?.content ?: "",
            platform = obj["platform"]?.jsonPrimitive?.content ?: "",
            level = obj["level"]?.jsonPrimitive?.content ?: "error",
            environment = obj["environment"]?.jsonPrimitive?.contentOrNull,
            release = obj["release"]?.jsonPrimitive?.contentOrNull,
            user = extractUserInfo(obj),
            tags = HashMap(tagsMap),
            contexts = jsonFieldAsStoredString(obj, "contexts", "{}"),
            exception = obj["exception"]?.jsonPrimitive?.contentOrNull,
            breadcrumbs = jsonFieldAsStoredStringOrNull(obj, "breadcrumbs")
        )
    }

    fun parseStringMap(element: JsonElement?): HashMap<String, String> {
        val objectValue = element as? JsonObject ?: return hashMapOf()
        return HashMap(
            objectValue.entries.associate { (key, value) ->
                key to (value.jsonPrimitive.contentOrNull ?: "")
            }
        )
    }

    fun parseTraceContext(contexts: String): JsonObject? {
        return suspendRunCatching {
            val contextsJson = json.parseToJsonElement(contexts) as? JsonObject ?: return null
            contextsJson["trace"] as? JsonObject
        }.getOrElse { _ ->
            null
        }
    }

    suspend fun getProjectRetentionDays(projectId: Long): Int {
        return retentionPolicyService.getRetentionDaysForProject(projectId) ?: PricingTier.FREE.retentionDays
    }

    fun timestampRetentionClause(
        column: String,
        retentionDays: Int,
        demoEpochMs: Long? = null
    ): String {
        val nowClause = demoNowClause(demoEpochMs)
        return "$column >= $nowClause - INTERVAL $retentionDays DAY"
    }

    fun demoNowClause(demoEpochMs: Long? = null): String {
        return if (demoEpochMs != null) {
            "toDateTime64(${demoEpochMs / MILLIS_PER_SECOND}, 3)"
        } else {
            "now()"
        }
    }

    fun getPeriodConfig(period: String): PeriodConfig {
        val window = when (period) {
            "24h" -> PeriodWindow.H24
            "30d" -> PeriodWindow.D30
            "90d" -> PeriodWindow.D90
            else -> PeriodWindow.DEFAULT
        }
        return PeriodConfig(
            hoursBack = window.hoursBack,
            intervalMinutes = window.intervalMinutes,
            periodMinutes = window.periodMinutes
        )
    }

    fun buildTransactionFilterClause(
        environment: String?,
        operation: String?
    ): String {
        val conditions = mutableListOf<String>()
        environment?.takeIf { it.isNotBlank() }?.let {
            conditions.add("environment = '${escapeSql(it)}'")
        }
        operation?.takeIf { it.isNotBlank() }?.let {
            conditions.add("transaction_op = '${escapeSql(it)}'")
        }

        return if (conditions.isEmpty()) {
            ""
        } else {
            conditions.joinToString(
                separator = "\n                ",
                prefix = "AND "
            )
        }
    }

    suspend fun executeScalarQuery(
        query: String,
        parentSpan: ISpan? = null
    ): Long {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to execute scalar query: ${response.status} ${body.take(LOG_BODY_CHARS)}" }
                return 0
            }
            if (body.isBlank()) return 0
            val obj = json.parseToJsonElement(body.lines().first()).jsonObject
            obj["total"]?.jsonPrimitive?.long ?: 0
        }.getOrElse { e ->
            logger.error(e) {
                "Scalar query failed (transport/parse): query=${query.take(LOG_SNIPPET_CHARS)}, returning 0"
            }
            0
        }
    }

    suspend fun executeTimelineQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<TimelinePoint> {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "ClickHouse query failed: ${body.take(LOG_BODY_CHARS)}" }
                return emptyList()
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    suspendRunCatching {
                        val obj = json.parseToJsonElement(line).jsonObject
                        TimelinePoint(
                            timestamp = obj["time"]?.jsonPrimitive?.content ?: "",
                            count = obj["count"]?.jsonPrimitive?.long ?: 0
                        )
                    }.getOrElse { e ->
                        logger.error(e) { "Failed to parse line: $line" }
                        null
                    }
                }
        }.getOrElse { e ->
            logger.error(
                e
            ) {
                "executeTimelineQuery failed (transport/parse): " +
                    "query=${query.take(LOG_SNIPPET_CHARS)}, returning emptyList"
            }
            emptyList()
        }
    }

    suspend fun executeSlowestTransactionsQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<SlowTransactionResponse> {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "ClickHouse query failed: ${body.take(LOG_BODY_CHARS)}" }
                return emptyList()
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    suspendRunCatching {
                        val obj = json.parseToJsonElement(line).jsonObject
                        SlowTransactionResponse(
                            eventId = obj["event_id"]?.jsonPrimitive?.content ?: "",
                            name = obj["name"]?.jsonPrimitive?.content ?: "",
                            op = obj["op"]?.jsonPrimitive?.content ?: "",
                            duration = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                            timestamp =
                            obj["timestamp_iso"]?.jsonPrimitive?.content
                                ?: obj["timestamp"]?.jsonPrimitive?.content
                                ?: ""
                        )
                    }.getOrElse { e ->
                        logger.error(e) { "Failed to parse line: $line" }
                        null
                    }
                }
        }.getOrElse { e ->
            logger.error(e) {
                "executeSlowestTransactionsQuery failed (transport/parse): query=${query.take(
                    LOG_SNIPPET_CHARS
                )}, returning emptyList"
            }
            emptyList()
        }
    }

    suspend fun executeMapQuery(
        query: String,
        keyField: String,
        parentSpan: ISpan? = null
    ): Map<String, Long> {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to execute map query: ${response.status} ${body.take(LOG_BODY_CHARS)}" }
                return emptyMap()
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    suspendRunCatching {
                        val obj = json.parseToJsonElement(line).jsonObject
                        val key = obj[keyField]?.jsonPrimitive?.content ?: "unknown"
                        val count = obj["count"]?.jsonPrimitive?.long ?: 0
                        key to count
                    }.getOrElse { e ->
                        logger.error(e) { "Failed to parse map query line: ${line.take(LOG_SNIPPET_CHARS)}" }
                        null
                    }
                }
                .toMap()
        }.getOrElse { e ->
            logger.error(e) {
                "executeMapQuery failed (transport/parse): query=${query.take(LOG_SNIPPET_CHARS)}, returning emptyMap"
            }
            emptyMap()
        }
    }

    /**
     * Executes a JSONEachRow query and returns a map from transformed rows.
     * Returns empty map on HTTP/ClickHouse error or when body is blank.
     */
    suspend fun <K, V> executeQueryToMap(
        query: String,
        errorContext: String,
        parentSpan: ISpan? = null,
        transform: (JsonObject) -> Pair<K, V>?
    ): Map<K, V> {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.isBlank() ||
                body.trimStart().startsWith("Code:")
            ) {
                if (response.status.value !in 200..299) {
                    logger.error { "$errorContext failed: ${response.status} ${body.take(LOG_BODY_CHARS)}" }
                }
                return emptyMap()
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                        ?.let(transform)
                }
                .toMap()
        }.getOrElse { e ->
            logger.error(e) { "Failed to execute $errorContext" }
            emptyMap()
        }
    }

    /**
     * Executes a mutation (ALTER TABLE UPDATE, etc.) and validates the response.
     * Throws IllegalStateException if the mutation failed.
     */
    suspend fun executeMutation(
        query: String,
        errorContext: String
    ) {
        val response = suspendRunCatching {
            ClickHouseClient.execute(query)
        }.getOrElse { e ->
            logger.error(e) { "Failed to execute $errorContext" }
            throw e
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error {
                "$errorContext failed: query=${query.take(LOG_SNIPPET_CHARS)}, status=${response.status}, " +
                    "body=${body.take(LOG_BODY_CHARS)}"
            }
            throw IllegalStateException(
                "ClickHouse mutation failed: ${response.status} ${body.take(LOG_SNIPPET_CHARS)}"
            )
        }
    }

    suspend fun executeTopIssuesQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<TopIssue> {
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query, parentSpan)
            val body = response.bodyAsText()
            if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
                logger.error { "Failed to execute top issues query: ${response.status} ${body.take(LOG_BODY_CHARS)}" }
                return emptyList()
            }
            body
                .lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    suspendRunCatching {
                        val obj = json.parseToJsonElement(line).jsonObject
                        TopIssue(
                            issueId = obj["issue_id"]?.jsonPrimitive?.content ?: "",
                            title = obj["title"]?.jsonPrimitive?.content ?: "",
                            count = obj["count"]?.jsonPrimitive?.long ?: 0
                        )
                    }.getOrElse { e ->
                        logger.error(e) { "Failed to parse top issues line: ${line.take(LOG_SNIPPET_CHARS)}" }
                        null
                    }
                }
        }.getOrElse { e ->
            logger.error(
                e
            ) {
                "executeTopIssuesQuery failed (transport/parse): " +
                    "query=${query.take(LOG_SNIPPET_CHARS)}, returning emptyList"
            }
            emptyList()
        }
    }

    companion object {
        private const val LOG_BODY_CHARS = 400
        private const val LOG_SNIPPET_CHARS = 200
        private const val MILLIS_PER_SECOND = 1000.0
        private const val UUID_HEX_BLOCK1_END = 8
        private const val UUID_HEX_BLOCK2_END = 12
        private const val UUID_HEX_BLOCK3_END = 16
        private const val UUID_HEX_BLOCK4_END = 20
    }
}
