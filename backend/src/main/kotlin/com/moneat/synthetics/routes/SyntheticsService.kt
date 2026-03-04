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

package com.moneat.synthetics.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.synthetics.models.CreateSyntheticTestRequest
import com.moneat.synthetics.models.SyntheticAssertion
import com.moneat.synthetics.models.SyntheticStep
import com.moneat.synthetics.models.SyntheticTestData
import com.moneat.synthetics.models.SyntheticTestResponse
import com.moneat.synthetics.models.SyntheticTests
import com.moneat.synthetics.models.UpdateSyntheticTestRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.time.Duration.Companion.seconds

class SyntheticsService {
    companion object {
        private val logger = KotlinLogging.logger {}
        private val runScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    fun createTest(
        organizationId: Int,
        request: CreateSyntheticTestRequest
    ): SyntheticTestResponse {
        checkQuota(organizationId)

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
                it[steps] = if (request.steps.isEmpty()) null else Json.encodeToString(request.steps)
                it[status] = "pending"
                it[lastRunAt] = null
                it[lastStatus] = null
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

    fun updateTest(
        testId: UUID,
        organizationId: Int,
        request: UpdateSyntheticTestRequest
    ): SyntheticTestResponse? {
        val updated = transaction {
            SyntheticTests
                .selectAll()
                .where { (SyntheticTests.id eq testId) and (SyntheticTests.organizationId eq organizationId) }
                .firstOrNull() ?: return@transaction false

            SyntheticTests.update(
                { (SyntheticTests.id eq testId) and (SyntheticTests.organizationId eq organizationId) }
            ) {
                request.name?.let { v -> it[name] = v }
                request.active?.let { v -> it[active] = v }
                request.intervalSeconds?.let { v -> it[intervalSeconds] = v }
                request.timeoutSeconds?.let { v -> it[timeoutSeconds] = v }
                request.url?.let { v -> it[url] = v }
                request.method?.let { v -> it[method] = v }
                request.headers?.let { v -> it[headers] = Json.encodeToString(v) }
                request.body?.let { v -> it[body] = v }
                request.authMethod?.let { v -> it[authMethod] = v }
                request.authUser?.let { v -> it[authUser] = v }
                request.authPass?.let { v -> it[authPass] = v }
                request.assertions?.let { v -> it[assertions] = Json.encodeToString(v) }
                request.steps?.let { v -> it[steps] = if (v.isEmpty()) null else Json.encodeToString(v) }
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

    fun updateTestStatus(testId: UUID, status: String, lastStatus: String) {
        transaction {
            SyntheticTests.update({ SyntheticTests.id eq testId }) {
                it[SyntheticTests.status] = status
                it[SyntheticTests.lastRunAt] = Clock.System.now()
                it[SyntheticTests.lastStatus] = lastStatus
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
        val result = try {
            withTimeout(test.timeoutSeconds * 1000L + 5000) {
                executor.executeTest(test)
            }
        } catch (e: Exception) {
            logger.error(e) { "Synthetic test execution failed for ${test.id}: ${e.message}" }
            SyntheticCheckResult(
                status = "failed",
                durationMs = 0,
                errorMessage = "Test execution failed: ${e.message}"
            )
        }

        try {
            recordResult(test, result)
        } catch (e: Exception) {
            logger.error(e) { "Failed to record synthetic result for ${test.id}: ${e.message}" }
        }

        try {
            updateTestStatus(test.id, result.status, result.status)
        } catch (e: Exception) {
            logger.error(e) { "Failed to update synthetic test status for ${test.id}: ${e.message}" }
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
            INSERT INTO ${ClickHouseClient.getDatabase()}.synthetic_results
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

    private fun escapeSql(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
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
            "FREE" -> 5
            "PRO" -> 20
            "TEAM" -> 50
            "BUSINESS" -> Int.MAX_VALUE
            else -> 5
        }

        if (currentCount >= limit) {
            throw IllegalStateException("Synthetic test limit reached ($limit for $tier tier)")
        }
    }

    private fun rowToData(row: ResultRow): SyntheticTestData {
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
            createdAt = row[SyntheticTests.createdAt],
            updatedAt = row[SyntheticTests.updatedAt]
        )
    }

    private fun rowToResponse(row: ResultRow): SyntheticTestResponse {
        val assertionsList: List<SyntheticAssertion> = try {
            Json.decodeFromString(row[SyntheticTests.assertions])
        } catch (_: Exception) {
            emptyList()
        }
        val stepsList: List<SyntheticStep> = try {
            row[SyntheticTests.steps]?.let { Json.decodeFromString(it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val headersMap: Map<String, String>? = try {
            row[SyntheticTests.headers]?.let { Json.decodeFromString(it) }
        } catch (_: Exception) {
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
            lastRunAt = row[SyntheticTests.lastRunAt]?.toEpochMilliseconds(),
            lastStatus = row[SyntheticTests.lastStatus],
            createdAt = row[SyntheticTests.createdAt].toEpochMilliseconds(),
            updatedAt = row[SyntheticTests.updatedAt].toEpochMilliseconds()
        )
    }
}
