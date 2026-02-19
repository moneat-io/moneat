package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
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
