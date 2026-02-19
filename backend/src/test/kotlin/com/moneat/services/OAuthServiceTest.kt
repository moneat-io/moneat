package com.moneat.services

import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OAuthServiceTest {

    @Test
    fun `generateGitHubAuthUrl uses configured base url and encoded redirect`() {
        MockHttpServer { exchange ->
            exchange.respond(404, """{"error":"not used"}""")
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "BACKEND_URL" to "https://api.test.local",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                val url = service.generateGitHubAuthUrl("state value")
                assertTrue(url.startsWith("${server.baseUrl}/login/oauth/authorize?"))
                assertTrue(url.contains("client_id=client-123"))
                assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapi.test.local%2Fauth%2Fgithub%2Fcallback"))
                assertTrue(url.contains("state=state+value") || url.contains("state=state%20value"))
            }
        }
    }

    @Test
    fun `handleGitHubCallback prefers user email when present`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> exchange.respond(200, """{"access_token":"token-1","token_type":"bearer","scope":"user:email"}""")
                "/user" -> exchange.respond(200, """{"id":42,"login":"octocat","email":"User@Test.com","name":"Octo Cat"}""")
                "/user/emails" -> exchange.respond(200, """[]""")
                else -> exchange.respond(404, """{"error":"not found"}""")
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                val user = runBlocking { service.handleGitHubCallback("code-1") }
                assertEquals("github", user.provider)
                assertEquals("42", user.providerId)
                assertEquals("user@test.com", user.email)
                assertEquals("Octo Cat", user.name)
                assertTrue(user.emailVerified)
            }
        }
    }

    @Test
    fun `handleGitHubCallback falls back to primary verified email`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> exchange.respond(200, """{"access_token":"token-2","token_type":"bearer","scope":"user:email"}""")
                "/user" -> exchange.respond(200, """{"id":77,"login":"fallback","email":null,"name":null}""")
                "/user/emails" -> exchange.respond(
                    200,
                    """
                    [
                      {"email":"secondary@test.com","primary":false,"verified":true,"visibility":null},
                      {"email":"primary@test.com","primary":true,"verified":true,"visibility":"public"}
                    ]
                    """.trimIndent()
                )
                else -> exchange.respond(404, """{"error":"not found"}""")
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                val user = runBlocking { service.handleGitHubCallback("code-2") }
                assertEquals("primary@test.com", user.email)
                assertEquals("fallback", user.name)
                assertTrue(user.emailVerified)
            }
        }
    }

    @Test
    fun `handleGitHubCallback fails when no verified email exists`() {
        MockHttpServer { exchange ->
            when (exchange.requestURI.path) {
                "/login/oauth/access_token" -> exchange.respond(200, """{"access_token":"token-3","token_type":"bearer","scope":"user:email"}""")
                "/user" -> exchange.respond(200, """{"id":88,"login":"noemail","email":null,"name":"No Email"}""")
                "/user/emails" -> exchange.respond(
                    200,
                    """
                    [
                      {"email":"not-verified@test.com","primary":true,"verified":false,"visibility":null}
                    ]
                    """.trimIndent()
                )
                else -> exchange.respond(404, """{"error":"not found"}""")
            }
        }.use { server ->
            withProperties(
                mapOf(
                    "GITHUB_OAUTH_CLIENT_ID" to "client-123",
                    "GITHUB_OAUTH_CLIENT_SECRET" to "secret-123",
                    "GITHUB_OAUTH_BASE_URL" to server.baseUrl,
                    "GITHUB_API_BASE_URL" to server.baseUrl
                )
            ) {
                val service = OAuthService()
                assertFailsWith<IllegalArgumentException> {
                    runBlocking { service.handleGitHubCallback("code-3") }
                }
            }
        }
    }

    private fun <T> withProperties(properties: Map<String, String>, block: () -> T): T {
        val previous = properties.keys.associateWith { System.getProperty(it) }
        properties.forEach { (k, v) -> System.setProperty(k, v) }
        return try {
            block()
        } finally {
            previous.forEach { (k, v) ->
                if (v == null) {
                    System.clearProperty(k)
                } else {
                    System.setProperty(k, v)
                }
            }
        }
    }
}
