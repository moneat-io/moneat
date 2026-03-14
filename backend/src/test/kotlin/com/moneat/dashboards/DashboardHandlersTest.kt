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

import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import com.moneat.dashboards.services.handlers.CloudWatchHandler
import com.moneat.dashboards.services.handlers.ElasticsearchHandler
import com.moneat.dashboards.services.handlers.GraphiteHandler
import com.moneat.dashboards.services.handlers.InfluxDBHandler
import com.moneat.dashboards.services.handlers.JdbcHandlerCommon
import com.moneat.dashboards.services.handlers.LokiHandler
import com.moneat.dashboards.services.handlers.MySQLHandler
import com.moneat.dashboards.services.handlers.PostgresHandler
import com.moneat.dashboards.services.handlers.PrometheusHandler
import com.moneat.dashboards.services.handlers.UnsupportedHandler
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardHandlersTest {

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 9090
        private const val DEFAULT_LIMIT = 100
        private const val DEFAULT_ROW_ID = 1L

        private val selfHostedLock = Any()

        fun <T> withSelfHosted(block: () -> T): T {
            synchronized(selfHostedLock) {
                val prev = System.getProperty("SELF_HOSTED")
                try {
                    System.setProperty("SELF_HOSTED", "true")
                    return block()
                } finally {
                    if (prev != null) {
                        System.setProperty("SELF_HOSTED", prev)
                    } else {
                        System.clearProperty("SELF_HOSTED")
                    }
                }
            }
        }

        fun extractPort(baseUrl: String): Int {
            val port = URI.create(baseUrl).port
            if (port == -1) {
                throw IllegalArgumentException("URL has no explicit port: $baseUrl")
            }
            return port
        }
    }

    private val prometheusHandler = PrometheusHandler()
    private val postgresHandler = PostgresHandler(ConcurrentHashMap())
    private val mysqlHandler = MySQLHandler(ConcurrentHashMap())

    // ==================== UnsupportedHandler ====================

    @Test
    fun `UnsupportedHandler testConnection returns not implemented`() =
        runBlocking {
            val handler = UnsupportedHandler("TestDB")
            val result = handler.testConnection(
                TestConnectionRequest(
                    sourceType = "testdb",
                    host = "localhost"
                )
            )
            assertFalse(result.success)
            assertContains(result.message, "not yet implemented")
        }

    @Test
    fun `UnsupportedHandler executeQuery throws`() {
        val handler = UnsupportedHandler("TestDB")
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                handler.executeQuery(
                    DEFAULT_ROW_ID,
                    "h",
                    null,
                    null,
                    DataSourceCredentials(),
                    "q",
                    10,
                    null
                )
            }
        }
    }

    @Test
    fun `UnsupportedHandler getSchema throws`() {
        val handler = UnsupportedHandler("TestDB")
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                handler.getSchema(
                    "h",
                    null,
                    null,
                    DataSourceCredentials()
                )
            }
        }
    }

    // ==================== HttpApiHandler URL building ====================

    @Test
    fun `buildUrlString normalizes bare IPv6 literal`() {
        val url = prometheusHandler.buildUrlString(
            "2001:db8::1",
            DEFAULT_PORT
        )
        assertEquals("http://[2001:db8::1]:$DEFAULT_PORT", url)
    }

    @Test
    fun `buildUrlString preserves bracketed IPv6`() {
        val url = prometheusHandler.buildUrlString(
            "[2001:db8::1]",
            DEFAULT_PORT
        )
        assertEquals("http://[2001:db8::1]:$DEFAULT_PORT", url)
    }

    @Test
    fun `buildUrlString omits default http port 80`() {
        val url = prometheusHandler.buildUrlString("example.com", 80)
        assertEquals("http://example.com", url)
    }

    @Test
    fun `buildUrlString skips port when host already has port`() {
        val url = prometheusHandler.buildUrlString(
            "[::1]:$DEFAULT_PORT",
            3000
        )
        assertEquals("http://[::1]:$DEFAULT_PORT", url)
    }

    // ==================== PrometheusHandler: internal parsing ====================

    @Test
    fun `parsePrometheusResponse handles NaN as null`() {
        val body = """{"status":"success","data":{"resultType":"vector",""" +
            """"result":[{"metric":{"__name__":"m"},""" +
            """"value":[1,"NaN"]}]}}"""
        val rows = prometheusHandler.parsePrometheusResponse(body, DEFAULT_LIMIT)
        assertEquals(1, rows.size)
        assertEquals(JsonNull, rows[0]["m"])
    }

    @Test
    fun `parsePrometheusResponse handles positive Inf as null`() {
        val body = """{"status":"success","data":{"resultType":"vector",""" +
            """"result":[{"metric":{"__name__":"m"},""" +
            """"value":[1,"+Inf"]}]}}"""
        val rows = prometheusHandler.parsePrometheusResponse(body, DEFAULT_LIMIT)
        assertEquals(JsonNull, rows[0]["m"])
    }

    @Test
    fun `parsePrometheusResponse handles negative Inf as null`() {
        val body = """{"status":"success","data":{"resultType":"vector",""" +
            """"result":[{"metric":{"__name__":"m"},""" +
            """"value":[1,"-Inf"]}]}}"""
        val rows = prometheusHandler.parsePrometheusResponse(body, DEFAULT_LIMIT)
        assertEquals(JsonNull, rows[0]["m"])
    }

    @Test
    fun `parsePrometheusResponse returns empty for missing data`() {
        val body = """{"status":"error","errorType":"bad_data"}"""
        val result = prometheusHandler.parsePrometheusResponse(body, DEFAULT_LIMIT)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parsePrometheusResponse returns empty for zero limit`() {
        val body = """{"status":"success","data":{"resultType":"vector",""" +
            """"result":[{"metric":{"__name__":"m"},""" +
            """"value":[1,"1"]}]}}"""
        assertTrue(
            prometheusHandler.parsePrometheusResponse(body, 0).isEmpty()
        )
    }

    @Test
    fun `parsePrometheusResponse matrix with multiple series`() {
        val body = """{"status":"success","data":{"resultType":"matrix",""" +
            """"result":[""" +
            """{"metric":{"__name__":"a"},"values":[[1,"10"]]},""" +
            """{"metric":{"__name__":"b"},"values":[[2,"20"]]}]}}"""
        val rows = prometheusHandler.parsePrometheusResponse(body, DEFAULT_LIMIT)
        assertEquals(2, rows.size)
        assertEquals(JsonPrimitive(10.0), rows[0]["a"])
        assertEquals(JsonPrimitive(20.0), rows[1]["b"])
    }

    @Test
    fun `parsePrometheusResponse vector without name uses value`() {
        val body = """{"status":"success","data":{"resultType":"vector",""" +
            """"result":[{"metric":{"job":"api"},""" +
            """"value":[1700000000,"42"]}]}}"""
        val rows = prometheusHandler.parsePrometheusResponse(body, DEFAULT_LIMIT)
        assertEquals(1, rows.size)
        assertEquals(JsonPrimitive(42.0), rows[0]["value"])
        assertEquals(JsonPrimitive("api"), rows[0]["job"])
    }

    // ==================== PrometheusHandler: time resolution ====================

    @Test
    fun `resolveRelativeTimeSec handles seconds`() {
        assertEquals(
            999970L,
            prometheusHandler.resolveRelativeTimeSec("now-30s", 1000000L)
        )
    }

    @Test
    fun `resolveRelativeTimeSec handles minutes`() {
        assertEquals(
            999700L,
            prometheusHandler.resolveRelativeTimeSec("now-5m", 1000000L)
        )
    }

    @Test
    fun `resolveRelativeTimeSec handles weeks`() {
        val n = 1000000L
        assertEquals(
            n - 604800,
            prometheusHandler.resolveRelativeTimeSec("now-1w", n)
        )
    }

    @Test
    fun `resolveRelativeTimeSec handles months`() {
        val n = 1000000L
        assertEquals(
            n - 2592000,
            prometheusHandler.resolveRelativeTimeSec("now-1M", n)
        )
    }

    @Test
    fun `resolveRelativeTimeSec handles years`() {
        val n = 1000000L
        assertEquals(
            n - 31536000,
            prometheusHandler.resolveRelativeTimeSec("now-1y", n)
        )
    }

    @Test
    fun `resolveRelativeTimeSec returns now for invalid input`() {
        assertEquals(
            1000000L,
            prometheusHandler.resolveRelativeTimeSec("garbage", 1000000L)
        )
    }

    // ==================== PrometheusHandler: MockHttpServer ====================

    @Test
    fun `Prometheus testConnection parses metric names`() =
        withSelfHosted {
            runBlocking {
                val resp = """{"status":"success",""" +
                    """"data":["up","process_cpu"]}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val result = PrometheusHandler().testConnection(
                        TestConnectionRequest(
                            sourceType = "prometheus",
                            host = DEFAULT_HOST,
                            port = port
                        )
                    )
                    assertTrue(result.success)
                    assertEquals(
                        listOf("up", "process_cpu"),
                        result.metrics
                    )
                }
            }
        }

    @Test
    fun `Prometheus executeQuery instant without timeRange`() =
        withSelfHosted {
            runBlocking {
                val resp =
                    """{"status":"success","data":{""" +
                        """"resultType":"vector","result":[""" +
                        """{"metric":{"__name__":"up"},""" +
                        """"value":[1700000000,"1"]}]}}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows = PrometheusHandler().executeQuery(
                        DEFAULT_ROW_ID,
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials(),
                        "up",
                        DEFAULT_LIMIT,
                        null
                    )
                    assertEquals(1, rows.size)
                    assertEquals(JsonPrimitive(1.0), rows[0]["up"])
                }
            }
        }

    @Test
    fun `Prometheus executeQuery range with timeRange`() =
        withSelfHosted {
            runBlocking {
                val resp =
                    """{"status":"success","data":{""" +
                        """"resultType":"matrix","result":[""" +
                        """{"metric":{"__name__":"up"},""" +
                        """"values":[[1700000000,"1"],""" +
                        """[1700000060,"1"]]}]}}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows = PrometheusHandler().executeQuery(
                        DEFAULT_ROW_ID,
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials(),
                        "up",
                        DEFAULT_LIMIT,
                        TimeRangeDef("now-1h", "now")
                    )
                    assertEquals(2, rows.size)
                }
            }
        }

    @Test
    fun `Prometheus executeQuery returns empty on server error`() =
        withSelfHosted {
            runBlocking {
                MockHttpServer { ex ->
                    ex.respond(500, "internal error")
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows = PrometheusHandler().executeQuery(
                        DEFAULT_ROW_ID,
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials(),
                        "up",
                        DEFAULT_LIMIT,
                        null
                    )
                    assertTrue(rows.isEmpty())
                }
            }
        }

    @Test
    fun `Prometheus getSchema returns metric fields`() =
        withSelfHosted {
            runBlocking {
                val resp = """{"status":"success",""" +
                    """"data":["cpu","memory"]}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val fields = PrometheusHandler().getSchema(
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials()
                    )
                    assertEquals(2, fields.size)
                    assertEquals("cpu", fields[0].name)
                    assertEquals("gauge", fields[0].type)
                }
            }
        }

    @Test
    fun `Prometheus executeLabelValuesQuery two-arg format`() =
        withSelfHosted {
            runBlocking {
                val resp = """{"status":"success",""" +
                    """"data":["host1","host2"]}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val vals =
                        PrometheusHandler().executeLabelValuesQuery(
                            DEFAULT_HOST,
                            port,
                            DataSourceCredentials(),
                            "label_values(up, instance)"
                        )
                    assertEquals(listOf("host1", "host2"), vals)
                }
            }
        }

    @Test
    fun `Prometheus executeLabelValuesQuery one-arg format`() =
        withSelfHosted {
            runBlocking {
                val resp = """{"status":"success",""" +
                    """"data":["api","worker"]}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val vals =
                        PrometheusHandler().executeLabelValuesQuery(
                            DEFAULT_HOST,
                            port,
                            DataSourceCredentials(),
                            "label_values(job)"
                        )
                    assertEquals(listOf("api", "worker"), vals)
                }
            }
        }

    @Test
    fun `Prometheus executeLabelValuesQuery invalid returns empty`() =
        runBlocking {
            val vals = prometheusHandler.executeLabelValuesQuery(
                "example.com",
                DEFAULT_PORT,
                DataSourceCredentials(),
                "invalid_query"
            )
            assertTrue(vals.isEmpty())
        }

    // ==================== ElasticsearchHandler ====================

    @Test
    fun `Elasticsearch testConnection succeeds on 200`() =
        withSelfHosted {
            runBlocking {
                MockHttpServer { ex ->
                    ex.respond(200, """{"status":"green"}""")
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val result =
                        ElasticsearchHandler().testConnection(
                            TestConnectionRequest(
                                sourceType = "elasticsearch",
                                host = DEFAULT_HOST,
                                port = port
                            )
                        )
                    assertTrue(result.success)
                }
            }
        }

    @Test
    fun `Elasticsearch testConnection fails on 503`() =
        withSelfHosted {
            runBlocking {
                MockHttpServer { ex ->
                    ex.respond(503, """{"error":"down"}""")
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val result =
                        ElasticsearchHandler().testConnection(
                            TestConnectionRequest(
                                sourceType = "elasticsearch",
                                host = DEFAULT_HOST,
                                port = port
                            )
                        )
                    assertFalse(result.success)
                }
            }
        }

    @Test
    fun `Elasticsearch executeQuery parses hits`() =
        withSelfHosted {
            runBlocking {
                val esResp =
                    """{"hits":{"total":{"value":1},"hits":[""" +
                        """{"_id":"d1","_index":"idx",""" +
                        """"_source":{"msg":"err","level":"error"}""" +
                        """}]}}"""
                MockHttpServer { ex ->
                    ex.respond(200, esResp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows =
                        ElasticsearchHandler().executeQuery(
                            DEFAULT_ROW_ID,
                            DEFAULT_HOST,
                            port,
                            "myindex",
                            DataSourceCredentials(),
                            """{"query":{"match_all":{}}}""",
                            DEFAULT_LIMIT,
                            null
                        )
                    assertEquals(1, rows.size)
                    assertEquals(
                        JsonPrimitive("err"),
                        rows[0]["msg"]
                    )
                    assertEquals(
                        JsonPrimitive("error"),
                        rows[0]["level"]
                    )
                    assertEquals(
                        JsonPrimitive("d1"),
                        rows[0]["_id"]
                    )
                    assertEquals(
                        JsonPrimitive("idx"),
                        rows[0]["_index"]
                    )
                }
            }
        }

    @Test
    fun `Elasticsearch executeQuery handles plain text query`() =
        withSelfHosted {
            runBlocking {
                val esResp =
                    """{"hits":{"total":{"value":1},"hits":[""" +
                        """{"_id":"d1","_index":"i",""" +
                        """"_source":{"f":"v"}}]}}"""
                MockHttpServer { ex ->
                    ex.respond(200, esResp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows =
                        ElasticsearchHandler().executeQuery(
                            DEFAULT_ROW_ID,
                            DEFAULT_HOST,
                            port,
                            null,
                            DataSourceCredentials(),
                            "error timeout",
                            DEFAULT_LIMIT,
                            null
                        )
                    assertEquals(1, rows.size)
                    assertEquals(JsonPrimitive("v"), rows[0]["f"])
                }
            }
        }

    @Test
    fun `Elasticsearch executeQuery returns empty on error`() =
        withSelfHosted {
            runBlocking {
                MockHttpServer { ex ->
                    ex.respond(400, """{"error":"bad"}""")
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows =
                        ElasticsearchHandler().executeQuery(
                            DEFAULT_ROW_ID,
                            DEFAULT_HOST,
                            port,
                            null,
                            DataSourceCredentials(),
                            """{"query":{}}""",
                            DEFAULT_LIMIT,
                            null
                        )
                    assertTrue(rows.isEmpty())
                }
            }
        }

    @Test
    fun `Elasticsearch getSchema returns index list`() =
        withSelfHosted {
            runBlocking {
                val resp = """[{"index":"events-2024"},""" +
                    """{"index":"logs-2024"}]"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val fields =
                        ElasticsearchHandler().getSchema(
                            DEFAULT_HOST,
                            port,
                            null,
                            DataSourceCredentials()
                        )
                    assertEquals(2, fields.size)
                    assertEquals("events-2024", fields[0].name)
                    assertEquals("index", fields[0].type)
                }
            }
        }

    // ==================== LokiHandler ====================

    @Test
    fun `Loki testConnection succeeds`() = withSelfHosted {
        runBlocking {
            MockHttpServer { ex ->
                ex.respond(200, "ready")
            }.use { server ->
                val port = extractPort(server.baseUrl)
                val result = LokiHandler().testConnection(
                    TestConnectionRequest(
                        sourceType = "loki",
                        host = DEFAULT_HOST,
                        port = port
                    )
                )
                assertTrue(result.success)
            }
        }
    }

    @Test
    fun `Loki testConnection fails on error`() = withSelfHosted {
        runBlocking {
            MockHttpServer { ex ->
                ex.respond(503, "unavailable")
            }.use { server ->
                val port = extractPort(server.baseUrl)
                val result = LokiHandler().testConnection(
                    TestConnectionRequest(
                        sourceType = "loki",
                        host = DEFAULT_HOST,
                        port = port
                    )
                )
                assertFalse(result.success)
            }
        }
    }

    @Test
    fun `Loki executeQuery parses stream response`() =
        withSelfHosted {
            runBlocking {
                val lokiResp =
                    """{"data":{"result":[{"stream":""" +
                        """{"job":"api","level":"error"},""" +
                        """"values":[""" +
                        """["1700000000000000000",""" +
                        """"connection timeout"],""" +
                        """["1700000001000000000",""" +
                        """"retry failed"]]}]}}"""
                MockHttpServer { ex ->
                    ex.respond(200, lokiResp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows = LokiHandler().executeQuery(
                        DEFAULT_ROW_ID,
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials(),
                        """{job="api"}""",
                        DEFAULT_LIMIT,
                        null
                    )
                    assertEquals(2, rows.size)
                    assertEquals(
                        JsonPrimitive("connection timeout"),
                        rows[0]["log"]
                    )
                    assertEquals(
                        JsonPrimitive("api"),
                        rows[0]["job"]
                    )
                    assertEquals(
                        JsonPrimitive("error"),
                        rows[0]["level"]
                    )
                    assertNotNull(rows[0]["time_bucket"])
                }
            }
        }

    @Test
    fun `Loki getSchema returns labels`() = withSelfHosted {
        runBlocking {
            val resp = """{"data":["job","level","ns"]}"""
            MockHttpServer { ex ->
                ex.respond(200, resp)
            }.use { server ->
                val port = extractPort(server.baseUrl)
                val fields = LokiHandler().getSchema(
                    DEFAULT_HOST,
                    port,
                    null,
                    DataSourceCredentials()
                )
                assertEquals(3, fields.size)
                assertEquals("job", fields[0].name)
                assertEquals("label", fields[0].type)
            }
        }
    }

    @Test
    fun `Loki executeLabelValuesQuery returns sorted values`() =
        withSelfHosted {
            runBlocking {
                val resp =
                    """{"data":["z-app","a-app","m-app"]}"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val vals =
                        LokiHandler().executeLabelValuesQuery(
                            DEFAULT_HOST,
                            port,
                            DataSourceCredentials(),
                            """label_values({job="api"}, inst)"""
                        )
                    assertEquals(
                        listOf("a-app", "m-app", "z-app"),
                        vals
                    )
                }
            }
        }

    // ==================== GraphiteHandler ====================

    @Test
    fun `Graphite testConnection succeeds`() = withSelfHosted {
        runBlocking {
            MockHttpServer { ex ->
                ex.respond(200, "[]")
            }.use { server ->
                val port = extractPort(server.baseUrl)
                val result = GraphiteHandler().testConnection(
                    TestConnectionRequest(
                        sourceType = "graphite",
                        host = DEFAULT_HOST,
                        port = port
                    )
                )
                assertTrue(result.success)
            }
        }
    }

    @Test
    fun `Graphite executeQuery parses render response`() =
        withSelfHosted {
            runBlocking {
                val resp =
                    """[{"target":"server.cpu",""" +
                        """"datapoints":[[0.5,1700000000],""" +
                        """[0.6,1700000060]]}]"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val rows = GraphiteHandler().executeQuery(
                        DEFAULT_ROW_ID,
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials(),
                        "server.cpu",
                        DEFAULT_LIMIT,
                        null
                    )
                    assertEquals(2, rows.size)
                    assertEquals(
                        JsonPrimitive(1700000000000L),
                        rows[0]["time_bucket"]
                    )
                    assertEquals(
                        JsonPrimitive(0.5),
                        rows[0]["server.cpu"]
                    )
                }
            }
        }

    @Test
    fun `Graphite getSchema returns metric names`() =
        withSelfHosted {
            runBlocking {
                val resp = """["server.cpu","server.mem"]"""
                MockHttpServer { ex ->
                    ex.respond(200, resp)
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val fields = GraphiteHandler().getSchema(
                        DEFAULT_HOST,
                        port,
                        null,
                        DataSourceCredentials()
                    )
                    assertEquals(2, fields.size)
                    assertEquals("server.cpu", fields[0].name)
                    assertEquals("metric", fields[0].type)
                }
            }
        }

    // ==================== InfluxDBHandler ====================

    @Test
    fun `InfluxDB testConnection succeeds`() = withSelfHosted {
        runBlocking {
            MockHttpServer { ex ->
                ex.respond(200, "ok")
            }.use { server ->
                val port = extractPort(server.baseUrl)
                val result = InfluxDBHandler().testConnection(
                    TestConnectionRequest(
                        sourceType = "influxdb",
                        host = DEFAULT_HOST,
                        port = port,
                        apiKey = "my-token"
                    )
                )
                assertTrue(result.success)
            }
        }
    }

    @Test
    fun `InfluxDB executeQuery parses Flux CSV`() =
        withSelfHosted {
            runBlocking {
                val csv = listOf(
                    "#group,false,false,true,true",
                    "result,table,_time,_value",
                    "_result,0,2024-01-01T00:00:00Z,42.5",
                    "_result,0,2024-01-02T00:00:00Z,43.1"
                ).joinToString("\n")
                MockHttpServer { ex ->
                    ex.respond(200, csv, "text/csv")
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val creds = DataSourceCredentials(
                        apiKey = "tok"
                    )
                    val rows = InfluxDBHandler().executeQuery(
                        DEFAULT_ROW_ID,
                        DEFAULT_HOST,
                        port,
                        "moneat",
                        creds,
                        "filter(fn: (r) => r._m == \"cpu\")",
                        DEFAULT_LIMIT,
                        null
                    )
                    assertEquals(2, rows.size)
                    assertEquals(
                        JsonPrimitive(42.5),
                        rows[0]["_value"]
                    )
                    assertEquals(
                        JsonPrimitive("2024-01-01T00:00:00Z"),
                        rows[0]["_time"]
                    )
                }
            }
        }

    @Test
    fun `InfluxDB getSchema returns measurements`() =
        withSelfHosted {
            runBlocking {
                val csv =
                    "header,name\n_,cpu_usage\n_,mem_usage"
                MockHttpServer { ex ->
                    ex.respond(200, csv, "text/csv")
                }.use { server ->
                    val port = extractPort(server.baseUrl)
                    val creds = DataSourceCredentials(
                        apiKey = "tok"
                    )
                    val fields = InfluxDBHandler().getSchema(
                        DEFAULT_HOST,
                        port,
                        "moneat",
                        creds
                    )
                    assertEquals(2, fields.size)
                    assertEquals("cpu_usage", fields[0].name)
                    assertEquals("measurement", fields[0].type)
                }
            }
        }

    // ==================== JdbcHandler: SQL validation ====================

    @Test
    fun `validateSqlQuery strips line comments`() {
        postgresHandler.validateSqlQuery(
            "SELECT 1 -- this is a comment"
        )
    }

    @Test
    fun `validateSqlQuery strips hash comments`() {
        postgresHandler.validateSqlQuery(
            "SELECT 1 # MySQL comment"
        )
    }

    @Test
    fun `validateSqlQuery rejects hidden DROP in line comment`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT 1 -- hide\nDROP TABLE users"
            )
        }
    }

    @Test
    fun `validateSqlQuery rejects GRANT`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT GRANT FROM t"
            )
        }
    }

    @Test
    fun `validateSqlQuery rejects REVOKE`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT REVOKE FROM t"
            )
        }
    }

    @Test
    fun `validateSqlQuery rejects EXEC keyword`() {
        assertFailsWith<IllegalArgumentException> {
            mysqlHandler.validateSqlQuery(
                "SELECT EXEC FROM t"
            )
        }
    }

    @Test
    fun `validateSqlQuery rejects EXECUTE keyword`() {
        assertFailsWith<IllegalArgumentException> {
            mysqlHandler.validateSqlQuery(
                "SELECT EXECUTE FROM t"
            )
        }
    }

    @Test
    fun `validateSqlQuery rejects COPY keyword`() {
        assertFailsWith<IllegalArgumentException> {
            mysqlHandler.validateSqlQuery(
                "SELECT COPY FROM t"
            )
        }
    }

    // ==================== PostgresHandler: forbidden patterns ====================

    @Test
    fun `PostgresHandler rejects VACUUM`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT VACUUM FROM t"
            )
        }
    }

    @Test
    fun `PostgresHandler rejects REINDEX`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT REINDEX FROM t"
            )
        }
    }

    @Test
    fun `PostgresHandler rejects pg_sleep call`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT pg_sleep(10) FROM users"
            )
        }
    }

    @Test
    fun `PostgresHandler rejects pg_terminate_backend call`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT pg_terminate_backend(12345)"
            )
        }
    }

    @Test
    fun `PostgresHandler rejects DBLINK call`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT dblink('conn', 'SELECT 1')"
            )
        }
    }

    @Test
    fun `PostgresHandler rejects SET_CONFIG call`() {
        assertFailsWith<IllegalArgumentException> {
            postgresHandler.validateSqlQuery(
                "SELECT set_config('key', 'val', false)"
            )
        }
    }

    // ==================== CloudWatchHandler ====================

    @Test
    fun `CloudWatch getSchema returns fixed fields`() = runBlocking {
        val handler = CloudWatchHandler()
        val fields = handler.getSchema(
            "",
            null,
            null,
            DataSourceCredentials()
        )
        assertEquals(2, fields.size)
        assertEquals("time_bucket", fields[0].name)
        assertEquals("timestamp", fields[0].type)
        assertEquals("value", fields[1].name)
        assertEquals("double", fields[1].type)
    }

    // ==================== JdbcHandlerCommon ====================

    @Test
    fun `JDBC_COMMON_FORBIDDEN catches all expected keywords`() {
        val keywords =
            JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN.map { it.second }
        assertContains(keywords, "INSERT")
        assertContains(keywords, "UPDATE")
        assertContains(keywords, "DELETE")
        assertContains(keywords, "DROP")
        assertContains(keywords, "ALTER")
        assertContains(keywords, "CREATE")
        assertContains(keywords, "TRUNCATE")
        assertContains(keywords, "GRANT")
        assertContains(keywords, "REVOKE")
        assertContains(keywords, "EXEC")
        assertContains(keywords, "EXECUTE")
        assertContains(keywords, "COPY")
    }

    @Test
    fun `JDBC_COMMON_FORBIDDEN matches case insensitively`() {
        val insertPattern =
            JdbcHandlerCommon.JDBC_COMMON_FORBIDDEN
                .first { it.second == "INSERT" }.first
        assertTrue(insertPattern.containsMatchIn("insert into t"))
        assertTrue(insertPattern.containsMatchIn("INSERT INTO t"))
        assertFalse(insertPattern.containsMatchIn("reinsert"))
    }

    // ==================== PostgresHandler: companion ====================

    @Test
    fun `POSTGRESQL_FORBIDDEN includes extra keywords`() {
        val keywords =
            PostgresHandler.POSTGRESQL_FORBIDDEN.map { it.second }
        assertContains(keywords, "VACUUM")
        assertContains(keywords, "ANALYZE")
        assertContains(keywords, "REINDEX")
        assertContains(keywords, "CLUSTER")
        assertContains(keywords, "COMMENT")
        assertContains(keywords, "LOCK")
    }
}
