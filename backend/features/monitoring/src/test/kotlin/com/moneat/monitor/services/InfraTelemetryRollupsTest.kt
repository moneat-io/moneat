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

package com.moneat.monitor.services

import com.moneat.config.ClickHouseClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfraTelemetryRollupsTest {
    @BeforeTest
    fun setUp() {
        mockkObject(ClickHouseClient)
        every { ClickHouseClient.getDatabase() } returns "testdb"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(ClickHouseClient)
    }

    @Test
    fun `insertMetricRollups writes disk metric identity and preserves tags`() = runBlocking {
        val queries = mutableListOf<String>()
        val response = mockk<HttpResponse>()
        every { response.status } returns HttpStatusCode.OK
        coEvery { ClickHouseClient.execute(capture(queries)) } returns response

        InfraTelemetryRollups.insertMetricRollups(
            listOf(
                InfraMetricRollupRow(
                    organizationId = 7,
                    metricName = "system.disk.percent",
                    timestampMs = 1_720_000_000_000,
                    value = 94.2,
                    host = "ubuntu1",
                    tags = mapOf(
                        "host_id" to "42",
                        "device_name" to "/dev/nvme0n1p1",
                        "mount_point" to "/",
                        "filesystem" to "ext4",
                    ),
                    unit = "%",
                    source = "otel",
                ),
                InfraMetricRollupRow(
                    organizationId = 7,
                    metricName = "system.cpu.user",
                    timestampMs = 1_720_000_000_000,
                    value = 12.5,
                    host = "ubuntu1",
                    tags = mapOf("host_id" to "42"),
                    unit = "%",
                    source = "otel",
                ),
            )
        )

        assertEquals(2, queries.size)
        assertTrue(queries[0].contains("metrics_latest_by_host"))
        assertTrue(queries[1].contains("metrics_rollup_1m"))

        queries.forEach { query ->
            assertTrue(query.contains("'system.disk.percent'"))
            assertTrue(query.contains("'system.cpu.user'"))
            assertTrue(query.contains("'device_name=/dev/nvme0n1p1|mount_point=/|filesystem=ext4'"))
            assertTrue(query.contains("'system.cpu.user',"))
            assertTrue(query.contains("'',"))
            assertTrue(query.contains("'device_name', '/dev/nvme0n1p1'"))
            assertTrue(query.contains("'mount_point', '/'"))
            assertTrue(query.contains("'filesystem', 'ext4'"))
        }

        coVerify(exactly = 2) { ClickHouseClient.execute(any()) }
    }

    @Test
    fun `insertMetricRollups skips unsupported metrics and rows without host identity`() = runBlocking {
        val querySlot = slot<String>()
        val response = mockk<HttpResponse>()
        every { response.status } returns HttpStatusCode.OK
        coEvery { ClickHouseClient.execute(capture(querySlot)) } returns response

        InfraTelemetryRollups.insertMetricRollups(
            listOf(
                InfraMetricRollupRow(
                    organizationId = 7,
                    metricName = "custom.metric",
                    timestampMs = 1_720_000_000_000,
                    value = 1.0,
                    host = "ubuntu1",
                    tags = mapOf("host_id" to "42"),
                    unit = "count",
                    source = "otel",
                ),
                InfraMetricRollupRow(
                    organizationId = 7,
                    metricName = "system.disk.used",
                    timestampMs = 1_720_000_000_000,
                    value = 1.0,
                    host = "ubuntu1",
                    tags = emptyMap(),
                    unit = "bytes",
                    source = "otel",
                ),
            )
        )

        coVerify(exactly = 0) { ClickHouseClient.execute(any()) }
    }
}
