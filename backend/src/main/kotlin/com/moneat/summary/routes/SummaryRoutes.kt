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

import com.moneat.shared.models.Memberships
import com.moneat.summary.services.SummaryService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

private val validPeriods = setOf("24h", "7d", "30d")
private const val INVALID_TOKEN = "Invalid token"
private const val NO_ORGANIZATION_MEMBERSHIP = "No organization membership"

private fun getOrganizationIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

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

fun Route.summaryRoutes(
    summaryService: SummaryService = GlobalContext.get().get(),
) {
    route("/v1/summary") {
        authenticate("auth-jwt") {
            get("/infrastructure") {
                runSummaryServiceCall(
                    call,
                    "Infrastructure summary error",
                    "Failed to get summary",
                ) {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(INVALID_TOKEN),
                        )
                        return@runSummaryServiceCall
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(NO_ORGANIZATION_MEMBERSHIP),
                        )
                        return@runSummaryServiceCall
                    }

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
                            orgIds.first(),
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
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(INVALID_TOKEN),
                        )
                        return@runSummaryServiceCall
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(NO_ORGANIZATION_MEMBERSHIP),
                        )
                        return@runSummaryServiceCall
                    }

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
                        .getOvernightSummary(orgIds.first(), timezone)
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            get("/weekly") {
                runSummaryServiceCall(
                    call,
                    "Weekly report error",
                    "Failed to get report",
                ) {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(INVALID_TOKEN),
                        )
                        return@runSummaryServiceCall
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(NO_ORGANIZATION_MEMBERSHIP),
                        )
                        return@runSummaryServiceCall
                    }

                    val result = summaryService
                        .getWeeklyReport(orgIds.first())
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            get("/incident/{incident_id}") {
                runSummaryServiceCall(
                    call,
                    "Incident context error",
                    "Failed to get context",
                ) {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(INVALID_TOKEN),
                        )
                        return@runSummaryServiceCall
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse(NO_ORGANIZATION_MEMBERSHIP),
                        )
                        return@runSummaryServiceCall
                    }

                    val incidentId = call.parameters["incident_id"]
                        ?.toLongOrNull()
                    if (incidentId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid incident_id"),
                        )
                        return@runSummaryServiceCall
                    }

                    val result = summaryService
                        .getIncidentContext(
                            orgIds.first(),
                            incidentId,
                        )
                    call.respond(HttpStatusCode.OK, result)
                }
            }
        }
    }
}
