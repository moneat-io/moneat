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
import com.moneat.events.models.SlowTransactionResponse
import com.moneat.events.models.TimelinePoint
import com.moneat.events.models.TopIssue
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.sentry.ISpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class PeriodConfig(
    val hoursBack: Int,
    val intervalMinutes: Int,
    val periodMinutes: Int
)

class DashboardQueryHelper {
    val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    val backendUrl: String get() = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
    val json = Json { ignoreUnknownKeys = true }
    val retentionPolicyService = RetentionPolicyService()
    val pricingTierService = PricingTierService()

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
            logger.warn { "ClickHouse error: ${body.take(200)}" }
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
            return "${trimmed.substring(0, 8)}-${trimmed.substring(8, 12)}-${trimmed.substring(12, 16)}-${trimmed.substring(16, 20)}-${trimmed.substring(20)}"
        }

        return null
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
        return try {
            val contextsJson = json.parseToJsonElement(contexts) as? JsonObject ?: return null
            contextsJson["trace"] as? JsonObject
        } catch (_: Exception) {
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
            "toDateTime64(${demoEpochMs / 1000.0}, 3)"
        } else {
            "now()"
        }
    }

    fun getPeriodConfig(period: String): PeriodConfig {
        return when (period) {
            "24h" -> PeriodConfig(hoursBack = 24, intervalMinutes = 60, periodMinutes = 24 * 60)
            "30d" -> PeriodConfig(hoursBack = 720, intervalMinutes = 1440, periodMinutes = 30 * 24 * 60)
            "90d" -> PeriodConfig(hoursBack = 2160, intervalMinutes = 4320, periodMinutes = 90 * 24 * 60)
            else -> PeriodConfig(hoursBack = 168, intervalMinutes = 360, periodMinutes = 7 * 24 * 60)
        }
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
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute scalar query: ${response.status} ${body.take(400)}" }
            return 0
        }
        if (body.isBlank()) return 0
        val obj = json.parseToJsonElement(body.lines().first()).jsonObject
        return obj["total"]?.jsonPrimitive?.long ?: 0
    }

    suspend fun executeTimelineQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<TimelinePoint> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "ClickHouse query failed: ${body.take(400)}" }
            return emptyList()
        }

        return body
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    TimelinePoint(
                        timestamp = obj["time"]?.jsonPrimitive?.content ?: "",
                        count = obj["count"]?.jsonPrimitive?.long ?: 0
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse line: $line" }
                    null
                }
            }
    }

    suspend fun executeSlowestTransactionsQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<SlowTransactionResponse> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "ClickHouse query failed: ${body.take(400)}" }
            return emptyList()
        }

        return body
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
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
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse line: $line" }
                    null
                }
            }
    }

    suspend fun executeMapQuery(
        query: String,
        keyField: String,
        parentSpan: ISpan? = null
    ): Map<String, Long> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute map query: ${response.status} ${body.take(400)}" }
            return emptyMap()
        }

        return body
            .lines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                val key = obj[keyField]?.jsonPrimitive?.content ?: "unknown"
                val count = obj["count"]?.jsonPrimitive?.long ?: 0
                key to count
            }
    }

    suspend fun executeTopIssuesQuery(
        query: String,
        parentSpan: ISpan? = null
    ): List<TopIssue> {
        val response = ClickHouseClient.execute(query, parentSpan)
        val body = response.bodyAsText()

        if (response.status.value !in 200..299 || body.trimStart().startsWith("Code:")) {
            logger.error { "Failed to execute top issues query: ${response.status} ${body.take(400)}" }
            return emptyList()
        }

        return body
            .lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = json.parseToJsonElement(line).jsonObject
                TopIssue(
                    issueId = obj["issue_id"]?.jsonPrimitive?.content ?: "",
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    count = obj["count"]?.jsonPrimitive?.long ?: 0
                )
            }
    }
}
