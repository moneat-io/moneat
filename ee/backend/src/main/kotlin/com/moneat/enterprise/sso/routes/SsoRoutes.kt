// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.routes

import com.moneat.config.EnvConfig
import com.moneat.enterprise.sso.models.SsoConfigRequest
import com.moneat.enterprise.sso.models.SsoInitRequest
import com.moneat.enterprise.sso.services.SsoService
import com.moneat.shared.models.Memberships
import com.moneat.utils.AuthCookieUtils
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

fun Route.ssoRoutes() {
    val ssoService = SsoService()
    val frontendUrl = EnvConfig.get("FRONTEND_URL")!!

    route("/auth/sso") {
        // Public SSO flow endpoints
        post("/init") {
            try {
                val request = call.receive<SsoInitRequest>()
                val response = ssoService.initSso(request.email, request.orgSlug)
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SSO init failed: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            } catch (e: Exception) {
                logger.error(e) { "SSO init error" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("SSO initialization failed"))
            }
        }

        get("/saml/metadata") {
            try {
                val orgSlug = call.parameters["org"]
                val metadata = ssoService.getSamlMetadata(orgSlug)
                call.respondText(metadata, ContentType.Text.Xml)
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SAML metadata request failed: ${e.message}" }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            } catch (e: Exception) {
                logger.error(e) { "SAML metadata error" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to generate SAML metadata"))
            }
        }

        post("/saml/acs") {
            try {
                val params = call.receiveParameters()
                val samlResponse = params["SAMLResponse"] ?: throw IllegalArgumentException("Missing SAMLResponse")
                val relayState = params["RelayState"]

                val callbackData = ssoService.handleSamlResponse(samlResponse, relayState)

                AuthCookieUtils.setAuthCookie(call, callbackData.token)
                call.respondRedirect("$frontendUrl/auth/sso/callback")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SAML ACS failed: ${e.message}" }
                call.respondRedirect("$frontendUrl/login?error=sso_failed")
            } catch (e: Exception) {
                logger.error(e) { "SAML ACS error" }
                call.respondRedirect("$frontendUrl/login?error=sso_failed")
            }
        }

        get("/oidc/callback") {
            try {
                val code = call.parameters["code"] ?: throw IllegalArgumentException("Missing authorization code")
                val state = call.parameters["state"] ?: throw IllegalArgumentException("Missing state parameter")

                val callbackData = ssoService.handleOidcCallback(code, state)

                AuthCookieUtils.setAuthCookie(call, callbackData.token)
                call.respondRedirect("$frontendUrl/auth/sso/callback")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "OIDC callback failed: ${e.message}" }
                call.respondRedirect("$frontendUrl/login?error=sso_failed")
            } catch (e: Exception) {
                logger.error(e) { "OIDC callback error" }
                call.respondRedirect("$frontendUrl/login?error=sso_failed")
            }
        }
    }

    // Protected SSO configuration endpoints
    route("/v1/sso") {
        authenticate("auth-jwt") {
            get("/config") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId =
                        principal?.payload?.getClaim("userId")?.asInt()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))

                    // Get user's primary organization
                    val orgId =
                        call.parameters["organizationId"]?.toIntOrNull()
                            ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Missing organizationId"),
                            )

                    // Verify user has access to this organization
                    val isMember =
                        transaction {
                            Memberships
                                .selectAll()
                                .where {
                                    (Memberships.organization_id eq orgId) and
                                        (Memberships.user_id eq userId)
                                }.firstOrNull() != null
                        }

                    if (!isMember) {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
                    }

                    val config = ssoService.getSsoConfig(orgId)
                    if (config != null) {
                        call.respond(config)
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("SSO not configured"))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Get SSO config error" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to retrieve SSO configuration"),
                    )
                }
            }

            put("/config") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId =
                        principal?.payload?.getClaim("userId")?.asInt()
                            ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))

                    val request = call.receive<SsoConfigRequest>()
                    val orgId =
                        call.parameters["organizationId"]?.toIntOrNull()
                            ?: return@put call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Missing organizationId"),
                            )

                    val config = ssoService.configureSso(orgId, userId, request)
                    call.respond(config)
                } catch (e: IllegalArgumentException) {
                    logger.error(e) { "Configure SSO failed: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Configure SSO error" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to configure SSO"))
                }
            }

            delete("/config") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId =
                        principal?.payload?.getClaim("userId")?.asInt()
                            ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))

                    val orgId =
                        call.parameters["organizationId"]?.toIntOrNull()
                            ?: return@delete call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Missing organizationId"),
                            )

                    val deleted = ssoService.deleteSsoConfig(orgId, userId)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, MessageResponse("SSO configuration deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("SSO configuration not found"))
                    }
                } catch (e: IllegalArgumentException) {
                    logger.error(e) { "Delete SSO config failed: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                } catch (e: Exception) {
                    logger.error(e) { "Delete SSO config error" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to delete SSO configuration"),
                    )
                }
            }

            post("/check-required") {
                try {
                    val request = call.receive<Map<String, String>>()
                    val email =
                        request["email"]
                            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing email"))

                    val required = ssoService.checkSsoRequired(email)
                    call.respond(mapOf("required" to required))
                } catch (e: Exception) {
                    logger.error(e) { "Check SSO required error" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to check SSO requirement"))
                }
            }
        }
    }
}
