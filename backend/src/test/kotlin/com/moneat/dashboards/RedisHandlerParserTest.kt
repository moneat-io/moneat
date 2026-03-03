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

package com.moneat.dashboards

import com.moneat.dashboards.services.handlers.RedisHandler
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedisHandlerParserTest {

    @Test
    fun `parseInfoOutput parses key-value lines`() {
        val raw = """
            # Stats
            instantaneous_ops_per_sec:12
            total_connections_received:100
            keyspace_hits:5000
        """.trimIndent()
        val result = RedisHandler.parseInfoOutput(raw)
        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(JsonPrimitive(12L), row["instantaneous_ops_per_sec"])
        assertEquals(JsonPrimitive(100L), row["total_connections_received"])
        assertEquals(JsonPrimitive(5000L), row["keyspace_hits"])
    }

    @Test
    fun `parseInfoOutput skips section headers and blank lines`() {
        val raw = "# Server\n\nredis_version:7.0.0\nos:Linux\n"
        val result = RedisHandler.parseInfoOutput(raw)
        assertEquals(1, result.size)
        assertEquals(JsonPrimitive("7.0.0"), result[0]["redis_version"])
        assertEquals(JsonPrimitive("Linux"), result[0]["os"])
    }

    @Test
    fun `parseInfoOutput handles doubles`() {
        val raw = "used_cpu_sys:1.234"
        val result = RedisHandler.parseInfoOutput(raw)
        assertEquals(JsonPrimitive(1.234), result[0]["used_cpu_sys"])
    }

    @Test
    fun `parseInfoOutput returns empty list for empty input`() {
        assertTrue(RedisHandler.parseInfoOutput("").isEmpty())
        assertTrue(RedisHandler.parseInfoOutput("# Server\n# Clients\n").isEmpty())
    }

    @Test
    fun `parseClientList parses space-separated key=value lines`() {
        val raw = "id=1 addr=127.0.0.1:6379 fd=5 name=test\n" +
            "id=2 addr=127.0.0.1:6380 fd=6 name=worker"
        val result = RedisHandler.parseClientList(raw)
        assertEquals(2, result.size)
        assertEquals(JsonPrimitive("1"), result[0]["id"])
        assertEquals(JsonPrimitive("127.0.0.1:6379"), result[0]["addr"])
        assertEquals(JsonPrimitive("test"), result[0]["name"])
        assertEquals(JsonPrimitive("2"), result[1]["id"])
    }

    @Test
    fun `parseClientList handles empty input`() {
        assertTrue(RedisHandler.parseClientList("").isEmpty())
    }

    @Test
    fun `parseSlowlog parses list entries`() {
        val entries: List<Any> = listOf(
            listOf(1L, 1700000000L, 500L, listOf("GET", "mykey")),
            listOf(2L, 1700000001L, 1200L, listOf("HGETALL", "myhash"))
        )
        val result = RedisHandler.parseSlowlog(entries)
        assertEquals(2, result.size)
        assertEquals(JsonPrimitive(1L), result[0]["Id"])
        assertEquals(JsonPrimitive(1700000000L), result[0]["Timestamp"])
        assertEquals(JsonPrimitive(500L), result[0]["Duration"])
        assertEquals(JsonPrimitive("GET mykey"), result[0]["Command"])
        assertEquals(JsonPrimitive(2L), result[1]["Id"])
        assertEquals(
            JsonPrimitive("HGETALL myhash"),
            result[1]["Command"]
        )
    }

    @Test
    fun `parseSlowlog handles empty input`() {
        assertTrue(RedisHandler.parseSlowlog(emptyList()).isEmpty())
    }

    @Test
    fun `parseSlowlog skips malformed entries`() {
        val entries: List<Any> = listOf("not a list", 42)
        assertTrue(RedisHandler.parseSlowlog(entries).isEmpty())
    }

    @Test
    fun `parseClusterNodes parses node lines`() {
        val raw = "abc123 127.0.0.1:6379@16379 master - 0 100 1 connected 0-5460\n" +
            "def456 127.0.0.1:6380@16380 slave abc123 0 101 1 connected"
        val result = RedisHandler.parseClusterNodes(raw)
        assertEquals(2, result.size)
        assertEquals(JsonPrimitive("abc123"), result[0]["Id"])
        assertEquals(
            JsonPrimitive("127.0.0.1:6379@16379"),
            result[0]["Address"]
        )
        assertEquals(JsonPrimitive("master"), result[0]["Flags"])
        assertEquals(JsonPrimitive("connected"), result[1]["Link"])
    }

    @Test
    fun `parseClusterNodes handles empty input`() {
        assertTrue(RedisHandler.parseClusterNodes("").isEmpty())
    }

    @Test
    fun `parseCommandStats parses cmdstat lines`() {
        val raw = """
            # Commandstats
            cmdstat_get:calls=100,usec=500,usec_per_call=5.00
            cmdstat_set:calls=50,usec=300,usec_per_call=6.00
        """.trimIndent()
        val result = RedisHandler.parseCommandStats(raw)
        assertEquals(2, result.size)
        assertEquals(JsonPrimitive("get"), result[0]["Command"])
        assertEquals(JsonPrimitive(100L), result[0]["Calls"])
        assertEquals(JsonPrimitive(500L), result[0]["Usec"])
        assertEquals(JsonPrimitive(5.0), result[0]["Usec_per_call"])
        assertEquals(JsonPrimitive("set"), result[1]["Command"])
    }

    @Test
    fun `parseCommandStats handles empty input`() {
        assertTrue(RedisHandler.parseCommandStats("").isEmpty())
        assertTrue(
            RedisHandler.parseCommandStats("# Commandstats\n").isEmpty()
        )
    }
}
