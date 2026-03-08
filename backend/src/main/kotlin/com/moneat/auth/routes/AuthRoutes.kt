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

package com.moneat.auth.routes

import com.moneat.auth.services.AuthService
import com.moneat.auth.services.OAuthService
import com.moneat.auth.services.SignupRequestContext
import com.moneat.config.EnvConfig
import com.moneat.events.models.CompleteOnboardingRequest
import com.moneat.events.models.ForgotPasswordRequest
import com.moneat.events.models.LoginRequest
import com.moneat.events.models.ResendVerificationRequest
import com.moneat.events.models.ResetPasswordRequest
import com.moneat.events.models.SignupRequest
import com.moneat.events.models.VerifyEmailRequest
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.utils.AuthCookieUtils
import com.moneat.utils.BooleanResponse
import com.moneat.utils.DemoLoginResponse
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

fun Route.authRoutes() {
    val authService = AuthService()
    val oauthService = OAuthService()
    val config =
        io.ktor.server.config
            .ApplicationConfig("application.conf")
    val jwtSecret = config.property("jwt.secret").getString()

    route("/auth") {
        post("/signup") {
            val request = call.receive<SignupRequest>()
            val inviteToken = call.request.queryParameters["inviteToken"]

            // Prefer CF-Connecting-IP when behind Cloudflare, fallback to X-Forwarded-For, then origin
            val cfConnectingIp =
                call.request.headers["CF-Connecting-IP"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            val forwardedFor =
                call.request.headers["X-Forwarded-For"]
                    ?.split(",")
                    ?.firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            val remoteHost =
                call.request.origin.remoteHost
                    .takeIf { it.isNotBlank() }
            val userAgent =
                call.request.headers["User-Agent"]
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.take(2048)
            val context =
                SignupRequestContext(
                    ipAddress = cfConnectingIp ?: forwardedFor ?: remoteHost,
                    userAgent = userAgent
                )

            try {
                val result = authService.signup(request, context, inviteToken)
                AuthCookieUtils.setAuthCookie(call, result.token)
                if (result.refreshToken != null) {
                    AuthCookieUtils.setRefreshCookie(call, result.refreshToken)
                } else {
                    AuthCookieUtils.clearRefreshCookie(call)
                }
                call.respond(HttpStatusCode.Created, result.copy(refreshToken = null))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            }
        }

        post("/login") {
            val request = call.receive<LoginRequest>()

            try {
                val result = authService.login(request)
                if (result != null) {
                    AuthCookieUtils.setAuthCookie(call, result.token)
                    if (result.refreshToken != null) {
                        AuthCookieUtils.setRefreshCookie(call, result.refreshToken)
                    } else {
                        AuthCookieUtils.clearRefreshCookie(call)
                    }
                    call.respond(result.copy(refreshToken = null))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(e.message))
            }
        }

        post("/verify-email") {
            val request = call.receive<VerifyEmailRequest>()

            val success = authService.verifyEmail(request.token)
            if (success) {
                call.respond(MessageResponse("Email verified successfully"))
            } else {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid or expired token"))
            }
        }

        post("/resend-verification") {
            val request = call.receive<ResendVerificationRequest>()

            try {
                val success = authService.resendVerificationEmail(request.email)
                if (success) {
                    call.respond(MessageResponse("Verification email sent"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to send email"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            }
        }

        post("/forgot-password") {
            val request = call.receive<ForgotPasswordRequest>()

            // Always return success to prevent email enumeration
            authService.requestPasswordReset(request.email)
            call.respond(MessageResponse("If an account exists with this email, a password reset link has been sent"))
        }

        post("/reset-password") {
            val request = call.receive<ResetPasswordRequest>()

            try {
                val success = authService.resetPassword(request.token, request.newPassword)
                if (success) {
                    call.respond(MessageResponse("Password reset successfully"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid or expired token"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
            }
        }

        post("/logout") {
            // Get userId from auth if available to revoke refresh tokens
            val authHeader = call.request.headers["Authorization"]
            val tokenFromHeader = authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            val tokenFromCookie = call.request.cookies["auth_token"]
            val token = tokenFromHeader ?: tokenFromCookie

            if (token != null) {
                try {
                    val jwtVerifier =
                        com.auth0.jwt.JWT
                            .require(
                                com.auth0.jwt.algorithms.Algorithm
                                    .HMAC256(jwtSecret)
                            ).build()
                    val decodedJWT = jwtVerifier.verify(token)
                    val userId = decodedJWT?.getClaim("userId")?.asInt()
                    if (userId != null) {
                        authService.logout(userId)
                    }
                } catch (e: Exception) {
                    // Token invalid or expired, continue with logout
                }
            }

            AuthCookieUtils.clearAuthCookie(call)
            AuthCookieUtils.clearRefreshCookie(call)
            call.respond(MessageResponse("Logged out"))
        }

        post("/refresh") {
            val refreshToken = call.request.cookies["refresh_token"]

            if (refreshToken == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("No refresh token provided")
                )
                return@post
            }

            try {
                val result = authService.refreshToken(refreshToken)
                if (result != null) {
                    AuthCookieUtils.setAuthCookie(call, result.token)
                    if (result.refreshToken != null) {
                        AuthCookieUtils.setRefreshCookie(call, result.refreshToken)
                    } else {
                        AuthCookieUtils.clearRefreshCookie(call)
                    }
                    call.respond(result.copy(refreshToken = null))
                } else {
                    AuthCookieUtils.clearAuthCookie(call)
                    AuthCookieUtils.clearRefreshCookie(call)
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("Invalid or expired refresh token")
                    )
                }
            } catch (e: IllegalArgumentException) {
                AuthCookieUtils.clearAuthCookie(call)
                AuthCookieUtils.clearRefreshCookie(call)
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(e.message)
                )
            }
        }

        // GitHub OAuth
        get("/github") {
            try {
                if (!oauthService.isGitHubEnabled()) {
                    call.respond(HttpStatusCode.NotImplemented, ErrorResponse("GitHub OAuth is not configured"))
                    return@get
                }

                val state = oauthService.generateState()
                val secureCookie =
                    EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
                        .startsWith("https://")
                call.response.cookies.append(
                    Cookie(
                        name = "oauth_state",
                        value = state,
                        httpOnly = true,
                        secure = secureCookie,
                        path = "/auth",
                        extensions = mapOf("SameSite" to "Lax")
                    )
                )
                val authUrl = oauthService.generateGitHubAuthUrl(state)
                call.respondRedirect(authUrl)
            } catch (e: Exception) {
                logger.error(e) { "GitHub OAuth init failed" }
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            }
        }

        get("/github/callback") {
            try {
                val code = call.parameters["code"]
                val state = call.parameters["state"]

                if (code == null || state == null) {
                    throw IllegalArgumentException("Missing code or state parameter")
                }
                val cookieState = call.request.cookies["oauth_state"]
                if (cookieState.isNullOrBlank() || cookieState != state) {
                    throw IllegalArgumentException("Invalid OAuth state")
                }
                call.response.cookies.append(
                    Cookie(
                        name = "oauth_state",
                        value = "",
                        path = "/auth",
                        maxAge = 0,
                        secure = EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
                            .startsWith("https://")
                    )
                )

                val userData = oauthService.handleGitHubCallback(code)
                val authResponse = oauthService.findOrCreateOAuthUser(userData)

                AuthCookieUtils.setAuthCookie(call, authResponse.token)
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/auth/oauth/callback")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "GitHub OAuth callback failed: ${e.message}" }
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            } catch (e: Exception) {
                logger.error(e) { "GitHub OAuth callback error" }
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            }
        }

        // Apple Sign In
        get("/apple") {
            try {
                if (!oauthService.isAppleEnabled()) {
                    call.respond(HttpStatusCode.NotImplemented, ErrorResponse("Apple Sign In is not configured"))
                    return@get
                }

                val state = oauthService.generateState()
                call.response.cookies.append(
                    Cookie(
                        name = "oauth_state",
                        value = state,
                        httpOnly = true,
                        // SameSite=None cookies must always be Secure, including behind TLS terminators.
                        secure = true,
                        path = "/auth",
                        // Apple uses response_mode=form_post which is a cross-site POST
                        // SameSite=Lax blocks cookies on cross-site POSTs, so we need SameSite=None
                        extensions = mapOf("SameSite" to "None")
                    )
                )
                val authUrl = oauthService.generateAppleAuthUrl(state)
                call.respondRedirect(authUrl)
            } catch (e: Exception) {
                logger.error(e) { "Apple OAuth init failed" }
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            }
        }

        post("/apple/callback") {
            try {
                val params = call.receiveParameters()
                val idToken = params["id_token"] ?: throw IllegalArgumentException("Missing id_token")
                val state = params["state"] ?: throw IllegalArgumentException("Missing state")
                val cookieState = call.request.cookies["oauth_state"]
                if (cookieState.isNullOrBlank() || cookieState != state) {
                    throw IllegalArgumentException("Invalid OAuth state")
                }
                call.response.cookies.append(
                    Cookie(
                        name = "oauth_state",
                        value = "",
                        path = "/auth",
                        maxAge = 0,
                        secure = true,
                        extensions = mapOf("SameSite" to "None")
                    )
                )

                val userData = oauthService.handleAppleCallback(idToken)
                val authResponse = oauthService.findOrCreateOAuthUser(userData)

                AuthCookieUtils.setAuthCookie(call, authResponse.token)
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/auth/oauth/callback")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "Apple OAuth callback failed: ${e.message}" }
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            } catch (e: Exception) {
                logger.error(e) { "Apple OAuth callback error" }
                val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            }
        }

        // Demo login endpoint (no authentication required)
        post("/demo-login") {
            if (!EnvConfig.Demo.enabled) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Demo mode not enabled"))
                return@post
            }

            try {
                val token = authService.generateDemoToken()

                // Set httpOnly auth cookie with extended lifetime for demo
                AuthCookieUtils.setDemoCookie(call, token)

                call.respond(
                    DemoLoginResponse(
                        token = token,
                        demoEpochMs = EnvConfig.Demo.epochMs
                    )
                )
            } catch (e: IllegalStateException) {
                logger.error(e) { "Demo login failed: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Demo mode not properly configured"))
            }
        }

        // Demo refresh endpoint — reissues a demo JWT without a refresh token
        post("/demo-refresh") {
            if (!EnvConfig.Demo.enabled) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Demo mode not enabled"))
                return@post
            }

            try {
                val token = authService.generateDemoToken()
                AuthCookieUtils.setDemoCookie(call, token)

                call.respond(
                    DemoLoginResponse(
                        token = token,
                        demoEpochMs = EnvConfig.Demo.epochMs
                    )
                )
            } catch (e: IllegalStateException) {
                logger.error(e) { "Demo refresh failed: ${e.message}" }
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Demo mode not properly configured"))
            }
        }
    }

    authenticate("auth-jwt") {
        route("/auth") {
            get("/check-slug") {
                val slug = call.request.queryParameters["slug"]
                if (slug.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("slug parameter is required"))
                    return@get
                }

                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()

                val available =
                    transaction {
                        // Get user's org ID to exclude from check
                        val membership =
                            Memberships
                                .selectAll()
                                .where { Memberships.user_id eq userId }
                                .firstOrNull()

                        val userOrgId = membership?.get(Memberships.organization_id)

                        // Check if slug exists in other organizations
                        val existingOrg =
                            if (userOrgId != null) {
                                Organizations
                                    .selectAll()
                                    .where { (Organizations.slug eq slug) and (Organizations.id neq userOrgId) }
                                    .firstOrNull()
                            } else {
                                Organizations
                                    .selectAll()
                                    .where { Organizations.slug eq slug }
                                    .firstOrNull()
                            }

                        existingOrg == null
                    }

                call.respond(BooleanResponse(available))
            }

            post("/complete-onboarding") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val request = call.receive<CompleteOnboardingRequest>()

                try {
                    val user =
                        authService.completeOnboarding(
                            userId,
                            request.organizationName,
                            request.companySize,
                            request.slug,
                            request.referralSource,
                            request.utmSource,
                            request.utmMedium,
                            request.utmCampaign,
                            request.utmContent,
                            request.utmTerm,
                            request.sidebarHiddenItems
                        )
                    call.respond(user)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
                }
            }
        }
    }
}
