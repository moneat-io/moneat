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
import com.moneat.sso.SsoForbiddenException
import com.moneat.sso.models.SsoConfigRequest
import com.moneat.sso.models.SsoInitRequest
import com.moneat.sso.models.SsoProviderType
import com.moneat.sso.services.SsoService
import com.moneat.utils.AuthCookieUtils
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import com.moneat.utils.suspendRunCatching
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
private const val STACKTRACE_MAX_LENGTH = 500

private data class SsoAuthContext(val userId: Int, val orgId: Int)

private fun Throwable.limitedStackTrace(): String =
    stackTraceToString().take(STACKTRACE_MAX_LENGTH)

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
            call.handleSsoInit(ssoService)
        }

        get("/oidc/callback") {
            call.handleOidcCallback(ssoService, frontendUrl)
        }
    }

    // Protected SSO configuration endpoints
    route("/v1/sso") {
        authenticate("auth-jwt") {
            get(SSO_CONFIG_PATH) {
                val ctx = call.requireSsoAuth() ?: return@get
                call.handleGetSsoConfig(ctx, ssoService)
            }

            put(SSO_CONFIG_PATH) {
                val ctx = call.requireSsoAuth() ?: return@put
                call.handlePutSsoConfig(ctx, ssoService)
            }

            delete(SSO_CONFIG_PATH) {
                val ctx = call.requireSsoAuth() ?: return@delete
                call.handleDeleteSsoConfig(ctx, ssoService)
            }

            post("/config/domain/verify") {
                val ctx = call.requireSsoAuth() ?: return@post
                call.handleVerifySsoDomain(ctx, ssoService)
            }

            post("/check-required") {
                call.handleCheckSsoRequired(ssoService)
            }
        }
    }
}

private suspend fun ApplicationCall.handleSsoInit(ssoService: SsoService) {
    suspendRunCatching {
        val request = receive<SsoInitRequest>()
        val response = ssoService.initSso(
            request.email,
            request.orgSlug,
        )
        respond(response)
    }.onFailure { e ->
        when (e) {
            is IllegalArgumentException -> {
                logger.error { "SSO init failed: ${e.limitedStackTrace()}" }
                respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            }
            else -> {
                logger.error { "SSO init error: ${e.limitedStackTrace()}" }
                respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("SSO initialization failed")
                )
            }
        }
    }
}

private suspend fun ApplicationCall.handleOidcCallback(
    ssoService: SsoService,
    frontendUrl: String,
) {
    suspendRunCatching {
        val code = parameters["code"]
            ?: throw IllegalArgumentException("Missing authorization code")
        val state = parameters["state"]
            ?: throw IllegalArgumentException("Missing state parameter")
        val callbackData = ssoService.handleOidcCallback(code, state)

        AuthCookieUtils.setAuthCookie(this, callbackData.token)
        respondRedirect("$frontendUrl/auth/sso/callback")
    }.onFailure { e ->
        logger.error { "OIDC callback error: ${e.limitedStackTrace()}" }
        respondRedirect("$frontendUrl/login?error=sso_failed")
    }
}

private suspend fun ApplicationCall.handleGetSsoConfig(
    ctx: SsoAuthContext,
    ssoService: SsoService,
) {
    suspendRunCatching {
        val config = ssoService.getSsoConfig(ctx.orgId)
        when (config) {
            null -> respond(HttpStatusCode.NotFound, ErrorResponse("SSO not configured"))
            else -> respond(config)
        }
    }.onFailure { e ->
        logger.error { "Get SSO config error: ${e.limitedStackTrace()}" }
        respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse("Failed to retrieve SSO configuration")
        )
    }
}

private suspend fun ApplicationCall.handlePutSsoConfig(
    ctx: SsoAuthContext,
    ssoService: SsoService,
) {
    suspendRunCatching {
        val request = receive<SsoConfigRequest>()
        val providerType = parseSsoProviderType(request.providerType)
        if (providerType == SsoProviderType.SAML && !FeatureRegistry.hasModule("SAML")) {
            respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("SAML SSO requires an enterprise license")
            )
            return@suspendRunCatching
        }
        val config = ssoService.configureSso(ctx.orgId, ctx.userId, request)
        respond(config)
    }.onFailure { e ->
        respondSsoConfigFailure(e)
    }
}

private fun parseSsoProviderType(providerType: String): SsoProviderType =
    try {
        SsoProviderType.fromString(providerType)
    } catch (e: IllegalArgumentException) {
        throw BadRequestException(e.message ?: "Invalid SSO provider type")
    }

private suspend fun ApplicationCall.respondSsoConfigFailure(e: Throwable) {
    when (e) {
        is BadRequestException -> {
            logger.error { "SSO config request failed: ${e.limitedStackTrace()}" }
            respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
        }
        is SsoForbiddenException -> {
            logger.error { "SSO config forbidden: ${e.limitedStackTrace()}" }
            respond(HttpStatusCode.Forbidden, ErrorResponse(e.message))
        }
        else -> {
            logger.error { "Configure SSO error: ${e.limitedStackTrace()}" }
            respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to configure SSO"))
        }
    }
}

private suspend fun ApplicationCall.handleVerifySsoDomain(
    ctx: SsoAuthContext,
    ssoService: SsoService,
) {
    suspendRunCatching {
        respond(ssoService.verifyEmailDomain(ctx.orgId, ctx.userId))
    }.onFailure { e ->
        respondSsoConfigFailure(e)
    }
}

private suspend fun ApplicationCall.handleDeleteSsoConfig(
    ctx: SsoAuthContext,
    ssoService: SsoService,
) {
    suspendRunCatching {
        val deleted = ssoService.deleteSsoConfig(ctx.orgId, ctx.userId)
        when (deleted) {
            true -> respond(HttpStatusCode.OK, MessageResponse("SSO configuration deleted"))
            false -> respond(
                HttpStatusCode.NotFound,
                ErrorResponse("SSO configuration not found")
            )
        }
    }.onFailure { e ->
        when (e) {
            is SsoForbiddenException -> respond(HttpStatusCode.Forbidden, ErrorResponse(e.message))
            else -> {
                logger.error { "Delete SSO config error: ${e.limitedStackTrace()}" }
                respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to delete SSO configuration")
                )
            }
        }
    }
}

private suspend fun ApplicationCall.handleCheckSsoRequired(ssoService: SsoService) {
    suspendRunCatching {
        val request = receive<Map<String, String>>()
        val email =
            request["email"]
                ?: return@suspendRunCatching respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing email")
                )

        val required = ssoService.checkSsoRequired(email)
        respond(mapOf("required" to required))
    }.onFailure { e ->
        logger.error { "Check SSO required error: ${e.limitedStackTrace()}" }
        respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse("Failed to check SSO requirement")
        )
    }
}
