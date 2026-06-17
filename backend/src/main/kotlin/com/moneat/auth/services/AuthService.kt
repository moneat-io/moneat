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
import com.moneat.auth.currentOrgIdOrNull
import com.moneat.auth.repositories.UserRepository
import com.moneat.config.EnvConfig
import com.moneat.events.models.AuthResponse
import com.moneat.events.models.LoginRequest
import com.moneat.events.models.SignupRequest
import com.moneat.events.models.UserResponse
import com.moneat.notifications.services.EmailService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.UserLegalAcceptances
import com.moneat.shared.models.Users
import com.moneat.shared.repositories.MembershipRepository
import com.moneat.shared.repositories.OrganizationRepository
import com.moneat.shared.services.SidebarPreferenceService
import com.moneat.utils.SentryUtils
import com.moneat.utils.suspendRunCatching
import com.moneat.workflows.services.WorkflowService
import io.ktor.server.config.ApplicationConfig
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

data class Sextuple<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F
)

data class SignupRequestContext(
    val ipAddress: String? = null,
    val userAgent: String? = null
)

class AuthService(
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val organizationRepository: OrganizationRepository,
    private val emailService: EmailService = EmailService(),
    private val refreshTokenService: RefreshTokenService = RefreshTokenService(),
    private val workflowService: WorkflowService = WorkflowService(),
) {
    companion object {
        private const val VERIFICATION_TTL_MS = 24 * 60 * 60 * 1000L
        private const val PASSWORD_RESET_TTL_MS = 60 * 60 * 1000L
        private const val MIN_PASSWORD_LENGTH = 8
        private const val ORG_SLUG_RANDOM_SUFFIX_LENGTH = 8
        private const val JWT_ACCESS_TOKEN_EXPIRY_MS = 3_600_000L
        private const val DEMO_TOKEN_EXPIRY_MS = 86_400_000L
        private const val TOKEN_ENTROPY_BYTES = 32
        private const val MAX_ORG_SLUG_LENGTH = 100
    }

    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    private val legalTermsVersion = config.property("legal.termsVersion").getString()
    private val legalPrivacyVersion = config.property("legal.privacyVersion").getString()
    private val secureRandom = SecureRandom()

    fun signup(
        request: SignupRequest,
        context: SignupRequestContext = SignupRequestContext(),
        inviteToken: String? = null
    ): AuthResponse {
        if (request.email.isBlank()) {
            throw IllegalArgumentException("Email is required")
        }
        require(request.password.length >= MIN_PASSWORD_LENGTH) { "Password must be at least 8 characters" }
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

        val (userId, emailVerified, verificationToken, orgId, orgRole, isAdmin) =
            transaction(transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE) {
                // Check if user exists
                val existing = Users.selectAll().where { Users.email eq normalizedEmail }.firstOrNull()
                if (existing != null) {
                    SentryUtils.breadcrumb("auth", "Signup failed - user exists", mapOf("email" to request.email))
                    throw IllegalArgumentException("User already exists")
                }

                val now = System.currentTimeMillis()

                // Resolve invite in a strict state-aware way.
                val pendingInvite = resolvePendingInvite(inviteToken, normalizedEmail, now)

                // Detect first real user (excludes demo user which has id < 0)
                val isFirstUser = Users.selectAll().where { Users.id greater 0 }.count() == 0L
                val isSelfHosted = EnvConfig.get("SELF_HOSTED", "false").trim().lowercase() == "true"
                val skipVerification =
                    isFirstUser ||
                        (
                            isSelfHosted &&
                                EnvConfig.get("DISABLE_EMAIL_VERIFICATION", "false").trim().lowercase() == "true"
                            )

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

                // Join existing org if invited, otherwise create new org
                val (finalOrgId, finalOrgRole) = createUserOrgMembership(id, pendingInvite, request)

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

                Sextuple(id, skipVerification, verificationToken, finalOrgId, finalOrgRole, isFirstUser)
            }

        if (!emailVerified && verificationToken != null) {
            suspendRunCatching {
                emailService.sendVerificationEmail(normalizedEmail, verificationToken, request.name)
            }.getOrElse { e ->
                // Log but don't fail signup if email fails
                logger.warn(e) { "Failed to send verification email" }
            }
        }

        val tokenPair = refreshTokenService.generateRefreshToken(userId, normalizedEmail, orgId, orgRole)
        SentryUtils.breadcrumb("auth", "Signup completed", mapOf("user_id" to userId))
        val user = userRepository.findById(userId)
            ?: throw IllegalStateException("Created user could not be reloaded")
        val organization = organizationRepository.findById(orgId)
        val organizationSlug = organization?.slug
        return AuthResponse(
            token = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = UserResponse(
                id = user.resourceId,
                email = normalizedEmail,
                name = request.name,
                emailVerified = emailVerified,
                onboardingCompleted = false,
                isAdmin = isAdmin,
                organizationSlug = organizationSlug,
                orgRole = orgRole,
                orgId = organization?.resourceId,
            )
        )
    }

    /**
     * Resolve a pending invitation for signup: validate a token-based invite or find an email-based one.
     * Must be called within a database transaction.
     */
    private fun resolvePendingInvite(inviteToken: String?, normalizedEmail: String, now: Long): ResultRow? {
        return if (inviteToken != null) {
            val inviteByToken =
                OrgInvitations
                    .selectAll()
                    .where { OrgInvitations.token eq inviteToken }
                    .singleOrNull()
                    ?: throw IllegalArgumentException("Invitation not found")

            val inviteStatus = inviteByToken[OrgInvitations.status]
            require(inviteStatus == "pending") { "Invitation is no longer valid" }

            val inviteExpiresAt = inviteByToken[OrgInvitations.expires_at]
            if (now > inviteExpiresAt) {
                OrgInvitations.update({ OrgInvitations.id eq inviteByToken[OrgInvitations.id] }) {
                    it[OrgInvitations.status] = "expired"
                }
                throw IllegalArgumentException("Invitation has expired")
            }

            require(inviteByToken[OrgInvitations.email].lowercase() == normalizedEmail) {
                "This invitation was sent to a different email address"
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
    }

    /**
     * Create an org membership for a new user: join via invite or create a new org.
     * Must be called within a database transaction.
     * Returns (orgId, orgRole).
     */
    private fun createUserOrgMembership(
        userId: Int,
        pendingInvite: ResultRow?,
        request: SignupRequest,
    ): Pair<Int, String> {
        return if (pendingInvite != null) {
            val orgId = pendingInvite[OrgInvitations.organization_id]
            val orgRole = pendingInvite[OrgInvitations.role]
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = orgRole
            }
            OrgInvitations.update({ OrgInvitations.id eq pendingInvite[OrgInvitations.id] }) {
                it[status] = "accepted"
            }
            SentryUtils.breadcrumb(
                "auth",
                "User joined via invitation",
                mapOf("user_id" to userId, "organization_id" to orgId, "role" to orgRole),
            )
            orgId to orgRole
        } else {
            val orgId =
                Organizations.insert {
                    it[name] = "${request.name ?: request.email}'s Organization"
                    it[slug] = "org-${UUID.randomUUID().toString().take(ORG_SLUG_RANDOM_SUFFIX_LENGTH)}"
                }[Organizations.id]
            workflowService.ensureDefaultWorkflowsForOrganization(orgId)
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
            SentryUtils.breadcrumb(
                "auth",
                "User created new org",
                mapOf("user_id" to userId, "organization_id" to orgId),
            )
            orgId to "owner"
        }
    }

    fun verifyEmail(token: String): Boolean {
        val user = userRepository.findByEmailVerificationToken(token) ?: return false
        val expiresAt = user.emailVerificationExpiresAt
        if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
            return false
        }
        userRepository.updateEmailVerified(user.id, true)
        userRepository.clearEmailVerificationToken(user.id)
        return true
    }

    fun resendVerificationEmail(email: String): Boolean {
        val normalizedEmail = email.lowercase().trim()
        val user = userRepository.findByEmail(normalizedEmail) ?: return false
        require(!user.emailVerified) { "Email already verified" }
        val verificationToken = generateVerificationToken()
        val expiresAt = System.currentTimeMillis() + VERIFICATION_TTL_MS
        return suspendRunCatching {
            emailService.sendVerificationEmail(normalizedEmail, verificationToken, user.name)
            userRepository.updateVerificationToken(user.id, verificationToken, expiresAt)
            true
        }.getOrElse { e ->
            logger.warn(e) { "Failed to send verification email" }
            false
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

        val user = userRepository.findByEmail(normalizedEmail) ?: return null

        if (!BCrypt.checkpw(request.password, user.passwordHash)) {
            return null
        }

        require(user.emailVerified) { "Email not verified. Please check your email for the verification link." }

        val (orgId, orgRole) = run {
            val membership = membershipRepository.getFirstMembershipForUser(user.id)
                ?: throw IllegalStateException("User has no organization membership")
            membership.organizationId to membership.role
        }

        val tokenPair = refreshTokenService.generateRefreshToken(user.id, user.email, orgId, orgRole)
        val organization = organizationRepository.findById(orgId)
        val organizationSlug = organization?.slug
        return AuthResponse(
            token = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = UserResponse(
                id = user.resourceId,
                email = user.email,
                name = user.name,
                emailVerified = user.emailVerified,
                onboardingCompleted = user.onboardingCompleted,
                isAdmin = user.isAdmin,
                organizationSlug = organizationSlug,
                orgRole = orgRole,
                orgId = organization?.resourceId,
            )
        )
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
            .withExpiresAt(Date(System.currentTimeMillis() + JWT_ACCESS_TOKEN_EXPIRY_MS))
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    fun generateImpersonationToken(
        userId: Int,
        email: String
    ): String {
        // For impersonation, get the user's org membership
        val (orgId, orgRole) = run {
            val membership = membershipRepository.getFirstMembershipForUser(userId)
                ?: throw IllegalStateException("User has no organization membership")
            membership.organizationId to membership.role
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
            .withExpiresAt(Date(System.currentTimeMillis() + DEMO_TOKEN_EXPIRY_MS)) // 24 hours
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    private fun generateVerificationToken(): String {
        val bytes = ByteArray(TOKEN_ENTROPY_BYTES)
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
        val user = userRepository.findByEmail(normalizedEmail) ?: return false
        val resetToken = generateVerificationToken()
        val expiresAt = System.currentTimeMillis() + PASSWORD_RESET_TTL_MS // 1 hour
        return suspendRunCatching {
            emailService.sendPasswordResetEmail(normalizedEmail, resetToken, user.name)
            userRepository.updatePasswordResetToken(user.id, resetToken, expiresAt)
            true
        }.getOrElse { e ->
            logger.warn(e) { "Failed to send password reset email" }
            false
        }
    }

    fun resetPassword(
        token: String,
        newPassword: String
    ): Boolean {
        require(newPassword.length >= MIN_PASSWORD_LENGTH) { "Password must be at least 8 characters" }

        val user = userRepository.findByPasswordResetToken(token) ?: return false
        val expiresAt = user.passwordResetExpiresAt
        if (expiresAt == null || expiresAt < System.currentTimeMillis()) {
            return false
        }
        val passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
        userRepository.updatePassword(user.id, passwordHash)
        userRepository.clearPasswordResetToken(user.id)
        return true
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
        val user = userRepository.findById(userId)
            ?: throw IllegalArgumentException("User not found")
        val membership = membershipRepository.getFirstMembershipForUser(userId)
            ?: throw IllegalArgumentException("No organization found for user")

        val baseSlug = (customSlug ?: organizationName)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_ORG_SLUG_LENGTH)

        val finalSlug = organizationRepository.updateOnboardingOrgAndMarkComplete(
            OrganizationRepository.OnboardingUpdate(
                orgId = membership.organizationId,
                userId = userId,
                baseSlug = baseSlug,
                name = organizationName,
                companySize = companySize,
                referralSource = referralSource,
                utmSource = utmSource,
                utmMedium = utmMedium,
                utmCampaign = utmCampaign,
                utmContent = utmContent,
                utmTerm = utmTerm,
            )
        )

        val hiddenItems = if (sidebarHiddenItems != null) {
            SidebarPreferenceService.updatePreferences(
                membershipId = membership.id,
                userId = userId,
                organizationId = membership.organizationId,
                hiddenItems = sidebarHiddenItems,
                source = "onboarding"
            )
        } else {
            emptyList()
        }

        val organization = organizationRepository.findById(membership.organizationId)
        return UserResponse(
            id = user.resourceId,
            email = user.email,
            name = user.name,
            emailVerified = user.emailVerified,
            onboardingCompleted = true,
            isAdmin = user.isAdmin,
            organizationSlug = finalSlug,
            orgRole = membership.role,
            demoEpochMs = null,
            sidebarHiddenItems = hiddenItems,
            orgId = organization?.resourceId,
        )
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
        val orgId = decodedJWT.currentOrgIdOrNull()
        val orgRole = decodedJWT.getClaim("orgRole").asString()

        val user = run {
            val userRow = userRepository.findById(userId) ?: return null
            val organization = orgId?.let { organizationRepository.findById(it) }
            val organizationSlug = organization?.slug
            UserResponse(
                id = userRow.resourceId,
                email = email,
                name = userRow.name,
                emailVerified = userRow.emailVerified,
                onboardingCompleted = userRow.onboardingCompleted,
                isAdmin = userRow.isAdmin,
                organizationSlug = organizationSlug,
                orgRole = orgRole,
                orgId = organization?.resourceId,
            )
        }

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
    private fun checkSsoRequired(email: String): Boolean = userRepository.requiresSsoForEmail(email)
}
