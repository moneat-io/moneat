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

import com.moneat.uptime.models.UptimeMonitors
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

internal suspend fun reseedUptimeHeartbeats() {
    val demoMonitors = listOf(
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002"
    )
    val demoMonitorUuids = demoMonitors.map { UUID.fromString(it) }

    // Delete all existing heartbeats for demo monitors and reseed with mostly-up data.
    // 30 days of 5-minute intervals = 8,640 points per monitor.
    // Two brief incident windows (numbers 150-161 and 500-509) simulate realistic downtime.
    for (monitorId in demoMonitors) {
        suspendRunCatching {
            val deleteResponse =
                ClickHouseClient.execute(
                    "ALTER TABLE uptime_heartbeats DELETE WHERE monitor_id = '$monitorId'"
                )
            requireClickHouse2xx(deleteResponse, "Delete uptime heartbeats for $monitorId")
            val insertResponse =
                ClickHouseClient.execute(
                    """
                    INSERT INTO uptime_heartbeats (
                        monitor_id, timestamp, status, response_time_ms, status_code, message, ping_ms)
                    SELECT
                        toUUID('$monitorId'),
                        now() - INTERVAL (number * 5) MINUTE,
                        if(number IN (150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161,
                                      500, 501, 502, 503, 504, 505, 506, 507, 508, 509), 0, 1),
                        if(number IN (150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161,
                                      500, 501, 502, 503, 504, 505, 506, 507, 508, 509),
                           0, 80 + (number % 120)) AS response_time_ms,
                        if(number IN (150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161,
                                      500, 501, 502, 503, 504, 505, 506, 507, 508, 509),
                           0, 200) AS status_code,
                        if(number IN (150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161,
                                      500, 501, 502, 503, 504, 505, 506, 507, 508, 509),
                           'Connection refused', 'OK') AS message,
                        if(number IN (150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161,
                                      500, 501, 502, 503, 504, 505, 506, 507, 508, 509),
                           0, 10 + (number % 30)) AS ping_ms
                    FROM numbers(8640)
                    """.trimIndent()
                )
            requireClickHouse2xx(insertResponse, "Insert uptime heartbeats for $monitorId")
        }.onFailure {
            logger.warn {
                "Failed to reseed uptime heartbeats for $monitorId (non-fatal): ${it.message}"
            }
        }
    }

    // Update postgres monitor status to "up" with a recent last_check_at.
    suspendRunCatching {
        transaction {
            UptimeMonitors.update({
                (UptimeMonitors.organizationId eq -1) and (UptimeMonitors.id inList demoMonitorUuids)
            }) {
                it[UptimeMonitors.status] = "up"
                it[UptimeMonitors.lastCheckAt] = Clock.System.now()
                it[UptimeMonitors.consecutiveFailures] = 0
            }
        }
    }.onFailure { logger.warn { "Failed to update demo uptime monitor status (non-fatal): ${it.message}" } }
}
