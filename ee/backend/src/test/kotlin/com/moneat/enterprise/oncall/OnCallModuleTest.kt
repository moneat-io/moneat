// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.alerts.services.AlertRouteFanout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import io.ktor.server.application.Application
import org.junit.jupiter.api.Test
import org.koin.core.KoinApplication
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OnCallModuleTest {
    @Test
    fun `provides synchronous alert route fanout without starting background jobs`() {
        val module = OnCallModule()
        val application = KoinApplication.init().apply { modules(module.koinModules()) }
        module.startBackgroundJobs(mockk<Application>(), startSchedulers = false, startIngestionWorkers = false)

        assertNotNull(application.koin.get<AlertRouteFanout>())
        application.close()
    }

    @Test
    fun `registers API routes before scheduler jobs start`() {
        val previousFrontendUrl = System.getProperty("FRONTEND_URL")
        System.setProperty("FRONTEND_URL", "https://app.moneat.test")
        try {
            testApplication {
                application {
                    install(Authentication) {
                        jwt("auth-jwt") {
                            verifier(
                                JWT
                                    .require(Algorithm.HMAC256("on-call-module-test-secret"))
                                    .build(),
                            )
                            validate { JWTPrincipal(it.payload) }
                        }
                    }
                    routing {
                        OnCallModule().registerRoutes(this)
                    }
                }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/on-call/schedules").status)
            }
        } finally {
            if (previousFrontendUrl == null) {
                System.clearProperty("FRONTEND_URL")
            } else {
                System.setProperty("FRONTEND_URL", previousFrontendUrl)
            }
        }
    }
}
