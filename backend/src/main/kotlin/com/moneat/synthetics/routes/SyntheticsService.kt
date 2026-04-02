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

package com.moneat.synthetics.routes

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.moneat.utils.suspendRunCatching
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG

/** Manages synthetic HTTP checks, variables, execution, and ClickHouse result storage. */
class SyntheticsService(
    private val emailService: EmailService = EmailService(),
    private val slackService: SlackService = SlackService(),
    private val discordService: DiscordService = DiscordService(),
    private val prefsService: AlertNotificationPreferencesService = AlertNotificationPreferencesService(),
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val runScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private const val SUMMARY_COLUMN_COUNT = 5
        private const val FREE_TIER_LIMIT = 5
        private const val PRO_TIER_LIMIT = 20
        private const val TEAM_TIER_LIMIT = 50
        private const val BUSINESS_TIER_LIMIT = Int.MAX_VALUE
        private const val TIMEOUT_BUFFER_MS = 5000L
    }

    fun createTest(
        organizationId: Int,
        request: CreateSyntheticTestRequest
    ): SyntheticTestResponse {
        checkQuota(organizationId)
        validateRetryParams(request.retryCount, request.retryIntervalMs)

        val testId = UUID.randomUUID()
        val now = Clock.System.now()

        transaction {
            SyntheticTests.insert {
                it[id] = testId
                it[SyntheticTests.organizationId] = organizationId
                it[name] = request.name
                it[testType] = request.testType
                it[active] = true
                it[intervalSeconds] = request.intervalSeconds
                it[timeoutSeconds] = request.timeoutSeconds
                it[url] = request.url
                it[method] = request.method
                it[headers] = request.headers?.let { h -> Json.encodeToString(h) }
                it[body] = request.body
                it[authMethod] = request.authMethod
                it[authUser] = request.authUser
                it[authPass] = request.authPass
                it[assertions] = Json.encodeToString(request.assertions)
                it[steps] = if (request.steps.isEmpty()) {
                    null
                } else {
                    Json.encodeToString(request.steps)
                }
                it[status] = "pending"
                it[lastRunAt] = null
                it[lastStatus] = null
                it[tags] = Json.encodeToString(request.tags)
                it[retryCount] = request.retryCount
                it[retryIntervalMs] = request.retryIntervalMs
                it[alertOnFailure] = request.alertOnFailure
                it[alertChannels] = Json.encodeToString(request.alertChannels)
                it[config] = request.config?.let { c ->
                    Json.encodeToString(c)
                }
                it[previousStatus] = null
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        return getTest(testId, organizationId)!!
    }

    fun listTests(organizationId: Int): List<SyntheticTestResponse> {
        return transaction {
            SyntheticTests
                .selectAll()
                .where { SyntheticTests.organizationId eq organizationId }
                .map { rowToResponse(it) }
        }
    }

    fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse? {
        return transaction {
            SyntheticTests
                .selectAll()
                .where { (SyntheticTests.id eq testId) and (SyntheticTests.organizationId eq organizationId) }
                .firstOrNull()
                ?.let { rowToResponse(it) }
        }
    }

    /** Applies partial updates to a synthetic test; validates retry fields when either count or interval is present. */
    fun updateTest(
        testId: UUID,
        organizationId: Int,
        request: UpdateSyntheticTestRequest
    ): SyntheticTestResponse? {
        val rc = request.retryCount
        val ri = request.retryIntervalMs
        if (rc != null) {
            validateRetryParams(rc, ri ?: RETRY_INTERVAL_MS_DEFAULT)
        }
        if (ri != null) {
            validateRetryParams(rc ?: RETRY_COUNT_DEFAULT, ri)
        }
        val updated = transaction {
            SyntheticTests
                .selectAll()
                .where {
                    (SyntheticTests.id eq testId) and
                        (SyntheticTests.organizationId eq organizationId)
                }
                .firstOrNull() ?: return@transaction false

            SyntheticTests.update(
                {
                    (SyntheticTests.id eq testId) and
                        (SyntheticTests.organizationId eq organizationId)
                }
            ) {
                request.name?.let { v -> it[name] = v }
                request.active?.let { v -> it[active] = v }
                request.intervalSeconds?.let { v -> it[intervalSeconds] = v }
                request.timeoutSeconds?.let { v -> it[timeoutSeconds] = v }
                request.url?.let { v -> it[url] = v }
                request.method?.let { v -> it[method] = v }
                request.headers?.let { v ->
                    it[headers] = Json.encodeToString(v)
                }
                request.body?.let { v -> it[body] = v }
                request.authMethod?.let { v -> it[authMethod] = v }
                request.authUser?.let { v -> it[authUser] = v }
                request.authPass?.let { v -> it[authPass] = v }
                request.assertions?.let { v ->
                    it[assertions] = Json.encodeToString(v)
                }
                request.steps?.let { v ->
                    it[steps] = if (v.isEmpty()) {
                        null
                    } else {
                        Json.encodeToString(v)
                    }
                }
                request.tags?.let { v ->
                    it[tags] = Json.encodeToString(v)
                }
                request.retryCount?.let { v -> it[retryCount] = v }
                request.retryIntervalMs?.let { v ->
                    it[retryIntervalMs] = v
                }
                request.alertOnFailure?.let { v ->
                    it[alertOnFailure] = v
                }
                request.alertChannels?.let { v ->
                    it[alertChannels] = Json.encodeToString(v)
                }
                request.config?.let { v ->
                    it[config] = Json.encodeToString(v)
                }
                it[updatedAt] = Clock.System.now()
            } > 0
        }

        return if (updated) getTest(testId, organizationId) else null
    }

    fun deleteTest(testId: UUID, organizationId: Int): Boolean {
        return transaction {
            SyntheticTests.deleteWhere {
                (id eq testId) and (SyntheticTests.organizationId eq organizationId)
            } > 0
        }
    }

    fun getTestsDueForRun(): List<SyntheticTestData> {
        return transaction {
            val now = Clock.System.now()
            SyntheticTests
                .selectAll()
                .where { SyntheticTests.active eq true }
                .filter { row ->
                    val lastRun = row[SyntheticTests.lastRunAt]
                    val interval = row[SyntheticTests.intervalSeconds]
                    if (lastRun == null) {
                        true
                    } else {
                        val nextRun = lastRun.plus(interval.toLong().seconds)
                        nextRun <= now
                    }
                }
                .map { rowToData(it) }
        }
    }

    fun updateTestStatus(
        testId: UUID,
        status: String,
        lastStatus: String,
        previousStatus: String? = null
    ) {
        transaction {
            SyntheticTests.update({ SyntheticTests.id eq testId }) {
                it[SyntheticTests.status] = status
                it[SyntheticTests.lastRunAt] = Clock.System.now()
                it[SyntheticTests.lastStatus] = lastStatus
                if (previousStatus != null) {
                    it[SyntheticTests.previousStatus] = previousStatus
                }
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    fun runTestNow(testId: UUID, organizationId: Int): Boolean {
        val test = transaction {
            SyntheticTests
                .selectAll()
                .where { (SyntheticTests.id eq testId) and (SyntheticTests.organizationId eq organizationId) }
                .firstOrNull()
                ?.let { rowToData(it) }
        } ?: return false

        runScope.launch {
            executeTestAndRecord(test)
        }

        return true
    }

    suspend fun executeTestAndRecord(
        test: SyntheticTestData,
        executor: SyntheticsCheckExecutor = SyntheticsCheckExecutor()
    ) {
        val resolvedTest = resolveGlobalVariables(test)
        val result = executeWithRetries(resolvedTest, executor)

        suspendRunCatching {
            recordResult(test, result)
        }.getOrElse { e ->
            logger.error(e) {
                "Failed to record synthetic result for ${test.id}"
            }
        }

        val oldStatus = test.lastStatus
        suspendRunCatching {
            updateTestStatus(
                test.id,
                result.status,
                result.status,
                oldStatus
            )
        }.getOrElse { e ->
            logger.error(e) {
                "Failed to update synthetic test status for ${test.id}"
            }
        }

        // Alert on transition from passing to failing
        if (test.alertOnFailure &&
            result.status == "failed" &&
            oldStatus != "failed"
        ) {
            suspendRunCatching {
                sendFailureAlert(test, result)
            }.getOrElse { e ->
                logger.error(e) {
                    "Failed to send alert for synthetic test ${test.id}"
                }
            }
        }
    }

    private suspend fun executeWithRetries(
        test: SyntheticTestData,
        executor: SyntheticsCheckExecutor
    ): SyntheticCheckResult {
        val maxAttempts = test.retryCount + 1
        var lastResult: SyntheticCheckResult? = null

        for (attempt in 1..maxAttempts) {
            lastResult = suspendRunCatching {
                withTimeout(test.timeoutSeconds * MILLIS_PER_SECOND_LONG + TIMEOUT_BUFFER_MS) {
                    executor.executeTest(test)
                }
            }.getOrElse { e ->
                logger.error(e) {
                    "Synthetic test execution failed for ${test.id} " +
                        "(attempt $attempt): ${e.message}"
                }
                SyntheticCheckResult(
                    status = "failed",
                    durationMs = 0,
                    errorMessage = "Test execution failed: ${e.message}"
                )
            }

            if (lastResult.status == "passed") return lastResult

            if (attempt < maxAttempts) {
                delay(test.retryIntervalMs.milliseconds)
            }
        }

        return lastResult!!
    }

    private fun sendFailureAlert(
        test: SyntheticTestData,
        result: SyntheticCheckResult
    ) {
        val subject = "Synthetic test failed: ${test.name}"
        val message = buildString {
            append("Test '${test.name}' (${test.testType}) failed.")
            if (result.errorMessage.isNotBlank()) {
                append(" Error: ${result.errorMessage}")
            }
        }
        val frontendUrl = EnvConfig.get(
            "FRONTEND_URL",
            "https://moneat.io"
        )

        val emailRecipients = prefsService.getUsersWithChannelEnabled(
            organizationId = test.organizationId,
            alertSource = "UPTIME_MONITOR",
            channel = "email"
        )
        emailRecipients.forEach { (_, email) ->
            emailService.sendUptimeAlertEmail(
                to = email,
                monitorName = test.name,
                status = "down",
                message = message,
                monitorUrl = "$frontendUrl/synthetics/${test.id}"
            )
        }

        val slackEnabled = prefsService.getUsersWithChannelEnabled(
            organizationId = test.organizationId,
            alertSource = "UPTIME_MONITOR",
            channel = "slack"
        ).isNotEmpty()
        if (slackEnabled) {
            runScope.launch {
                slackService.sendUptimeAlert(
                    organizationId = test.organizationId,
                    monitorName = test.name,
                    oldStatus = "passing",
                    newStatus = "failing",
                    message = message,
                    monitorId = test.id,
                    baseUrl = frontendUrl
                )
            }
        }

        val discordEnabled = prefsService.getUsersWithChannelEnabled(
            organizationId = test.organizationId,
            alertSource = "UPTIME_MONITOR",
            channel = "discord"
        ).isNotEmpty()
        if (discordEnabled) {
            runScope.launch {
                discordService.sendUptimeAlert(
                    organizationId = test.organizationId,
                    monitorUrl = test.url ?: test.name,
                    isDown = true,
                    statusCode = null,
                    responseTime = result.durationMs,
                    errorMessage = result.errorMessage,
                    monitorId = test.id,
                    baseUrl = frontendUrl
                )
            }
        }
    }

    private suspend fun recordResult(test: SyntheticTestData, result: SyntheticCheckResult) {
        val tsMs = Clock.System.now().toEpochMilliseconds()
        val timingsStr = if (result.timings.isEmpty()) {
            "map()"
        } else {
            val entries = result.timings.entries.joinToString(", ") { (k, v) -> "'${escapeSql(k)}', $v" }
            "map($entries)"
        }
        val sql = """
            INSERT INTO `${ClickHouseClient.getDatabase()}`.synthetic_results
            (organization_id, test_id, test_name, test_type, status, probe_dc,
             duration_ms, error_message, timings, tags, timestamp)
            VALUES (
                toUInt64(${test.organizationId}),
                '${test.id}',
                '${escapeSql(test.name)}',
                '${escapeSql(test.testType)}',
                '${escapeSql(result.status)}',
                'moneat',
                ${result.durationMs},
                '${escapeSql(result.errorMessage)}',
                $timingsStr,
                map(),
                fromUnixTimestamp64Milli($tsMs)
            )
        """.trimIndent()
        ClickHouseClient.execute(sql)
    }

    private fun resolveGlobalVariables(
        test: SyntheticTestData
    ): SyntheticTestData {
        val vars = suspendRunCatching {
            getVariablesMap(test.organizationId)
        }.getOrElse { e ->
            logger.warn(e) {
                "Failed to load global variables for org ${test.organizationId}"
            }
            return test
        }
        if (vars.isEmpty()) return test

        fun sub(input: String?): String? {
            if (input == null) return null
            var result = input
            vars.forEach { (name, value) ->
                result = result!!.replace("{{global.$name}}", value)
            }
            return result
        }

        return test.copy(
            url = sub(test.url),
            headers = sub(test.headers),
            body = sub(test.body),
            steps = sub(test.steps)
        )
    }

    private fun checkQuota(organizationId: Int) {
        if (EnvConfig.SelfHost.enabled) return

        val currentCount = transaction {
            SyntheticTests
                .selectAll()
                .where { SyntheticTests.organizationId eq organizationId }
                .count()
        }

        val tier = transaction {
            val org = Organizations
                .selectAll()
                .where { Organizations.id eq organizationId }
                .firstOrNull()

            org?.let {
                val subQuery = Subscriptions
                    .selectAll()
                    .where { Subscriptions.organization_id eq organizationId }
                    .limit(1)
                    .firstOrNull()

                subQuery?.get(Subscriptions.plan) ?: "FREE"
            } ?: "FREE"
        }

        val limit = when (tier) {
            "FREE" -> FREE_TIER_LIMIT
            "PRO" -> PRO_TIER_LIMIT
            "TEAM" -> TEAM_TIER_LIMIT
            "BUSINESS" -> BUSINESS_TIER_LIMIT
            else -> FREE_TIER_LIMIT
        }

        if (currentCount >= limit) {
            throw IllegalStateException("Synthetic test limit reached ($limit for $tier tier)")
        }
    }

    /** Ensures retry count and interval are non-negative before create/update. */
    private fun validateRetryParams(retryCount: Int, retryIntervalMs: Int) {
        require(retryCount >= 0) {
            "retryCount must be non-negative, got $retryCount"
        }
        require(retryIntervalMs >= 0) {
            "retryIntervalMs must be non-negative, got $retryIntervalMs"
        }
    }

    private fun rowToData(row: ResultRow): SyntheticTestData {
        val tagsList: List<String> = suspendRunCatching {
            Json.decodeFromString<List<String>>(row[SyntheticTests.tags])
        }.getOrElse { _ ->
            emptyList()
        }
        val alertChannelsList: List<String> = suspendRunCatching {
            Json.decodeFromString<List<String>>(row[SyntheticTests.alertChannels])
        }.getOrElse { _ ->
            emptyList()
        }
        return SyntheticTestData(
            id = row[SyntheticTests.id],
            organizationId = row[SyntheticTests.organizationId],
            name = row[SyntheticTests.name],
            testType = row[SyntheticTests.testType],
            active = row[SyntheticTests.active],
            intervalSeconds = row[SyntheticTests.intervalSeconds],
            timeoutSeconds = row[SyntheticTests.timeoutSeconds],
            url = row[SyntheticTests.url],
            method = row[SyntheticTests.method],
            headers = row[SyntheticTests.headers],
            body = row[SyntheticTests.body],
            authMethod = row[SyntheticTests.authMethod],
            authUser = row[SyntheticTests.authUser],
            authPass = row[SyntheticTests.authPass],
            assertions = row[SyntheticTests.assertions],
            steps = row[SyntheticTests.steps],
            status = row[SyntheticTests.status],
            lastRunAt = row[SyntheticTests.lastRunAt],
            lastStatus = row[SyntheticTests.lastStatus],
            tags = tagsList,
            retryCount = row[SyntheticTests.retryCount],
            retryIntervalMs = row[SyntheticTests.retryIntervalMs],
            alertOnFailure = row[SyntheticTests.alertOnFailure],
            alertChannels = alertChannelsList,
            config = row[SyntheticTests.config],
            previousStatus = row[SyntheticTests.previousStatus],
            createdAt = row[SyntheticTests.createdAt],
            updatedAt = row[SyntheticTests.updatedAt]
        )
    }

    private fun rowToResponse(row: ResultRow): SyntheticTestResponse {
        val assertionsList: List<SyntheticAssertion> = suspendRunCatching {
            Json.decodeFromString<List<SyntheticAssertion>>(row[SyntheticTests.assertions])
        }.getOrElse { _ ->
            emptyList()
        }
        val stepsList: List<SyntheticStep> = suspendRunCatching {
            row[SyntheticTests.steps]?.let {
                Json.decodeFromString<List<SyntheticStep>>(it)
            } ?: emptyList()
        }.getOrElse { _ ->
            emptyList()
        }
        val headersMap: Map<String, String>? = suspendRunCatching {
            row[SyntheticTests.headers]?.let {
                Json.decodeFromString<Map<String, String>>(it)
            }
        }.getOrElse { _ ->
            null
        }
        val tagsList: List<String> = suspendRunCatching {
            Json.decodeFromString<List<String>>(row[SyntheticTests.tags])
        }.getOrElse { _ ->
            emptyList()
        }
        val alertChannelsList: List<String> = suspendRunCatching {
            Json.decodeFromString<List<String>>(row[SyntheticTests.alertChannels])
        }.getOrElse { _ ->
            emptyList()
        }
        val testConfig: SyntheticTestConfig? = suspendRunCatching {
            row[SyntheticTests.config]?.let {
                Json.decodeFromString<SyntheticTestConfig>(it)
            }
        }.getOrElse { _ ->
            null
        }

        return SyntheticTestResponse(
            id = row[SyntheticTests.id].toString(),
            organizationId = row[SyntheticTests.organizationId],
            name = row[SyntheticTests.name],
            testType = row[SyntheticTests.testType],
            active = row[SyntheticTests.active],
            intervalSeconds = row[SyntheticTests.intervalSeconds],
            timeoutSeconds = row[SyntheticTests.timeoutSeconds],
            url = row[SyntheticTests.url],
            method = row[SyntheticTests.method],
            headers = headersMap,
            body = row[SyntheticTests.body],
            authMethod = row[SyntheticTests.authMethod],
            authUser = row[SyntheticTests.authUser],
            assertions = assertionsList,
            steps = stepsList,
            status = row[SyntheticTests.status],
            lastRunAt = row[SyntheticTests.lastRunAt]
                ?.toEpochMilliseconds(),
            lastStatus = row[SyntheticTests.lastStatus],
            tags = tagsList,
            retryCount = row[SyntheticTests.retryCount],
            retryIntervalMs = row[SyntheticTests.retryIntervalMs],
            alertOnFailure = row[SyntheticTests.alertOnFailure],
            alertChannels = alertChannelsList,
            config = testConfig,
            createdAt = row[SyntheticTests.createdAt]
                .toEpochMilliseconds(),
            updatedAt = row[SyntheticTests.updatedAt]
                .toEpochMilliseconds()
        )
    }

    // --- Summary Stats ---

    suspend fun getTestSummary(
        testId: String,
        orgIds: List<Int>
    ): SyntheticTestSummary? {
        val orgCondition = orgIds.joinToString(",") {
            "toUInt64($it)"
        }
        val query = """
            SELECT
                countIf(status = 'passed') * 100.0
                    / greatest(count(), 1) AS uptime_percent,
                avg(duration_ms) AS avg_response_ms,
                quantile(0.95)(duration_ms) AS p95_response_ms,
                count() AS total_runs,
                countIf(status = 'failed') AS failure_count
            FROM synthetic_results
            WHERE test_id = '${escapeSql(testId)}'
              AND organization_id IN ($orgCondition)
              AND timestamp >= now() - INTERVAL 30 DAY
        """.trimIndent()

        val response = runCatching {
            ClickHouseClient.execute(query)
        }.getOrNull() ?: return null

        if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            logger.warn {
                "ClickHouse summary query failed: ${response.status}"
            }
            return null
        }

        val body = response.bodyAsText().trim()
        val parts = body.split('\t')
        if (parts.size < SUMMARY_COLUMN_COUNT) return null

        return SyntheticTestSummary(
            testId = testId,
            uptimePercent = parts[0].toDoubleOrNull() ?: 0.0,
            avgResponseMs = parts[1].toDoubleOrNull() ?: 0.0,
            p95ResponseMs = parts[2].toDoubleOrNull() ?: 0.0,
            totalRuns = parts[3].toLongOrNull() ?: 0L,
            failureCount = parts[4].toLongOrNull() ?: 0L
        )
    }

    // --- Global Variables ---

    fun listVariables(organizationId: Int): List<SyntheticVariableResponse> {
        return transaction {
            SyntheticVariables
                .selectAll()
                .where {
                    SyntheticVariables.organizationId eq organizationId
                }
                .map { variableRowToResponse(it) }
        }
    }

    fun createVariable(
        organizationId: Int,
        request: SyntheticVariableRequest
    ): SyntheticVariableResponse {
        val id = transaction {
            SyntheticVariables.insert {
                it[SyntheticVariables.organizationId] = organizationId
                it[name] = request.name
                it[value] = request.value
                it[isSecret] = request.isSecret
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            } get SyntheticVariables.id
        }
        return getVariable(id, organizationId)!!
    }

    fun getVariable(
        variableId: Int,
        organizationId: Int
    ): SyntheticVariableResponse? {
        return transaction {
            SyntheticVariables
                .selectAll()
                .where {
                    (SyntheticVariables.id eq variableId) and
                        (SyntheticVariables.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { variableRowToResponse(it) }
        }
    }

    fun updateVariable(
        variableId: Int,
        organizationId: Int,
        request: SyntheticVariableRequest
    ): SyntheticVariableResponse? {
        val updated = transaction {
            SyntheticVariables.update(
                {
                    (SyntheticVariables.id eq variableId) and
                        (SyntheticVariables.organizationId eq organizationId)
                }
            ) {
                it[name] = request.name
                it[value] = request.value
                it[isSecret] = request.isSecret
                it[updatedAt] = Clock.System.now()
            } > 0
        }
        return if (updated) {
            getVariable(variableId, organizationId)
        } else {
            null
        }
    }

    fun deleteVariable(
        variableId: Int,
        organizationId: Int
    ): Boolean {
        return transaction {
            SyntheticVariables.deleteWhere {
                (id eq variableId) and
                    (SyntheticVariables.organizationId eq organizationId)
            } > 0
        }
    }

    fun getVariablesMap(organizationId: Int): Map<String, String> {
        return transaction {
            SyntheticVariables
                .selectAll()
                .where {
                    SyntheticVariables.organizationId eq organizationId
                }
                .associate {
                    it[SyntheticVariables.name] to
                        it[SyntheticVariables.value]
                }
        }
    }

    private fun variableRowToResponse(
        row: ResultRow
    ): SyntheticVariableResponse {
        val maskedValue = if (row[SyntheticVariables.isSecret]) {
            "********"
        } else {
            row[SyntheticVariables.value]
        }
        return SyntheticVariableResponse(
            id = row[SyntheticVariables.id],
            organizationId = row[SyntheticVariables.organizationId],
            name = row[SyntheticVariables.name],
            value = maskedValue,
            isSecret = row[SyntheticVariables.isSecret],
            createdAt = row[SyntheticVariables.createdAt]
                .toEpochMilliseconds(),
            updatedAt = row[SyntheticVariables.updatedAt]
                .toEpochMilliseconds()
        )
    }
}
