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

package com.moneat.config

import com.moneat.synthetics.routes.AlertConfig
import com.moneat.synthetics.routes.BrowserStep
import com.moneat.synthetics.routes.SyntheticAssertion
import com.moneat.synthetics.routes.SyntheticTests
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

// ── Synthetics Demo Data ────────────────────────────────────────────────

private const val DEMO_ORG = -1

// The redesign's demo tests (managed-location, multi-region). `d3` (Payments webhook) fails
// from EU · Frankfurt so the overview/detail show a real failing + degraded story.
private data class DemoSyntheticTest(
    val id: String,
    val name: String,
    val type: String,
    val service: String,
    val failing: Boolean,
)

private val DEMO_TESTS = listOf(
    DemoSyntheticTest("d1a11111-0000-4000-8000-000000000001", "Checkout API", "api", "shop", false),
    DemoSyntheticTest("d2a22222-0000-4000-8000-000000000002", "Login flow", "browser", "web", false),
    DemoSyntheticTest("d3a33333-0000-4000-8000-000000000003", "Payments webhook", "api", "payments", true),
    DemoSyntheticTest("d4a44444-0000-4000-8000-000000000004", "Search API", "api", "search", false),
    DemoSyntheticTest("d5a55555-0000-4000-8000-000000000005", "Auth token refresh", "multistep", "identity", false),
    DemoSyntheticTest("d6a66666-0000-4000-8000-000000000006", "SSL · api.acme.com", "ssl", "web", false),
)

private val DEMO_LOCATIONS = listOf("aws-us-east-1", "aws-us-west-2", "aws-eu-central-1", "aws-ap-southeast-1")

internal suspend fun checkFreshSyntheticsDataCount(): Long {
    val query = """
        SELECT count() FROM synthetic_results
        WHERE organization_id IN ($P1, $P2, $P3)
            AND timestamp >= now() - INTERVAL 2 HOUR
    """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            0L
        } else {
            response.bodyAsText().trim().toLongOrNull() ?: 0L
        }
    }.getOrElse {
        logger.warn { "Failed to check fresh synthetics demo data (non-fatal): ${it.message}" }
        0L
    }
}

internal suspend fun purgeSyntheticsDemoData() {
    suspendRunCatching {
        requireClickHouse2xx(
            ClickHouseClient.execute(
                "ALTER TABLE synthetic_results DELETE WHERE organization_id IN ($P1, $P2, $P3)"
            ),
            "Purge synthetic_results"
        )
    }.onFailure { logger.warn { "Purge synthetic_results failed (non-fatal): ${it.message}" } }
    suspendRunCatching {
        requireClickHouse2xx(
            ClickHouseClient.execute(
                "ALTER TABLE synthetic_run_details DELETE WHERE organization_id IN ($P1, $P2, $P3)"
            ),
            "Purge synthetic_run_details"
        )
    }.onFailure { logger.warn { "Purge synthetic_run_details failed (non-fatal): ${it.message}" } }
    suspendRunCatching {
        transaction { SyntheticTests.deleteWhere { organizationId eq DEMO_ORG } }
    }.onFailure { logger.warn { "Purge demo synthetic_tests failed (non-fatal): ${it.message}" } }
}

private fun alertConfigJson(): String =
    Json.encodeToString(
        AlertConfig(
            consecutiveChecks = 3,
            minLocations = 2,
            totalLocations = 4,
            retestCount = 1
        )
    )

private fun browserStepsJson(): String =
    Json.encodeToString(
        listOf(
            BrowserStep("navigate", "Navigate to acme.com", value = "https://acme.com"),
            BrowserStep("click", "Click Sign in", selector = "text=Sign in"),
            BrowserStep(
                "type",
                "Type email",
                selector = "#email",
                value = "{{USER_EMAIL}}"
            ),
            BrowserStep("click", "Click Log in", selector = "#login"),
            BrowserStep("assert", "Dashboard visible", value = "Dashboard", assertType = "text_visible"),
        )
    )

