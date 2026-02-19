package com.moneat.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class IncidentProviderRoutesTest {
    private val jwtSecret = "incident-provider-routes-secret"

    @Test
    fun `test connection route returns 400 for non numeric config id`() = testApplication {
        application {
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
            routing { incidentProviderRoutes() }
        }

        val response = client.post("/api/incident-providers/not-an-int/test") {
            header(HttpHeaders.Authorization, "Bearer ${tokenForUser(7)}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private fun tokenForUser(userId: Int): String {
        return JWT.create()
            .withIssuer("moneat")
            .withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
