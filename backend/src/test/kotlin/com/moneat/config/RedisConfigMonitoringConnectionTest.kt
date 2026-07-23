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

package com.moneat.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RedisConfigMonitoringConnectionTest {
    @Test
    fun `keeps an open monitoring connection`() {
        val current = mockk<StatefulRedisConnection<String, String>>()
        val client = mockk<RedisClient>()
        every { current.isOpen } returns true

        assertSame(current, reconnectClosedMonitoringConnection(current, client))
        verify(exactly = 0) { client.connect() }
    }

    @Test
    fun `replaces a closed monitoring connection`() {
        val current = mockk<StatefulRedisConnection<String, String>>()
        val replacement = mockk<StatefulRedisConnection<String, String>>()
        val client = mockk<RedisClient>()
        every { current.isOpen } returns false
        every { current.close() } just runs
        every { client.connect() } returns replacement
        every { replacement.timeout = any() } just runs

        assertSame(replacement, reconnectClosedMonitoringConnection(current, client))
        verify(exactly = 1) { current.close() }
        verify(exactly = 1) { client.connect() }
        verify(exactly = 1) { replacement.timeout = any() }
    }

    @Test
    fun `returns null when a monitoring reconnect fails`() {
        val client = mockk<RedisClient>()
        every { client.connect() } throws IllegalStateException("redis unavailable")

        assertNull(reconnectClosedMonitoringConnection(null, client))
    }

    @Test
    fun `classifies monitoring connection failures for recovery`() {
        assertTrue(isMonitoringConnectionFailure(RedisCommandTimeoutException("timed out")))
        assertTrue(isMonitoringConnectionFailure(RedisConnectionException("disconnected")))
        assertFalse(isMonitoringConnectionFailure(RedisCommandExecutionException("NOGROUP")))
    }

    @Test
    fun `preserves redis command execution failures`() {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        val commands = mockk<RedisCommands<String, String>>()
        val failure = RedisCommandExecutionException("NOGROUP")
        every { connection.sync() } returns commands

        val thrown = assertFailsWith<RedisCommandExecutionException> {
            executeMonitoringRead(connection) { throw failure }
        }

        assertSame(failure, thrown)
    }

    @Test
    fun `returns the failed connection for a monitoring timeout`() {
        val connection = mockk<StatefulRedisConnection<String, String>>()
        val commands = mockk<RedisCommands<String, String>>()
        every { connection.sync() } returns commands

        val result = executeMonitoringRead(connection) {
            throw RedisCommandTimeoutException("timed out")
        }

        assertEquals(MonitoringRead.ConnectionFailure(connection), result)
    }

    @Test
    fun `returns a successful monitoring read without reconnecting`() {
        val reconnect = mockk<() -> Unit>(relaxed = true)
        val primaryRead = mockk<() -> String>(relaxed = true)

        val result = readMonitoringWithRecovery(
            read = { MonitoringRead.Success("monitoring") },
            invalidate = {},
            reconnect = reconnect,
            primaryRead = primaryRead,
        )

        assertEquals("monitoring", result)
        verify(exactly = 0) { reconnect() }
        verify(exactly = 0) { primaryRead() }
    }

    @Test
    fun `invalidates a failed monitoring read and returns the retry`() {
        val failed = mockk<StatefulRedisConnection<String, String>>()
        val invalidated = mutableListOf<StatefulRedisConnection<String, String>>()
        var attempt = 0

        val result = readMonitoringWithRecovery(
            read = {
                attempt += 1
                if (attempt == 1) MonitoringRead.ConnectionFailure(failed) else MonitoringRead.Success("retry")
            },
            invalidate = invalidated::add,
            reconnect = {},
            primaryRead = { "primary" },
        )

        assertEquals("retry", result)
        assertEquals(listOf(failed), invalidated)
        assertEquals(2, attempt)
    }

    @Test
    fun `falls back to primary when a retried monitoring read fails`() {
        val first = mockk<StatefulRedisConnection<String, String>>()
        val second = mockk<StatefulRedisConnection<String, String>>()
        val invalidated = mutableListOf<StatefulRedisConnection<String, String>>()
        var attempt = 0

        val result = readMonitoringWithRecovery(
            read = {
                attempt += 1
                MonitoringRead.ConnectionFailure(if (attempt == 1) first else second)
            },
            invalidate = invalidated::add,
            reconnect = {},
            primaryRead = { "primary" },
        )

        assertEquals("primary", result)
        assertEquals(listOf(first, second), invalidated)
        assertEquals(2, attempt)
    }

    @Test
    fun `falls back to primary when no monitoring connection is available`() {
        val result = readMonitoringWithRecovery(
            read = { null },
            invalidate = {},
            reconnect = {},
            primaryRead = { "primary" },
        )

        assertEquals("primary", result)
    }
}
