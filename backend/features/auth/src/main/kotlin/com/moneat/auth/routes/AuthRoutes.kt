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
import org.koin.core.context.GlobalContext
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private const val GITHUB_OAUTH_CALLBACK_ERROR = "GitHub OAuth callback error"
private const val APPLE_OAUTH_CALLBACK_ERROR = "Apple OAuth callback error"
private const val USER_AGENT_MAX_LENGTH = 2048

fun Route.authRoutes(
    authService: AuthService = GlobalContext.get().get(),
    oauthService: OAuthService = GlobalContext.get().get(),
) {
    route("/auth") {
        post("/signup") { handleSignup(authService) }
        post("/login") { handleLogin(authService) }
        post("/verify-email") { handleVerifyEmail(authService) }
        post("/resend-verification") { handleResendVerification(authService) }
        post("/forgot-password") { handleForgotPassword(authService) }
        post("/reset-password") { handleResetPassword(authService) }
        post("/logout") { handleLogout(authService) }
        post("/refresh") { handleRefreshToken(authService) }
        get("/github") { handleGitHubOAuth(oauthService) }
        get("/github/callback") { handleGitHubCallback(oauthService) }
        get("/apple") { handleAppleOAuth(oauthService) }
        post("/apple/callback") { handleAppleCallback(oauthService) }
        post("/demo-login") { handleDemoLogin(authService) }
        post("/demo-refresh") { handleDemoRefresh(authService) }
    }

    authenticate("auth-jwt") {
        route("/auth") {
            get("/check-slug") { handleCheckSlug() }
            post("/complete-onboarding") { handleCompleteOnboarding(authService) }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleSignup(authService: AuthService) {
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
            ?.take(USER_AGENT_MAX_LENGTH)
    val context =
        SignupRequestContext(
            ipAddress = cfConnectingIp ?: forwardedFor ?: remoteHost,
            userAgent = userAgent
        )

    try {
        val result = authService.signup(request, context, inviteToken)
        AuthCookieUtils.setAuthCookie(call, result.token)
        val refreshToken = result.refreshToken
        if (refreshToken != null) {
            AuthCookieUtils.setRefreshCookie(call, refreshToken)
        } else {
            AuthCookieUtils.clearRefreshCookie(call)
        }
        call.respond(HttpStatusCode.Created, result.copy(refreshToken = null))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLogin(authService: AuthService) {
    val request = call.receive<LoginRequest>()

    try {
        val result = authService.login(request)
        if (result != null) {
            AuthCookieUtils.setAuthCookie(call, result.token)
            val refreshToken = result.refreshToken
            if (refreshToken != null) {
                AuthCookieUtils.setRefreshCookie(call, refreshToken)
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

private suspend fun io.ktor.server.routing.RoutingContext.handleVerifyEmail(authService: AuthService) {
    val request = call.receive<VerifyEmailRequest>()

    val success = authService.verifyEmail(request.token)
    if (success) {
        call.respond(MessageResponse("Email verified successfully"))
    } else {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid or expired token"))
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleResendVerification(authService: AuthService) {
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

private suspend fun io.ktor.server.routing.RoutingContext.handleForgotPassword(authService: AuthService) {
    val request = call.receive<ForgotPasswordRequest>()

    // Always return success to prevent email enumeration
    authService.requestPasswordReset(request.email)
    call.respond(MessageResponse("If an account exists with this email, a password reset link has been sent"))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleResetPassword(authService: AuthService) {
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

private suspend fun io.ktor.server.routing.RoutingContext.handleLogout(authService: AuthService) {
    val jwtSecret =
        io.ktor.server.config
            .ApplicationConfig("application.conf")
            .property("jwt.secret")
            .getString()

    // Get userId from auth if available to revoke refresh tokens
    val authHeader = call.request.headers["Authorization"]
    val tokenFromHeader = authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
    val tokenFromCookie = call.request.cookies["auth_token"]
    val token = tokenFromHeader ?: tokenFromCookie

    if (token != null) {
        suspendRunCatching {
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
        }.getOrElse { _ ->
            // Token invalid or expired, continue with logout
        }
    }

    AuthCookieUtils.clearAuthCookie(call)
    AuthCookieUtils.clearRefreshCookie(call)
    call.respond(MessageResponse("Logged out"))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleRefreshToken(authService: AuthService) {
    val refreshToken = call.request.cookies["refresh_token"]

    if (refreshToken == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("No refresh token provided")
        )
        return
    }

    try {
        val result = authService.refreshToken(refreshToken)
        if (result != null) {
            AuthCookieUtils.setAuthCookie(call, result.token)
            val nextRefreshToken = result.refreshToken
            if (nextRefreshToken != null) {
                AuthCookieUtils.setRefreshCookie(call, nextRefreshToken)
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
private suspend fun io.ktor.server.routing.RoutingContext.handleGitHubOAuth(oauthService: OAuthService) {
    suspendRunCatching {
        if (!oauthService.isGitHubEnabled()) {
            call.respond(HttpStatusCode.NotImplemented, ErrorResponse("GitHub OAuth is not configured"))
            return
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
    }.getOrElse { e ->
        logger.error(e) { "GitHub OAuth init failed" }
        val dashboardUrl = EnvConfig.get("FRONTEND_URL")!!
        call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleGitHubCallback(oauthService: OAuthService) {
    suspendRunCatching {
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
        val dashboardUrl = EnvConfig.get("FRONTEND_URL")!!
        call.respondRedirect("$dashboardUrl/auth/oauth/callback")
    }.onFailure { e ->
        logger.error(e) { GITHUB_OAUTH_CALLBACK_ERROR }
        val dashboardUrl = EnvConfig.get("FRONTEND_URL")!!
        call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
    }
}

// Apple Sign In
private suspend fun io.ktor.server.routing.RoutingContext.handleAppleOAuth(oauthService: OAuthService) {
    suspendRunCatching {
        if (!oauthService.isAppleEnabled()) {
            call.respond(HttpStatusCode.NotImplemented, ErrorResponse("Apple Sign In is not configured"))
            return
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
    }.getOrElse { e ->
        logger.error(e) { "Apple OAuth init failed" }
        val dashboardUrl = EnvConfig.get("FRONTEND_URL")!!
        call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleAppleCallback(oauthService: OAuthService) {
    suspendRunCatching {
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
        val dashboardUrl = EnvConfig.get("FRONTEND_URL")!!
        call.respondRedirect("$dashboardUrl/auth/oauth/callback")
    }.onFailure { e ->
        logger.error(e) { APPLE_OAUTH_CALLBACK_ERROR }
        val dashboardUrl = EnvConfig.get("FRONTEND_URL")!!
        call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
    }
}

// Demo login endpoint (no authentication required)
private suspend fun io.ktor.server.routing.RoutingContext.handleDemoLogin(authService: AuthService) {
    if (!EnvConfig.Demo.enabled) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Demo mode not enabled"))
        return
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
private suspend fun io.ktor.server.routing.RoutingContext.handleDemoRefresh(authService: AuthService) {
    if (!EnvConfig.Demo.enabled) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Demo mode not enabled"))
        return
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

private suspend fun io.ktor.server.routing.RoutingContext.handleCheckSlug() {
    val slug = call.request.queryParameters["slug"]
    if (slug.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("slug parameter is required"))
        return
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

private suspend fun io.ktor.server.routing.RoutingContext.handleCompleteOnboarding(authService: AuthService) {
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
