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

package com.moneat.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.EnvConfig
import com.moneat.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.*

private val logger = KotlinLogging.logger {}

@Serializable
data class GitHubAccessTokenResponse(
    val access_token: String,
    val token_type: String,
    val scope: String
)

@Serializable
data class GitHubUser(
    val id: Long,
    val login: String,
    val email: String?,
    val name: String?
)

@Serializable
data class GitHubEmail(
    val email: String,
    val primary: Boolean,
    val verified: Boolean,
    val visibility: String?
)

@Serializable
data class ApplePublicKey(
    val kty: String,
    val kid: String,
    val use: String,
    val alg: String,
    val n: String,
    val e: String
)

@Serializable
data class ApplePublicKeys(
    val keys: List<ApplePublicKey>
)

data class OAuthUserData(
    val provider: String,
    val providerId: String,
    val email: String,
    val name: String?,
    val emailVerified: Boolean
)

class OAuthService {
    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    private val backendUrl = EnvConfig.get("BACKEND_URL") ?: "https://api.moneat.io"
    private val dashboardUrl = EnvConfig.get("FRONTEND_URL", "https://moneat.io")

    private val githubClientId = EnvConfig.get("GITHUB_OAUTH_CLIENT_ID")
    private val githubClientSecret = EnvConfig.get("GITHUB_OAUTH_CLIENT_SECRET")
    private val githubOauthBaseUrl = EnvConfig.get("GITHUB_OAUTH_BASE_URL", "https://github.com").trimEnd('/')
    private val githubApiBaseUrl = EnvConfig.get("GITHUB_API_BASE_URL", "https://api.github.com").trimEnd('/')

