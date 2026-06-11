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

package com.moneat.ingestion.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngestionQueueSettingsTest {

    @Test
    fun `queue backend defaults to redis lists`() {
        assertEquals(IngestionQueueBackend.REDIS_LIST, IngestionQueueBackend.from(null))
        assertEquals(IngestionQueueBackend.REDIS_LIST, IngestionQueueBackend.from(""))
        assertEquals(IngestionQueueBackend.REDIS_LIST, IngestionQueueBackend.from("redis-list"))
    }

    @Test
    fun `queue backend accepts redis stream aliases`() {
        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueBackend.from("redis-streams"))
        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueBackend.from("redis_streams"))
        assertEquals(IngestionQueueBackend.REDIS_STREAMS, IngestionQueueBackend.from(" stream "))
    }

    @Test
    fun `read mode follows backend when unset`() {
        assertEquals(
            IngestionQueueReadMode.LIST,
            IngestionQueueReadMode.from(null, IngestionQueueBackend.REDIS_LIST)
        )
        assertEquals(
            IngestionQueueReadMode.STREAMS,
            IngestionQueueReadMode.from(null, IngestionQueueBackend.REDIS_STREAMS)
        )
    }

    @Test
    fun `read mode accepts explicit transition values`() {
        assertEquals(
            IngestionQueueReadMode.DUAL,
            IngestionQueueReadMode.from("dual", IngestionQueueBackend.REDIS_LIST)
        )
        assertEquals(
            IngestionQueueReadMode.STREAMS,
            IngestionQueueReadMode.from("redis-streams", IngestionQueueBackend.REDIS_LIST)
        )
        assertEquals(
            IngestionQueueReadMode.LIST,
            IngestionQueueReadMode.from("redis-list", IngestionQueueBackend.REDIS_STREAMS)
        )
    }

    @Test
    fun `pipeline parser accepts ids and enum names`() {
        assertEquals(IngestionPipeline.LOGS, IngestionPipeline.parse("logs"))
        assertEquals(IngestionPipeline.OTLP_TRACES, IngestionPipeline.parse("otlp-traces"))
        assertEquals(IngestionPipeline.DD_SECURITY, IngestionPipeline.parse("DD_SECURITY"))
        assertNull(IngestionPipeline.parse("unknown"))
    }
}
