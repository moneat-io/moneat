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

package com.moneat.featureflags

import com.moneat.config.ClickHouseClient
import com.moneat.featureflags.models.FeatureFlagSdkKeyPrincipal
import com.moneat.featureflags.models.OfrepFlagEvaluationResponse
import com.moneat.featureflags.models.OfrepTrackRequest
import com.moneat.featureflags.services.FeatureFlagEventService
import io.ktor.client.statement.HttpResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EVENT_TEST_ORG_ID = 41
private const val EVENT_TEST_ENVIRONMENT_ID = 7

class FeatureFlagEventServiceTest {
    private val principal = FeatureFlagSdkKeyPrincipal(
        organizationId = EVENT_TEST_ORG_ID,
        environmentId = EVENT_TEST_ENVIRONMENT_ID,
        environmentKey = "production",
        keyType = "client",
        keyPrefix = "mffpk_testprefix",
    )

    @Test
    fun `records evaluation and tracking events in clickhouse`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.isInitialized() } returns true
            coEvery { ClickHouseClient.execute(capture(queries), any()) } returns mockk<HttpResponse>(relaxed = true)

            val service = FeatureFlagEventService()
            service.recordEvaluation(
                principal = principal,
                response = OfrepFlagEvaluationResponse(
                    key = "flag'one",
                    value = JsonPrimitive(true),
                    variant = "on",
                    reason = "STATIC",
                    metadata = mapOf("valueType" to "BOOLEAN"),
                ),
                context = context("user-123"),
                durationMs = 12.5,
            )
            service.recordTrackingEvent(
                principal = principal,
                request = OfrepTrackRequest(
                    eventName = "checkout.started",
                    context = context("user-123"),
                    value = 99.5,
                    flagKey = "flag'one",
                    variant = "on",
                    properties = JsonObject(mapOf("plan" to JsonPrimitive("pro"))),
                )
            )

            assertEquals(2, queries.size)
            assertTrue(queries.first().contains("INSERT INTO feature_flag_evaluations"))
            assertTrue(queries.first().contains("'flag\\'one'"))
            assertTrue(queries.first().contains("'user-123'"))
            assertTrue(queries.last().contains("INSERT INTO feature_flag_tracking_events"))
            assertTrue(queries.last().contains("'checkout.started'"))
            assertTrue(queries.last().contains("99.5"))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `records missing optional tracking fields as clickhouse nulls`() = runBlocking {
        val queries = mutableListOf<String>()
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.isInitialized() } returns true
            coEvery { ClickHouseClient.execute(capture(queries), any()) } returns mockk<HttpResponse>(relaxed = true)

            FeatureFlagEventService().recordTrackingEvent(
                principal = principal,
                request = OfrepTrackRequest(eventName = "checkout.started")
            )

            assertEquals(1, queries.size)
            val query = queries.single()
            assertTrue(query.contains("INSERT INTO feature_flag_tracking_events"))
            assertEquals(3, Regex("""\bNULL\b""").findAll(query).count())
            assertTrue(query.contains("'{}'"))
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `skips event recording when clickhouse is unavailable`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.isInitialized() } returns false

            val service = FeatureFlagEventService()
            service.recordEvaluation(
                principal = principal,
                response = OfrepFlagEvaluationResponse(
                    key = "flag-one",
                    value = JsonPrimitive(false),
                    variant = "off",
                    reason = "DISABLED",
                ),
                context = JsonObject(emptyMap()),
                durationMs = 0.0,
            )
            service.recordTrackingEvent(principal, OfrepTrackRequest(eventName = "ignored"))

            coVerify(exactly = 0) { ClickHouseClient.execute(any<String>(), any()) }
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    private fun context(targetingKey: String): JsonObject =
        JsonObject(mapOf("targetingKey" to JsonPrimitive(targetingKey)))
}
