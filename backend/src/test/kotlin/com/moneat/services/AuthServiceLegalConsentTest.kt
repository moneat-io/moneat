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

import com.moneat.auth.services.AuthService
import com.moneat.auth.services.SignupRequestContext
import com.moneat.events.models.SignupRequest
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.RefreshTokens
import com.moneat.shared.models.UserLegalAcceptances
import com.moneat.shared.models.Users
import io.ktor.server.config.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.*

class AuthServiceLegalConsentTest {
    private val authService = AuthService()
    private val appConfig = ApplicationConfig("application.conf")
    private val termsVersion = appConfig.property("legal.termsVersion").getString()
    private val privacyVersion = appConfig.property("legal.privacyVersion").getString()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_legal;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(OrgInvitations, EmailsSent, RefreshTokens, UserLegalAcceptances, Memberships, Organizations, Users)
            SchemaUtils.create(Users, Organizations, Memberships, UserLegalAcceptances, RefreshTokens, EmailsSent, OrgInvitations)
        }
    }

    @Test
    fun `signup fails when terms are not accepted`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                authService.signup(validSignupRequest(acceptTerms = false))
            }

        assertTrue(error.message?.contains("must accept", ignoreCase = true) == true)
    }

    @Test
    fun `signup fails when privacy policy is not accepted`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                authService.signup(validSignupRequest(acceptPrivacy = false))
            }

        assertTrue(error.message?.contains("must accept", ignoreCase = true) == true)
    }

    @Test
    fun `signup fails when legal versions mismatch`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                authService.signup(validSignupRequest(termsVersion = "2026-01-01"))
            }

        assertTrue(error.message?.contains("latest", ignoreCase = true) == true)
    }

    @Test
    fun `signup stores terms and privacy acceptance with request metadata`() {
        val response =
            authService.signup(
                validSignupRequest(),
                SignupRequestContext(
                    ipAddress = "203.0.113.11",
                    userAgent = "moneat-test-agent/1.0"
                )
            )

        val acceptances =
            transaction {
                UserLegalAcceptances
                    .selectAll()
                    .where { UserLegalAcceptances.user_id eq response.user.id }
                    .toList()
            }

        assertEquals(2, acceptances.size)
        assertEquals(setOf("terms", "privacy"), acceptances.map { it[UserLegalAcceptances.document_type] }.toSet())

        val termsAcceptance = acceptances.first { it[UserLegalAcceptances.document_type] == "terms" }
        val privacyAcceptance = acceptances.first { it[UserLegalAcceptances.document_type] == "privacy" }

        assertEquals(termsVersion, termsAcceptance[UserLegalAcceptances.document_version])
        assertEquals(privacyVersion, privacyAcceptance[UserLegalAcceptances.document_version])
        assertEquals("203.0.113.11", termsAcceptance[UserLegalAcceptances.ip_address])
        assertEquals("203.0.113.11", privacyAcceptance[UserLegalAcceptances.ip_address])
        assertEquals("moneat-test-agent/1.0", termsAcceptance[UserLegalAcceptances.user_agent])
        assertEquals("moneat-test-agent/1.0", privacyAcceptance[UserLegalAcceptances.user_agent])
    }

    private fun validSignupRequest(
        acceptTerms: Boolean = true,
        acceptPrivacy: Boolean = true,
        termsVersion: String = this.termsVersion,
        privacyVersion: String = this.privacyVersion
    ): SignupRequest {
        return SignupRequest(
            email = "legal-test-${System.nanoTime()}@example.com",
            password = "password123",
            name = "Legal Test",
            acceptTerms = acceptTerms,
            acceptPrivacy = acceptPrivacy,
            termsVersion = termsVersion,
            privacyVersion = privacyVersion
        )
    }
}
