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

package com.moneat.events.routes

import com.moneat.config.ClickHouseClient
import com.moneat.shared.services.PulsePayload
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Receives anonymous telemetry pulses from self-hosted Moneat deployments and
 * stores them in ClickHouse for analysis. Only active on the cloud-hosted platform.
 *
 * POST /v1/pulse — no authentication required (payloads are fully anonymous).
 */
fun Route.telemetryIngestRoutes(
    insertPulse: suspend (PulsePayload) -> Unit = { pulse ->
        val esc = ClickHouseSqlUtils::escapeSql
        ClickHouseClient.execute(
            """
            INSERT INTO telemetry_pulses (
                deployment_id, version, cpu_count, mem_total_bytes, mem_used_bytes,
                os_name, os_arch, jvm_version,
                project_count, user_count, event_count, issue_count,
                ssl_enabled
            ) VALUES (
                '${esc(pulse.deploymentId)}',
                '${esc(pulse.version)}',
                ${pulse.cpuCount},
                ${pulse.memTotalBytes},
                ${pulse.memUsedBytes},
                '${esc(pulse.osName)}',
                '${esc(pulse.osArch)}',
                '${esc(pulse.jvmVersion)}',
                ${pulse.projectCount},
                ${pulse.userCount},
                ${pulse.eventCount},
                ${pulse.issueCount},
                ${if (pulse.sslEnabled) 1 else 0}
            )
            """.trimIndent()
        )
    }
) {
    route("/v1/pulse") {
        post {
            val payload =
                suspendRunCatching {
                    call.receive<PulsePayload>()
                }.getOrElse { e ->
                    logger.warn { "Invalid telemetry pulse payload: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid payload"))
                    return@post
                }

            if (payload.deploymentId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "deploymentId is required"))
                return@post
            }

            suspendRunCatching {
                insertPulse(payload)
                logger.debug { "Telemetry pulse stored for deployment=${payload.deploymentId}" }
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "ok"))
            }.onFailure { e ->
                logger.error(e) { "Failed to store telemetry pulse: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to store pulse"))
            }
        }
    }
}
