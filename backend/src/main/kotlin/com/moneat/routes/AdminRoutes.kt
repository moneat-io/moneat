package com.moneat.routes

import com.moneat.models.Users
import com.moneat.services.AdminService
import com.moneat.services.PricingTierService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.adminRoutes() {
    val adminService = AdminService()
    val pricingTierService = PricingTierService()
    
    authenticate("auth-jwt") {
        route("/v1/admin") {
            intercept(ApplicationCallPipeline.Call) {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, "Authentication required")
                    finish()
                    return@intercept
                }
                val userId = principal.payload.getClaim("userId").asInt()
                val isAdmin = transaction {
                    Users.selectAll().where { Users.id eq userId }
                        .firstOrNull()?.get(Users.is_admin) ?: false
                }
                if (!isAdmin) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin access required"))
                    finish()
                    return@intercept
                }
            }
            
            get("/overview") {
                val stats = adminService.getOverviewStats()
                call.respond(stats)
            }
            
            get("/organizations") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 25
                val orgs = adminService.getAllOrganizations(page, limit)
                call.respond(orgs)
            }
            
            get("/organizations/{orgId}") {
                val orgId = call.parameters["orgId"]?.toIntOrNull()
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid org ID")
                    return@get
                }
                val detail = adminService.getOrgDetail(orgId)
                if (detail == null) {
                    call.respond(HttpStatusCode.NotFound, "Organization not found")
                } else {
                    call.respond(detail)
                }
            }
            
            get("/organizations/{orgId}/usage") {
                val orgId = call.parameters["orgId"]?.toIntOrNull()
                if (orgId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid org ID")
                    return@get
                }
                val period = call.request.queryParameters["period"] ?: "7d"
                val usage = adminService.getOrgUsage(orgId, period)
                val response = usage.map { u ->
                    mapOf(
                        "date" to u.date.toString(),
                        "eventType" to u.eventType,
                        "eventCount" to u.eventCount,
                        "bytesIngested" to u.bytesIngested
                    )
                }
                call.respond(response)
            }
            
            get("/usage") {
                val period = call.request.queryParameters["period"] ?: "7d"
                val breakdown = adminService.getUsageBreakdown(period)
                call.respond(breakdown)
            }
            
            get("/revenue") {
                val metrics = adminService.getRevenueMetrics()
                call.respond(metrics)
            }
            
            get("/infrastructure") {
                val health = adminService.getInfrastructureHealth()
                call.respond(health)
            }
            
            get("/top-consumers") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
                val consumers = adminService.getTopConsumers(limit)
                call.respond(consumers)
            }
            
            get("/emails") {
                val period = call.request.queryParameters["period"] ?: "30d"
                val stats = adminService.getEmailStats(period)
                call.respond(stats)
            }

            route("/billing") {
                get("/tiers") {
                    val tierName = call.request.queryParameters["tier"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(pricingTierService.getCurrentPlans())
                    } else {
                        call.respond(pricingTierService.getTierVersions(tierName))
                    }
                }

                post("/tiers/{tierName}/versions") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing tier name"))
                        return@post
                    }
                    val request = call.receive<com.moneat.models.CreateTierVersionRequest>()
                    val created = pricingTierService.createTierVersion(tierName, request)
                    call.respond(HttpStatusCode.Created, created)
                }

                post("/tiers/{tierName}/migrate") {
                    val tierName = call.parameters["tierName"]?.uppercase()
                    if (tierName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing tier name"))
                        return@post
                    }
                    val request = call.receive<com.moneat.models.TierMigrationRequest>()
                    val response = pricingTierService.migrateSubscribers(tierName, request)
                    call.respond(response)
                }

                get("/subscriptions") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                    call.respond(pricingTierService.listAdminSubscriptions(limit))
                }
            }
        }
    }
}
