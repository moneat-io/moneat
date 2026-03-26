// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.routes

import com.moneat.config.EnvConfig
import com.moneat.enterprise.sso.services.SamlService
import com.moneat.sso.models.SsoInitRequest
import com.moneat.utils.AuthCookieUtils
import com.moneat.utils.ErrorResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private suspend fun handleSamlInit(call: ApplicationCall, samlService: SamlService) {
    try {
        val request = call.receive<SsoInitRequest>()
        val response = samlService.initSaml(request.email, request.orgSlug)
        call.respond(response)
    } catch (e: IllegalArgumentException) {
        val err = e.stackTraceToString().take(500)
        logger.error { "SAML init failed: $err" }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    } catch (e: Exception) {
        val err = e.stackTraceToString().take(500)
        logger.error { "SAML init error: $err" }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("SAML initialization failed"))
    }
}

private suspend fun handleSamlMetadata(call: ApplicationCall, samlService: SamlService) {
    try {
        val orgSlug = call.parameters["org"]
        val metadata = samlService.getSamlMetadata(orgSlug)
        call.respondText(metadata, ContentType.Text.Xml)
    } catch (e: IllegalArgumentException) {
        val err = e.stackTraceToString().take(500)
        logger.error { "SAML metadata request failed: $err" }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    } catch (e: Exception) {
        val err = e.stackTraceToString().take(500)
        logger.error { "SAML metadata error: $err" }
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to generate SAML metadata"))
    }
}

private suspend fun handleSamlAcs(call: ApplicationCall, samlService: SamlService, frontendUrl: String) {
    try {
        val params = call.receiveParameters()
        val samlResponse = params["SAMLResponse"] ?: throw IllegalArgumentException("Missing SAMLResponse")
        val relayState = params["RelayState"]
        val callbackData = samlService.handleSamlResponse(samlResponse, relayState)
        AuthCookieUtils.setAuthCookie(call, callbackData.token)
        call.respondRedirect("$frontendUrl/auth/sso/callback")
    } catch (e: IllegalArgumentException) {
        val err = e.stackTraceToString().take(500)
        logger.error { "SAML ACS failed: $err" }
        call.respondRedirect("$frontendUrl/login?error=sso_failed")
    } catch (e: Exception) {
        val err = e.stackTraceToString().take(500)
        logger.error { "SAML ACS error: $err" }
        call.respondRedirect("$frontendUrl/login?error=sso_failed")
    }
}

fun Route.samlRoutes() {
    val samlService = SamlService()
    val frontendUrl =
        EnvConfig.get("FRONTEND_URL")
            ?: throw IllegalStateException(
                "FRONTEND_URL must be set for SAML routes (SAML startup). " +
                    "Set FRONTEND_URL in the environment or use a documented dev default."
            )

    route("/auth/sso") {
        post("/init/saml") { handleSamlInit(call, samlService) }
        get("/saml/metadata") { handleSamlMetadata(call, samlService) }
        post("/saml/acs") { handleSamlAcs(call, samlService, frontendUrl) }
    }
}
