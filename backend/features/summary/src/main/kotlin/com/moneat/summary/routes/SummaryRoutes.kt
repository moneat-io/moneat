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

package com.moneat.summary.routes

import com.moneat.auth.requireCurrentOrg
import com.moneat.shared.services.toUuidOrNull
import com.moneat.summary.services.SummaryService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mu.KotlinLogging
import java.time.DateTimeException
import java.time.ZoneId
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val validPeriods = setOf("24h", "7d", "30d")

/**
 * Handles expected failure modes from JDBC/Exposed, ClickHouse HTTP, and JSON parsing.
 * Other throwables propagate to the global StatusPages handler in [com.moneat.plugins.configureMonitoring].
 */
private suspend fun runSummaryServiceCall(
    call: ApplicationCall,
    logMessage: String,
    userMessage: String,
    block: suspend () -> Unit,
) {
    suspendRunCatching {
        block()
    }.onFailure { e ->
        logger.error(e) { "$logMessage: ${e.message}" }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(userMessage))
    }
}

fun Route.summaryRoutes(summaryService: SummaryService) {
    route("/v1/summary") {
        authenticate("auth-jwt") {
            get("/infrastructure") {
                runSummaryServiceCall(
                    call,
                    "Infrastructure summary error",
                    "Failed to get summary",
                ) {
                    val orgId = call.requireCurrentOrg()?.orgId ?: return@runSummaryServiceCall

                    val period = call.parameters["period"] ?: "24h"
                    if (period !in validPeriods) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                "Invalid period. Use: " +
                                    validPeriods.joinToString(", "),
                            ),
                        )
                        return@runSummaryServiceCall
                    }

                    val result = summaryService
                        .getInfrastructureSummary(
                            orgId,
                            period,
                        )
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            get("/overnight") {
                runSummaryServiceCall(
                    call,
                    "Overnight summary error",
                    "Failed to get summary",
                ) {
                    val orgId = call.requireCurrentOrg()?.orgId ?: return@runSummaryServiceCall

                    val timezone = call.parameters["timezone"]
                        ?: "America/New_York"
                    try {
                        ZoneId.of(timezone)
                    } catch (e: DateTimeException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                "Invalid timezone: $timezone",
                            ),
                        )
                        return@runSummaryServiceCall
                    }

                    val result = summaryService
                        .getOvernightSummary(orgId, timezone)
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            get("/weekly") {
                runSummaryServiceCall(
                    call,
                    "Weekly report error",
                    "Failed to get report",
                ) {
                    val orgId = call.requireCurrentOrg()?.orgId ?: return@runSummaryServiceCall

                    val result = summaryService
                        .getWeeklyReport(orgId)
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            get("/incident/{incident_id}") {
                runSummaryServiceCall(
                    call,
                    "Incident context error",
                    "Failed to get context",
                ) {
                    val orgId = call.requireCurrentOrg()?.orgId ?: return@runSummaryServiceCall

                    val incidentId = call.parameters["incident_id"]
                        ?.let(::parseSummaryResourceId)
                    if (incidentId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid incident_id"),
                        )
                        return@runSummaryServiceCall
                    }

                    val result = summaryService
                        .getIncidentContext(
                            orgId,
                            incidentId,
                        )
                    call.respond(HttpStatusCode.OK, result)
                }
            }
        }
    }
}

private fun parseSummaryResourceId(value: String): Uuid? =
    value.toUuidOrNull()
