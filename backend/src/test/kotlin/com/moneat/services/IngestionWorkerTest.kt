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

import com.moneat.config.RedisConfig
import com.moneat.events.services.IngestionWorker
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IngestionWorkerTest {
    @Test
    fun `encode and decode message roundtrip preserves project and payload`() {
        val projectId = 987654321L
        val payload = """{"event_id":"evt-1"}""".toByteArray()

        val encoded = IngestionWorker.encodeMessage(projectId, payload)
        val (decodedProjectId, decodedPayload) = IngestionWorker.decodeMessage(encoded)

        assertEquals(projectId, decodedProjectId)
        assertTrue(payload.contentEquals(decodedPayload))
    }

    @Test
    fun `decode message throws for too-short payload`() {
        val tooShort =
            java.util.Base64
                .getEncoder()
                .encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertFailsWith<IllegalArgumentException> {
            IngestionWorker.decodeMessage(tooShort)
        }
    }

    @Test
    fun `worker sends message to DLQ when base64 decode fails`() =
        runBlocking {
            val worker = IngestionWorker("q:test", "q:test:dlq", 1)
            val redis = mockDlqStream()
            val badMessage = "!!!not-base64!!!"

            try {
                worker.processMessageForTest(workerId = 1, value = badMessage)

                verify(exactly = 1) {
                    redis.xadd("q:test:dlq:stream", any<XAddArgs>(), any<Map<String, String>>())
                }
            } finally {
                unmockkObject(RedisConfig)
            }
        }

    @Test
    fun `worker sends message to DLQ when envelope parse fails`() =
        runBlocking {
            val worker = IngestionWorker("q:test", "q:test:dlq", 1)
            val redis = mockDlqStream()
            val badEnvelopeMessage = IngestionWorker.encodeMessage(42L, "invalid-envelope".toByteArray())

            try {
                worker.processMessageForTest(workerId = 2, value = badEnvelopeMessage)

                verify(exactly = 1) {
                    redis.xadd("q:test:dlq:stream", any<XAddArgs>(), any<Map<String, String>>())
                }
            } finally {
                unmockkObject(RedisConfig)
            }
        }

    private fun mockDlqStream(): RedisCommands<String, String> {
        val redis = mockk<RedisCommands<String, String>>()
        mockkObject(RedisConfig)
        every { RedisConfig.sync() } returns redis
        every { redis.xadd("q:test:dlq:stream", any<XAddArgs>(), any<Map<String, String>>()) } returns "1-0"
        return redis
    }
}
