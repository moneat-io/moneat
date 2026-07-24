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
import com.moneat.auth.repositories.UserRepository
import com.moneat.auth.repositories.UserRepositoryImpl
import com.moneat.auth.repositories.models.UserRow
import com.moneat.auth.services.AuthService
import com.moneat.auth.services.RefreshTokenResponse
import com.moneat.auth.services.RefreshTokenService
import com.moneat.config.EnvConfig
import com.moneat.events.models.LoginRequest
import com.moneat.events.models.SignupRequest
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.RefreshTokens
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.UsageRecords
import com.moneat.shared.models.UserLegalAcceptances
import com.moneat.shared.models.Users
import com.moneat.shared.repositories.MembershipRepository
import com.moneat.shared.repositories.MembershipRepositoryImpl
import com.moneat.shared.repositories.OrganizationRepository
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import com.moneat.shared.repositories.models.MembershipRow
import com.moneat.shared.repositories.models.OrganizationRow
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.services.WorkflowService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceExtendedTest {

    companion object {
        private var db: Database? = null
        private const val TERMS_VERSION = "2026-02-08"
        private const val PRIVACY_VERSION = "2026-02-08"
        private const val TEST_EMAIL = "test@example.com"
        private const val MY_COMPANY = "My Company"
        private const val LOGOUT_EMAIL = "logout@test.com"
        private const val STRONG_PASS = "StrongPass123!"
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_auth_extended;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            UserLegalAcceptances,
            RefreshTokens,
            EmailsSent,
            SsoConfigurations,
            OrgInvitations,
            UsageRecords
        )
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun insertUser(
        email: String = TEST_EMAIL,
        password: String = "password123",
        emailVerified: Boolean = true,
        onboardingCompleted: Boolean = false,
        isAdmin: Boolean = false
    ): Int = transaction {
        Users.insert {
            it[Users.email] = email
            it[password_hash] = BCrypt.hashpw(password, BCrypt.gensalt())
            it[name] = "Test User"
            it[email_verified] = emailVerified
            it[Users.onboarding_completed] = onboardingCompleted
            it[Users.is_admin] = isAdmin
        }[Users.id]
    }

    private fun insertOrg(
        name: String = "Test Org",
        slug: String = "test-org"
    ): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[Organizations.slug] = slug
        }[Organizations.id]
    }

    private fun insertMembership(
        userId: Int,
        orgId: Int,
        role: String = "owner"
    ): Int = transaction {
        Memberships.insert {
            it[user_id] = userId
            it[organization_id] = orgId
            it[Memberships.role] = role
        }[Memberships.id]
    }

    private fun insertUserWithOrg(
        email: String = TEST_EMAIL,
        password: String = "password123",
        emailVerified: Boolean = true,
        role: String = "owner"
    ): Pair<Int, Int> {
        val userId = insertUser(email = email, password = password, emailVerified = emailVerified)
        val orgId = insertOrg(
            name = "Org for $email",
            slug = "org-${email.replace("@", "-").replace(".", "-")}"
        )
        insertMembership(userId, orgId, role)
        return userId to orgId
    }

    // ── RefreshTokenService ─────────────────────────────────────────

    @Test
    fun `generateRefreshToken creates token pair and persists hash`() {
        val (userId, orgId) = insertUserWithOrg()
        val service = RefreshTokenService()

        val result = service.generateRefreshToken(userId, TEST_EMAIL, orgId, "owner")

        assertNotNull(result.accessToken)
        assertNotNull(result.refreshToken)
        assertTrue(result.accessToken.isNotBlank())
        assertTrue(result.refreshToken.isNotBlank())
        assertEquals(3600L, result.expiresIn)

        val stored = transaction {
            RefreshTokens.selectAll()
                .where { RefreshTokens.user_id eq userId }
                .toList()
        }
        assertEquals(1, stored.size)
        assertFalse(stored[0][RefreshTokens.revoked])
    }

    @Test
    fun `validateAndRefresh returns a new access token for a stable device session`() {
        val (userId, orgId) = insertUserWithOrg()
        val service = RefreshTokenService()

        val original = service.generateRefreshToken(userId, TEST_EMAIL, orgId, "owner")
        val refreshed = service.validateAndRefresh(original.refreshToken)

        assertNotNull(refreshed)
        assertEquals(original.refreshToken, refreshed.refreshToken)
        assertNotEquals(original.accessToken, refreshed.accessToken)

        assertNotNull(service.validateAndRefresh(original.refreshToken))
    }

    @Test
    fun `validateAndRefresh returns null for invalid token`() {
        val service = RefreshTokenService()
        assertNull(service.validateAndRefresh("invalid-token-value"))
    }

    @Test
    fun `validateAndRefresh returns null for expired token`() {
        val (userId, orgId) = insertUserWithOrg()
        val service = RefreshTokenService()

        val response = service.generateRefreshToken(
            userId,
            TEST_EMAIL,
            orgId,
            "owner"
        )
        val rawToken = response.refreshToken
        val tokenHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray())
            .joinToString("") { "%02x".format(it) }

        transaction {
            RefreshTokens.update(
                { (RefreshTokens.user_id eq userId) and (RefreshTokens.token_hash eq tokenHash) }
            ) {
                it[expires_at] = System.currentTimeMillis() - 1000
            }
        }

        assertNull(service.validateAndRefresh(rawToken))
    }

    @Test
    fun `revokeAllUserTokens revokes all active tokens`() {
        val (userId, orgId) = insertUserWithOrg()
        val service = RefreshTokenService()

        service.generateRefreshToken(userId, TEST_EMAIL, orgId, "owner")
        service.generateRefreshToken(userId, TEST_EMAIL, orgId, "owner")

        val revokedCount = service.revokeAllUserTokens(userId)
        assertEquals(2, revokedCount)

        val activeTokens = transaction {
            RefreshTokens.selectAll()
                .where { (RefreshTokens.user_id eq userId) and (RefreshTokens.revoked eq false) }
                .count()
        }
        assertEquals(0L, activeTokens)
    }

    @Test
    fun `revokeAllUserTokens returns zero when no tokens exist`() {
        val userId = insertUser()
        val service = RefreshTokenService()
        assertEquals(0, service.revokeAllUserTokens(userId))
    }

    @Test
    fun `cleanupExpiredTokens removes expired and revoked tokens`() {
        val (userId, orgId) = insertUserWithOrg()
        val service = RefreshTokenService()

        // Generate a token then revoke it
        service.generateRefreshToken(userId, TEST_EMAIL, orgId, "owner")
        service.revokeAllUserTokens(userId)

        // Also insert an expired token
        transaction {
            RefreshTokens.insert {
                it[RefreshTokens.user_id] = userId
                it[token_hash] = "old-expired-hash"
                it[created_at] = System.currentTimeMillis() - 200_000
                it[expires_at] = System.currentTimeMillis() - 100_000
                it[last_used_at] = null
                it[revoked] = false
            }
        }

        val deletedCount = service.cleanupExpiredTokens()
        assertTrue(deletedCount >= 2, "Should delete expired and revoked tokens")

        val remaining = transaction { RefreshTokens.selectAll().count() }
        assertEquals(0L, remaining)
    }

    // ── AuthService - completeOnboarding ────────────────────────────

    @Test
    fun `completeOnboarding updates org name and slug`() {
        val (userId, orgId) = insertUserWithOrg(email = "onboard@test.com")

        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl(),
            workflowService = mockk<WorkflowService>(relaxed = true),
        )

        val result = authService.completeOnboarding(
            userId = userId,
            organizationName = MY_COMPANY,
            companySize = "10-50",
            referralSource = "google"
        )

        assertNotNull(result)
        assertTrue(result.onboardingCompleted)
        assertNotNull(result.organizationSlug)

        // Verify org name updated in DB
        val org = transaction {
            Organizations.selectAll()
                .where { Organizations.id eq orgId }
                .single()
        }
        assertEquals(MY_COMPANY, org[Organizations.name])

        // Verify user onboarding_completed flag
        val user = transaction {
            Users.selectAll().where { Users.id eq userId }.single()
        }
        assertTrue(user[Users.onboarding_completed])
    }

    @Test
    fun `completeOnboarding fails for non-existent user`() {
        assertFailsWith<IllegalArgumentException> {
            val authService = AuthService(
                UserRepositoryImpl(),
                MembershipRepositoryImpl(),
                OrganizationRepositoryImpl()
            )
            authService.completeOnboarding(
                userId = 99999,
                organizationName = "Ghost Org",
                companySize = "1-10",
                referralSource = "friend"
            )
        }
    }

    @Test
    fun `completeOnboarding uses custom slug when provided`() {
        val (userId, _) = insertUserWithOrg(email = "slug-test@test.com")

        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        val result = authService.completeOnboarding(
            userId = userId,
            organizationName = MY_COMPANY,
            companySize = "1-10",
            customSlug = "my-custom-slug",
            referralSource = "twitter"
        )

        val slug = result.organizationSlug
        assertNotNull(slug)
        assertTrue(
            slug.startsWith("my-custom-slug"),
            "Slug should be based on custom slug"
        )
    }

    // ── AuthService - logout ────────────────────────────────────────

    @Test
    fun `logout revokes only the current refresh token session`() {
        val (userId, orgId) = insertUserWithOrg(email = LOGOUT_EMAIL)
        val refreshTokenService = RefreshTokenService()
        val currentSession =
            refreshTokenService.generateRefreshToken(userId, LOGOUT_EMAIL, orgId, "owner")
        val otherDeviceSession =
            refreshTokenService.generateRefreshToken(userId, LOGOUT_EMAIL, orgId, "owner")

        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl(),
            refreshTokenService = refreshTokenService,
            workflowService = mockk<WorkflowService>(relaxed = true),
        )

        assertTrue(authService.logout(currentSession.refreshToken))

        assertNull(refreshTokenService.validateAndRefresh(currentSession.refreshToken))
        assertNotNull(refreshTokenService.validateAndRefresh(otherDeviceSession.refreshToken))
    }

    // ── AuthService - refreshToken ──────────────────────────────────

    @Test
    fun `refreshToken returns new tokens for valid refresh token`() {
        val (userId, orgId) = insertUserWithOrg(email = "refresh@test.com")
        val refreshTokenService = RefreshTokenService()
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl(),
            refreshTokenService = refreshTokenService,
            workflowService = mockk<WorkflowService>(relaxed = true),
        )

        val signup = authService.signup(
            SignupRequest(
                email = "refresh-new@test.com",
                password = STRONG_PASS,
                name = "Refresh User",
                acceptTerms = true,
                acceptPrivacy = true,
                termsVersion = TERMS_VERSION,
                privacyVersion = PRIVACY_VERSION
            )
        )

        val refreshed = authService.refreshToken(signup.refreshToken!!)
        assertNotNull(refreshed)
        assertNotEquals(signup.token, refreshed.token)
        assertNotNull(refreshed.refreshToken)
    }

    @Test
    fun `refreshToken response uses org context from refreshed access token`() {
        val userId = 123
        val tokenOrgId = 202
        val firstOrgId = 101
        val email = "refresh-context@test.com"
        val tokenPair = RefreshTokenResponse(
            accessToken = testAccessToken(userId, email, tokenOrgId, "admin"),
            refreshToken = "refresh-token"
        )
        val userRepository = mockk<UserRepository>()
        val membershipRepository = mockk<MembershipRepository>()
        val organizationRepository = mockk<OrganizationRepository>()
        val refreshTokenService = mockk<RefreshTokenService>()

        every { refreshTokenService.validateAndRefresh("refresh-token") } returns tokenPair
        every { userRepository.findById(userId) } returns userRow(userId, email)
        every { membershipRepository.getFirstMembershipForUser(userId) } returns
            MembershipRow(id = 1, userId = userId, organizationId = firstOrgId, role = "viewer")
        every { organizationRepository.findById(firstOrgId) } returns
            OrganizationRow(
                id = firstOrgId,
                resourceId = "11111111-1111-4111-8111-111111111111",
                name = "First Org",
                slug = "first-org"
            )
        every { organizationRepository.findById(tokenOrgId) } returns
            OrganizationRow(
                id = tokenOrgId,
                resourceId = "22222222-2222-4222-8222-222222222222",
                name = "Token Org",
                slug = "token-org"
            )

        val authService = AuthService(
            userRepository,
            membershipRepository,
            organizationRepository,
            refreshTokenService = refreshTokenService
        )

        val refreshed = authService.refreshToken("refresh-token")

        assertNotNull(refreshed)
        assertEquals("token-org", refreshed.user.organizationSlug)
        assertEquals("admin", refreshed.user.orgRole)
    }

    @Test
    fun `refreshToken returns null for invalid token`() {
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl(),
            workflowService = mockk<WorkflowService>(relaxed = true),
        )
        assertNull(authService.refreshToken("invalid-refresh-token"))
    }

    private fun userRow(
        userId: Int,
        email: String
    ): UserRow = UserRow(
        id = userId,
        resourceId = "33333333-3333-4333-8333-333333333333",
        email = email,
        passwordHash = "hash",
        name = "Refresh Context",
        emailVerified = true,
        isAdmin = false,
        onboardingCompleted = true,
        emailVerificationToken = null,
        emailVerificationExpiresAt = null,
        passwordResetToken = null,
        passwordResetExpiresAt = null,
        oauthProvider = null,
        oauthProviderId = null
    )

    private fun userIdForResourceId(resourceId: String): Int =
        transaction {
            Users
                .selectAll()
                .where { Users.resource_id eq kotlin.uuid.Uuid.parse(resourceId) }
                .single()[Users.id]
        }

    private fun testAccessToken(
        userId: Int,
        email: String,
        orgId: Int,
        orgRole: String
    ): String {
        return JWT
            .create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withClaim("orgId", orgId)
            .withClaim("orgRole", orgRole)
            .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000L))
            .sign(Algorithm.HMAC256("test-secret-for-unit-tests"))
    }

    // ── AuthService - generateImpersonationToken ────────────────────

    @Test
    fun `generateImpersonationToken returns valid JWT`() {
        val (userId, _) = insertUserWithOrg(email = "admin@test.com")
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl(),
            workflowService = mockk<WorkflowService>(relaxed = true),
        )

        val token = authService.generateImpersonationToken(userId, "admin@test.com")
        assertNotNull(token)
        assertTrue(token.isNotBlank())
    }

    @Test
    fun `generateImpersonationToken fails for user without membership`() {
        val userId = insertUser(email = "nomember@test.com")
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        assertFailsWith<IllegalStateException> {
            authService.generateImpersonationToken(userId, "nomember@test.com")
        }
    }

    // ── AuthService - login edge cases ──────────────────────────────

    @Test
    fun `login rejects unverified email`() {
        insertUserWithOrg(
            email = "unverified@test.com",
            password = "password123",
            emailVerified = false
        )
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            authService.login(LoginRequest(email = "unverified@test.com", password = "password123"))
        }
        assertTrue(ex.message?.contains("not verified") == true)
    }

    @Test
    fun `login returns refresh token`() {
        insertUserWithOrg(email = "withrefresh@test.com", password = "password123")
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        val result = authService.login(
            LoginRequest(email = "withrefresh@test.com", password = "password123")
        )
        assertNotNull(result)
        assertNotNull(result.refreshToken)
        val expiresIn = result.expiresIn
        assertNotNull(expiresIn)
        assertTrue(expiresIn > 0)
    }

    // ── AuthService - signup with invite ────────────────────────────

    @Test
    fun `signup with valid invite joins existing organization`() {
        // Create an existing user + org to ensure this isn't the first user
        val (inviterId, orgId) = insertUserWithOrg(email = "inviter@test.com")

        val inviteToken = "invite-token-123"
        transaction {
            OrgInvitations.insert {
                it[organization_id] = orgId
                it[email] = "invitee@test.com"
                it[role] = "member"
                it[invited_by] = inviterId
                it[token] = inviteToken
                it[status] = "pending"
                it[expires_at] = System.currentTimeMillis() + 86_400_000
                it[created_at] = kotlin.time.Clock.System.now()
            }
        }

        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        val result = authService.signup(
            SignupRequest(
                email = "invitee@test.com",
                password = STRONG_PASS,
                name = "Invited User",
                acceptTerms = true,
                acceptPrivacy = true,
                termsVersion = TERMS_VERSION,
                privacyVersion = PRIVACY_VERSION
            ),
            inviteToken = inviteToken
        )

        assertNotNull(result)

        // Verify user is member of the inviter's org
        val createdUserId = userIdForResourceId(result.user.id)
        val membership = transaction {
            Memberships.selectAll()
                .where { Memberships.user_id eq createdUserId }
                .single()
        }
        assertEquals(orgId, membership[Memberships.organization_id])
        assertEquals("member", membership[Memberships.role])

        // Verify invitation status changed
        val invitation = transaction {
            OrgInvitations.selectAll()
                .where { OrgInvitations.token eq inviteToken }
                .single()
        }
        assertEquals("accepted", invitation[OrgInvitations.status])
    }

    @Test
    fun `signup with expired invite fails`() {
        val (inviterId, orgId) = insertUserWithOrg(email = "inviter2@test.com")
        val inviteToken = "expired-invite-token"
        transaction {
            OrgInvitations.insert {
                it[organization_id] = orgId
                it[email] = "late-invitee@test.com"
                it[role] = "member"
                it[invited_by] = inviterId
                it[token] = inviteToken
                it[status] = "pending"
                it[expires_at] = System.currentTimeMillis() - 1000
                it[created_at] = kotlin.time.Clock.System.now()
            }
        }

        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        assertFailsWith<IllegalArgumentException> {
            authService.signup(
                SignupRequest(
                    email = "late-invitee@test.com",
                    password = STRONG_PASS,
                    name = "Late User",
                    acceptTerms = true,
                    acceptPrivacy = true,
                    termsVersion = TERMS_VERSION,
                    privacyVersion = PRIVACY_VERSION
                ),
                inviteToken = inviteToken
            )
        }
    }

    @Test
    fun `signup with invite for wrong email fails`() {
        val (inviterId, orgId) = insertUserWithOrg(email = "inviter3@test.com")
        val inviteToken = "wrong-email-invite"
        transaction {
            OrgInvitations.insert {
                it[organization_id] = orgId
                it[email] = "someone-else@test.com"
                it[role] = "member"
                it[invited_by] = inviterId
                it[token] = inviteToken
                it[status] = "pending"
                it[expires_at] = System.currentTimeMillis() + 86_400_000
                it[created_at] = kotlin.time.Clock.System.now()
            }
        }

        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        assertFailsWith<IllegalArgumentException> {
            authService.signup(
                SignupRequest(
                    email = "different@test.com",
                    password = STRONG_PASS,
                    name = "Wrong User",
                    acceptTerms = true,
                    acceptPrivacy = true,
                    termsVersion = TERMS_VERSION,
                    privacyVersion = PRIVACY_VERSION
                ),
                inviteToken = inviteToken
            )
        }
    }

    // ── AuthService - resendVerificationEmail ───────────────────────

    @Test
    fun `resendVerificationEmail returns false for non-existent user`() {
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )
        val result = authService.resendVerificationEmail("nobody@test.com")
        assertFalse(result)
    }

    // ── AuthService - generateDemoToken claims ──────────────────────

    @Test
    fun `generateDemoToken contains demo claims`() {
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl()
        )

        val token = authService.generateDemoToken()
        val decoded = com.auth0.jwt.JWT.decode(token)

        assertEquals("viewer", decoded.getClaim("orgRole").asString())
        assertTrue(decoded.getClaim("isDemo").asBoolean())
        assertNotNull(decoded.getClaim("demoEpochMs").asLong())
    }

    // ── RefreshTokenService - access token demo identity ────────────

    @Test
    fun `generateRefreshToken for demo user produces viewer role token`() {
        // Insert demo user in DB using EnvConfig.Demo constants
        transaction {
            Users.insert {
                it[id] = EnvConfig.Demo.USER_ID.toInt()
                it[email] = EnvConfig.Demo.USER_EMAIL
                it[password_hash] = ""
                it[name] = "Demo"
                it[email_verified] = true
            }
            Organizations.insert {
                it[id] = EnvConfig.Demo.ORG_ID.toInt()
                it[name] = "Demo Org"
                it[slug] = "demo-org"
            }
            Memberships.insert {
                it[user_id] = EnvConfig.Demo.USER_ID.toInt()
                it[organization_id] = EnvConfig.Demo.ORG_ID.toInt()
                it[role] = "owner"
            }
        }

        val service = RefreshTokenService()
        val result = service.generateRefreshToken(
            EnvConfig.Demo.USER_ID.toInt(),
            EnvConfig.Demo.USER_EMAIL,
            EnvConfig.Demo.ORG_ID.toInt(),
            "owner"
        )

        val decoded = com.auth0.jwt.JWT.decode(result.accessToken)
        assertEquals("viewer", decoded.getClaim("orgRole").asString())
        assertTrue(decoded.getClaim("isDemo").asBoolean())
    }

    // ── AuthService - first user auto-verifies email ────────────────

    @Test
    fun `signup first user auto-verifies email and grants admin`() {
        val authService = AuthService(
            UserRepositoryImpl(),
            MembershipRepositoryImpl(),
            OrganizationRepositoryImpl(),
            workflowService = mockk<WorkflowService>(relaxed = true),
        )

        val result = authService.signup(
            SignupRequest(
                email = "firstuser@test.com",
                password = STRONG_PASS,
                name = "First User",
                acceptTerms = true,
                acceptPrivacy = true,
                termsVersion = TERMS_VERSION,
                privacyVersion = PRIVACY_VERSION
            )
        )

        assertTrue(result.user.emailVerified, "First user should be auto-verified")
        assertTrue(result.user.isAdmin, "First user should be admin")
    }
}
