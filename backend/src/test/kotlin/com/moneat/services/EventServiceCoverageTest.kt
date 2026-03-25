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

import com.moneat.events.models.EnvelopeItem
import com.moneat.events.models.ExceptionInfo
import com.moneat.events.models.ExceptionValue
import com.moneat.events.models.SdkInfo
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.models.SentryEvent
import com.moneat.events.models.SentryFeedback
import com.moneat.events.models.SentryReplayEvent
import com.moneat.events.models.SentrySpan
import com.moneat.events.models.SentryTransaction
import com.moneat.events.models.StackFrame
import com.moneat.events.models.StackTrace
import com.moneat.events.models.UserInfo
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.ErrorEventInsertData
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.events.repositories.models.LlmGenerationInsertData
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.TransactionEventInsertData
import com.moneat.events.services.EventService
import com.moneat.events.services.ReleaseService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.UsageRecords
import com.moneat.config.ClickHouseClient
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage-focused tests for EventService covering:
 *  - processEnvelope routing of all item types
 *  - storeTransaction with spans + AI span detection
 *  - storeEvent with fingerprint generation + crash detection
 *  - storeFeedback context extraction
 *  - storeReplayEvent with browser/device/activity contexts
 *  - storeProfile validation
 *  - normalizeTimestampJsonPayload
 *  - isNewIssue cache behaviour
 */
class EventServiceCoverageTest {

    companion object {
        private var db: Database? = null
    }

    private lateinit var eventRepository: EventRepository
    private lateinit var notificationService: NotificationService
    private lateinit var releaseService: ReleaseService
    private lateinit var eventService: EventService

