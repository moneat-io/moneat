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

package com.moneat.auth.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.config.EnvConfig
import com.moneat.events.models.AuthResponse
import com.moneat.events.models.LoginRequest
import com.moneat.events.models.SignupRequest
import com.moneat.events.models.UserResponse
import com.moneat.notifications.services.EmailService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.UserLegalAcceptances
import com.moneat.shared.models.Users
import com.moneat.shared.services.SidebarPreferenceService
import com.moneat.utils.SentryUtils
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.trim
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.*
import kotlin.time.Clock

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class SignupRequestContext(
    val ipAddress: String? = null,
    val userAgent: String? = null
)

class AuthService {
    companion object {
        private const val VERIFICATION_TTL_MS = 24 * 60 * 60 * 1000L
    }

    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    private val legalTermsVersion = config.property("legal.termsVersion").getString()
    private val legalPrivacyVersion = config.property("legal.privacyVersion").getString()
    private val emailService = EmailService()
    private val secureRandom = SecureRandom()
    private val refreshTokenService = RefreshTokenService()

    fun signup(
        request: SignupRequest,
        context: SignupRequestContext = SignupRequestContext(),
        inviteToken: String? = null
    ): AuthResponse {
        if (request.email.isBlank()) {
            throw IllegalArgumentException("Email is required")
        }
        if (request.password.length < 8) {
            throw IllegalArgumentException("Password must be at least 8 characters")
        }
        validateSignupLegalConsent(request)

        val normalizedEmail = request.email.lowercase().trim()

        SentryUtils.breadcrumb(
            "auth",
            "User signup started",
            mapOf(
                "email" to normalizedEmail,
                "terms_version" to request.termsVersion,
                "privacy_version" to request.privacyVersion,
                "has_invite" to (inviteToken != null)
            )
        )

        val (userId, emailVerified, orgId, orgRole) =
            transaction(transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE) {
                // Check if user exists
                val existing = Users.selectAll().where { Users.email eq normalizedEmail }.firstOrNull()
                if (existing != null) {
                    SentryUtils.breadcrumb("auth", "Signup failed - user exists", mapOf("email" to request.email))
                    throw IllegalArgumentException("User already exists")
                }

                val now = System.currentTimeMillis()

                // Resolve invite in a strict state-aware way.
                val pendingInvite =
                    if (inviteToken != null) {
                        val inviteByToken =
                            OrgInvitations
                                .selectAll()
                                .where { OrgInvitations.token eq inviteToken }
                                .singleOrNull()
                                ?: throw IllegalArgumentException("Invitation not found")

                        val inviteStatus = inviteByToken[OrgInvitations.status]
                        if (inviteStatus != "pending") {
                            throw IllegalArgumentException("Invitation is no longer valid")
                        }

                        val inviteExpiresAt = inviteByToken[OrgInvitations.expires_at]
                        if (now > inviteExpiresAt) {
                            OrgInvitations.update({ OrgInvitations.id eq inviteByToken[OrgInvitations.id] }) {
                                it[OrgInvitations.status] = "expired"
                            }
                            throw IllegalArgumentException("Invitation has expired")
                        }

                        if (inviteByToken[OrgInvitations.email].lowercase() != normalizedEmail) {
                            throw IllegalArgumentException("This invitation was sent to a different email address")
                        }

                        inviteByToken
                    } else {
                        OrgInvitations.update({
                            (OrgInvitations.email eq normalizedEmail) and
                                (OrgInvitations.status eq "pending") and
                                (OrgInvitations.expires_at lessEq now)
                        }) {
                            it[OrgInvitations.status] = "expired"
                        }

                        OrgInvitations
                            .selectAll()
                            .where {
                                (OrgInvitations.email eq normalizedEmail) and
                                    (OrgInvitations.status eq "pending") and
                                    (OrgInvitations.expires_at greater now)
                            }.orderBy(OrgInvitations.created_at, org.jetbrains.exposed.v1.core.SortOrder.DESC)
                            .limit(1)
                            .singleOrNull()
                    }

                // Detect first real user (excludes demo user which has id < 0)
                val isFirstUser = Users.selectAll().where { Users.id greater 0 }.count() == 0L
                val isSelfHosted = EnvConfig.get("SELF_HOSTED", "false").trim().lowercase() == "true"
                val skipVerification =
                    isFirstUser ||
                        (isSelfHosted && EnvConfig.get("DISABLE_EMAIL_VERIFICATION", "false").trim().lowercase() == "true")

                // Generate verification token (only used if verification is required)
                val verificationToken = if (!skipVerification) generateVerificationToken() else null
                val expiresAt =
                    if (!skipVerification) System.currentTimeMillis() + VERIFICATION_TTL_MS else null

                // Create user
                val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
                val id =
                    Users.insert {
                        it[email] = normalizedEmail
                        it[password_hash] = passwordHash
                        it[name] = request.name
                        it[email_verified] = skipVerification
                        it[is_admin] = isFirstUser
                        it[email_verification_token] = verificationToken
                        it[email_verification_expires_at] = expiresAt
                    }[Users.id]

                val finalOrgId: Int
                val finalOrgRole: String

                // Join existing org if invited, otherwise create new org
                if (pendingInvite != null) {
                    finalOrgId = pendingInvite[OrgInvitations.organization_id]
                    finalOrgRole = pendingInvite[OrgInvitations.role]

                    // Create membership
                    Memberships.insert {
                        it[user_id] = id
                        it[organization_id] = finalOrgId
                        it[role] = finalOrgRole
                    }

                    // Mark invitation as accepted
                    OrgInvitations.update(
                        { OrgInvitations.id eq pendingInvite[OrgInvitations.id] }
                    ) {
                        it[status] = "accepted"
                    }

                    SentryUtils.breadcrumb(
                        "auth",
                        "User joined via invitation",
                        mapOf(
                            "user_id" to id,
                            "organization_id" to finalOrgId,
                            "role" to finalOrgRole
                        )
                    )
                } else {
                    // Create default organization
                    finalOrgId =
                        Organizations.insert {
                            it[name] = "${request.name ?: request.email}'s Organization"
                            it[slug] = "org-${UUID.randomUUID().toString().take(8)}"
                        }[Organizations.id]
                    finalOrgRole = "owner"

                    // Add membership
                    Memberships.insert {
                        it[user_id] = id
                        it[organization_id] = finalOrgId
                        it[role] = finalOrgRole
                    }

                    SentryUtils.breadcrumb(
                        "auth",
                        "User created new org",
                        mapOf(
                            "user_id" to id,
                            "organization_id" to finalOrgId
                        )
                    )
                }

                val acceptedAt = Clock.System.now()
                UserLegalAcceptances.insert {
                    it[user_id] = id
                    it[document_type] = "terms"
                    it[document_version] = request.termsVersion
                    it[accepted_at] = acceptedAt
                    it[ip_address] = context.ipAddress
                    it[user_agent] = context.userAgent
                }
                UserLegalAcceptances.insert {
                    it[user_id] = id
                    it[document_type] = "privacy"
                    it[document_version] = request.privacyVersion
                    it[accepted_at] = acceptedAt
                    it[ip_address] = context.ipAddress
                    it[user_agent] = context.userAgent
                }

                SentryUtils.breadcrumb(
                    "auth",
                    "User created",
                    mapOf(
                        "user_id" to id,
                        "organization_id" to finalOrgId
                    )
                )
                SentryUtils.breadcrumb(
                    "auth",
                    "Legal consent captured",
                    mapOf(
                        "user_id" to id,
                        "terms_version" to request.termsVersion,
                        "privacy_version" to request.privacyVersion,
                        "ip_present" to (context.ipAddress != null),
                        "user_agent_present" to (context.userAgent != null)
                    )
                )

                // Send verification email only if verification is required
                if (!skipVerification && verificationToken != null) {
                    try {
                        emailService.sendVerificationEmail(normalizedEmail, verificationToken, request.name)
                    } catch (e: Exception) {
                        // Log but don't fail signup if email fails
                        println("Failed to send verification email: ${e.message}")
                    }
                }

                Quadruple(id, skipVerification, finalOrgId, finalOrgRole)
            }

        val tokenPair = refreshTokenService.generateRefreshToken(userId, normalizedEmail, orgId, orgRole)
        SentryUtils.breadcrumb("auth", "Signup completed", mapOf("user_id" to userId))
        return AuthResponse(
            token = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = UserResponse(userId, normalizedEmail, request.name, emailVerified, false, false)
        )
    }

