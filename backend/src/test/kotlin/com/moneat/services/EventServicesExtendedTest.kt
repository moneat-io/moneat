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

package com.moneat.services

import com.moneat.events.models.EventResponse
import com.moneat.events.models.FeedbackUpdateRequest
import com.moneat.events.models.IssueTransactionResponse
import com.moneat.events.models.IssueUpdateRequest
import com.moneat.events.repositories.IssueRepository
import com.moneat.events.repositories.models.IssueDetailRow
import com.moneat.events.repositories.models.IssueRow
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.FeedbackService
import com.moneat.events.services.IngestionWorker
import com.moneat.events.services.IssueService
import io.ktor.server.plugins.BadRequestException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extended tests for events/services covering:
 *  - IssueService (getIssues, getIssue, getIssueEvents, getIssueTransactions, updateIssue)
 *  - FeedbackService (parseTagsMap, updateFeedback validation)
 *  - DashboardQueryHelper (normalizeUuid, extractUserInfo, getPeriodConfig, etc.)
 *  - IngestionWorker (encode/decode edge cases)
 */
class EventServicesExtendedTest {

    companion object {
        private const val ISSUE_1 = "issue-1"
        private const val TIMESTAMP_2024_01_01 = "2024-01-01T00:00:00.000Z"
        private const val UUID_550E8400 = "550e8400-e29b-41d4-a716-446655440000"
        private const val EMAIL_A_B = "a@b.com"
    }

    // ──── shared mocks ────
    private lateinit var issueRepository: IssueRepository
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var issueService: IssueService

    private val testProjectId = 10L
    private val testIssueId = "abc-123"

    @BeforeTest
    fun setup() {
        issueRepository = mockk(relaxed = true)
        queryHelper = mockk(relaxed = true)

        coEvery { queryHelper.getProjectRetentionDays(any()) } returns 30
        every {
            queryHelper.timestampRetentionClause(any(), any(), any())
        } returns "timestamp >= now() - INTERVAL 30 DAY"
        every { queryHelper.demoNowClause(any()) } returns "now()"

        issueService = IssueService(issueRepository, queryHelper)
    }

    // ================================================================
    //  IssueService – getProjectIdForIssue
    // ================================================================