private fun insertDemoSyntheticTest(
    test: DemoSyntheticTest,
    now: Instant,
    locationsJson: String,
    alertJson: String,
    browserStepsJson: String,
) {
    SyntheticTests.insert {
        it[id] = UUID.fromString(test.id)
        it[organizationId] = DEMO_ORG
        it[name] = test.name
        it[testType] = test.type
        it[active] = true
        it[intervalSeconds] = if (test.type == "browser") 300 else 60
        it[timeoutSeconds] = 30
        it[url] = if (test.type == "browser") "https://acme.com" else "https://api.acme.com/v1/${test.service}"
        it[method] = if (test.name == "Payments webhook") "POST" else "GET"
        it[assertions] = Json.encodeToString(
            listOf(SyntheticAssertion(type = "status_code", operator = "equals", value = "200"))
        )
        it[status] = if (test.failing) "failed" else "passed"
        it[lastRunAt] = now
        it[lastStatus] = if (test.failing) "failed" else "passed"
        it[tags] = Json.encodeToString(listOf("team:${test.service}", "tier:critical"))
        it[service] = test.service
        it[environment] = "production"
        it[locations] = locationsJson
        it[alertConfig] = if (test.failing) alertJson else null
        it[browserSteps] = if (test.type == "browser") browserStepsJson else null
        it[createdAt] = now
        it[updatedAt] = now
    }
}

private fun insertDemoSyntheticTests() {
    val now = Clock.System.now()
    val locationsJson = Json.encodeToString(DEMO_LOCATIONS)
    val alertJson = alertConfigJson()
    val browserStepsJson = browserStepsJson()
    transaction {
        DEMO_TESTS.forEach { test -> insertDemoSyntheticTest(test, now, locationsJson, alertJson, browserStepsJson) }
    }
}

private fun buildSyntheticResultsInsertSql(): String {
    val uuids = DEMO_TESTS.joinToString(", ") { "'${it.id}'" }
    val names = DEMO_TESTS.joinToString(", ") { "'${it.name}'" }
    val types = DEMO_TESTS.joinToString(", ") { "'${it.type}'" }
    val locs = DEMO_LOCATIONS.joinToString(", ") { "'$it'" }
    val failingId = DEMO_TESTS.first { it.failing }.id
    return """
        INSERT INTO synthetic_results (
            result_id, organization_id, test_id, test_name, test_type, status, probe_dc, location_code,
            duration_ms, error_message, timings, tags, status_code, assertions_total, assertions_failed,
            resolved_ip, timestamp
        )
        WITH
            arrayElement([$uuids], number % 6 + 1) AS tid,
            arrayElement([$names], number % 6 + 1) AS tname,
            arrayElement([$types], number % 6 + 1) AS ttype,
            arrayElement([$locs], number % 4 + 1) AS loc,
            multiIf(tid = '$failingId' AND loc = 'aws-eu-central-1', 'failed',
                number % 41 = 7, 'failed',
                'passed') AS st
        SELECT
            generateUUIDv4(), toUInt64(-1), tid, tname, ttype, st, loc, loc,
            toUInt64(multiIf(ttype = 'browser', 1500 + number % 1500, 60 + number % 400)),
            multiIf(st = 'failed', 'Assertion failed: status code is 200', ''),
            map('dns', toFloat64(5 + number % 15), 'tcp', toFloat64(8 + number % 20),
                'tls', toFloat64(20 + number % 40), 'ttfb', toFloat64(40 + number % 120),
                'download', toFloat64(10 + number % 30)),
            map('env', 'production'),
            multiIf(st = 'failed', toUInt16(503), toUInt16(200)),
            toUInt16(4), multiIf(st = 'failed', toUInt16(1), toUInt16(0)),
            concat('3.122.', toString(number % 250), '.', toString(number % 99)),
            now() - INTERVAL (number * 5 % 1440) MINUTE
        FROM numbers(288)
    """.trimIndent()
}

internal suspend fun reseedSyntheticsData() {
    suspendRunCatching { insertDemoSyntheticTests() }
        .onFailure { logger.warn { "Reseed demo synthetic_tests failed (non-fatal): ${it.message}" } }

    val insertResult = suspendRunCatching {
        requireClickHouse2xx(
            ClickHouseClient.execute(buildSyntheticResultsInsertSql()),
            "Reseed synthetic_results"
        )
    }
    insertResult.onFailure { logger.warn { "Reseed synthetic_results failed (non-fatal): ${it.message}" } }
    if (insertResult.isSuccess) {
        logger.info { "Synthetics demo data reseed complete" }
    }
}
