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
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