    fun verifyEmail(token: String): Boolean {
        return transaction {
            val user =
                Users
                    .selectAll()
                    .where { Users.email_verification_token eq token }
                    .firstOrNull()
                    ?: return@transaction false

            val expiresAt = user[Users.email_verification_expires_at]
            if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
                return@transaction false
            }

            // Mark as verified and clear token
            Users.update({ Users.id eq user[Users.id] }) {
                it[email_verified] = true
                it[email_verification_token] = null
                it[email_verification_expires_at] = null
            }

            true
        }
    }

    fun resendVerificationEmail(email: String): Boolean {
        val normalizedEmail = email.lowercase().trim()
        return transaction {
            val user =
                Users
                    .selectAll()
                    .where { Users.email eq normalizedEmail }
                    .firstOrNull()
                    ?: return@transaction false

            // Check if already verified
            if (user[Users.email_verified]) {
                throw IllegalArgumentException("Email already verified")
            }

            // Generate new token
            val verificationToken = generateVerificationToken()
            val expiresAt = System.currentTimeMillis() + VERIFICATION_TTL_MS

            Users.update({ Users.id eq user[Users.id] }) {
                it[email_verification_token] = verificationToken
                it[email_verification_expires_at] = expiresAt
            }

            // Send email
            try {
                emailService.sendVerificationEmail(normalizedEmail, verificationToken, user[Users.name])
                true
            } catch (e: Exception) {
                println("Failed to send verification email: ${e.message}")
                false
            }
        }
    }

    fun login(request: LoginRequest): AuthResponse? {
        val normalizedEmail = request.email.lowercase().trim()

        // Check if SSO is required for this email domain
        if (checkSsoRequired(normalizedEmail)) {
            throw IllegalArgumentException(
                "SSO is required for your organization. Please use the 'Login with SSO' option."
            )
        }

        return transaction {
            val user =
                Users.selectAll().where { Users.email eq normalizedEmail }.firstOrNull()
                    ?: return@transaction null

            if (!BCrypt.checkpw(request.password, user[Users.password_hash])) {
                return@transaction null
            }

            if (!user[Users.email_verified]) {
                throw IllegalArgumentException("Email not verified. Please check your email for the verification link.")
            }

            val userId = user[Users.id]

            // Get user's org membership
            val membership =
                Memberships
                    .selectAll()
                    .where { Memberships.user_id eq userId }
                    .firstOrNull()
                    ?: throw IllegalStateException("User has no organization membership")

            val orgId = membership[Memberships.organization_id]
            val orgRole = membership[Memberships.role]

            val tokenPair = refreshTokenService.generateRefreshToken(userId, user[Users.email], orgId, orgRole)
            AuthResponse(
                token = tokenPair.accessToken,
                refreshToken = tokenPair.refreshToken,
                expiresIn = tokenPair.expiresIn,
                user =
                UserResponse(
                    userId,
                    user[Users.email],
                    user[Users.name],
                    user[Users.email_verified],
                    user[Users.onboarding_completed],
                    user[Users.is_admin]
                )
            )
        }
    }

    private fun generateToken(
        userId: Int,
        email: String,
        orgId: Int,
        orgRole: String
    ): String {
        return JWT
            .create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("orgId", orgId)
            .withClaim("orgRole", orgRole)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    fun generateImpersonationToken(
        userId: Int,
        email: String
    ): String {
        // For impersonation, get the user's org membership
        val (orgId, orgRole) =
            transaction {
                val membership =
                    Memberships
                        .selectAll()
                        .where { Memberships.user_id eq userId }
                        .firstOrNull()
                        ?: throw IllegalStateException("User has no organization membership")

                membership[Memberships.organization_id] to membership[Memberships.role]
            }

        return generateToken(userId, email, orgId, orgRole)
    }

    fun generateDemoToken(): String {
        val demoUserId = com.moneat.config.EnvConfig.Demo.USER_ID
        val demoOrgId = com.moneat.config.EnvConfig.Demo.ORG_ID
        val demoEpochMs = com.moneat.config.EnvConfig.Demo.epochMs

        return JWT
            .create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", demoUserId)
            .withClaim("email", com.moneat.config.EnvConfig.Demo.USER_EMAIL)
            .withClaim("orgId", demoOrgId)
            .withClaim("orgRole", "viewer")
            .withClaim("isDemo", true)
            .withClaim("demoEpochMs", demoEpochMs)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun generateVerificationToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun validateSignupLegalConsent(request: SignupRequest) {
        if (EnvConfig.SelfHost.enabled) return
        if (!request.acceptTerms || !request.acceptPrivacy) {
            throw IllegalArgumentException("You must accept the Terms of Use and Privacy Policy to create an account")
        }
        if (request.termsVersion != legalTermsVersion || request.privacyVersion != legalPrivacyVersion) {
            throw IllegalArgumentException("Please review and accept the latest Terms of Use and Privacy Policy")
        }
    }

    fun requestPasswordReset(email: String): Boolean {
        val normalizedEmail = email.lowercase().trim()
        return transaction {
            val user =
                Users
                    .selectAll()
                    .where { Users.email eq normalizedEmail }
                    .firstOrNull()
                    ?: return@transaction false

            // Generate reset token
            val resetToken = generateVerificationToken()
            val expiresAt = System.currentTimeMillis() + (60 * 60 * 1000) // 1 hour

            Users.update({ Users.id eq user[Users.id] }) {
                it[password_reset_token] = resetToken
                it[password_reset_expires_at] = expiresAt
            }

            // Send password reset email
            try {
                emailService.sendPasswordResetEmail(normalizedEmail, resetToken, user[Users.name])
                true
            } catch (e: Exception) {
                println("Failed to send password reset email: ${e.message}")
                false
            }
        }
    }

    fun resetPassword(
        token: String,
        newPassword: String
    ): Boolean {
        if (newPassword.length < 8) {
            throw IllegalArgumentException("Password must be at least 8 characters")
        }

        return transaction {
            val user =
                Users
                    .selectAll()
                    .where { Users.password_reset_token eq token }
                    .firstOrNull()
                    ?: return@transaction false

            val expiresAt = user[Users.password_reset_expires_at]
            if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
                return@transaction false
            }

            // Update password and clear reset token
            val passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            Users.update({ Users.id eq user[Users.id] }) {
                it[password_hash] = passwordHash
                it[password_reset_token] = null
                it[password_reset_expires_at] = null
            }

            true
        }
    }

    fun completeOnboarding(
        userId: Int,
        organizationName: String,
        companySize: String,
        customSlug: String? = null,
        referralSource: String,
        utmSource: String? = null,
        utmMedium: String? = null,
        utmCampaign: String? = null,
        utmContent: String? = null,
        utmTerm: String? = null,
        sidebarHiddenItems: List<String>? = null
    ): UserResponse {
        return transaction {
            val user =
                Users.selectAll().where { Users.id eq userId }.firstOrNull()
                    ?: throw IllegalArgumentException("User not found")

            // Get the user's default organization
            val membership =
                Memberships
                    .selectAll()
                    .where { Memberships.user_id eq userId }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("No organization found for user")

            val orgId = membership[Memberships.organization_id]
            val membershipId = membership[Memberships.id]

            // Generate slug from custom input or organization name
            val baseSlug =
                (customSlug ?: organizationName)
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .take(100)

            // Ensure slug uniqueness
            var slug = baseSlug
            var suffix = 2
            while (Organizations
                    .selectAll()
                    .where { (Organizations.slug eq slug) and (Organizations.id neq orgId) }
                    .count() > 0
            ) {
                slug = "$baseSlug-$suffix"
                suffix++
            }

            // Update organization with new name, slug, company size, referral source, and UTM parameters
            Organizations.update({ Organizations.id eq orgId }) {
                it[name] = organizationName
                it[Organizations.slug] = slug
                it[company_size] = companySize
                it[Organizations.referral_source] = referralSource
                it[utm_source] = utmSource
                it[utm_medium] = utmMedium
                it[utm_campaign] = utmCampaign
                it[utm_content] = utmContent
                it[utm_term] = utmTerm
            }

            // Update sidebar preferences if provided
            val hiddenItems =
                if (sidebarHiddenItems != null) {
                    SidebarPreferenceService.updatePreferences(
                        membershipId = membershipId,
                        userId = userId,
                        organizationId = orgId,
                        hiddenItems = sidebarHiddenItems,
                        source = "onboarding"
                    )
                } else {
                    emptyList()
                }

            // Mark onboarding as completed
            Users.update({ Users.id eq userId }) {
                it[onboarding_completed] = true
            }

            UserResponse(
                user[Users.id],
                user[Users.email],
                user[Users.name],
                user[Users.email_verified],
                true,
                user[Users.is_admin],
                slug,
                null,
                hiddenItems
            )
        }
    }

    fun logout(userId: Int) {
        refreshTokenService.revokeAllUserTokens(userId)
    }

    fun refreshToken(token: String): AuthResponse? {
        val tokenPair =
            refreshTokenService.validateAndRotate(token)
                ?: return null

        // Get user info from the new access token to build response
        val jwtVerifier =
            com.auth0.jwt.JWT
                .require(
                    com.auth0.jwt.algorithms.Algorithm
                        .HMAC256(jwtSecret)
                ).build()
        val decodedJWT = jwtVerifier.verify(tokenPair.accessToken)
        val userId = decodedJWT.getClaim("userId").asInt()
        val email = decodedJWT.getClaim("email").asString()

        val user =
            transaction {
                val userRow =
                    Users.selectAll().where { Users.id eq userId }.firstOrNull()
                        ?: return@transaction null

                UserResponse(
                    userId,
                    email,
                    userRow[Users.name],
                    userRow[Users.email_verified],
                    userRow[Users.onboarding_completed],
                    userRow[Users.is_admin]
                )
            } ?: return null

        return AuthResponse(
            token = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = user
        )
    }

    /**
     * Check if SSO is required for this user's password login.
     * Tenant-scoped: only blocks when the user is a member of an org that has
     * require_sso enabled for their email domain. Prevents one org from blocking
     * password login for users in other orgs sharing the same domain.
     * Domain comparison is normalized (lowercase, trim) to avoid bypass.
     */
    private fun checkSsoRequired(email: String): Boolean {
        val normalizedDomain = email.substringAfter("@").lowercase().trim()
        if (normalizedDomain.isBlank()) return false
        return transaction {
            (
                Users.innerJoin(Memberships) { Users.id eq Memberships.user_id }
                    .innerJoin(SsoConfigurations) { Memberships.organization_id eq SsoConfigurations.organizationId }
                )
                .selectAll()
                .where {
                    (Users.email eq email.lowercase().trim()) and
                        (SsoConfigurations.emailDomain.isNotNull()) and
                        (SsoConfigurations.emailDomain.trim().lowerCase() eq normalizedDomain) and
                        (SsoConfigurations.isEnabled eq true) and
                        (SsoConfigurations.requireSso eq true)
                }
                .firstOrNull() != null
        }
    }
}
