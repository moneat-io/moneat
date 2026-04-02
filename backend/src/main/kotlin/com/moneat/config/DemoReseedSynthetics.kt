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

import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

// ── Synthetics Demo Data ────────────────────────────────────────────────

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
        val response = ClickHouseClient.execute(
            "ALTER TABLE synthetic_results DELETE WHERE organization_id IN ($P1, $P2, $P3)"
        )
        requireClickHouse2xx(response, "Purge synthetic_results")
    }.onFailure { logger.warn { "Purge synthetic_results failed (non-fatal): ${it.message}" } }
}

private fun buildSyntheticResultsInsertSql(): String =
    """
        INSERT INTO synthetic_results (
            result_id, organization_id, test_id, test_name, test_type,
            status, probe_dc, duration_ms, error_message, timings, tags, timestamp
        )
        SELECT
            generateUUIDv4(),
            arrayElement([$P1, $P2, $P3], number % 3 + 1),
            concat('test-', toString(number % 10 + 1)),
            arrayElement([
                'Homepage availability', 'Login flow', 'API health check',
                'Checkout flow', 'Search endpoint', 'User profile API',
                'Payment gateway', 'Image upload', 'Auth token refresh',
                'Webhook delivery'
            ], number % 10 + 1),
            arrayElement(['api', 'browser', 'multistep'], number % 3 + 1),
            arrayElement(['passed', 'passed', 'passed', 'failed', 'passed'], number % 5 + 1),
            arrayElement([
                'aws:us-east-1', 'aws:eu-west-1', 'aws:ap-southeast-1',
                'gcp:us-central1', 'azure:eastus'
            ], number % 5 + 1),
            toUInt64(50 + number % 450),
            if(number % 5 = 3, 'Connection timeout after 30s', ''),
            map(
                'dns', toFloat64(5 + number % 20),
                'connect', toFloat64(10 + number % 30),
                'ttfb', toFloat64(30 + number % 100)
            ),
            map('env', 'production'),
            now() - INTERVAL (number * 23 % 2880) MINUTE
        FROM numbers(80)
    """.trimIndent()

internal suspend fun reseedSyntheticsData() {
    val insertResult =
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute(buildSyntheticResultsInsertSql()),
                "Reseed synthetic_results"
            )
        }
    insertResult.onFailure {
        logger.warn { "Reseed synthetic_results failed (non-fatal): ${it.message}" }
    }
    if (insertResult.isSuccess) {
        logger.info { "Synthetics demo data reseed complete" }
    }
}