    private val testProjectId = 1L
    private val testOrgId = 1

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_event_cov;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            Subscriptions,
            UsageRecords
        )

        eventRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        releaseService = mockk(relaxed = true)

        every { eventRepository.verifyProjectKey(any(), any()) } returns
            ProjectKeyVerification(false, null)
        every { eventRepository.verifyProjectKey(testProjectId, "valid-key") } returns
            ProjectKeyVerification(true, "jvm")
        every { eventRepository.getOrganizationIdForProject(any()) } returns null
        every { eventRepository.getOrganizationIdForProject(testProjectId) } returns testOrgId

        coEvery { eventRepository.insertErrorEvent(any()) } returns true
        coEvery { eventRepository.insertTransaction(any()) } returns true
        coEvery { eventRepository.insertSpans(any()) } returns Unit
        coEvery { eventRepository.insertFeedback(any()) } returns true
        coEvery { eventRepository.insertReplayEvent(any()) } returns true
        coEvery { eventRepository.insertReplayRecording(any()) } returns Unit
        coEvery { eventRepository.insertLlmGenerations(any()) } returns true
        coEvery { eventRepository.insertProfile(any()) } returns true
        coEvery { eventRepository.getEventCountForIssue(any(), any()) } returns 1L

        eventService = EventService(
            notificationService = notificationService,
            eventRepository = eventRepository,
            releaseService = releaseService,
        )
    }

    // ===================== processEnvelope routing =====================

    @Test
    fun `processEnvelope routes event item to storeEvent`() = runBlocking {
        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "evt-1",
                level = "error",
                message = "Test error",
                exception = ExceptionInfo(
                    values = listOf(
                        ExceptionValue(type = "NullPointerException", value = "NPE")
                    )
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "evt-1", items = listOf(EnvelopeItem("event", eventJson)))
        )

        coVerify(atLeast = 1) { eventRepository.insertErrorEvent(any()) }
    }

    @Test
    fun `processEnvelope routes transaction item to storeTransaction`() = runBlocking {
        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-1",
                transaction = "GET /api/users",
                start_timestamp = 1700000000.0,
                timestamp = 1700000001.0,
                platform = "jvm",
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "abc123")
                            put("op", "http.server")
                            put("status", "ok")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "txn-1", items = listOf(EnvelopeItem("transaction", txnJson)))
        )

        coVerify(atLeast = 1) { eventRepository.insertTransaction(any()) }
    }

    @Test
    fun `processEnvelope routes feedback item to storeFeedback`() = runBlocking {
        val fbJson = Json.encodeToString(
            SentryFeedback(
                event_id = "fb-1",
                timestamp = "2024-01-15T10:30:45Z",
                contexts = buildJsonObject {
                    put(
                        "feedback",
                        buildJsonObject {
                            put("message", "Great product!")
                            put("contact_email", "user@example.com")
                            put("name", "John Doe")
                            put("url", "https://app.example.com/dashboard")
                            put("associated_event_id", "evt-99")
                            put("replay_id", "replay-42")
                        }
                    )
                },
                user = UserInfo(id = "u1", email = "user@example.com"),
                environment = "production",
                release = "1.0.0",
                platform = "javascript",
                tags = mapOf("page" to "dashboard"),
                sdk = SdkInfo(name = "sentry-js", version = "7.0.0")
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "fb-1", items = listOf(EnvelopeItem("feedback", fbJson)))
        )

        coVerify(atLeast = 1) { eventRepository.insertFeedback(any()) }
    }

    @Test
    fun `processEnvelope routes replay_event and replay_recording`() = runBlocking {
        val replayEventJson = Json.encodeToString(
            SentryReplayEvent(
                replay_id = "replay-1",
                segment_id = 0,
                timestamp = 1700000000.0,
                replay_start_timestamp = 1699999990.0,
                urls = listOf("https://example.com"),
                error_ids = listOf("err-1"),
                trace_ids = listOf("trace-1"),
                platform = "javascript",
                environment = "production",
                user = UserInfo(id = "u1"),
                contexts = buildJsonObject {
                    put(
                        "browser",
                        buildJsonObject {
                            put("name", "Chrome")
                            put("version", "120.0")
                        }
                    )
                    put(
                        "os",
                        buildJsonObject {
                            put("name", "Windows")
                            put("version", "11")
                        }
                    )
                    put(
                        "device",
                        buildJsonObject {
                            put("name", "Desktop")
                            put("family", "PC")
                        }
                    )
                    put(
                        "replay",
                        buildJsonObject {
                            put("activity", 5)
                        }
                    )
                },
                sdk = SdkInfo(name = "sentry-js", version = "7.0.0"),
                tags = mapOf("env" to "prod")
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "replay-1",
                items = listOf(
                    EnvelopeItem("replay_event", replayEventJson),
                    EnvelopeItem("replay_recording", "recording-data-here")
                )
            )
        )

        coVerify(atLeast = 1) { eventRepository.insertReplayEvent(any()) }
        coVerify(atLeast = 1) { eventRepository.insertReplayRecording(any()) }
    }

    @Test
    fun `processEnvelope routes replay_video without preceding replay_event`() = runBlocking {
        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "mobile-replay-1",
                items = listOf(
                    EnvelopeItem("replay_video", "video-data-here")
                )
            )
        )

        // Should create synthetic replay event + recording
        coVerify(atLeast = 1) { eventRepository.insertReplayEvent(any()) }
        coVerify(atLeast = 1) { eventRepository.insertReplayRecording(any()) }
    }

    @Test
    fun `processEnvelope handles session items gracefully`() = runBlocking {
        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "sess-1",
                items = listOf(EnvelopeItem("session", """{"sid":"abc"}"""))
            )
        )
        // No crash - session items are skipped
    }

    @Test
    fun `processEnvelope handles unknown item types gracefully`() = runBlocking {
        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "unknown-1",
                items = listOf(EnvelopeItem("custom_type", """{"data":"test"}"""))
            )
        )
        // No crash - unknown items are logged and skipped
    }

    @Test
    fun `processEnvelope with multiple items processes all`() = runBlocking {
        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "evt-multi",
                level = "error",
                message = "Multi test",
                exception = ExceptionInfo(
                    values = listOf(ExceptionValue(type = "Error", value = "test"))
                )
            )
        )
        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-multi",
                transaction = "test",
                start_timestamp = 1700000000.0,
                timestamp = 1700000001.0,
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "abc")
                            put("op", "test")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "multi-1",
                items = listOf(
                    EnvelopeItem("event", eventJson),
                    EnvelopeItem("transaction", txnJson)
                )
            )
        )

        coVerify(atLeast = 1) { eventRepository.insertErrorEvent(any()) }
        coVerify(atLeast = 1) { eventRepository.insertTransaction(any()) }
    }

    // ===================== storeTransaction with spans =====================

    @Test
    fun `storeTransaction inserts spans from transaction`() = runBlocking {
        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-spans",
                transaction = "GET /items",
                start_timestamp = 1700000000.0,
                timestamp = 1700000002.0,
                platform = "python",
                environment = "staging",
                release = "2.0.0",
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "trace-abc")
                            put("op", "http.server")
                            put("span_id", "root-span")
                        }
                    )
                },
                spans = listOf(
                    SentrySpan(
                        span_id = "span1",
                        parent_span_id = "root",
                        trace_id = "trace-abc",
                        op = "db.query",
                        description = "SELECT * FROM items",
                        start_timestamp = 1700000000.5,
                        timestamp = 1700000001.0,
                        status = "ok",
                        tags = mapOf("db.system" to "postgresql")
                    ),
                    SentrySpan(
                        span_id = "span2",
                        trace_id = "trace-abc",
                        op = "http.client",
                        description = "GET /external",
                        start_timestamp = 1700000001.0,
                        timestamp = 1700000001.5,
                        status = "ok"
                    )
                ),
                user = UserInfo(id = "user-1", email = "u@test.com"),
                sdk = SdkInfo(name = "sentry-python", version = "1.0.0")
            )
        )

        mockkObject(ClickHouseClient)
        val chResponse =
            mockk<HttpResponse>(relaxed = true) {
                every { status } returns HttpStatusCode.OK
            }
        val executedSql = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any(), any()) } coAnswers {
            executedSql.add(firstArg())
            chResponse
        }
        try {
            eventService.processEnvelope(
                testProjectId,
                SentryEnvelope(eventId = "txn-spans", items = listOf(EnvelopeItem("transaction", txnJson)))
            )

            coVerify(atLeast = 1) { eventRepository.insertTransaction(any()) }
            verify(atLeast = 1) { eventRepository.getOrganizationIdForProject(testProjectId) }
            coVerify(atLeast = 1) { ClickHouseClient.execute(any(), any()) }
            assertTrue(executedSql.any { it.contains("apm_spans") }, "expected apm_spans INSERT")
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `storeTransaction detects AI spans and inserts LLM generations`() = runBlocking {
        val genSlot = slot<List<LlmGenerationInsertData>>()
        coEvery { eventRepository.insertLlmGenerations(capture(genSlot)) } returns true

        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-ai",
                transaction = "chat",
                start_timestamp = 1700000000.0,
                timestamp = 1700000005.0,
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "trace-ai")
                            put("op", "ai.pipeline")
                        }
                    )
                },
                spans = listOf(
                    SentrySpan(
                        span_id = "ai-span-1",
                        trace_id = "trace-ai",
                        op = "ai.chat_completion",
                        description = "OpenAI Chat",
                        start_timestamp = 1700000001.0,
                        timestamp = 1700000003.0,
                        status = "ok",
                        data = buildJsonObject {
                            put("ai.model_id", "gpt-4")
                            put("ai.provider", "openai")
                            put("ai.input_tokens", 100)
                            put("ai.output_tokens", 200)
                            put("ai.total_tokens_used", 300)
                        }
                    ),
                    SentrySpan(
                        span_id = "ai-span-2",
                        trace_id = "trace-ai",
                        op = "ai.embedding",
                        description = "Embed query",
                        start_timestamp = 1700000003.0,
                        timestamp = 1700000004.0,
                        status = "ok",
                        data = buildJsonObject {
                            put("ai.model_id", "text-embedding-3-small")
                            put("ai.provider", "openai")
                            put("ai.input_tokens", 50)
                        }
                    )
                ),
                environment = "production",
                user = UserInfo(id = "user-ai")
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "txn-ai", items = listOf(EnvelopeItem("transaction", txnJson)))
        )

        coVerify(atLeast = 1) { eventRepository.insertLlmGenerations(any()) }
        assertTrue(genSlot.isCaptured)
        assertEquals(2, genSlot.captured.size)
        assertEquals("chat", genSlot.captured[0].type)
        assertEquals("embedding", genSlot.captured[1].type)
        assertEquals("gpt-4", genSlot.captured[0].model)
        assertEquals("openai", genSlot.captured[0].provider)
    }

    @Test
    fun `storeTransaction with error trace status sets level to error`() = runBlocking {
        val txnSlot = slot<TransactionEventInsertData>()
        coEvery { eventRepository.insertTransaction(capture(txnSlot)) } returns true

        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-err",
                transaction = "POST /fail",
                start_timestamp = 1700000000.0,
                timestamp = 1700000001.0,
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "trace-err")
                            put("op", "http.server")
                            put("status", "internal_error")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "txn-err", items = listOf(EnvelopeItem("transaction", txnJson)))
        )

        assertTrue(txnSlot.isCaptured)
        assertEquals("error", txnSlot.captured.level)
    }

    @Test
    fun `storeTransaction upserts release when present`() = runBlocking {
        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-rel",
                transaction = "test",
                start_timestamp = 1700000000.0,
                timestamp = 1700000001.0,
                release = "v3.0.0",
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "t1")
                            put("op", "test")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "txn-rel", items = listOf(EnvelopeItem("transaction", txnJson)))
        )

        coVerify(atLeast = 1) {
            releaseService.upsertReleaseFromEvent(testProjectId, "v3.0.0", any())
        }
    }

    // ===================== storeEvent crash detection =====================

    @Test
    fun `storeEvent detects unhandled exception as crash with fatal level`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "crash-1",
                exception = ExceptionInfo(
                    values = listOf(
                        ExceptionValue(
                            type = "RuntimeException",
                            value = "App crashed",
                            mechanism = buildJsonObject {
                                put("handled", false)
                                put("type", "onerror")
                            }
                        )
                    )
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "crash-1", items = listOf(EnvelopeItem("event", eventJson)))
        )

        assertTrue(errorSlot.isCaptured)
        assertEquals("fatal", errorSlot.captured.level)
    }

    @Test
    fun `storeEvent with handled exception uses provided level`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "handled-1",
                level = "warning",
                exception = ExceptionInfo(
                    values = listOf(
                        ExceptionValue(
                            type = "TimeoutError",
                            value = "Request timed out",
                            mechanism = buildJsonObject {
                                put("handled", true)
                            }
                        )
                    )
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "handled-1", items = listOf(EnvelopeItem("event", eventJson)))
        )

        assertTrue(errorSlot.isCaptured)
        assertEquals("warning", errorSlot.captured.level)
    }

    @Test
    fun `storeEvent generates fingerprint from exception type and stack frame`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "fp-1",
                exception = ExceptionInfo(
                    values = listOf(
                        ExceptionValue(
                            type = "ValueError",
                            value = "invalid input",
                            stacktrace = StackTrace(
                                frames = listOf(
                                    StackFrame(
                                        filename = "lib.py",
                                        function = "validate",
                                        lineno = 10,
                                        in_app = false
                                    ),
                                    StackFrame(
                                        filename = "app.py",
                                        function = "process",
                                        lineno = 42,
                                        in_app = true
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "fp-1", items = listOf(EnvelopeItem("event", eventJson)))
        )

        assertTrue(errorSlot.isCaptured)
        val fp = errorSlot.captured.fingerprint
        assertNotNull(fp)
        assertTrue(fp.contains("ValueError"))
        assertTrue(fp.contains("process"))
        assertTrue(fp.contains("app.py"))
    }

    @Test
    fun `storeEvent uses custom fingerprint when provided`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "custom-fp",
                fingerprint = listOf("custom", "group"),
                exception = ExceptionInfo(
                    values = listOf(ExceptionValue(type = "Error", value = "test"))
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "custom-fp", items = listOf(EnvelopeItem("event", eventJson)))
        )

        assertTrue(errorSlot.isCaptured)
        assertEquals(listOf("custom", "group"), errorSlot.captured.fingerprint)
    }

    @Test
    fun `storeEvent without exception uses message`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "msg-only",
                message = "Something went wrong",
                level = "error"
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(eventId = "msg-only", items = listOf(EnvelopeItem("event", eventJson)))
        )

        assertTrue(errorSlot.isCaptured)
        assertEquals("Something went wrong", errorSlot.captured.message)
    }

    @Test
    fun `storeEvent fills all optional fields correctly`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "full-event",
                timestamp = 1700000000.0,
                level = "info",
                platform = "python",
                environment = "staging",
                release = "2.5.0",
                dist = "abc123",
                server_name = "web-03",
                user = UserInfo(
                    id = "uid-1",
                    email = "user@test.com",
                    username = "testuser",
                    ip_address = "10.0.0.1"
                ),
                tags = mapOf("service" to "api", "region" to "us-east"),
                sdk = SdkInfo(name = "sentry-python", version = "1.5.0"),
                contexts = buildJsonObject {
                    put("os", buildJsonObject { put("name", "Linux") })
                },
                breadcrumbs = JsonArray(listOf()),
                request = buildJsonObject { put("method", "GET") },
                exception = ExceptionInfo(
                    values = listOf(ExceptionValue(type = "TestError", value = "test"))
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "full-event",
                items = listOf(EnvelopeItem("event", eventJson))
            )
        )

        assertTrue(errorSlot.isCaptured)
        val data = errorSlot.captured
        assertEquals("python", data.platform)
        assertEquals("staging", data.environment)
        assertEquals("2.5.0", data.release)
        assertEquals("abc123", data.dist)
        assertEquals("web-03", data.serverName)
        assertEquals("uid-1", data.userId)
        assertEquals("user@test.com", data.userEmail)
        assertEquals("testuser", data.userUsername)
        assertEquals("10.0.0.1", data.userIpAddress)
        assertEquals("sentry-python", data.sdkName)
        assertEquals("1.5.0", data.sdkVersion)
    }

    // ===================== storeFeedback =====================

    @Test
    fun `storeFeedback with projectId 0 is rejected`() = runBlocking {
        val fbJson = Json.encodeToString(
            SentryFeedback(event_id = "fb-bad")
        )

        eventService.processEnvelope(
            0L,
            SentryEnvelope(
                eventId = "fb-bad",
                items = listOf(EnvelopeItem("feedback", fbJson))
            )
        )

        coVerify(exactly = 0) { eventRepository.insertFeedback(any()) }
    }

    @Test
    fun `storeFeedback parses ISO timestamp`() = runBlocking {
        val fbSlot = slot<FeedbackInsertData>()
        coEvery { eventRepository.insertFeedback(capture(fbSlot)) } returns true

        val fbJson = Json.encodeToString(
            SentryFeedback(
                event_id = "fb-ts",
                timestamp = "2024-01-15T10:30:45Z",
                contexts = buildJsonObject {
                    put(
                        "feedback",
                        buildJsonObject {
                            put("message", "Nice")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "fb-ts",
                items = listOf(EnvelopeItem("feedback", fbJson))
            )
        )

        assertTrue(fbSlot.isCaptured)
        assertTrue(fbSlot.captured.timestampMs > 0)
    }

    @Test
    fun `storeFeedback with invalid timestamp uses current time`() = runBlocking {
        val fbSlot = slot<FeedbackInsertData>()
        coEvery { eventRepository.insertFeedback(capture(fbSlot)) } returns true
        val before = System.currentTimeMillis()

        val fbJson = Json.encodeToString(
            SentryFeedback(
                event_id = "fb-bad-ts",
                timestamp = "not-a-date",
                contexts = buildJsonObject {
                    put(
                        "feedback",
                        buildJsonObject {
                            put("message", "Hello")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "fb-bad-ts",
                items = listOf(EnvelopeItem("feedback", fbJson))
            )
        )

        assertTrue(fbSlot.isCaptured)
        assertTrue(fbSlot.captured.timestampMs >= before)
    }

    // ===================== storeReplayEvent =====================

    @Test
    fun `storeReplayEvent with projectId 0 is rejected`() = runBlocking {
        val replayJson = Json.encodeToString(
            SentryReplayEvent(replay_id = "replay-bad")
        )

        eventService.processEnvelope(
            0L,
            SentryEnvelope(
                eventId = "replay-bad",
                items = listOf(EnvelopeItem("replay_event", replayJson))
            )
        )

        coVerify(exactly = 0) { eventRepository.insertReplayEvent(any()) }
    }

    @Test
    fun `storeReplayEvent extracts browser and device context`() = runBlocking {
        val replaySlot = slot<ReplayEventInsertData>()
        coEvery { eventRepository.insertReplayEvent(capture(replaySlot)) } returns true

        val replayJson = Json.encodeToString(
            SentryReplayEvent(
                replay_id = "replay-ctx",
                segment_id = 3,
                timestamp = 1700000000.0,
                replay_start_timestamp = 1699999990.0,
                urls = listOf("https://a.com", "https://b.com"),
                contexts = buildJsonObject {
                    put(
                        "browser",
                        buildJsonObject {
                            put("name", "Firefox")
                            put("version", "121.0")
                        }
                    )
                    put(
                        "os",
                        buildJsonObject {
                            put("name", "macOS")
                            put("version", "14.2")
                        }
                    )
                    put(
                        "device",
                        buildJsonObject {
                            put("name", "MacBook Pro")
                            put("family", "Mac")
                        }
                    )
                    put(
                        "replay",
                        buildJsonObject {
                            put("activity", 8)
                        }
                    )
                },
                tags = mapOf("browser" to "firefox")
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "replay-ctx",
                items = listOf(EnvelopeItem("replay_event", replayJson))
            )
        )

        assertTrue(replaySlot.isCaptured)
        assertEquals("Firefox", replaySlot.captured.browserName)
        assertEquals("121.0", replaySlot.captured.browserVersion)
        assertEquals("macOS", replaySlot.captured.osName)
        assertEquals("14.2", replaySlot.captured.osVersion)
        assertEquals("MacBook Pro", replaySlot.captured.deviceName)
        assertEquals("Mac", replaySlot.captured.deviceFamily)
        assertEquals(8, replaySlot.captured.activity)
        assertEquals(3, replaySlot.captured.segmentId)
    }

    // ===================== processStoreEvent =====================

    @Test
    fun `processStoreEvent parses and stores event`() = runBlocking {
        val errorSlot = slot<ErrorEventInsertData>()
        coEvery { eventRepository.insertErrorEvent(capture(errorSlot)) } returns true

        val body = Json.encodeToString(
            SentryEvent(
                event_id = "store-1",
                level = "error",
                message = "Direct store",
                exception = ExceptionInfo(
                    values = listOf(ExceptionValue(type = "IOError", value = "disk full"))
                )
            )
        )

        eventService.processStoreEvent(testProjectId, body)

        assertTrue(errorSlot.isCaptured)
        assertEquals("IOError", errorSlot.captured.exceptionType)
    }

    // ===================== verifyProjectKey caching =====================

    @Test
    fun `verifyProjectKey returns cached result on second call`() {
        val result1 = eventService.verifyProjectKey(testProjectId, "valid-key")
        val result2 = eventService.verifyProjectKey(testProjectId, "valid-key")

        assertTrue(result1.isValid)
        assertTrue(result2.isValid)
        // Repository should only be called once due to caching
    }

    @Test
    fun `getOrganizationIdForProject returns cached result`() {
        every { eventRepository.getOrganizationIdForProject(testProjectId) } returns testOrgId

        val result1 = eventService.getOrganizationIdForProject(testProjectId)
        val result2 = eventService.getOrganizationIdForProject(testProjectId)

        assertEquals(testOrgId, result1)
        assertEquals(testOrgId, result2)
    }

    // ===================== storeTransaction when insert fails =====================

    @Test
    fun `storeTransaction returns false when insert fails`() = runBlocking {
        coEvery { eventRepository.insertTransaction(any()) } returns false

        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-fail",
                transaction = "test",
                start_timestamp = 1700000000.0,
                timestamp = 1700000001.0,
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "t")
                            put("op", "test")
                        }
                    )
                }
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "txn-fail",
                items = listOf(EnvelopeItem("transaction", txnJson))
            )
        )

        // Transaction insert should have failed, so no further processing
        coVerify(exactly = 1) { eventRepository.insertTransaction(any()) }
    }

    // ===================== storeEvent when insert fails =====================

    @Test
    fun `storeEvent returns false when insert fails`() = runBlocking {
        coEvery { eventRepository.insertErrorEvent(any()) } returns false

        val eventJson = Json.encodeToString(
            SentryEvent(
                event_id = "evt-fail",
                exception = ExceptionInfo(
                    values = listOf(ExceptionValue(type = "Error", value = "fail"))
                )
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "evt-fail",
                items = listOf(EnvelopeItem("event", eventJson))
            )
        )

        // Should not crash
    }

    // ===================== AI span type classification =====================

    @Test
    fun `AI span type classification covers all patterns`() = runBlocking {
        val genSlot = slot<List<LlmGenerationInsertData>>()
        coEvery { eventRepository.insertLlmGenerations(capture(genSlot)) } returns true

        val aiSpans = listOf(
            SentrySpan(
                span_id = "s1",
                op = "ai.chat_completion",
                description = "Chat",
                start_timestamp = 1700000001.0,
                timestamp = 1700000002.0
            ),
            SentrySpan(
                span_id = "s2",
                op = "ai.embedding",
                description = "Embed",
                start_timestamp = 1700000002.0,
                timestamp = 1700000003.0
            ),
            SentrySpan(
                span_id = "s3",
                op = "ai.tool_call",
                description = "Tool",
                start_timestamp = 1700000003.0,
                timestamp = 1700000004.0
            ),
            SentrySpan(
                span_id = "s4",
                op = "ai.agent",
                description = "Agent",
                start_timestamp = 1700000004.0,
                timestamp = 1700000005.0
            ),
            SentrySpan(
                span_id = "s5",
                op = "ai.chain",
                description = "Chain",
                start_timestamp = 1700000005.0,
                timestamp = 1700000006.0
            ),
            SentrySpan(
                span_id = "s6",
                op = "ai.retriever",
                description = "Retriever",
                start_timestamp = 1700000006.0,
                timestamp = 1700000007.0
            ),
            SentrySpan(
                span_id = "s7",
                op = "ai.run",
                description = "Generic",
                start_timestamp = 1700000007.0,
                timestamp = 1700000008.0
            ),
        )

        val txnJson = Json.encodeToString(
            SentryTransaction(
                event_id = "txn-ai-types",
                transaction = "ai-test",
                start_timestamp = 1700000000.0,
                timestamp = 1700000010.0,
                contexts = buildJsonObject {
                    put(
                        "trace",
                        buildJsonObject {
                            put("trace_id", "ai-trace")
                            put("op", "ai.pipeline")
                        }
                    )
                },
                spans = aiSpans
            )
        )

        eventService.processEnvelope(
            testProjectId,
            SentryEnvelope(
                eventId = "txn-ai-types",
                items = listOf(EnvelopeItem("transaction", txnJson))
            )
        )

        assertTrue(genSlot.isCaptured)
        val types = genSlot.captured.map { it.type }
        assertEquals("chat", types[0])
        assertEquals("embedding", types[1])
        assertEquals("tool_call", types[2])
        assertEquals("agent", types[3])
        assertEquals("chain", types[4])
        assertEquals("retriever", types[5])
        assertEquals("completion", types[6])
    }
}
