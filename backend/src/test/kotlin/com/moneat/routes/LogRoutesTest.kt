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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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

class LogRoutesTest {
    private val jwtSecret = "log-routes-secret"

    @Test
    fun `otlp endpoint accepts empty payload without auth`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        JWT.require(Algorithm.HMAC256(jwtSecret))
                            .withIssuer("moneat")
                            .withAudience("moneat-users")
                            .build()
                    )
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing { logRoutes() }
        }

        val response = client.post("/v1/logs/otlp") {
            setBody("""{"resourceLogs":[]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("accepted"))
    }

    @Test
    fun `otlp endpoint returns bad request when project id is missing`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(
                        JWT.require(Algorithm.HMAC256(jwtSecret))
                            .withIssuer("moneat")
                            .withAudience("moneat-users")
                            .build()
                    )
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing { logRoutes() }
        }

        val payload = """
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

        val response = client.post("/v1/logs/otlp") {
            setBody(payload)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Missing project ID"))
    }
}
