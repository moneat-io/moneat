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

package com.moneat.sso.routes

import com.moneat.config.EnvConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.shared.models.Memberships
import com.moneat.sso.models.SsoConfigRequest
import com.moneat.sso.models.SsoInitRequest
import com.moneat.sso.models.SsoProviderType
import com.moneat.sso.services.SsoService
import com.moneat.utils.AuthCookieUtils
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
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

private const val SSO_CONFIG_PATH = "/config"
private const val ERROR_INVALID_TOKEN = "Invalid token"
private const val ERROR_MISSING_ORG_ID = "Missing organizationId"

private data class SsoAuthContext(val userId: Int, val orgId: Int)

private suspend fun ApplicationCall.requireSsoAuth(): SsoAuthContext? {
    val userId = principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
        ?: run {
            respond(HttpStatusCode.Unauthorized, ErrorResponse(ERROR_INVALID_TOKEN))
            return null
        }
    val orgId = parameters["organizationId"]?.toIntOrNull()
        ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponse(ERROR_MISSING_ORG_ID))
            return null
        }
    val isMember = transaction {
        Memberships.selectAll()
            .where {
                (Memberships.organization_id eq orgId) and (Memberships.user_id eq userId)
            }.firstOrNull() != null
    }
    if (!isMember) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied"))
        return null
    }
    return SsoAuthContext(userId, orgId)
}

fun Route.ssoRoutes() {
    val ssoService = SsoService()
    val frontendUrl = EnvConfig.get("FRONTEND_URL")!!

    route("/auth/sso") {
        // Public OIDC SSO flow endpoints
        post("/init") {
            try {
                val request = call.receive<SsoInitRequest>()
                val response = ssoService.initSso(
                    request.email,
                    request.orgSlug,
                )
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "SSO init failed: ${e.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(e.message),
                )
            } catch (e: Exception) {
                logger.error(e) { "SSO init error" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("SSO initialization failed")
                )
            }
        }

        get("/oidc/callback") {
            try {
                val code = call.parameters["code"]
                    ?: throw IllegalArgumentException(
                        "Missing authorization code"
                    )
                val state = call.parameters["state"]
                    ?: throw IllegalArgumentException(
                        "Missing state parameter"
                    )

                val callbackData =
                    ssoService.handleOidcCallback(code, state)

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
            get(SSO_CONFIG_PATH) {
                val ctx = call.requireSsoAuth() ?: return@get
                try {
                    val config = ssoService.getSsoConfig(ctx.orgId)
                    when (config) {
                        null -> call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("SSO not configured")
                        )
                        else -> call.respond(config)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Get SSO config error" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Failed to retrieve SSO configuration")
                    )
                }
            }

            put(SSO_CONFIG_PATH) {
                val ctx = call.requireSsoAuth() ?: return@put
                val request = call.receive<SsoConfigRequest>()
                val providerType =
                    try {
                        SsoProviderType.fromString(request.providerType)
                    } catch (e: IllegalArgumentException) {
                        throw BadRequestException(e.message ?: "Invalid SSO provider type")
                    }
                if (providerType == SsoProviderType.SAML &&
                    !FeatureRegistry.hasModule("SAML")
                ) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("SAML SSO requires an enterprise license")
                    )
                    return@put
                }
                val config = ssoService.configureSso(ctx.orgId, ctx.userId, request)
                call.respond(config)
            }

            delete(SSO_CONFIG_PATH) {
                val ctx = call.requireSsoAuth() ?: return@delete
                val deleted = ssoService.deleteSsoConfig(ctx.orgId, ctx.userId)
                when (deleted) {
                    true -> call.respond(
                        HttpStatusCode.OK,
                        MessageResponse("SSO configuration deleted")
                    )
                    false -> call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("SSO configuration not found")
                    )
                }
            }

            post("/check-required") {
                try {
                    val request = call.receive<Map<String, String>>()
                    val email =
                        request["email"]
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Missing email")
                            )

                    val required = ssoService.checkSsoRequired(email)
                    call.respond(mapOf("required" to required))
                } catch (e: Exception) {
                    logger.error(e) { "Check SSO required error" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            "Failed to check SSO requirement"
                        )
                    )
                }
            }
        }
    }
}
