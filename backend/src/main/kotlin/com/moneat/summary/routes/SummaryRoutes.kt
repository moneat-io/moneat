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
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

private val validPeriods = setOf("24h", "7d", "30d")

private fun getOrganizationIdsForUser(userId: Int): List<Int> {
    return transaction {
        Memberships
            .selectAll()
            .where { Memberships.user_id eq userId }
            .map { it[Memberships.organization_id] }
    }
}

fun Route.summaryRoutes(
    summaryService: SummaryService = GlobalContext.get().get(),
) {
    route("/v1/summary") {
        authenticate("auth-jwt") {
            get("/infrastructure") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid token")
                        )
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("No organization membership")
                        )
                        return@get
                    }

                    val period = call.parameters["period"] ?: "24h"
                    if (period !in validPeriods) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                "Invalid period. Use: " +
                                    validPeriods.joinToString(", ")
                            )
                        )
                        return@get
                    }

                    val result = summaryService
                        .getInfrastructureSummary(
                            orgIds.first(),
                            period
                        )
                    call.respond(HttpStatusCode.OK, result)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    logger.error(e) {
                        "Infrastructure summary error: ${e.message}"
                    }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get summary")
                    )
                }
            }

            get("/overnight") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid token")
                        )
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("No organization membership")
                        )
                        return@get
                    }

                    val timezone = call.parameters["timezone"]
                        ?: "America/New_York"
                    try {
                        ZoneId.of(timezone)
                    } catch (
                        @Suppress("SwallowedException")
                        e: DateTimeException
                    ) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                "Invalid timezone: $timezone"
                            )
                        )
                        return@get
                    }

                    val result = summaryService
                        .getOvernightSummary(orgIds.first(), timezone)
                    call.respond(HttpStatusCode.OK, result)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    logger.error(e) {
                        "Overnight summary error: ${e.message}"
                    }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get summary")
                    )
                }
            }

            get("/weekly") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid token")
                        )
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("No organization membership")
                        )
                        return@get
                    }

                    val result = summaryService
                        .getWeeklyReport(orgIds.first())
                    call.respond(HttpStatusCode.OK, result)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    logger.error(e) {
                        "Weekly report error: ${e.message}"
                    }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get report")
                    )
                }
            }

            get("/incident/{incident_id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload
                        ?.getClaim("userId")?.asInt()
                    if (userId == null) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid token")
                        )
                        return@get
                    }

                    val orgIds = getOrganizationIdsForUser(userId)
                    if (orgIds.isEmpty()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("No organization membership")
                        )
                        return@get
                    }

                    val incidentId = call.parameters["incident_id"]
                        ?.toLongOrNull()
                    if (incidentId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid incident_id")
                        )
                        return@get
                    }

                    val result = summaryService
                        .getIncidentContext(
                            orgIds.first(),
                            incidentId
                        )
                    call.respond(HttpStatusCode.OK, result)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception
                ) {
                    logger.error(e) {
                        "Incident context error: ${e.message}"
                    }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to get context")
                    )
                }
            }
        }
    }
}
