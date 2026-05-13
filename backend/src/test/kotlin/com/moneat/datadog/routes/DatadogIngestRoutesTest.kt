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

package com.moneat.datadog.routes

import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.services.DatadogEventService
import com.moneat.datadog.services.DatadogHostService
import com.moneat.datadog.services.QueuedServiceCheckBatch
import com.moneat.datadog.services.DatadogLogService
import com.moneat.datadog.services.DatadogMetricService
import com.moneat.datadog.services.DatadogService
import com.moneat.datadog.services.DbmIngestionService
import com.moneat.datadog.services.DebuggerIngestionService
import com.moneat.datadog.services.MiscIngestionService
import com.moneat.datadog.services.OrchestratorIngestionService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogIngestRoutesTest {

    companion object {
        private const val DD_API_KEY_HEADER = "DD-API-KEY"
        private const val VALID_KEY = "dd-ingest-test-key"
        private const val ORG_ID = 5
    }

    private var previousSelfHosted: String? = null

    @BeforeTest
    fun setup() {
        previousSelfHosted = System.getProperty("SELF_HOSTED")
        System.setProperty("SELF_HOSTED", "true")
        startTestKoin()
        DatadogAuthMiddleware.clearCache()
        mockkObject(DatadogService)
        mockkObject(DatadogMetricService)
        mockkObject(DatadogLogService)
        mockkObject(DatadogEventService)
        mockkObject(DatadogHostService)
        mockkObject(MiscIngestionService)
        mockkObject(DbmIngestionService)
        mockkObject(DebuggerIngestionService)
        mockkObject(OrchestratorIngestionService)

        coEvery { DatadogMetricService.enqueueMetrics(any(), any()) } returns 0
        coEvery { DatadogLogService.enqueueLogs(any(), any()) } returns 0
        coEvery { DatadogEventService.enqueueEvents(any(), any()) } returns 0
        every { DatadogEventService.mapServiceChecks(any(), any()) } returns
            QueuedServiceCheckBatch(0L, emptyList())
        coEvery { DatadogEventService.insertServiceCheckBatch(any()) } returns Unit
        every { DatadogHostService.touchHostLastSeen(any(), any()) } returns Unit
        every { MiscIngestionService.enqueueSymbolDb(any(), any()) } returns Unit
        every { MiscIngestionService.enqueuePipelineStats(any(), any()) } returns 0
        every { MiscIngestionService.enqueueDataLineage(any(), any()) } returns Unit
        every { MiscIngestionService.enqueueDataStreams(any(), any()) } returns 0
        every { MiscIngestionService.enqueueSynthetics(any(), any()) } returns 0
        every { MiscIngestionService.enqueueContainerImage(any(), any()) } returns Unit
        every { MiscIngestionService.enqueueSbom(any(), any()) } returns 0
        every { DbmIngestionService.enqueueQueries(any(), any()) } returns 0
        every { DbmIngestionService.enqueueMetrics(any(), any()) } returns 0
        every { DbmIngestionService.enqueueActivity(any(), any()) } returns 0
        every { DbmIngestionService.enqueueMetadata(any(), any()) } returns 0
        every { DbmIngestionService.enqueueHealth(any(), any()) } returns 0
        every { DebuggerIngestionService.enqueueDebuggerLogs(any(), any()) } returns 0
        every { DebuggerIngestionService.enqueueDiagnostics(any(), any()) } returns 0
        every { OrchestratorIngestionService.enqueueResources(any(), any()) } returns 0
        every { OrchestratorIngestionService.enqueueManifests(any(), any()) } returns 0
    }

    @AfterTest
    fun teardown() {
        unmockkObject(OrchestratorIngestionService)
        unmockkObject(DebuggerIngestionService)
        unmockkObject(DbmIngestionService)
        unmockkObject(MiscIngestionService)
        unmockkObject(DatadogHostService)
        unmockkObject(DatadogEventService)
        unmockkObject(DatadogLogService)
        unmockkObject(DatadogMetricService)
        unmockkObject(DatadogService)
        DatadogAuthMiddleware.clearCache()
        stopTestKoin()
        if (previousSelfHosted != null) {
            System.setProperty("SELF_HOSTED", previousSelfHosted!!)
        } else {
            System.clearProperty("SELF_HOSTED")
        }
    }

    private fun installRoutes(): io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {
        application {
            install(ContentNegotiation) { json() }
            routing {
                datadogMetricRoutes()
                datadogLogRoutes()
                datadogEventRoutes()
                datadogValidateRoutes()
                miscIngestRoutes()
                dbmIngestRoutes()
                debuggerIngestRoutes()
                orchestratorIngestRoutes()
            }
        }
    }

    // ──── V1 Metric Series ────

    @Test
    fun `v1 series returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("API key"))
    }

    @Test
    fun `v1 series returns 403 when api key is invalid`() = testApplication {
        every { DatadogService.validateApiKey("bad-key") } returns null
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, "bad-key")
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 series returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{not valid json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v1 series accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"cpu","points":[[1,0.5]],"host":"h1"}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── V2 Metric Series ────

    @Test
    fun `v2 series returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 series returns 403 with invalid api key`() = testApplication {
        every { DatadogService.validateApiKey("wrong") } returns null
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, "wrong")
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 series accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"mem","points":[[1,100]],"host":""}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v2 series returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── V3 Metric Series ────

    @Test
    fun `v3 series returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v3/series") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v3 series accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v3/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"disk","points":[[1,42]],"host":"h1"}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v3 series returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v3/series") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Sketches ────

    @Test
    fun `v1 sketches returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/sketches") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `beta sketches returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/beta/sketches") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v3 sketches returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v3/sketches") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 sketches accepts empty body with valid key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/sketches") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v1 sketches accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        coEvery { DatadogMetricService.insertSketchBatch(any()) } returns Unit
        installRoutes()()
        val response = client.post("/dd/api/v1/sketches") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"sketches":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Logs ────

    @Test
    fun `v2 logs returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            contentType(ContentType.Application.Json)
            setBody("""[{"message":"hello"}]""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 logs returns 403 with invalid api key`() = testApplication {
        every { DatadogService.validateApiKey("nope") } returns null
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, "nope")
            contentType(ContentType.Application.Json)
            setBody("""[{"message":"hello"}]""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 logs returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v2 logs accepts empty array`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v2 logs accepts single log object`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"message":"test","ddsource":"app","ddtags":"env:test","hostname":"h1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v2 logs accepts array of multiple log objects`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val body = """[{"message":"a","ddsource":"s","ddtags":"","hostname":"h"},""" +
            """{"message":"b","ddsource":"s","ddtags":"","hostname":"h"}]"""
        val response = client.post("/dd/api/v2/logs") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ──── Events ────

    @Test
    fun `v2 events returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 events returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{corrupt")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v2 events accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Service Checks ────

    @Test
    fun `v1 check_run returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/check_run") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 check_run accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/check_run") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""[{"check":"test","host_name":"h1","status":0}]""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v1 check_run returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/check_run") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `v2 service_checks returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/service_checks") {
            contentType(ContentType.Application.Json)
            setBody("""{"service_checks":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v2 service_checks accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/service_checks") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("""{"service_checks":[{"check":"sc","host_name":"h","status":0}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v2 service_checks returns 400 for malformed json`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/service_checks") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{bad")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Validate ────

    @Test
    fun `v1 validate GET returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.get("/dd/api/v1/validate")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `v1 validate returns valid with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.get("/dd/api/v1/validate") {
            header(DD_API_KEY_HEADER, VALID_KEY)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("true"))
    }

    // ──── Misc Ingest – /dd prefix ────

    @Test
    fun `symdb input returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/symdb/v1/input") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `symdb input accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/symdb/v1/input") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `pipeline stats returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/v0.1/pipeline_stats") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `pipeline stats accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/v0.1/pipeline_stats") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `data lineage returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v1/lineage") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `data lineage accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/lineage") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd data streams returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/data_streams") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd data streams accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/data_streams") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd synthetics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/synthetics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd synthetics accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/synthetics") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd contimage returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/contimage") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd contimage accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/contimage") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dd sbom returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/sbom") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dd sbom accepts valid json payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/sbom") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Misc Ingest – non-/dd prefix ────

    @Test
    fun `contlcycle returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/contlcycle") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `contlcycle returns accepted with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/api/v2/contlcycle") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `event management returns accepted with valid api key`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/api/v2/events") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `non-dd contimage returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/contimage") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd sbom returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/sbom") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd synthetics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/synthetics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd data_streams_messages returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/data_streams_messages") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd events returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/events") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── DBM Ingest – /dd prefix ────

    @Test
    fun `dbm databasequery returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/databasequery") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm databasequery accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/databasequery") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm dbmmetrics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetrics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm dbmmetrics accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetrics") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm dbmactivity returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmactivity") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm dbmactivity accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmactivity") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm metadata returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetadata") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm metadata accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmmetadata") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `dbm health returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmhealth") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `dbm health accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/dbmhealth") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── DBM Ingest – non-/dd prefix ────

    @Test
    fun `non-dd dbm databasequery returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/databasequery") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmmetrics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmmetrics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmactivity returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmactivity") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmmetadata returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmmetadata") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `non-dd dbm dbmhealth returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/api/v2/dbmhealth") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── Debugger Ingest ────

    @Test
    fun `debugger v1 input returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/debugger/v1/input") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `debugger v1 input accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/debugger/v1/input") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `debugger v1 diagnostics returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/debugger/v1/diagnostics") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `debugger v1 diagnostics accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/debugger/v1/diagnostics") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `debugger v2 input returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/debugger/v2/input") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `debugger v2 input accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/debugger/v2/input") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("[{}]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── Orchestrator Ingest ────

    @Test
    fun `orchestrator orch returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/orch") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `orchestrator orch accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/orch") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `orchestrator orchmanif returns 403 when api key is missing`() = testApplication {
        installRoutes()()
        val response = client.post("/dd/api/v2/orchmanif") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `orchestrator orchmanif accepts valid payload`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/orchmanif") {
            header(DD_API_KEY_HEADER, VALID_KEY)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    // ──── API key via query parameter ────

    @Test
    fun `v1 series accepts api key via query parameter`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v1/series?api_key=$VALID_KEY") {
            contentType(ContentType.Application.Json)
            setBody("""{"series":[{"metric":"cpu","points":[[1,0.5]],"host":""}]}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `v2 logs accepts api key via query parameter`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.post("/dd/api/v2/logs?api_key=$VALID_KEY") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `v1 validate accepts api key via query parameter`() = testApplication {
        every { DatadogService.validateApiKey(VALID_KEY) } returns ORG_ID
        installRoutes()()
        val response = client.get("/dd/api/v1/validate?api_key=$VALID_KEY")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
