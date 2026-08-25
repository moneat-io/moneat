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

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.OnCallBridge
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.notifications.services.SlackInboundGateway
import com.moneat.notifications.services.SlackInboundRequestType
import com.moneat.org.routes.integrationCallbackRoutes
import com.moneat.shared.models.SlackInboundDeliveries
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.startTestKoin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationSlackCallbackRoutesTest {
    companion object {
        private const val SECRET = "integration-route-secret"
        private var database: Database? = null
    }

    @BeforeTest
    fun setUp() {
        startTestKoin()
        mockkObject(IngestionQueueClient)
        every { IngestionQueueClient.enqueue(any(), any(), any()) } returns "1-0"
        if (database == null) {
            database = Database.connect(
                url = "jdbc:h2:mem:moneat_slack_callback_routes;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = database
        TestDatabaseHelper.resetSchema(SlackInboundDeliveries)
        loadKoinModules(
            module {
                single<SlackInboundGateway> { SlackInboundGateway(signingSecret = SECRET) }
            },
        )
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(FeatureRegistry)
        unmockkObject(IngestionQueueClient)
    }

    @Test
    fun `routes a fresh Slack delivery through the on-call bridge and accepts retries`() = testApplication {
        val bridge = mockk<OnCallBridge>()
        coEvery { bridge.handleSlackInbound(any(), any(), any()) } returns "{\"response_type\":\"ephemeral\"}"
        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns bridge

        application {
            installJwtAuth()
            routing { integrationCallbackRoutes() }
        }

        val body = "team_id=T1&user_id=U1&trigger_id=TR1"
        val headers = signedHeaders(body)
        val first = client.post("/integrations/slack/commands") {
            headers.forEach { key, values -> header(key, values.single()) }
            setBody(body)
        }
        val retry = client.post("/integrations/slack/commands") {
            headers.forEach { key, values -> header(key, values.single()) }
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("{\"response_type\":\"ephemeral\"}", first.bodyAsText())
        assertEquals(HttpStatusCode.Accepted, retry.status)
        assertTrue(retry.bodyAsText().contains("duplicate"))
    }

    @Test
    fun `returns accepted when the bridge declines a fresh Slack delivery`() = testApplication {
        val bridge = mockk<OnCallBridge>()
        coEvery { bridge.handleSlackInbound(any(), any(), any()) } returns null
        mockkObject(FeatureRegistry)
        every { FeatureRegistry.getOnCallBridge() } returns bridge

        application {
            installJwtAuth()
            routing { integrationCallbackRoutes() }
        }

        val body = "team_id=T2&user_id=U2&trigger_id=TR2"
        val response = client.post("/integrations/slack/commands") {
            signedHeaders(body).forEach { key, values -> header(key, values.single()) }
            setBody(body)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains(SlackInboundRequestType.COMMAND.wire))
    }

    private fun signedHeaders(body: String) =
        (System.currentTimeMillis() / 1_000L).toString().let { timestamp ->
            headersOf(
                "X-Slack-Request-Timestamp" to listOf(timestamp),
                "X-Slack-Signature" to listOf(signature(body, timestamp)),
                HttpHeaders.ContentType to listOf("application/x-www-form-urlencoded"),
            )
        }

    private fun signature(body: String, timestamp: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("v0:$timestamp:$body".toByteArray())
        return "v0=" + digest.joinToString("") { "%02x".format(it) }
    }
}