    @Test
    fun `getProjectIdForIssue delegates to repository`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        val result = issueService.getProjectIdForIssue(testIssueId)
        assertEquals(testProjectId, result)
    }

    @Test
    fun `getProjectIdForIssue returns null for unknown issue`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue("unknown") } returns null
        assertNull(issueService.getProjectIdForIssue("unknown"))
    }

    // ================================================================
    //  IssueService – getIssues (pagination, status filtering, overrides)
    // ================================================================

    @Test
    fun `getIssues returns empty list when repository returns no rows`() = runBlocking {
        every { issueRepository.getIssueStatusOverrides(testProjectId) } returns emptyMap()
        coEvery {
            issueRepository.getIssuesRaw(
                projectId = testProjectId,
                offset = any(),
                overfetch = any(),
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns emptyList()

        val result = issueService.getIssues(testProjectId, page = 1, limit = 10, status = null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getIssues applies status override from postgres`() = runBlocking {
        val row = makeIssueRow(issueId = ISSUE_1, status = "unresolved")
        every { issueRepository.getIssueStatusOverrides(testProjectId) } returns
            mapOf(ISSUE_1 to "resolved")
        coEvery {
            issueRepository.getIssuesRaw(
                projectId = testProjectId,
                offset = any(),
                overfetch = any(),
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns listOf(row)

        val result = issueService.getIssues(testProjectId, page = 1, limit = 10, status = null)
        assertEquals(1, result.size)
        assertEquals("resolved", result[0].status)
    }

    @Test
    fun `getIssues filters by status after applying overrides`() = runBlocking {
        val rows = listOf(
            makeIssueRow(issueId = "i1", status = "unresolved"),
            makeIssueRow(issueId = "i2", status = "unresolved")
        )
        every { issueRepository.getIssueStatusOverrides(testProjectId) } returns
            mapOf("i1" to "resolved")
        coEvery {
            issueRepository.getIssuesRaw(
                projectId = testProjectId,
                offset = any(),
                overfetch = any(),
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns rows

        val result = issueService.getIssues(
            testProjectId,
            page = 1,
            limit = 10,
            status = "unresolved"
        )
        assertEquals(1, result.size)
        assertEquals("i2", result[0].id)
    }

    @Test
    fun `getIssues respects limit parameter`() = runBlocking {
        val rows = (1..5).map { makeIssueRow(issueId = "i$it") }
        every { issueRepository.getIssueStatusOverrides(testProjectId) } returns emptyMap()
        coEvery {
            issueRepository.getIssuesRaw(
                projectId = testProjectId,
                offset = any(),
                overfetch = any(),
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns rows

        val result = issueService.getIssues(testProjectId, page = 1, limit = 3, status = null)
        assertEquals(3, result.size)
    }

    @Test
    fun `getIssues page 2 skips first page of results`() = runBlocking {
        val rows = (1..5).map { makeIssueRow(issueId = "i$it") }
        every { issueRepository.getIssueStatusOverrides(testProjectId) } returns emptyMap()
        coEvery {
            issueRepository.getIssuesRaw(
                projectId = testProjectId,
                offset = any(),
                overfetch = any(),
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns rows

        val result = issueService.getIssues(testProjectId, page = 2, limit = 2, status = null)
        assertEquals(2, result.size)
        assertEquals("i3", result[0].id)
        assertEquals("i4", result[1].id)
    }

    // ================================================================
    //  IssueService – getIssue
    // ================================================================

    @Test
    fun `getIssue returns null when project not found`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns null
        assertNull(issueService.getIssue(testIssueId))
    }

    @Test
    fun `getIssue returns null when detail row not found`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        coEvery {
            issueRepository.getIssueDetailRaw(
                issueId = testIssueId,
                projectId = testProjectId,
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns null

        assertNull(issueService.getIssue(testIssueId))
    }

    @Test
    fun `getIssue applies PG status override`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        coEvery {
            issueRepository.getIssueDetailRaw(
                issueId = testIssueId,
                projectId = testProjectId,
                retentionDays = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns makeIssueDetailRow(issueId = testIssueId, status = "unresolved")
        every { issueRepository.getIssueStatus(testIssueId, testProjectId) } returns "resolved"
        every { issueRepository.getProjectName(testProjectId) } returns "TestProject"
        coEvery {
            issueRepository.getIssueEvents(
                issueId = testIssueId,
                projectId = testProjectId,
                limit = any(),
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns emptyList()

        val detail = issueService.getIssue(testIssueId)
        assertNotNull(detail)
        assertEquals("resolved", detail.status)
        assertEquals("TestProject", detail.projectName)
    }

    // ================================================================
    //  IssueService – getIssueEvents
    // ================================================================

    @Test
    fun `getIssueEvents returns empty when project not found`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue("bad") } returns null
        val events = issueService.getIssueEvents("bad", limit = 10)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `getIssueEvents delegates to repository`() = runBlocking {
        val event = EventResponse(
            eventId = "e1",
            timestamp = TIMESTAMP_2024_01_01,
            message = "err",
            platform = "jvm",
            level = "error",
            environment = null,
            release = null,
            user = null,
            contexts = "{}",
            exception = null,
            breadcrumbs = null
        )
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        coEvery {
            issueRepository.getIssueEvents(
                issueId = testIssueId,
                projectId = testProjectId,
                limit = 5,
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns listOf(event)

        val result = issueService.getIssueEvents(testIssueId, limit = 5)
        assertEquals(1, result.size)
        assertEquals("e1", result[0].eventId)
    }

    // ================================================================
    //  IssueService – getIssueTransactions
    // ================================================================

    @Test
    fun `getIssueTransactions returns empty when project not found`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue("x") } returns null
        assertTrue(issueService.getIssueTransactions("x", limit = 5).isEmpty())
    }

    @Test
    fun `getIssueTransactions returns results from repository`() = runBlocking {
        val txn = IssueTransactionResponse(
            eventId = "t1",
            name = "/api/test",
            op = "http.server",
            duration = 150.0,
            timestamp = TIMESTAMP_2024_01_01,
            status = "ok"
        )
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        coEvery {
            issueRepository.getIssueTransactions(
                issueId = testIssueId,
                projectId = testProjectId,
                limit = 5,
                retentionClause = any(),
                projectIdClause = any()
            )
        } returns listOf(txn)

        val result = issueService.getIssueTransactions(testIssueId, limit = 5)
        assertEquals(1, result.size)
        assertEquals("/api/test", result[0].name)
    }

    // ================================================================
    //  IssueService – updateIssue
    // ================================================================

    @Test
    fun `updateIssue throws when issue not found`() {
        coEvery { issueRepository.getProjectIdForIssue("missing") } returns null
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                issueService.updateIssue("missing", IssueUpdateRequest(status = "resolved"))
            }
        }
    }

    @Test
    fun `updateIssue throws BadRequest for invalid status`() {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        assertFailsWith<BadRequestException> {
            runBlocking {
                issueService.updateIssue(
                    testIssueId,
                    IssueUpdateRequest(status = "invalid_status")
                )
            }
        }
    }

    @Test
    fun `updateIssue accepts all valid statuses`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId

        for (status in listOf("unresolved", "resolved", "archived", "ignored")) {
            issueService.updateIssue(testIssueId, IssueUpdateRequest(status = status))
            verify { issueRepository.upsertIssueStatus(testIssueId, testProjectId, status) }
        }
    }

    @Test
    fun `updateIssue with null status does not call repository`() = runBlocking {
        coEvery { issueRepository.getProjectIdForIssue(testIssueId) } returns testProjectId
        issueService.updateIssue(testIssueId, IssueUpdateRequest(status = null))
        verify(exactly = 0) { issueRepository.upsertIssueStatus(any(), any(), any()) }
    }

    // ================================================================
    //  DashboardQueryHelper – normalizeUuid
    // ================================================================

    @Test
    fun `normalizeUuid accepts standard UUID`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val uuid = UUID_550E8400
        assertEquals(uuid, helper.normalizeUuid(uuid))
    }

    @Test
    fun `normalizeUuid converts 32 hex chars to UUID format`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val hex = "550e8400e29b41d4a716446655440000"
        assertEquals(UUID_550E8400, helper.normalizeUuid(hex))
    }

    @Test
    fun `normalizeUuid handles uppercase input`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val hex = "550E8400E29B41D4A716446655440000"
        assertEquals(UUID_550E8400, helper.normalizeUuid(hex))
    }

    @Test
    fun `normalizeUuid returns null for invalid input`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        assertNull(helper.normalizeUuid("not-a-uuid"))
        assertNull(helper.normalizeUuid(""))
        assertNull(helper.normalizeUuid("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
    }

    @Test
    fun `normalizeUuid trims whitespace`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val uuid = "  $UUID_550E8400  "
        assertEquals(UUID_550E8400, helper.normalizeUuid(uuid))
    }

    // ================================================================
    //  DashboardQueryHelper – extractUserInfo
    // ================================================================

    @Test
    fun `extractUserInfo returns UserInfo when fields present`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val obj = buildJsonObject {
            put("user_id", "u1")
            put("user_email", EMAIL_A_B)
            put("user_username", "alice")
        }
        val user = helper.extractUserInfo(obj)
        assertNotNull(user)
        assertEquals("u1", user.id)
        assertEquals(EMAIL_A_B, user.email)
        assertEquals("alice", user.username)
    }

    @Test
    fun `extractUserInfo returns null when no user fields`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val obj = buildJsonObject { put("other", "value") }
        assertNull(helper.extractUserInfo(obj))
    }

    @Test
    fun `extractUserInfo returns UserInfo with partial fields`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val obj = buildJsonObject { put("user_email", "x@y.com") }
        val user = helper.extractUserInfo(obj)
        assertNotNull(user)
        assertNull(user.id)
        assertEquals("x@y.com", user.email)
    }

    // ================================================================
    //  DashboardQueryHelper – getPeriodConfig
    // ================================================================

    @Test
    fun `getPeriodConfig returns correct config for 24h`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val config = helper.getPeriodConfig("24h")
        assertEquals(24, config.hoursBack)
        assertEquals(60, config.intervalMinutes)
    }

    @Test
    fun `getPeriodConfig returns correct config for 30d`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val config = helper.getPeriodConfig("30d")
        assertEquals(720, config.hoursBack)
        assertEquals(1440, config.intervalMinutes)
    }

    @Test
    fun `getPeriodConfig returns correct config for 90d`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val config = helper.getPeriodConfig("90d")
        assertEquals(2160, config.hoursBack)
        assertEquals(4320, config.intervalMinutes)
    }

    @Test
    fun `getPeriodConfig defaults to 7d for unknown period`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val config = helper.getPeriodConfig("unknown")
        assertEquals(168, config.hoursBack)
        assertEquals(360, config.intervalMinutes)
    }

    // ================================================================
    //  DashboardQueryHelper – buildTransactionFilterClause
    // ================================================================

    @Test
    fun `buildTransactionFilterClause returns empty for null inputs`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        assertEquals("", helper.buildTransactionFilterClause(null, null))
    }

    @Test
    fun `buildTransactionFilterClause includes environment filter`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val clause = helper.buildTransactionFilterClause("production", null)
        assertTrue(clause.contains("environment = 'production'"))
    }

    @Test
    fun `buildTransactionFilterClause includes operation filter`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val clause = helper.buildTransactionFilterClause(null, "http.server")
        assertTrue(clause.contains("transaction_op = 'http.server'"))
    }

    @Test
    fun `buildTransactionFilterClause combines environment and operation`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val clause = helper.buildTransactionFilterClause("staging", "db.query")
        assertTrue(clause.contains("environment = 'staging'"))
        assertTrue(clause.contains("transaction_op = 'db.query'"))
    }

    @Test
    fun `buildTransactionFilterClause ignores blank strings`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        assertEquals("", helper.buildTransactionFilterClause("", ""))
        assertEquals("", helper.buildTransactionFilterClause("  ", "  "))
    }

    // ================================================================
    //  DashboardQueryHelper – timestampRetentionClause & demoNowClause
    // ================================================================

    @Test
    fun `timestampRetentionClause uses now when demoEpochMs is null`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val clause = helper.timestampRetentionClause("timestamp", 30, null)
        assertTrue(clause.contains("now()"))
        assertTrue(clause.contains("INTERVAL 30 DAY"))
    }

    @Test
    fun `timestampRetentionClause uses demo time when demoEpochMs is provided`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val demoMs = 1705316445000L
        val clause = helper.timestampRetentionClause("timestamp", 7, demoMs)
        assertTrue(clause.contains("toDateTime64"))
        assertTrue(clause.contains("INTERVAL 7 DAY"))
    }

    @Test
    fun `demoNowClause returns now() when null`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        assertEquals("now()", helper.demoNowClause(null))
    }

    @Test
    fun `demoNowClause returns toDateTime64 when non-null`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val result = helper.demoNowClause(1705316445000L)
        assertTrue(result.startsWith("toDateTime64("))
    }

    // ================================================================
    //  DashboardQueryHelper – parseStringMap
    // ================================================================

    @Test
    fun `parseStringMap returns empty for null input`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val result = helper.parseStringMap(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseStringMap parses JsonObject to HashMap`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val obj = buildJsonObject {
            put("env", "prod")
            put("version", "1.0")
        }
        val result = helper.parseStringMap(obj)
        assertEquals("prod", result["env"])
        assertEquals("1.0", result["version"])
    }

    @Test
    fun `parseStringMap returns empty for non-object element`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val result = helper.parseStringMap(JsonPrimitive("not-an-object"))
        assertTrue(result.isEmpty())
    }

    // ================================================================
    //  DashboardQueryHelper – parseTraceContext
    // ================================================================

    @Test
    fun `parseTraceContext extracts trace object`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val contexts = """{"trace":{"trace_id":"abc","status":"ok"}}"""
        val trace = helper.parseTraceContext(contexts)
        assertNotNull(trace)
        assertEquals("abc", trace["trace_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parseTraceContext returns null for missing trace`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        assertNull(helper.parseTraceContext("""{"os":{"name":"Linux"}}"""))
    }

    @Test
    fun `parseTraceContext returns null for invalid JSON`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        assertNull(helper.parseTraceContext("not json"))
    }

    // ================================================================
    //  DashboardQueryHelper – mapEventRow
    // ================================================================

    @Test
    fun `mapEventRow maps all fields`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val obj = buildJsonObject {
            put("event_id", "e1")
            put("timestamp", "2024-01-15T00:00:00Z")
            put("message", "Test error")
            put("platform", "python")
            put("level", "warning")
            put("environment", "staging")
            put("release", "2.0")
            put("user_id", "u1")
            put("user_email", EMAIL_A_B)
            put("contexts", "{}")
        }
        val event = helper.mapEventRow(obj)
        assertEquals("e1", event.eventId)
        assertEquals("2024-01-15T00:00:00Z", event.timestamp)
        assertEquals("Test error", event.message)
        assertEquals("python", event.platform)
        assertEquals("warning", event.level)
        assertEquals("staging", event.environment)
        assertEquals("2.0", event.release)
        assertNotNull(event.user)
        assertEquals("u1", event.user?.id)
    }

    @Test
    fun `mapEventRow handles missing optional fields gracefully`() {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val obj = buildJsonObject {
            put("event_id", "e2")
            put("timestamp", "2024-01-01T00:00:00Z")
            put("message", "msg")
            put("platform", "jvm")
            put("level", "error")
            put("contexts", "{}")
        }
        val event = helper.mapEventRow(obj)
        assertEquals("e2", event.eventId)
        assertNull(event.environment)
        assertNull(event.release)
        assertNull(event.user)
    }

    // ================================================================
    //  FeedbackService – updateFeedback validation
    // ================================================================

    @Test
    fun `updateFeedback throws BadRequest for invalid status`() {
        val feedbackService = FeedbackService(queryHelper)
        every { queryHelper.normalizeUuid(any()) } returns UUID_550E8400

        assertFailsWith<BadRequestException> {
            runBlocking {
                feedbackService.updateFeedback(
                    UUID_550E8400,
                    FeedbackUpdateRequest(status = "deleted")
                )
            }
        }
    }

    @Test
    fun `updateFeedback with null status does nothing`() = runBlocking {
        val feedbackService = FeedbackService(queryHelper)
        feedbackService.updateFeedback("any-id", FeedbackUpdateRequest(status = null))
        coVerify(exactly = 0) { queryHelper.executeMutation(any(), any()) }
    }

    @Test
    fun `updateFeedback accepts valid statuses`() = runBlocking {
        val feedbackService = FeedbackService(queryHelper)
        every { queryHelper.normalizeUuid(any()) } returns UUID_550E8400
        coEvery { queryHelper.executeMutation(any(), any()) } returns Unit

        for (status in listOf("unresolved", "resolved", "archived")) {
            feedbackService.updateFeedback(
                UUID_550E8400,
                FeedbackUpdateRequest(status = status)
            )
        }
        coVerify(exactly = 3) { queryHelper.executeMutation(any(), any()) }
    }

    @Test
    fun `updateFeedback throws for invalid feedback ID`() {
        val feedbackService = FeedbackService(queryHelper)
        every { queryHelper.normalizeUuid(any()) } returns null

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                feedbackService.updateFeedback(
                    "bad-id",
                    FeedbackUpdateRequest(status = "resolved")
                )
            }
        }
    }

    // ================================================================
    //  IngestionWorker – encode/decode edge cases
    // ================================================================

    @Test
    fun `encodeMessage and decodeMessage roundtrip with zero projectId`() {
        val payload = "hello".toByteArray()
        val encoded = IngestionWorker.encodeMessage(0L, payload)
        val (id, data) = IngestionWorker.decodeMessage(encoded)
        assertEquals(0L, id)
        assertTrue(payload.contentEquals(data))
    }

    @Test
    fun `encodeMessage and decodeMessage roundtrip with max Long`() {
        val payload = "data".toByteArray()
        val encoded = IngestionWorker.encodeMessage(Long.MAX_VALUE, payload)
        val (id, _) = IngestionWorker.decodeMessage(encoded)
        assertEquals(Long.MAX_VALUE, id)
    }

    @Test
    fun `encodeMessage with empty payload`() {
        val encoded = IngestionWorker.encodeMessage(1L, byteArrayOf())
        val (id, data) = IngestionWorker.decodeMessage(encoded)
        assertEquals(1L, id)
        assertEquals(0, data.size)
    }

    @Test
    fun `decodeMessage with exactly 8 bytes returns empty payload`() {
        val bytes = ByteArray(8)
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val (id, data) = IngestionWorker.decodeMessage(encoded)
        assertEquals(0L, id)
        assertEquals(0, data.size)
    }

    @Test
    fun `decodeMessage with 7 bytes throws`() {
        val bytes = ByteArray(7)
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        assertFailsWith<IllegalArgumentException> {
            IngestionWorker.decodeMessage(encoded)
        }
    }

    @Test
    fun `worker DLQ callback invoked on parse failure`() = runBlocking {
        val worker = IngestionWorker("q:test", "q:test:dlq", 1)
        val dlq = mutableListOf<String>()
        val badPayload = IngestionWorker.encodeMessage(1L, "not-an-envelope".toByteArray())

        worker.processMessageForTest(workerId = 1, value = badPayload) { dlq.add(it) }

        assertEquals(1, dlq.size)
        assertEquals(badPayload, dlq[0])
    }

    // ================================================================
    //  DashboardQueryHelper – extractClickHouseBody
    // ================================================================

    @Test
    fun `extractClickHouseBody returns null for non-200 response`() = runBlocking {
        val helper = DashboardQueryHelper(mockk(relaxed = true), mockk(relaxed = true))
        val response = mockk<io.ktor.client.statement.HttpResponse>()
        every { response.status } returns io.ktor.http.HttpStatusCode.InternalServerError
        assertNull(helper.extractClickHouseBody(response))
    }

    // ================================================================
    //  Helper factories
    // ================================================================

    private fun makeIssueRow(
        issueId: String = ISSUE_1,
        status: String = "unresolved"
    ): IssueRow = IssueRow(
        issueId = issueId,
        projectId = testProjectId,
        title = "Test $issueId",
        culprit = "com.example.Main",
        level = "error",
        platform = "jvm",
        firstSeen = TIMESTAMP_2024_01_01,
        lastSeen = "2024-01-15T00:00:00.000Z",
        eventCount = 10,
        userCount = 3,
        status = status,
        fingerprint = listOf("fp1")
    )

    private fun makeIssueDetailRow(
        issueId: String = ISSUE_1,
        status: String = "unresolved"
    ): IssueDetailRow = IssueDetailRow(
        issueId = issueId,
        projectId = testProjectId,
        title = "Test $issueId",
        culprit = "com.example.Main",
        level = "error",
        platform = "jvm",
        firstSeen = TIMESTAMP_2024_01_01,
        lastSeen = "2024-01-15T00:00:00.000Z",
        eventCount = 10,
        userCount = 3,
        status = status,
        fingerprint = listOf("fp1")
    )
}
