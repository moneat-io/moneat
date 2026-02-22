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

import com.moneat.uptime.models.UptimeMonitorData
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class UptimeCheckExecutorTest {
    private val executor = UptimeCheckExecutor()

    private fun monitor(
        type: String,
        url: String? = null,
        hostname: String? = null,
        port: Int? = null,
        dbConnectionString: String? = null
    ): UptimeMonitorData {
        val now = Clock.System.now()
        return UptimeMonitorData(
            id = UUID.randomUUID(),
            organizationId = 1,
            name = "test-$type",
            type = type,
            active = true,
            url = url,
            hostname = hostname,
            port = port,
            dbConnectionString = dbConnectionString,
            intervalSeconds = 60,
            timeoutSeconds = 1,
            retries = 0,
            retryIntervalSeconds = 1,
            status = "pending",
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `executeCheck returns pending for push monitors`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "push"))
            assertEquals(2, result.status)
            assertTrue(result.message.contains("don't perform active checks"))
        }

    @Test
    fun `executeCheck returns unknown type error for unsupported monitor types`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "unsupported"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("Unknown monitor type"))
        }

    @Test
    fun `executeCheck fails http monitor without url`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "http", url = null))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No URL configured"))
        }

    @Test
    fun `executeCheck fails tcp monitor without hostname`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "tcp", hostname = null, port = 443))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No hostname configured"))
        }

    @Test
    fun `executeCheck fails database monitor without connection string`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "database", dbConnectionString = null))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No connection string configured"))
        }
}
