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

package com.moneat.datadog.security

import com.moneat.config.ClickHouseClient
import com.moneat.plugins.installErrorHandling
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.RouteTestSupport.withAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityQueryRoutesTest {

    companion object {
        private const val EVENTS_PATH = "/v1/security/events"
        private const val DUMPS_PATH = "/v1/security/dumps"
        private const val COMPLIANCE_PATH = "/v1/security/compliance"
        private const val SUMMARY_PATH = "/v1/security/compliance/summary"
        private const val TRENDS_PATH = "/v1/security/compliance/trends"
        private const val ORG_ID = 42
        private const val OTHER_ORG_ID = 99
        private const val DEMO_ORG_ID = -1
        private const val TEST_DB = "testdb"

        private fun isCountQuery(sql: String): Boolean =
            sql.contains("SELECT count() as cnt") && !sql.contains("GROUP BY")
    }

    /**
     * Minimal in-memory ClickHouse stand-in. INSERT statements (produced by the real
     * SecurityIngestionService insert path) are captured; SELECT statements are served from
     * rows registered by the test, so an ingested batch is queryable through the routes.
     */
    private class FakeClickHouse {
        val insertSql = mutableListOf<String>()
        private val rowsByMarker = mutableMapOf<String, List<String>>()

        /** Register the JSONEachRow lines a SELECT touching [marker] should return. */
        fun registerRows(marker: String, rows: List<String>) {
            rowsByMarker[marker] = rows
        }

        fun answer(sql: String): String {
            if (sql.trimStart().startsWith("INSERT")) {
                insertSql.add(sql)
                return ""
            }
            val marker = rowsByMarker.keys.firstOrNull { sql.contains(it) }
            val rows = marker?.let { rowsByMarker[it] } ?: emptyList()
            // A pure count query projects only count() with no GROUP BY (unlike the summary,
            // which also projects count() per framework/status group).
            return if (isCountQuery(sql)) {
                """{"cnt":"${rows.size}"}"""
            } else {
                rows.joinToString("\n")
            }
        }
    }

    @BeforeTest
    fun setup() {
        startTestKoin()
        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
        every { ClickHouseClient.getDatabase() } returns TEST_DB
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
        stopTestKoin()
    }

    private fun token(orgId: Int? = ORG_ID): String =
        RouteTestSupport.createToken(userId = 1, orgId = orgId)

    private fun Application.installRoutes() {
        installJwtAuth()
        // Wire the production error handling (handleClickHouseQueryException) so the sanitization
        // assertions exercise the real plugin rather than a local copy of the generic message.
        installErrorHandling()
        routing { securityQueryRoutes() }
    }

    /** Stub ClickHouse.execute to delegate to [fake], capturing SQL and serving registered rows. */
    private fun stubClickHouse(fake: FakeClickHouse) {
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val sql = firstArg<String>()
            val response = mockk<HttpResponse>()
            every { response.status } returns HttpStatusCode.OK
            coEvery { response.bodyAsText(any()) } returns fake.answer(sql)
            response
        }
    }

    /**
     * Stub ClickHouse.execute capturing every issued query into [captured]. Count queries get a
     * valid zero-count body so the row query still runs; other queries return no rows.
     */
    private fun captureStub(captured: MutableList<String>) {
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val sql = firstArg<String>()
            captured.add(sql)
            val response = mockk<HttpResponse>()
            every { response.status } returns HttpStatusCode.OK
            coEvery { response.bodyAsText(any()) } returns if (isCountQuery(sql)) """{"cnt":"0"}""" else ""
            response
        }
    }

    /** Stub ClickHouse.execute to return a ClickHouse error body for every query. */
    private fun stubClickHouseError(errorBody: String) {
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val response = mockk<HttpResponse>()
            every { response.status } returns HttpStatusCode.OK
            coEvery { response.bodyAsText(any()) } returns errorBody
            response
        }
    }

    private fun eventRow(eventId: String, severity: String = "high"): String =
        """{"event_id":"$eventId","rule_id":"cws-001","rule_name":"Sensitive file accessed",
            "rule_category":"file","severity":"$severity","agent_rule_version":"7.52.1",
            "event_type":"file_open","process_name":"bash","file_path":"/etc/passwd",
            "host":"prod-web-01","env":"production","tags":{"team":"backend"},
            "ts":"2026-05-30T12:00:00.000Z"}""".replace("\n", " ").replace(Regex(" +"), " ")

    // ──── Authentication ────

    @Test
    fun `events list returns 401 without jwt`() = testApplication {
        application { installRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get(EVENTS_PATH).status)
    }

    @Test
    fun `event detail returns 401 without jwt`() = testApplication {
        application { installRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("$EVENTS_PATH/ev-1").status)
    }

    @Test
    fun `dumps list returns 401 without jwt`() = testApplication {
        application { installRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get(DUMPS_PATH).status)
    }

    @Test
    fun `compliance list returns 401 without jwt`() = testApplication {
        application { installRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get(COMPLIANCE_PATH).status)
    }

    @Test
    fun `compliance summary returns 401 without jwt`() = testApplication {
        application { installRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get(SUMMARY_PATH).status)
    }

    @Test
    fun `compliance trends returns 401 without jwt`() = testApplication {
        application { installRoutes() }
        assertEquals(HttpStatusCode.Unauthorized, client.get(TRENDS_PATH).status)
    }

    // ──── Ingest → store → query (real insert path) ────

    @Test
    fun `ingested events batch becomes queryable via the events route`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)

        // Drive the same insert path the ingestion worker uses.
        val batch = QueuedSecurityBatch(
            organizationId = ORG_ID,
            batchType = "events",
            events = listOf(
                QueuedSecurityEventEntry(
                    ruleId = "cws-001",
                    ruleName = "Sensitive file accessed",
                    ruleCategory = "file",
                    severity = "critical",
                    eventType = "file_open",
                    processName = "bash",
                    host = "prod-web-01",
                    env = "production",
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        runBlocking { SecurityIngestionService.insertBatch(batch) }

        // The real insert path emitted an INSERT carrying the org and field values.
        assertTrue(fake.insertSql.any { it.contains("security_events") }, "expected events insert")
        val insert = fake.insertSql.first { it.contains("security_events") }
        assertTrue(insert.contains("$ORG_ID"), "insert must carry org id")
        assertTrue(insert.contains("cws-001"), "insert must carry rule id")
        assertTrue(insert.contains("prod-web-01"), "insert must carry host")

        // Querying that org now returns the ingested event.
        fake.registerRows("security_events", listOf(eventRow("ev-1", "critical")))
        application { installRoutes() }
        val response = client.get("$EVENTS_PATH?limit=50") { withAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"eventId\":\"ev-1\""), "expected mapped eventId: $body")
        assertTrue(body.contains("\"severity\":\"critical\""))
        assertTrue(body.contains("\"totalCount\":1"))
    }

    @Test
    fun `event detail resolves an ingested event by id`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        fake.registerRows("event_id = 'ev-77'", listOf(eventRow("ev-77")))
        application { installRoutes() }
        val response = client.get("$EVENTS_PATH/ev-77") { withAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"eventId\":\"ev-77\""))
        assertTrue(body.contains("\"agentRuleVersion\":\"7.52.1\""))
    }

    @Test
    fun `event detail returns 404 when not found`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        application { installRoutes() }
        val response = client.get("$EVENTS_PATH/missing") { withAuth(token()) }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Activity dumps ────

    @Test
    fun `ingested dumps batch becomes queryable via the dumps route`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        val batch = QueuedSecurityBatch(
            organizationId = ORG_ID,
            batchType = "dumps",
            dumps = listOf(
                QueuedActivityDumpEntry(
                    activityType = "process",
                    processName = "sshd",
                    host = "prod-api-01",
                    durationNs = 5_000_000L,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        runBlocking { SecurityIngestionService.insertBatch(batch) }
        assertTrue(fake.insertSql.any { it.contains("security_dumps") }, "expected dumps insert")

        fake.registerRows(
            "security_dumps",
            listOf(
                """{"dump_id":"d-1","activity_type":"process","process_name":"sshd",
                    "host":"prod-api-01","duration_ns":"5000000","tags":{},
                    "ts":"2026-05-30T12:00:00.000Z"}""".replace("\n", " ").replace(Regex(" +"), " "),
            ),
        )
        application { installRoutes() }
        val response = client.get(DUMPS_PATH) { withAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"dumpId\":\"d-1\""), "expected mapped dumpId: $body")
        assertTrue(body.contains("\"activityType\":\"process\""))
        assertTrue(body.contains("\"totalCount\":1"))
    }

    // ──── Compliance ────

    @Test
    fun `compliance summary aggregates framework status counts`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        fake.registerRows(
            "GROUP BY framework",
            listOf(
                """{"framework":"CIS","status":"passed","cnt":"4"}""",
                """{"framework":"CIS","status":"failed","cnt":"1"}""",
            ),
        )
        application { installRoutes() }
        val response = client.get(SUMMARY_PATH) { withAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"summary\""))
        assertTrue(body.contains("\"framework\":\"CIS\""))
        assertTrue(body.contains("\"status\":\"failed\""))
        assertTrue(body.contains("\"count\":\"1\""))
    }

    @Test
    fun `compliance list maps findings`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        fake.registerRows(
            "compliance_findings",
            listOf(
                """{"finding_id":"f-1","framework":"PCI-DSS","rule_id":"rule-2",
                    "rule_name":"Encrypt data at rest","status":"failed",
                    "resource_type":"aws_s3_bucket","resource_id":"res-1",
                    "resource_name":"prod-bucket-01","tags":{},
                    "ts":"2026-05-30T12:00:00.000Z"}""".replace("\n", " ").replace(Regex(" +"), " "),
            ),
        )
        application { installRoutes() }
        val response = client.get("$COMPLIANCE_PATH?limit=50") { withAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"findingId\":\"f-1\""))
        assertTrue(body.contains("\"framework\":\"PCI-DSS\""))
        assertTrue(body.contains("\"totalCount\":1"))
    }

    @Test
    fun `compliance trends groups pass rate buckets by framework`() = testApplication {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        fake.registerRows(
            "GROUP BY framework, bucket",
            listOf(
                """{"framework":"CIS","bucket":"2026-05-30T00:00:00.000Z",
                    "passed":"8","failed":"2","skipped":"1","error":"0","total":"11"}"""
                    .replace("\n", " ")
                    .replace(Regex(" +"), " "),
            ),
        )
        application { installRoutes() }
        val response = client.get(TRENDS_PATH) { withAuth(token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"framework\":\"CIS\""))
        assertTrue(body.contains("\"bucketStart\":\"2026-05-30T00:00:00.000Z\""))
        assertTrue(body.contains("\"passRate\":0.8"))
    }

    // ──── Org scoping ────

    @Test
    fun `events query is scoped to the caller org id`() = testApplication {
        val captured = mutableListOf<String>()
        captureStub(captured)
        application { installRoutes() }
        client.get(EVENTS_PATH) { withAuth(token(OTHER_ORG_ID)) }
        assertTrue(captured.isNotEmpty(), "expected a query to be issued")
        assertTrue(
            captured.all { it.contains("organization_id = $OTHER_ORG_ID") },
            "every query must be scoped to the caller org: $captured",
        )
        assertFalse(
            captured.any { it.contains("organization_id = $ORG_ID") },
            "must not query another org",
        )
    }

    @Test
    fun `events query for a demo org id targets the demo-org representation`() = testApplication {
        val captured = mutableListOf<String>()
        captureStub(captured)
        application { installRoutes() }
        client.get(EVENTS_PATH) { withAuth(token(DEMO_ORG_ID)) }
        assertTrue(captured.isNotEmpty(), "expected a query to be issued")
        // Demo rows are inserted with toUInt64(-1); the read path must match them via
        // toInt64(organization_id), otherwise reseeds succeed but stay unreadable.
        assertTrue(
            captured.all { it.contains("toInt64(organization_id) IN (-1, -2, -3)") },
            "demo-org reads must use the toInt64 representation that matches toUInt64(-1) rows: $captured",
        )
    }

    // ──── Parameter sanitization (migrated from SecurityRoutesTest) ────

    @Test
    fun `events query escapes severity filter value`() = testApplication {
        val captured = mutableListOf<String>()
        captureStub(captured)
        application { installRoutes() }
        client.get("$EVENTS_PATH?severity=high'%20OR%201=1") { withAuth(token()) }
        // The injected quote is escaped (doubled), so it cannot terminate the literal.
        assertTrue(
            captured.none { it.contains("severity = 'high' OR 1=1") },
            "single quote must be escaped: $captured",
        )
    }

    @Test
    fun `events query coerces limit above the maximum`() = testApplication {
        val captured = mutableListOf<String>()
        captureStub(captured)
        application { installRoutes() }
        client.get("$EVENTS_PATH?limit=9999") { withAuth(token()) }
        assertTrue(
            captured.any { it.contains("LIMIT 200") },
            "limit must be coerced to the 200 maximum: $captured",
        )
    }

    // ──── Error sanitization ────

    @Test
    fun `clickhouse error does not leak internals to the client`() = testApplication {
        val chError =
            "Code: 47. DB::Exception: Unknown column secret_col in table security_events"
        stubClickHouseError(chError)
        application { installRoutes() }
        val response = client.get(EVENTS_PATH) { withAuth(token()) }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertEquals("""{"error":"Data unavailable"}""", body)
        assertFalse(body.contains("DB::Exception"), "must not leak ClickHouse exception text")
        assertFalse(body.contains("secret_col"), "must not leak column names")
        assertFalse(body.contains("Code: 47"), "must not leak ClickHouse error codes")
        assertFalse(body.contains("security_events"), "must not leak table names")
    }

    // ──── Demo reseed: negative org id is cast for the UInt64 column ────

    @Test
    fun `insert path wraps a negative demo org id in toUInt64`() {
        val fake = FakeClickHouse()
        stubClickHouse(fake)
        // Demo orgs carry a negative id (-1); the reseed inserts through this same path. A bare
        // negative literal into the UInt64 organization_id column is rejected by ClickHouse, so the
        // helpers must wrap it as toUInt64(...).
        val batch = QueuedSecurityBatch(
            organizationId = DEMO_ORG_ID,
            batchType = "events",
            events = listOf(
                QueuedSecurityEventEntry(
                    ruleId = "cws-001",
                    ruleName = "Sensitive file accessed",
                    ruleCategory = "file",
                    severity = "critical",
                    eventType = "file_open",
                    processName = "bash",
                    host = "prod-web-01",
                    env = "production",
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        runBlocking { SecurityIngestionService.insertBatch(batch) }

        val insert = fake.insertSql.first { it.contains("security_events") }
        assertTrue(
            insert.contains("toUInt64($DEMO_ORG_ID)"),
            "negative demo org id must be wrapped in toUInt64: $insert",
        )
        assertFalse(
            insert.contains("($DEMO_ORG_ID,"),
            "must not emit a bare negative org literal into the UInt64 column: $insert",
        )
    }

    // ──── Missing orgId claim returns the endpoint's empty shape ────

    @Test
    fun `events list returns empty shape when token has no org id`() = testApplication {
        captureStub(mutableListOf())
        application { installRoutes() }
        val response = client.get(EVENTS_PATH) { withAuth(token(orgId = null)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"events":[],"totalCount":0}""", response.bodyAsText())
    }

    @Test
    fun `dumps list returns empty shape when token has no org id`() = testApplication {
        captureStub(mutableListOf())
        application { installRoutes() }
        val response = client.get(DUMPS_PATH) { withAuth(token(orgId = null)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"dumps":[],"totalCount":0}""", response.bodyAsText())
    }

    @Test
    fun `compliance list returns empty shape when token has no org id`() = testApplication {
        captureStub(mutableListOf())
        application { installRoutes() }
        val response = client.get(COMPLIANCE_PATH) { withAuth(token(orgId = null)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"findings":[],"totalCount":0}""", response.bodyAsText())
    }

    @Test
    fun `compliance summary returns empty shape when token has no org id`() = testApplication {
        captureStub(mutableListOf())
        application { installRoutes() }
        val response = client.get(SUMMARY_PATH) { withAuth(token(orgId = null)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"summary":[]}""", response.bodyAsText())
    }

    @Test
    fun `compliance trends returns empty shape when token has no org id`() = testApplication {
        captureStub(mutableListOf())
        application { installRoutes() }
        val response = client.get(TRENDS_PATH) { withAuth(token(orgId = null)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"frameworks":[]}""", response.bodyAsText())
    }
}
