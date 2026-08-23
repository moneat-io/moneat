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

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertFanoutPlan
import com.moneat.alerts.services.AlertLifecycleOrchestrator
import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.organizationResourceId
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.uuid.Uuid

/** Manages synthetic HTTP checks, variables, execution, and ClickHouse result storage. */
class SyntheticsService(
    private val billingQuotaService: BillingQuotaService = BillingQuotaService(),
    private val workflowService: WorkflowService = WorkflowService(),
    private val locationService: SyntheticLocationService = SyntheticLocationService(),
    private val alertOrchestrator: AlertLifecycleOrchestrator = AlertLifecycleOrchestrator(
        workflowFanoutProvider = { workflowService },
    ),
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

        /** Label for runs of a test that has no explicit locations selected (back-compat). */
        const val DEFAULT_LOCATION = "moneat"
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
                it[service] = request.service
                it[environment] = request.environment
                it[locations] = Json.encodeToString(request.locations)
                it[alertConfig] = request.alertConfig?.let { c ->
                    Json.encodeToString(c)
                }
                it[alertRecipients] = Json.encodeToString(request.alertRecipients)
                it[browserSteps] = if (request.browserSteps.isEmpty()) {
                    null
                } else {
                    Json.encodeToString(request.browserSteps)
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
            val rows = SyntheticTests
                .selectAll()
                .where { SyntheticTests.organizationId eq organizationId }
                .toList()
            if (rows.isEmpty()) {
                emptyList()
            } else {
                val organizationResourceId = organizationResourceId(organizationId)
                rows
                    .map { rowToResponse(it, organizationResourceId) }
            }
        }
    }

    fun getTest(testId: UUID, organizationId: Int): SyntheticTestResponse? {
        return transaction {
            SyntheticTests
                .selectAll()
                .where { (SyntheticTests.id eq testId) and (SyntheticTests.organizationId eq organizationId) }
                .firstOrNull()
                ?.let { rowToResponse(it, organizationResourceId(organizationId)) }
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
                request.service?.let { v -> it[service] = v }
                request.environment?.let { v -> it[environment] = v }
                request.locations?.let { v ->
                    it[locations] = Json.encodeToString(v)
                }
                request.alertConfig?.let { v ->
                    it[alertConfig] = Json.encodeToString(v)
                }
                request.alertRecipients?.let { v ->
                    it[alertRecipients] = Json.encodeToString(v)
                }
                request.browserSteps?.let { v ->
                    it[browserSteps] = if (v.isEmpty()) {
                        null
                    } else {
                        Json.encodeToString(v)
                    }
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

    private fun markRunAttempted(testId: UUID) {
        transaction {
            SyntheticTests.update({ SyntheticTests.id eq testId }) {
                it[SyntheticTests.lastRunAt] = Clock.System.now()
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
        // The built-in worker runs managed locations in-process; private locations are
        // pulled and executed by their own worker agents via the probe protocol.
        val managed = suspendRunCatching { locationService.managedCodes() }.getOrElse { emptySet() }
        val toRun = if (test.locations.isEmpty()) {
            listOf(DEFAULT_LOCATION)
        } else {
            test.locations.filter { it in managed }
        }
        if (toRun.isEmpty()) {
            markRunAttempted(test.id)
            return
        }

        var anyFailed = false
        var firstFailure: SyntheticCheckResult? = null

        // Fan out across the test's managed locations, recording one result per location.
        for (locationCode in toRun) {
            val result = executeWithRetries(resolvedTest, executor)

            suspendRunCatching {
                recordResult(test, result, locationCode)
            }.getOrElse { e ->
                logger.error(e) {
                    "Failed to record synthetic result for ${test.id} @ $locationCode"
                }
            }

            suspendRunCatching {
                incrementSyntheticRunCount(test.organizationId)
            }.getOrElse { e ->
                logger.debug(e) { "Failed to increment synthetic run count for org ${test.organizationId}" }
            }

            if (result.status == "failed") {
                anyFailed = true
                if (firstFailure == null) firstFailure = result
            }
        }

        val aggregateStatus = if (anyFailed) "failed" else "passed"
        val oldStatus = test.lastStatus
        suspendRunCatching {
            updateTestStatus(test.id, aggregateStatus, aggregateStatus, oldStatus)
        }.getOrElse { e ->
            logger.error(e) {
                "Failed to update synthetic test status for ${test.id}"
            }
        }

        // Structured alert condition (consecutive checks × M-of-N locations). The workflow
        // episode gating handles initial notification dedup and recurring reminders.
        val alertingEnabled = test.alertOnFailure ||
            test.alertConfig != null ||
            test.alertRecipients.isNotEmpty()
        if (alertingEnabled) {
            suspendRunCatching {
                evaluateAndAlert(test, oldStatus, firstFailure)
            }.getOrElse { e ->
                logger.error(e) {
                    "Failed to evaluate alert for synthetic test ${test.id}"
                }
            }
        }
    }

    /** Fires/recovers based on the structured [AlertConfig] over recent per-location results. */
    private suspend fun evaluateAndAlert(
        test: SyntheticTestData,
        oldStatus: String?,
        firstFailure: SyntheticCheckResult?
    ) {
        val cfg = test.alertConfig ?: AlertConfig()
        // When run history is unavailable (e.g. ClickHouse down), fall back to this cycle's signal.
        val shouldFire = evaluateAlertCondition(test, cfg, fallbackFailed = firstFailure != null)
        if (shouldFire) {
            sendFailureAlert(test, firstFailure ?: SyntheticCheckResult("failed", 0))
        } else if (oldStatus == "failed" && cfg.notifyOnRecovery) {
            sendRecoveryAlert(test)
        }
    }

    /** True when at least [AlertConfig.minLocations] locations have failed their last N checks. */
    private suspend fun evaluateAlertCondition(
        test: SyntheticTestData,
        cfg: AlertConfig,
        fallbackFailed: Boolean
    ): Boolean {
        val rows = executeChRows(
            """
            SELECT location_code, status
            FROM synthetic_results
            WHERE test_id = '${test.id}'
              AND organization_id = toUInt64(${test.organizationId})
            ORDER BY timestamp DESC
            LIMIT 200
            FORMAT JSONEachRow
            """.trimIndent()
        )
        if (rows.isNullOrEmpty()) return fallbackFailed

        val consecutive = cfg.consecutiveChecks.coerceAtLeast(1)
        val minLocations = cfg.minLocations.coerceAtLeast(1)
        val byLocation = rows.groupBy { it["location_code"]?.jsonPrimitive?.content ?: "" }
        val failingLocations = byLocation.count { (_, locRows) ->
            val recent = locRows.take(consecutive).map { it["status"]?.jsonPrimitive?.content }
            recent.size >= consecutive && recent.all { it == "failed" }
        }
        return failingLocations >= minLocations
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

    private suspend fun sendFailureAlert(
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

        alertOrchestrator.process(
            AlertLifecycleEvent(
                title = subject,
                description = message,
                priority = AlertPriority.P1,
                status = AlertStatus.FIRING,
                source = AlertSource.SYNTHETIC_TEST,
                deduplicationKey = "moneat-synthetic-${test.id}",
                organizationId = test.organizationId,
                moneatUrl = "$frontendUrl/synthetics/${test.id}"
            ),
            AlertFanoutPlan.WORKFLOW_ONLY,
        )
    }

    private suspend fun sendRecoveryAlert(test: SyntheticTestData) {
        val frontendUrl = EnvConfig.get(
            "FRONTEND_URL",
            "https://moneat.io"
        )
        alertOrchestrator.process(
            AlertLifecycleEvent(
                title = "Synthetic test recovered: ${test.name}",
                description = "Test '${test.name}' (${test.testType}) passed after previous failures.",
                priority = AlertPriority.P3,
                status = AlertStatus.RESOLVED,
                source = AlertSource.SYNTHETIC_TEST,
                deduplicationKey = "moneat-synthetic-${test.id}",
                organizationId = test.organizationId,
                moneatUrl = "$frontendUrl/synthetics/${test.id}"
            ),
            AlertFanoutPlan.WORKFLOW_ONLY,
        )
    }

    private suspend fun recordResult(
        test: SyntheticTestData,
        result: SyntheticCheckResult,
        locationCode: String
    ) {
        val resultId = UUID.randomUUID()
        val tsMs = Clock.System.now().toEpochMilliseconds()
        val timingsStr = if (result.timings.isEmpty()) {
            "map()"
        } else {
            val entries = result.timings.entries.joinToString(", ") { (k, v) -> "'${escapeSql(k)}', $v" }
            "map($entries)"
        }
        val assertionsTotal = result.assertionResults.size
        val assertionsFailed = result.assertionResults.count { !it.passed }
        val sql = """
            INSERT INTO `${ClickHouseClient.getDatabase()}`.synthetic_results
            (result_id, organization_id, test_id, test_name, test_type, status, probe_dc,
             location_code, attempt, status_code, assertions_total, assertions_failed, resolved_ip,
             duration_ms, error_message, timings, tags, timestamp)
            VALUES (
                '$resultId',
                toUInt64(${test.organizationId}),
                '${test.id}',
                '${escapeSql(test.name)}',
                '${escapeSql(test.testType)}',
                '${escapeSql(result.status)}',
                '${escapeSql(locationCode)}',
                '${escapeSql(locationCode)}',
                1,
                ${result.statusCode},
                $assertionsTotal,
                $assertionsFailed,
                '${escapeSql(result.resolvedIp)}',
                ${result.durationMs},
                '${escapeSql(result.errorMessage)}',
                $timingsStr,
                map(),
                fromUnixTimestamp64Milli($tsMs)
            )
        """.trimIndent()
        ClickHouseClient.execute(sql)
        recordRunDetail(test, result, resultId, locationCode, tsMs)
    }

    /** Persists the rich per-run detail (assertions, request/response, browser) keyed by resultId. */
    private suspend fun recordRunDetail(
        test: SyntheticTestData,
        result: SyntheticCheckResult,
        resultId: UUID,
        locationCode: String,
        tsMs: Long
    ) {
        val hasDetail = result.assertionResults.isNotEmpty() ||
            result.request != null || result.response != null || result.browser != null
        if (!hasDetail) return

        val detail = SyntheticRunDetail(
            assertions = result.assertionResults,
            request = result.request,
            response = result.response,
            timings = result.timings,
            resolvedIp = result.resolvedIp,
            browser = result.browser
        )
        val detailJson = Json.encodeToString(detail)
        val sql = """
            INSERT INTO `${ClickHouseClient.getDatabase()}`.synthetic_run_details
            (result_id, organization_id, test_id, location_code, details, timestamp)
            VALUES (
                '$resultId',
                toUInt64(${test.organizationId}),
                '${test.id}',
                '${escapeSql(locationCode)}',
                '${escapeSql(detailJson)}',
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

    private fun parseLocations(raw: String?): List<String> =
        suspendRunCatching {
            Json.decodeFromString<List<String>>(raw ?: "[]")
        }.getOrElse { emptyList() }

    private fun parseAlertConfig(raw: String?): AlertConfig? =
        raw?.let {
            suspendRunCatching {
                Json.decodeFromString<AlertConfig>(it)
            }.getOrElse { null }
        }

    private fun parseRecipients(raw: String?): List<AlertRecipient> =
        suspendRunCatching {
            Json.decodeFromString<List<AlertRecipient>>(raw ?: "[]")
        }.getOrElse { emptyList() }

    private fun parseBrowserSteps(raw: String?): List<BrowserStep> =
        raw?.let {
            suspendRunCatching {
                Json.decodeFromString<List<BrowserStep>>(it)
            }.getOrElse { emptyList() }
        } ?: emptyList()

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
            service = row[SyntheticTests.service],
            environment = row[SyntheticTests.environment],
            locations = parseLocations(row[SyntheticTests.locations]),
            alertConfig = parseAlertConfig(row[SyntheticTests.alertConfig]),
            alertRecipients = parseRecipients(row[SyntheticTests.alertRecipients]),
            browserSteps = row[SyntheticTests.browserSteps],
            previousStatus = row[SyntheticTests.previousStatus],
            createdAt = row[SyntheticTests.createdAt],
            updatedAt = row[SyntheticTests.updatedAt]
        )
    }

    private fun rowToResponse(row: ResultRow, organizationResourceId: String): SyntheticTestResponse {
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
            organizationId = organizationResourceId,
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
            service = row[SyntheticTests.service],
            environment = row[SyntheticTests.environment],
            locations = parseLocations(row[SyntheticTests.locations]),
            alertConfig = parseAlertConfig(row[SyntheticTests.alertConfig]),
            alertRecipients = parseRecipients(row[SyntheticTests.alertRecipients]),
            browserSteps = parseBrowserSteps(row[SyntheticTests.browserSteps]),
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

    /** Runs a ClickHouse query and parses JSONEachRow output (snake_case keys preserved). */
    private suspend fun executeChRows(query: String): List<kotlinx.serialization.json.JsonObject>? =
        runCatching {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) return@runCatching null
            response.bodyAsText().trim().lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                }
        }.getOrNull()

    /** Fetches a single run (result row + its rich detail) for the result drill-in. */
    suspend fun getRunDetail(
        testId: String,
        resultId: String,
        orgIds: List<Int>
    ): SyntheticRunResponse? {
        val orgCondition = orgIds.joinToString(",") { "toUInt64($it)" }
        val rowQuery = """
            SELECT result_id, test_id, test_name, test_type, status, location_code,
                   duration_ms, status_code, attempt, assertions_total, assertions_failed,
                   error_message,
                   formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S', 'UTC') AS ts
            FROM synthetic_results
            WHERE result_id = '${escapeSql(resultId)}'
              AND test_id = '${escapeSql(testId)}'
              AND organization_id IN ($orgCondition)
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()
        val row = executeChRows(rowQuery)?.firstOrNull() ?: return null

        val detailQuery = """
            SELECT details FROM synthetic_run_details
            WHERE result_id = '${escapeSql(resultId)}'
              AND organization_id IN ($orgCondition)
            LIMIT 1
            FORMAT JSONEachRow
        """.trimIndent()
        val detail = executeChRows(detailQuery)?.firstOrNull()
            ?.get("details")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let {
                suspendRunCatching {
                    Json.decodeFromString<SyntheticRunDetail>(it)
                }.getOrElse { null }
            }

        fun str(key: String): String = row[key]?.jsonPrimitive?.content ?: ""
        fun lng(key: String): Long = str(key).toLongOrNull() ?: 0L
        fun int(key: String): Int = str(key).toIntOrNull() ?: 0

        return SyntheticRunResponse(
            resultId = str("result_id"),
            testId = str("test_id"),
            testName = str("test_name"),
            testType = str("test_type"),
            status = str("status"),
            locationCode = str("location_code"),
            durationMs = lng("duration_ms"),
            statusCode = int("status_code"),
            attempt = int("attempt"),
            assertionsTotal = int("assertions_total"),
            assertionsFailed = int("assertions_failed"),
            errorMessage = str("error_message"),
            timestamp = str("ts"),
            detail = detail
        )
    }

    /** Per-location uptime/latency rollup (last 24h) for a test's detail grid. */
    suspend fun getLocationSummaries(
        testId: String,
        orgIds: List<Int>
    ): List<LocationSummary> {
        val orgCondition = orgIds.joinToString(",") { "toUInt64($it)" }
        val query = """
            SELECT location_code,
                   countIf(status = 'passed') * 100.0 / greatest(count(), 1) AS uptime,
                   avg(duration_ms) AS avg_ms,
                   quantile(0.95)(duration_ms) AS p95_ms,
                   count() AS total,
                   countIf(status = 'failed') AS failures
            FROM synthetic_results
            WHERE test_id = '${escapeSql(testId)}'
              AND organization_id IN ($orgCondition)
              AND timestamp >= now() - INTERVAL 1 DAY
            GROUP BY location_code
            ORDER BY location_code
            FORMAT JSONEachRow
        """.trimIndent()
        return executeChRows(query)?.map { row ->
            fun dbl(key: String): Double = row[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            fun lng(key: String): Long = row[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            LocationSummary(
                locationCode = row["location_code"]?.jsonPrimitive?.content ?: "",
                uptimePercent = dbl("uptime"),
                avgResponseMs = dbl("avg_ms"),
                p95ResponseMs = dbl("p95_ms"),
                totalRuns = lng("total"),
                failureCount = lng("failures")
            )
        } ?: emptyList()
    }

    /** Executes an unsaved test config once (no persistence) for the builder's live preview. */
    suspend fun previewTest(
        organizationId: Int,
        request: CreateSyntheticTestRequest,
        executor: SyntheticsCheckExecutor = SyntheticsCheckExecutor()
    ): SyntheticRunResponse {
        val now = Clock.System.now()
        val data = SyntheticTestData(
            id = UUID.randomUUID(),
            organizationId = organizationId,
            name = request.name.ifBlank { "Preview" },
            testType = request.testType,
            active = true,
            intervalSeconds = request.intervalSeconds,
            timeoutSeconds = request.timeoutSeconds,
            url = request.url,
            method = request.method,
            headers = request.headers?.let { Json.encodeToString(it) },
            body = request.body,
            authMethod = request.authMethod,
            authUser = request.authUser,
            authPass = request.authPass,
            assertions = Json.encodeToString(request.assertions),
            steps = if (request.steps.isEmpty()) null else Json.encodeToString(request.steps),
            status = "pending",
            tags = request.tags,
            config = request.config?.let { Json.encodeToString(it) },
            locations = request.locations,
            browserSteps = if (request.browserSteps.isEmpty()) {
                null
            } else {
                Json.encodeToString(request.browserSteps)
            },
            createdAt = now,
            updatedAt = now
        )
        val resolved = resolveGlobalVariables(data)
        val result = executor.executeTest(resolved)
        val detail = SyntheticRunDetail(
            assertions = result.assertionResults,
            request = result.request,
            response = result.response,
            timings = result.timings,
            resolvedIp = result.resolvedIp,
            browser = result.browser
        )
        return SyntheticRunResponse(
            resultId = "preview",
            testId = "preview",
            testName = data.name,
            testType = data.testType,
            status = result.status,
            locationCode = DEFAULT_LOCATION,
            durationMs = result.durationMs,
            statusCode = result.statusCode,
            attempt = 1,
            assertionsTotal = result.assertionResults.size,
            assertionsFailed = result.assertionResults.count { !it.passed },
            errorMessage = result.errorMessage,
            timestamp = now.toString(),
            detail = detail
        )
    }

    // --- Private-location worker (probe) protocol ---

    /** Work due at a private location: active tests including this code, past their interval. */
    suspend fun getProbeWork(organizationId: Int, locationCode: String): List<ProbeWorkItem> {
        val testDatas = transaction {
            SyntheticTests
                .selectAll()
                .where {
                    (SyntheticTests.organizationId eq organizationId) and
                        (SyntheticTests.active eq true)
                }
                .map { rowToData(it) }
        }.filter { locationCode in it.locations }
        if (testDatas.isEmpty()) return emptyList()

        val lastRuns = lastRunPerTest(organizationId, locationCode, testDatas.map { it.id.toString() })
        val now = System.currentTimeMillis()
        return testDatas
            .filter { t ->
                val last = lastRuns[t.id.toString()]
                last == null || (now - last) >= t.intervalSeconds * MILLIS_PER_SECOND_LONG
            }
            .map { t ->
                val resolved = resolveGlobalVariables(t)
                ProbeWorkItem(
                    testId = t.id.toString(),
                    testType = t.testType,
                    url = resolved.url,
                    method = t.method,
                    headers = parseHeadersMap(resolved.headers),
                    body = resolved.body,
                    assertions = parseAssertionList(t.assertions),
                    steps = parseStepList(resolved.steps),
                    browserSteps = parseBrowserSteps(t.browserSteps),
                    timeoutSeconds = t.timeoutSeconds,
                    config = parseTestConfig(t.config)
                )
            }
    }

    private suspend fun lastRunPerTest(
        organizationId: Int,
        locationCode: String,
        testIds: List<String>
    ): Map<String, Long> {
        if (testIds.isEmpty()) return emptyMap()
        val idList = testIds.joinToString(",") { "'${escapeSql(it)}'" }
        val query = """
            SELECT test_id, toUnixTimestamp64Milli(max(timestamp)) AS last_ms
            FROM synthetic_results
            WHERE organization_id = toUInt64($organizationId)
              AND location_code = '${escapeSql(locationCode)}'
              AND test_id IN ($idList)
            GROUP BY test_id
            FORMAT JSONEachRow
        """.trimIndent()
        return executeChRows(query)?.associate {
            (it["test_id"]?.jsonPrimitive?.content ?: "") to
                (it["last_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
        } ?: emptyMap()
    }

    /** Ingests a result a private-location worker posted back after running a [ProbeWorkItem]. */
    suspend fun recordProbeResult(
        organizationId: Int,
        locationCode: String,
        submission: ProbeResultSubmission
    ): Boolean {
        val testUuid = suspendRunCatching {
            UUID.fromString(submission.testId)
        }.getOrElse { return false }
        val test = transaction {
            SyntheticTests
                .selectAll()
                .where {
                    (SyntheticTests.id eq testUuid) and
                        (SyntheticTests.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { rowToData(it) }
        } ?: return false
        if (locationCode !in test.locations) return false

        val result = SyntheticCheckResult(
            status = submission.status,
            durationMs = submission.durationMs,
            errorMessage = submission.errorMessage,
            timings = submission.timings,
            statusCode = submission.statusCode,
            assertionResults = submission.assertions,
            request = submission.request,
            response = submission.response,
            resolvedIp = submission.resolvedIp,
            browser = submission.browser
        )
        suspendRunCatching { recordResult(test, result, locationCode) }.getOrElse { e ->
            logger.error(e) { "Failed to record probe result for ${test.id} @ $locationCode" }
            return false
        }
        suspendRunCatching { incrementSyntheticRunCount(organizationId) }
        suspendRunCatching {
            val aggregateStatus = aggregateProbeStatus(test, submission.status)
            updateTestStatus(test.id, aggregateStatus, aggregateStatus, test.lastStatus)
        }
        return true
    }

    private suspend fun aggregateProbeStatus(test: SyntheticTestData, fallbackStatus: String): String {
        if (test.locations.isEmpty()) return fallbackStatus
        val locationList = test.locations.joinToString(",") { "'${escapeSql(it)}'" }
        val rows = executeChRows(
            """
            SELECT location_code, argMax(status, timestamp) AS status
            FROM synthetic_results
            WHERE test_id = '${test.id}'
              AND organization_id = toUInt64(${test.organizationId})
              AND location_code IN ($locationList)
            GROUP BY location_code
            FORMAT JSONEachRow
            """.trimIndent()
        )
        if (rows.isNullOrEmpty()) return fallbackStatus
        return if (rows.any { it["status"]?.jsonPrimitive?.content == "failed" }) "failed" else "passed"
    }

    private fun parseHeadersMap(raw: String?): Map<String, String>? =
        raw?.let {
            suspendRunCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrElse { null }
        }

    private fun parseAssertionList(raw: String): List<SyntheticAssertion> =
        suspendRunCatching { Json.decodeFromString<List<SyntheticAssertion>>(raw) }.getOrElse { emptyList() }

    private fun parseStepList(raw: String?): List<SyntheticStep> =
        raw?.let {
            suspendRunCatching { Json.decodeFromString<List<SyntheticStep>>(it) }.getOrElse { emptyList() }
        } ?: emptyList()

    private fun parseTestConfig(raw: String?): SyntheticTestConfig? =
        raw?.let {
            suspendRunCatching { Json.decodeFromString<SyntheticTestConfig>(it) }.getOrElse { null }
        }

    // --- Global Variables ---

    fun listVariables(organizationId: Int): List<SyntheticVariableResponse> {
        return transaction {
            val rows = SyntheticVariables
                .selectAll()
                .where {
                    SyntheticVariables.organizationId eq organizationId
                }
                .toList()
            if (rows.isEmpty()) {
                emptyList()
            } else {
                val organizationResourceId = organizationResourceId(organizationId)
                rows
                    .map { variableRowToResponse(it, organizationResourceId) }
            }
        }
    }

    fun createVariable(
        organizationId: Int,
        request: SyntheticVariableRequest
    ): SyntheticVariableResponse {
        val row = transaction {
            SyntheticVariables.insert {
                it[SyntheticVariables.organizationId] = organizationId
                it[name] = request.name
                it[value] = request.value
                it[isSecret] = request.isSecret
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
        return getVariable(row[SyntheticVariables.resourceId], organizationId)!!
    }

    fun getVariable(
        variableResourceId: Uuid,
        organizationId: Int
    ): SyntheticVariableResponse? {
        return transaction {
            SyntheticVariables
                .selectAll()
                .where {
                    (SyntheticVariables.resourceId eq variableResourceId) and
                        (SyntheticVariables.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { variableRowToResponse(it, organizationResourceId(organizationId)) }
        }
    }

    fun updateVariable(
        variableResourceId: Uuid,
        organizationId: Int,
        request: SyntheticVariableRequest
    ): SyntheticVariableResponse? {
        val updated = transaction {
            SyntheticVariables.update(
                {
                    (SyntheticVariables.resourceId eq variableResourceId) and
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
            getVariable(variableResourceId, organizationId)
        } else {
            null
        }
    }

    fun deleteVariable(
        variableResourceId: Uuid,
        organizationId: Int
    ): Boolean {
        return transaction {
            SyntheticVariables.deleteWhere {
                (resourceId eq variableResourceId) and
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
        row: ResultRow,
        organizationResourceId: String
    ): SyntheticVariableResponse {
        val maskedValue = if (row[SyntheticVariables.isSecret]) {
            "********"
        } else {
            row[SyntheticVariables.value]
        }
        return SyntheticVariableResponse(
            id = row[SyntheticVariables.resourceId].toString(),
            organizationId = organizationResourceId,
            name = row[SyntheticVariables.name],
            value = maskedValue,
            isSecret = row[SyntheticVariables.isSecret],
            createdAt = row[SyntheticVariables.createdAt]
                .toEpochMilliseconds(),
            updatedAt = row[SyntheticVariables.updatedAt]
                .toEpochMilliseconds()
        )
    }

    private fun incrementSyntheticRunCount(organizationId: Int) {
        billingQuotaService.incrementUsageCounters(organizationId, syntheticRuns = 1)
    }
}
