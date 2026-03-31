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

internal suspend fun checkFreshLogsCount(): Long {
    val query =
        """
        SELECT count() as cnt
        FROM logs
        WHERE organization_id = $P1
            AND timestamp >= now() - INTERVAL 2 HOUR
        """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            0L
        } else {
            body.trim().toLongOrNull() ?: 0L
        }
    }.getOrElse {
        logger.warn { "Failed to check fresh logs demo data (non-fatal): ${it.message}" }
        0L
    }
}

internal suspend fun purgeLogsDemoData() {
    suspendRunCatching {
        val response = ClickHouseClient.execute(
            "ALTER TABLE logs DELETE WHERE organization_id = $P1 OR organization_id = 0"
        )
        requireClickHouse2xx(response, "Purge logs")
    }.onFailure {
        logger.warn { "Purge logs failed (non-fatal): ${it.message}" }
    }
}

internal suspend fun reseedLogs() {
    val msgCase =
        """
            CASE number % 8
                WHEN 0 THEN concat(
                    'HTTP GET /api/products completed in ', toString(45 + number % 200), 'ms with status 200')
                WHEN 1 THEN concat(
                    'HTTP POST /api/orders completed in ', toString(123 + number % 300), 'ms with status 201')
                WHEN 2 THEN concat(
                    'User user', toString(number % 50), '@example.com authenticated successfully')
                WHEN 3 THEN concat('Cache miss for key: product:', toString(100 + number % 900))
                WHEN 4 THEN concat(
                    'Rate limit approaching for IP 192.168.1.',
                    toString(number % 254), ': ', toString(950 + number % 50), '/1000 requests')
                WHEN 5 THEN concat(
                    'Database connection timeout after ', toString(30 + number % 30),
                    's for query: SELECT * FROM orders')
                WHEN 6 THEN concat(
                    'Payment processing failed for order ORD-',
                    toString(10000 + number % 90000), ': card_declined')
                ELSE concat(
                    'Redis command executed: GET product:', toString(number % 500),
                    ' in ', toString(2 + number % 20), 'ms')
            END
        """.trimIndent()
    val tagsServiceCase =
        "CASE number % 5 WHEN 0 THEN 'api-server' WHEN 1 THEN 'auth-service' " +
            "WHEN 2 THEN 'payment-processor' WHEN 3 THEN 'notification-service' ELSE 'cache-service' END"
    val tagsEnvCase = "CASE number % 7 WHEN 0 THEN 'staging' ELSE 'production' END"
    val sql =
        """
        INSERT INTO logs (
            log_id, organization_id, timestamp, received_at, level, message, body,
            service, environment, host, source, trace_id, span_id, tags,
            container_name, container_id, container_image, resource_attributes
        )
        SELECT
            generateUUIDv4() AS log_id,
            $P1 AS organization_id,
            now64(3) - INTERVAL (
                CASE
                    WHEN number < $LOG_BUCKET_1_MAX THEN number % 10
                    WHEN number < $LOG_BUCKET_2_MAX THEN 10 + (number % 20)
                    WHEN number < $LOG_BUCKET_3_MAX THEN 30 + (number % 30)
                    ELSE $LOG_BUCKET_4_BASE_MINUTES + (number % 60)
                END * 60 + number % 60
            ) SECOND AS timestamp,
            now64(3) AS received_at,
            CASE (number * 7 + 3) % 100
                WHEN 0 THEN 'debug'
                WHEN 1 THEN 'debug'
                WHEN 2 THEN 'debug'
                WHEN 3 THEN 'debug'
                WHEN 4 THEN 'debug'
                WHEN 5 THEN 'error'
                WHEN 6 THEN 'error'
                WHEN 7 THEN 'error'
                WHEN 8 THEN 'error'
                WHEN 9 THEN 'error'
                WHEN 10 THEN 'error'
                WHEN 11 THEN 'error'
                WHEN 12 THEN 'error'
                WHEN 13 THEN 'error'
                WHEN 14 THEN 'error'
                WHEN 15 THEN 'warn'
                WHEN 16 THEN 'warn'
                WHEN 17 THEN 'warn'
                WHEN 18 THEN 'warn'
                WHEN 19 THEN 'warn'
                WHEN 20 THEN 'warn'
                WHEN 21 THEN 'warn'
                WHEN 22 THEN 'warn'
                WHEN 23 THEN 'warn'
                WHEN 24 THEN 'warn'
                WHEN 25 THEN 'warn'
                WHEN 26 THEN 'warn'
                WHEN 27 THEN 'warn'
                WHEN 28 THEN 'warn'
                WHEN 29 THEN 'warn'
                WHEN 30 THEN 'warn'
                WHEN 31 THEN 'warn'
                WHEN 32 THEN 'warn'
                WHEN 33 THEN 'warn'
                WHEN 34 THEN 'warn'
                WHEN 35 THEN 'warn'
                WHEN 36 THEN 'warn'
                WHEN 37 THEN 'warn'
                WHEN 38 THEN 'warn'
                WHEN 39 THEN 'warn'
                ELSE 'info'
            END AS level,
            $msgCase AS message,
            $msgCase AS body,
            CASE number % 5
                WHEN 0 THEN 'api-server'
                WHEN 1 THEN 'auth-service'
                WHEN 2 THEN 'payment-processor'
                WHEN 3 THEN 'notification-service'
                ELSE 'cache-service'
            END AS service,
            CASE number % 7
                WHEN 0 THEN 'staging'
                ELSE 'production'
            END AS environment,
            CASE number % 5
                WHEN 0 THEN 'api-prod-1'
                WHEN 1 THEN 'api-prod-2'
                WHEN 2 THEN 'api-prod-3'
                WHEN 3 THEN 'worker-prod-1'
                ELSE 'worker-prod-2'
            END AS host,
            'sdk' AS source,
            lower(hex(generateUUIDv4())) AS trace_id,
            substring(lower(hex(generateUUIDv4())), 1, 16) AS span_id,
            map(
                'service', $tagsServiceCase,
                'environment', $tagsEnvCase,
                'version', concat('1.', toString(number % 5), '.', toString(number % 10))
            ) AS tags,
            '' AS container_name,
            '' AS container_id,
            '' AS container_image,
            map() AS resource_attributes
        FROM numbers($LOG_SEED_ROWS)
        """.trimIndent()
    suspendRunCatching {
        val response = ClickHouseClient.execute(sql)
        requireClickHouse2xx(response, "Reseed logs")
    }.onFailure { logger.warn { "Reseed logs failed (non-fatal): ${it.message}" } }
}
