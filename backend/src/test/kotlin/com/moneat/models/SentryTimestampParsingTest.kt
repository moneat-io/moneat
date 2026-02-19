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

@file:Suppress("USELESS_CAST", "UNNECESSARY_NOT_NULL_ASSERTION", "UNNECESSARY_SAFE_CALL")

package com.moneat.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SentryTimestampParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `transaction timestamps decode from ISO-8601 strings`() {
        val payload = """
            {
              "event_id": "abc3035cf03042628ee6514d915711b8",
              "type": "transaction",
              "transaction": "GET /api/health",
              "start_timestamp": "2026-02-13T22:06:38.84660646Z",
              "timestamp": "2026-02-13T22:06:39.24660646Z",
              "spans": [
                {
                  "span_id": "a1b2c3d4e5f67890",
                  "op": "db",
                  "start_timestamp": "2026-02-13T22:06:38.94660646Z",
                  "timestamp": "2026-02-13T22:06:39.04660646Z"
                }
              ]
            }
        """.trimIndent()

        val transaction = json.decodeFromString<SentryTransaction>(payload)

        val startTs = transaction.start_timestamp
        val endTs = transaction.timestamp
        assertNotNull(startTs)
        assertNotNull(endTs)
        assertTrue(endTs >= startTs)
        assertNotNull(transaction.spans?.firstOrNull()?.start_timestamp)
        assertNotNull(transaction.spans?.firstOrNull()?.timestamp)
    }

    @Test
    fun `replay timestamps decode from ISO-8601 strings`() {
        val payload = """
            {
              "replay_id": "7f0f9f17-97b8-4fe3-9b8d-f4db94f4f687",
              "segment_id": 1,
              "timestamp": "2026-02-13T22:06:38.84660646Z",
              "replay_start_timestamp": "2026-02-13T22:06:00.000000000Z"
            }
        """.trimIndent()

        val replayEvent = json.decodeFromString<SentryReplayEvent>(payload)

        val replayTs = replayEvent.timestamp
        val replayStartTs = replayEvent.replay_start_timestamp
        assertNotNull(replayTs)
        assertNotNull(replayStartTs)
        assertTrue(replayTs >= replayStartTs)
    }
}
