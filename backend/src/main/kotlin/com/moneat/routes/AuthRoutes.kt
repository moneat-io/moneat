package com.moneat.routes

import com.moneat.config.EnvConfig
import com.moneat.models.*
import com.moneat.services.AuthService
import com.moneat.services.OAuthService
import com.moneat.services.SignupRequestContext
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

fun Route.authRoutes() {
    val authService = AuthService()
    val oauthService = OAuthService()
    
    route("/auth") {
        post("/signup") {
            val request = call.receive<SignupRequest>()
            val inviteToken = call.request.queryParameters["inviteToken"]
            
            val forwardedFor = call.request.headers["X-Forwarded-For"]
                ?.split(",")
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val remoteHost = call.request.origin.remoteHost.takeIf { it.isNotBlank() }
            val userAgent = call.request.headers["User-Agent"]?.trim()?.takeIf { it.isNotBlank() }?.take(2048)
            val context = SignupRequestContext(
                ipAddress = forwardedFor ?: remoteHost,
                userAgent = userAgent
            )
            
            try {
                val result = authService.signup(request, context, inviteToken)
                call.respond(HttpStatusCode.Created, result)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }
        
        post("/login") {
            val request = call.receive<LoginRequest>()
            
            try {
                val result = authService.login(request)
                if (result != null) {
                    call.respond(result)
                } else {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
            }
        }
        
        post("/verify-email") {
            val request = call.receive<VerifyEmailRequest>()
            
            val success = authService.verifyEmail(request.token)
            if (success) {
                call.respond(mapOf("message" to "Email verified successfully"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired token"))
            }
        }
        
        post("/resend-verification") {
            val request = call.receive<ResendVerificationRequest>()
            
            try {
                val success = authService.resendVerificationEmail(request.email)
                if (success) {
                    call.respond(mapOf("message" to "Verification email sent"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to send email"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }
        
        post("/forgot-password") {
            val request = call.receive<ForgotPasswordRequest>()
            
            // Always return success to prevent email enumeration
            authService.requestPasswordReset(request.email)
            call.respond(mapOf("message" to "If an account exists with this email, a password reset link has been sent"))
        }
        
        post("/reset-password") {
            val request = call.receive<ResetPasswordRequest>()
            
            try {
                val success = authService.resetPassword(request.token, request.newPassword)
                if (success) {
                    call.respond(mapOf("message" to "Password reset successfully"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or expired token"))
                }
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }
        
        // GitHub OAuth
        get("/github") {
            try {
                if (!oauthService.isGitHubEnabled()) {
                    call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "GitHub OAuth is not configured"))
                    return@get
                }
                
                val state = oauthService.generateState()
                val secureCookie = call.request.origin.scheme == "https"
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
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
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
                        secure = call.request.origin.scheme == "https"
                    )
                )
                
                val userData = oauthService.handleGitHubCallback(code)
                val authResponse = oauthService.findOrCreateOAuthUser(userData)
                
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/auth/oauth/callback?token=${authResponse.token}")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "GitHub OAuth callback failed: ${e.message}" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed&message=${e.message}")
            } catch (e: Exception) {
                logger.error(e) { "GitHub OAuth callback error" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            }
        }
        
        // Apple Sign In
        get("/apple") {
            try {
                if (!oauthService.isAppleEnabled()) {
                    call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Apple Sign In is not configured"))
                    return@get
                }
                
                val state = oauthService.generateState()
                val secureCookie = call.request.origin.scheme == "https"
                call.response.cookies.append(
                    Cookie(
                        name = "oauth_state",
                        value = state,
                        httpOnly = true,
                        secure = secureCookie,
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
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
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
                        secure = call.request.origin.scheme == "https"
                    )
                )
                
                val userData = oauthService.handleAppleCallback(idToken)
                val authResponse = oauthService.findOrCreateOAuthUser(userData)
                
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/auth/oauth/callback?token=${authResponse.token}")
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "Apple OAuth callback failed: ${e.message}" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed&message=${e.message}")
            } catch (e: Exception) {
                logger.error(e) { "Apple OAuth callback error" }
                val dashboardUrl = EnvConfig.get("DASHBOARD_URL", "https://moneat.io")
                call.respondRedirect("$dashboardUrl/login?error=oauth_failed")
            }
        }
    }
    
    authenticate("auth-jwt") {
        route("/auth") {
            get("/check-slug") {
                val slug = call.request.queryParameters["slug"]
                if (slug.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "slug parameter is required"))
                    return@get
                }
                
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                
                val available = transaction {
                    // Get user's org ID to exclude from check
                    val membership = Memberships.selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                    
                    val userOrgId = membership?.get(Memberships.organization_id)
                    
                    // Check if slug exists in other organizations
                    val existingOrg = if (userOrgId != null) {
                        Organizations.selectAll()
                            .where { (Organizations.slug eq slug) and (Organizations.id neq userOrgId) }
                            .firstOrNull()
                    } else {
                        Organizations.selectAll()
                            .where { Organizations.slug eq slug }
                            .firstOrNull()
                    }
                    
                    existingOrg == null
                }
                
                call.respond(mapOf("available" to available))
            }
            
            post("/complete-onboarding") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal!!.payload.getClaim("userId").asInt()
                val request = call.receive<CompleteOnboardingRequest>()
                
                try {
                    val user = authService.completeOnboarding(userId, request.organizationName, request.companySize, request.slug, request.referralSource)
                    call.respond(user)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