    private val appleClientId = EnvConfig.get("APPLE_CLIENT_ID")
    private val appleTeamId = EnvConfig.get("APPLE_TEAM_ID")
    private val appleKeyId = EnvConfig.get("APPLE_KEY_ID")
    private val applePrivateKey = EnvConfig.get("APPLE_PRIVATE_KEY")
    private val appleKeysUrl = EnvConfig.get("APPLE_KEYS_URL", "https://appleid.apple.com/auth/keys")

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
    }

    fun isGitHubEnabled(): Boolean = githubClientId != null && githubClientSecret != null
    fun isAppleEnabled(): Boolean = appleClientId != null && appleTeamId != null && appleKeyId != null && applePrivateKey != null

    fun generateGitHubAuthUrl(state: String): String {
        if (!isGitHubEnabled()) {
            throw IllegalStateException("GitHub OAuth is not configured")
        }

        val redirectUri = "$backendUrl/auth/github/callback"
        return "$githubOauthBaseUrl/login/oauth/authorize?" +
            "client_id=$githubClientId&" +
            "redirect_uri=${redirectUri.encodeURLParameter()}&" +
            "scope=user:email&" +
            "state=${state.encodeURLParameter()}"
    }

    suspend fun handleGitHubCallback(code: String): OAuthUserData {
        if (!isGitHubEnabled()) {
            throw IllegalStateException("GitHub OAuth is not configured")
        }

        // Exchange code for access token
        val tokenResponse: HttpResponse = httpClient.post("$githubOauthBaseUrl/login/oauth/access_token") {
            headers {
                append(HttpHeaders.Accept, "application/json")
            }
            parameter("client_id", githubClientId)
            parameter("client_secret", githubClientSecret)
            parameter("code", code)
        }

        if (tokenResponse.status.value !in 200..299) {
            logger.error { "GitHub token exchange failed: ${tokenResponse.status}" }
            throw IllegalArgumentException("Failed to exchange code for token")
        }

        val tokenData: GitHubAccessTokenResponse = tokenResponse.body()
        val accessToken = tokenData.access_token

        // Fetch user info
        val userResponse: HttpResponse = httpClient.get("$githubApiBaseUrl/user") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $accessToken")
                append(HttpHeaders.Accept, "application/json")
            }
        }

        if (userResponse.status.value !in 200..299) {
            logger.error { "GitHub user fetch failed: ${userResponse.status}" }
            throw IllegalArgumentException("Failed to fetch user info")
        }

        val user: GitHubUser = userResponse.body()

        // Fetch user emails if email is not in user object
        var email = user.email
        var emailVerified = false

        if (email.isNullOrBlank()) {
            val emailsResponse: HttpResponse = httpClient.get("$githubApiBaseUrl/user/emails") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                    append(HttpHeaders.Accept, "application/json")
                }
            }

            if (emailsResponse.status.value in 200..299) {
                val emails: List<GitHubEmail> = emailsResponse.body()
                val primaryEmail = emails.firstOrNull { it.primary && it.verified }
                    ?: emails.firstOrNull { it.verified }

                if (primaryEmail != null) {
                    email = primaryEmail.email
                    emailVerified = primaryEmail.verified
                }
            }
        } else {
            emailVerified = true // GitHub emails in user object are verified
        }

        if (email.isNullOrBlank()) {
            throw IllegalArgumentException("No verified email found in GitHub account")
        }

        return OAuthUserData(
            provider = "github",
            providerId = user.id.toString(),
            email = email.lowercase().trim(),
            name = user.name ?: user.login,
            emailVerified = emailVerified
        )
    }

    fun generateAppleAuthUrl(state: String): String {
        if (!isAppleEnabled()) {
            throw IllegalStateException("Apple Sign In is not configured")
        }

        val redirectUri = "$backendUrl/auth/apple/callback"
        return "https://appleid.apple.com/auth/authorize?" +
            "client_id=$appleClientId&" +
            "redirect_uri=${redirectUri.encodeURLParameter()}&" +
            "response_type=code id_token&" +
            "scope=name email&" +
            "response_mode=form_post&" +
            "state=${state.encodeURLParameter()}"
    }

    suspend fun handleAppleCallback(idToken: String): OAuthUserData {
        if (!isAppleEnabled()) {
            throw IllegalStateException("Apple Sign In is not configured")
        }

        val decodedToken = verifyAppleIdToken(idToken)

        // Verify issuer
        if (decodedToken.issuer != "https://appleid.apple.com") {
            throw IllegalArgumentException("Invalid Apple ID token issuer")
        }

        // Verify audience
        if (decodedToken.audience.firstOrNull() != appleClientId) {
            throw IllegalArgumentException("Invalid Apple ID token audience")
        }

        // Verify expiration
        if (decodedToken.expiresAt?.before(Date()) != false) {
            throw IllegalArgumentException("Apple ID token has expired")
        }

        // Extract user info from token claims
        val subject = decodedToken.subject
        val email = decodedToken.getClaim("email").asString()
        val emailVerified = decodedToken.getClaim("email_verified").asBoolean() ?: false

        if (email.isNullOrBlank()) {
            throw IllegalArgumentException("No email found in Apple ID token")
        }

        // Apple doesn't always provide name in token, it's provided separately on first auth
        // For now, use email as fallback for name
        val name = email.substringBefore("@")

        return OAuthUserData(
            provider = "apple",
            providerId = subject,
            email = email.lowercase().trim(),
            name = name,
            emailVerified = emailVerified
        )
    }

    private suspend fun verifyAppleIdToken(idToken: String): com.auth0.jwt.interfaces.DecodedJWT {
        val decoded = JWT.decode(idToken)
        val keyId = decoded.keyId ?: throw IllegalArgumentException("Missing key ID in Apple ID token")
        val keysResponse: ApplePublicKeys = httpClient.get(appleKeysUrl).body()
        val key = keysResponse.keys.firstOrNull { it.kid == keyId && it.kty == "RSA" }
            ?: throw IllegalArgumentException("Unable to find Apple signing key")

        val publicKey = buildAppleRsaPublicKey(key)
        val verifier = JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer("https://appleid.apple.com")
            .withAudience(appleClientId)
            .build()

        return verifier.verify(idToken)
    }

    private fun buildAppleRsaPublicKey(key: ApplePublicKey): RSAPublicKey {
        val modulus = BigInteger(1, java.util.Base64.getUrlDecoder().decode(key.n))
        val exponent = BigInteger(1, java.util.Base64.getUrlDecoder().decode(key.e))
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        return KeyFactory.getInstance("RSA").generatePublic(keySpec) as RSAPublicKey
    }

    fun findOrCreateOAuthUser(userData: OAuthUserData): AuthResponse {
        return transaction {
            // Check if user exists with this OAuth provider
            val existingOAuthUser = Users.selectAll()
                .where {
                    (Users.oauth_provider eq userData.provider) and
                        (Users.oauth_provider_id eq userData.providerId)
                }
                .firstOrNull()

            if (existingOAuthUser != null) {
                // Existing OAuth user - log them in
                val userId = existingOAuthUser[Users.id]
                val membership = Memberships.selectAll()
                    .where { Memberships.user_id eq userId }
                    .firstOrNull()
                    ?: throw IllegalStateException("User has no organization membership")

                val orgId = membership[Memberships.organization_id]
                val orgRole = membership[Memberships.role]

                val token = generateToken(userId, existingOAuthUser[Users.email], orgId, orgRole)

                return@transaction AuthResponse(
                    token = token,
                    user = UserResponse(
                        userId,
                        existingOAuthUser[Users.email],
                        existingOAuthUser[Users.name],
                        existingOAuthUser[Users.email_verified],
                        existingOAuthUser[Users.onboarding_completed],
                        existingOAuthUser[Users.is_admin]
                    )
                )
            }

            // Check if user exists with matching email
            val existingEmailUser = Users.selectAll()
                .where { Users.email eq userData.email }
                .firstOrNull()

            if (existingEmailUser != null) {
                // User exists with this email
                val existingProvider = existingEmailUser[Users.oauth_provider]

                if (existingProvider == null) {
                    // Email/password user - link OAuth to this account
                    Users.update({ Users.id eq existingEmailUser[Users.id] }) {
                        it[oauth_provider] = userData.provider
                        it[oauth_provider_id] = userData.providerId
                        if (userData.emailVerified) {
                            it[email_verified] = true
                        }
                    }

                    val userId = existingEmailUser[Users.id]
                    val membership = Memberships.selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?: throw IllegalStateException("User has no organization membership")

                    val orgId = membership[Memberships.organization_id]
                    val orgRole = membership[Memberships.role]

                    val token = generateToken(userId, existingEmailUser[Users.email], orgId, orgRole)

                    return@transaction AuthResponse(
                        token = token,
                        user = UserResponse(
                            userId,
                            existingEmailUser[Users.email],
                            existingEmailUser[Users.name],
                            existingEmailUser[Users.email_verified],
                            existingEmailUser[Users.onboarding_completed],
                            existingEmailUser[Users.is_admin]
                        )
                    )
                } else if (existingProvider != userData.provider) {
                    // Different OAuth provider
                    throw IllegalArgumentException(
                        "This email is already registered with $existingProvider. Please sign in with $existingProvider."
                    )
                } else {
                    // Same provider but different ID? Shouldn't happen, but log them in
                    val userId = existingEmailUser[Users.id]
                    val membership = Memberships.selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?: throw IllegalStateException("User has no organization membership")

                    val orgId = membership[Memberships.organization_id]
                    val orgRole = membership[Memberships.role]

                    val token = generateToken(userId, existingEmailUser[Users.email], orgId, orgRole)

                    return@transaction AuthResponse(
                        token = token,
                        user = UserResponse(
                            userId,
                            existingEmailUser[Users.email],
                            existingEmailUser[Users.name],
                            existingEmailUser[Users.email_verified],
                            existingEmailUser[Users.onboarding_completed],
                            existingEmailUser[Users.is_admin]
                        )
                    )
                }
            }

            // New user - create account
            val userId = Users.insert {
                it[email] = userData.email
                it[password_hash] = "" // No password for OAuth users
                it[name] = userData.name
                it[email_verified] = userData.emailVerified
                it[onboarding_completed] = false // Require onboarding for OAuth users
                it[oauth_provider] = userData.provider
                it[oauth_provider_id] = userData.providerId
            }[Users.id]

            // Create default organization
            val orgId = Organizations.insert {
                it[name] = "${userData.name ?: userData.email}'s Organization"
                it[slug] = "org-${UUID.randomUUID().toString().take(8)}"
            }[Organizations.id]

            // Add membership
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val token = generateToken(userId, userData.email, orgId, "owner")

            AuthResponse(
                token = token,
                user = UserResponse(
                    userId,
                    userData.email,
                    userData.name,
                    userData.emailVerified,
                    true,
                    false
                )
            )
        }
    }

    private fun generateToken(userId: Int, email: String, orgId: Int, orgRole: String): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("orgId", orgId)
            .withClaim("orgRole", orgRole)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    fun generateState(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
