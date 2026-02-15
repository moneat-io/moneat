package com.moneat.routes

import com.moneat.config.EnvConfig
import com.moneat.models.*
import com.moneat.services.SsoService
import com.moneat.utils.AuthCookieUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

fun Route.ssoRoutes() {
    val ssoService = SsoService()
    
    route("/auth/sso") {
        // Public SSO flow endpoints
        post("/init") {
            try {
                val request = call.receive<SsoInitRequest>()
                val response = ssoService.initSso(request.email, request.orgSlug)
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SSO init failed: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error(e) { "SSO init error" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "SSO initialization failed"))
            }
        }
        
        get("/saml/metadata") {
            try {
                val orgSlug = call.parameters["org"]
                val metadata = ssoService.getSamlMetadata(orgSlug)
                call.respondText(metadata, ContentType.Text.Xml)
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SAML metadata request failed: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                logger.error(e) { "SAML metadata error" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to generate SAML metadata"))
            }
        }
        
        post("/saml/acs") {
            try {
                val params = call.receiveParameters()
                val samlResponse = params["SAMLResponse"] ?: throw IllegalArgumentException("Missing SAMLResponse")
                val relayState = params["RelayState"]
                
                val callbackData = ssoService.handleSamlResponse(samlResponse, relayState)
                
                AuthCookieUtils.setAuthCookie(call, callbackData.token)
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/auth/sso/callback")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SAML ACS failed: ${e.message}" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=sso_failed&message=${e.message}")
            } catch (e: Exception) {
                logger.error(e) { "SAML ACS error" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=sso_failed")
            }
        }
        
        get("/oidc/callback") {
            try {
                val code = call.parameters["code"] ?: throw IllegalArgumentException("Missing authorization code")
                val state = call.parameters["state"] ?: throw IllegalArgumentException("Missing state parameter")
                
                val callbackData = ssoService.handleOidcCallback(code, state)
                
                AuthCookieUtils.setAuthCookie(call, callbackData.token)
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/auth/sso/callback")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "OIDC callback failed: ${e.message}" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=sso_failed&message=${e.message}")
            } catch (e: Exception) {
                logger.error(e) { "OIDC callback error" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=sso_failed")
            }
        }
    }
    
    // Protected SSO configuration endpoints
    route("/v1/sso") {
        authenticate("auth-jwt") {
            get("/config") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    
                    // Get user's primary organization
                    val orgId = call.parameters["organizationId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing organizationId"))
                    
                    // Verify user has access to this organization
                    val isMember = transaction {
                        Memberships.selectAll()
                            .where {
                                (Memberships.organization_id eq orgId) and
                                (Memberships.user_id eq userId)
                            }
                            .firstOrNull() != null
                    }
                    
                    if (!isMember) {
                        return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    }
                    
                    val config = ssoService.getSsoConfig(orgId)
                    if (config != null) {
                        call.respond(config)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "SSO not configured"))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Get SSO config error" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to retrieve SSO configuration"))
                }
            }
            
            put("/config") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    
                    val request = call.receive<SsoConfigRequest>()
                    val orgId = call.parameters["organizationId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing organizationId"))
                    
                    val config = ssoService.configureSso(orgId, userId, request)
                    call.respond(config)
                } catch (e: IllegalArgumentException) {
                    logger.error(e) { "Configure SSO failed: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Configure SSO error" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to configure SSO"))
                }
            }
            
            delete("/config") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asInt()
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
                    
                    val orgId = call.parameters["organizationId"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing organizationId"))
                    
                    val deleted = ssoService.deleteSsoConfig(orgId, userId)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "SSO configuration deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "SSO configuration not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    logger.error(e) { "Delete SSO config failed: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Delete SSO config error" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete SSO configuration"))
                }
            }
            
            post("/check-required") {
                try {
                    val request = call.receive<Map<String, String>>()
                    val email = request["email"] 
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing email"))
                    
                    val required = ssoService.checkSsoRequired(email)
                    call.respond(mapOf("required" to required))
                } catch (e: Exception) {
                    logger.error(e) { "Check SSO required error" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to check SSO requirement"))
                }
            }
        }
    }
}
