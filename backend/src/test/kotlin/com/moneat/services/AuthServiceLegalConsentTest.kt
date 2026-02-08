package com.moneat.services

import com.moneat.models.*
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.*

class AuthServiceLegalConsentTest {
    private val authService = AuthService()
    private val appConfig = ApplicationConfig("application.conf")
    private val termsVersion = appConfig.property("legal.termsVersion").getString()
    private val privacyVersion = appConfig.property("legal.privacyVersion").getString()

    @BeforeTest
    fun setupDatabase() {
        Database.connect(
            url = "jdbc:h2:mem:moneat_legal_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        transaction {
            SchemaUtils.create(Users, UserLegalAcceptances, Organizations, Memberships)
        }
    }

    @Test
    fun `signup fails when terms are not accepted`() {
        val error = assertFailsWith<IllegalArgumentException> {
            authService.signup(validSignupRequest(acceptTerms = false))
        }

        assertTrue(error.message?.contains("must accept", ignoreCase = true) == true)
    }

    @Test
    fun `signup fails when privacy policy is not accepted`() {
        val error = assertFailsWith<IllegalArgumentException> {
            authService.signup(validSignupRequest(acceptPrivacy = false))
        }

        assertTrue(error.message?.contains("must accept", ignoreCase = true) == true)
    }

    @Test
    fun `signup fails when legal versions mismatch`() {
        val error = assertFailsWith<IllegalArgumentException> {
            authService.signup(validSignupRequest(termsVersion = "2026-01-01"))
        }

        assertTrue(error.message?.contains("latest", ignoreCase = true) == true)
    }

    @Test
    fun `signup stores terms and privacy acceptance with request metadata`() {
        val response = authService.signup(
            validSignupRequest(),
            SignupRequestContext(
                ipAddress = "203.0.113.11",
                userAgent = "moneat-test-agent/1.0"
            )
        )

        val acceptances = transaction {
            UserLegalAcceptances.selectAll()
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
