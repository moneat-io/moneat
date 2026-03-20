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

package com.moneat.routes

import com.moneat.logs.routes.logIngestRoutes
import com.moneat.logs.routes.logRoutes
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class LogRoutesTest {
    private val jwtSecret = "log-routes-secret"

    companion object {
        private const val OTLP_LOGS_PATH = "/v1/logs/otlp"
    }

    private fun token(userId: Int, orgId: Int = 1) =
        JWT.create().withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId)
            .withClaim("orgId", orgId)
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun setupApp(block: io.ktor.server.application.Application.() -> Unit) =
        block.also { }

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
    }

    @Test
    fun `otlp endpoint returns 415 for application-x-protobuf content type`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT
                                .require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("moneat")
                                .withAudience("moneat-users")
                                .build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                routing { logIngestRoutes() }
            }

            val response =
                client.post(OTLP_LOGS_PATH) {
                    header(HttpHeaders.ContentType, "application/x-protobuf")
                    setBody(ByteArray(0))
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertTrue(response.bodyAsText().contains("application/json"))
            assertTrue(response.bodyAsText().contains("Protobuf"))
        }

    @Test
    fun `otlp endpoint returns 401 without auth even for empty payload`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT
                                .require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("moneat")
                                .withAudience("moneat-users")
                                .build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                routing { logIngestRoutes() }
            }

            val response =
                client.post("/v1/logs/otlp") {
                    setBody("""{"resourceLogs":[]}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Missing or invalid"))
        }

    @Test
    fun `otlp endpoint returns 401 when auth is missing`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT
                                .require(Algorithm.HMAC256(jwtSecret))
                                .withIssuer("moneat")
                                .withAudience("moneat-users")
                                .build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                routing { logIngestRoutes() }
            }

            val payload =
                """
            {
              "resourceLogs": [
                {
                  "resource": {
                    "attributes": [
                      {"key":"service.name", "value":{"stringValue":"checkout"}}
                    ]
                  },
                  "scopeLogs": [
                    {
                      "logRecords": [
                        {
                          "body": {"stringValue":"hello"},
                          "severityText":"INFO",
                          "timeUnixNano":"1730000000000000000"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
                """.trimIndent()

            val response =
                client.post(OTLP_LOGS_PATH) {
                    setBody(payload)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("Missing or invalid"))
        }

    // ──── Authenticated log query endpoints (org-scoped) ────

    @Test
    fun `tag values returns 400 for missing key parameter`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Authentication) {
                    jwt("auth-jwt") {
                        verifier(
                            JWT.require(
                                Algorithm.HMAC256(jwtSecret)
                            ).withIssuer("moneat").withAudience("moneat-users").build()
                        )
                        validate { JWTPrincipal(it.payload) }
                    }
                }
                routing { logRoutes() }
            }
            val response = client.get("/v1/logs/tag-values") {
                header(HttpHeaders.Authorization, "Bearer ${token(1)}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Missing tag key"))
        }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
