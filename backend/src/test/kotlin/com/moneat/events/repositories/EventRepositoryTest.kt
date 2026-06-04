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

package com.moneat.events.repositories

import com.moneat.config.ClickHouseClient
import com.moneat.events.repositories.models.ErrorEventInsertData
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.events.repositories.models.LlmGenerationInsertData
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import com.moneat.events.repositories.models.SessionInsertData
import com.moneat.events.repositories.models.SpanInsertData
import com.moneat.events.repositories.models.TransactionEventInsertData
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventRepositoryTest {

    private var db: Database? = null
    private lateinit var repository: EventRepository

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_event_repo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects, ProjectKeys)
        repository = EventRepositoryImpl()
    }

    private fun seedProjectWithKey(
        orgSlug: String = "org",
        projectSlug: String = "proj",
        publicKey: String = "pub",
        active: Boolean = true,
        platformTarget: String? = null
    ): Pair<Long, Int> = transaction {
        val orgId = Organizations.insert {
            it[name] = orgSlug
            it[slug] = orgSlug
        } get Organizations.id
        val projectId = Projects.insert {
            it[organization_id] = orgId
            it[name] = projectSlug
            it[slug] = projectSlug
        } get Projects.id
        ProjectKeys.insert {
            it[project_id] = projectId
            it[public_key] = publicKey
            it[secret_key] = "sec"
            it[is_active] = active
            it[platform_target] = platformTarget
        }
        Pair(projectId, orgId)
    }

    // ──── verifyProjectKey ────

    @Test
    fun `verifyProjectKey returns valid for matching active key`() {
        val (projectId, _) = seedProjectWithKey(publicKey = "valid-key")
        val result = repository.verifyProjectKey(projectId, "valid-key")
        assertTrue(result.isValid)
        assertNull(result.platformTarget)
    }

    @Test
    fun `verifyProjectKey returns invalid for wrong public key`() {
        val (projectId, _) = seedProjectWithKey(publicKey = "correct-key")
        val result = repository.verifyProjectKey(projectId, "wrong-key")
        assertFalse(result.isValid)
    }

    @Test
    fun `verifyProjectKey returns invalid for inactive key`() {
        val (projectId, _) = seedProjectWithKey(publicKey = "inactive-key", active = false)
        val result = repository.verifyProjectKey(projectId, "inactive-key")
        assertFalse(result.isValid)
    }

    @Test
    fun `verifyProjectKey returns platformTarget when set`() {
        val (projectId, _) = seedProjectWithKey(publicKey = "android-key", platformTarget = "android")
        val result = repository.verifyProjectKey(projectId, "android-key")
        assertTrue(result.isValid)
        assertEquals("android", result.platformTarget)
    }

    // ──── getOrganizationIdForProject ────

    @Test
    fun `getOrganizationIdForProject returns correct org id`() {
        val (projectId, orgId) = seedProjectWithKey()
        val result = repository.getOrganizationIdForProject(projectId)
        assertNotNull(result)
        assertEquals(orgId, result)
    }

    @Test
    fun `getOrganizationIdForProject returns null for unknown project`() {
        val result = repository.getOrganizationIdForProject(999999L)
        assertNull(result)
    }

    // ──── getServiceNameForProject ────

    @Test
    fun `getServiceNameForProject returns project slug`() {
        val (projectId, _) = seedProjectWithKey(projectSlug = "checkout-api")

        assertEquals("checkout-api", repository.getServiceNameForProject(projectId))
    }

    // ──── insertSessions ────

    @Test
    fun `insertSessions returns true for empty rows`() = runBlocking {
        assertTrue(repository.insertSessions(emptyList()))
    }

    @Test
    fun `insertSessions writes ClickHouse session rows`() = runBlocking {
        val queries = mutableListOf<String>()
        try {
            MockHttpServer { exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = "text/plain")
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                val result = repository.insertSessions(
                    listOf(
                        SessionInsertData(
                            sessionId = "11111111-1111-1111-1111-111111111111",
                            projectId = 42L,
                            organizationId = 7,
                            startedMs = 1767225600000L,
                            durationMs = 1500.0,
                            status = "ok",
                            errors = 0,
                            release = "1.0.0",
                            environment = "production",
                            userId = "user-123",
                            receivedAtMs = 1767225601000L
                        )
                    )
                )

                assertTrue(result)
            }
        } finally {
            ClickHouseClient.close()
        }

        val sql = queries.single()
        val compactSql = sql.replace(Regex("\\s+"), " ")
        assertTrue(sql.contains("INSERT INTO `test`.sessions"))
        assertTrue(sql.contains("session_id, service_id, project_id, organization_id"))
        assertTrue(sql.contains("toUUID('11111111-1111-1111-1111-111111111111')"))
        assertTrue(compactSql.contains("42, 42, 7, fromUnixTimestamp64Milli(1767225600000)"))
        assertTrue(sql.contains("fromUnixTimestamp64Milli(1767225600000)"))
        assertTrue(sql.contains("1500.0"))
        assertTrue(sql.contains("'ok'"))
        assertTrue(sql.contains("'1.0.0'"))
        assertTrue(sql.contains("'production'"))
        assertTrue(sql.contains("'user-123'"))
    }

    @Test
    fun `ClickHouse inserts include organization id across event row types`() = runBlocking {
        val queries = mutableListOf<String>()
        try {
            MockHttpServer { exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "", contentType = "text/plain")
            }.use { server ->
                ClickHouseClient.close()
                ClickHouseClient.init(server.baseUrl, "test", "default", "")

                assertTrue(repository.insertErrorEvent(errorEventData()))
                assertTrue(repository.insertTransaction(transactionEventData()))
                repository.insertSpans(listOf(spanData()))
                assertTrue(repository.insertFeedback(feedbackData()))
                assertTrue(repository.insertReplayEvent(replayEventData()))
                repository.insertReplayRecording(replayRecordingData())
                assertTrue(repository.insertLlmGenerations(listOf(llmGenerationData())))
            }
        } finally {
            ClickHouseClient.close()
        }

        val compactSql = queries.joinToString("\n").replace(Regex("\\s+"), " ")
        assertTrue(compactSql.contains("event_id, service_id, project_id, organization_id"))
        assertTrue(compactSql.contains("transaction_id, service_id, project_id, organization_id"))
        assertTrue(
            compactSql.contains(
                "span_id, parent_span_id, trace_id, transaction_id, service_id, project_id, organization_id"
            )
        )
        assertTrue(compactSql.contains("feedback_id, service_id, project_id, organization_id"))
        assertTrue(compactSql.contains("replay_id, service_id, project_id, organization_id"))
        assertTrue(compactSql.contains("generation_id, service_id, project_id, organization_id"))
        assertTrue(compactSql.contains("42, 7"))
        assertTrue(compactSql.contains("service_id, project_id, organization_id, bucket_start"))
        assertTrue(compactSql.contains("service_id, project_id, organization_id, issue_id"))
    }

    private fun errorEventData(): ErrorEventInsertData =
        ErrorEventInsertData(
            eventId = "11111111-1111-1111-1111-111111111111",
            projectId = 42L,
            organizationId = 7,
            timestampMs = 1767225600000L,
            level = "error",
            message = "boom",
            platform = "jvm",
            environment = "production",
            release = "1.0.0",
            dist = "",
            serverName = "api",
            userId = "user-1",
            userEmail = "user@example.com",
            userUsername = "user",
            userIpAddress = "127.0.0.1",
            exceptionType = "Error",
            exceptionValue = "boom",
            stackTrace = "stack",
            fingerprint = listOf("fp"),
            issueId = "issue-1",
            tags = mapOf("service.name" to "checkout"),
            contexts = "{}",
            breadcrumbs = "[]",
            request = "{}",
            sdkName = "sentry",
            sdkVersion = "1.0.0"
        )

    private fun transactionEventData(): TransactionEventInsertData =
        TransactionEventInsertData(
            eventId = "22222222-2222-2222-2222-222222222222",
            projectId = 42L,
            organizationId = 7,
            timestampMs = 1767225600000L,
            level = "info",
            message = "GET /checkout",
            platform = "jvm",
            environment = "production",
            release = "1.0.0",
            dist = "",
            serverName = "api",
            userId = "user-1",
            userEmail = "user@example.com",
            userUsername = "user",
            userIpAddress = "127.0.0.1",
            transactionName = "GET /checkout",
            transactionOp = "http.server",
            durationMs = 125.0,
            tags = mapOf("service.name" to "checkout"),
            contexts = "{}",
            breadcrumbs = "[]",
            request = "{}",
            sdkName = "sentry",
            sdkVersion = "1.0.0"
        )

    private fun spanData(): SpanInsertData =
        SpanInsertData(
            spanId = "span-1",
            parentSpanId = "root",
            traceId = "trace-1",
            transactionId = "22222222-2222-2222-2222-222222222222",
            projectId = 42L,
            organizationId = 7,
            op = "db.query",
            description = "SELECT 1",
            startTimestampMs = 1767225600000L,
            endTimestampMs = 1767225600100L,
            durationMs = 100.0,
            status = "ok",
            tags = mapOf("db.system" to "postgresql"),
            data = "{}"
        )

    private fun feedbackData(): FeedbackInsertData =
        FeedbackInsertData(
            feedbackId = "33333333-3333-3333-3333-333333333333",
            projectId = 42L,
            organizationId = 7,
            timestampMs = 1767225600000L,
            message = "help",
            contactEmail = "user@example.com",
            name = "User",
            url = "https://example.com",
            associatedEventId = "11111111-1111-1111-1111-111111111111",
            replayId = "",
            environment = "production",
            release = "1.0.0",
            platform = "browser",
            userId = "user-1",
            userEmail = "user@example.com",
            userUsername = "user",
            userIpAddress = "127.0.0.1",
            sdkName = "sentry",
            sdkVersion = "1.0.0",
            tags = mapOf("service.name" to "checkout")
        )

    private fun replayEventData(): ReplayEventInsertData =
        ReplayEventInsertData(
            replayId = "44444444-4444-4444-4444-444444444444",
            projectId = 42L,
            organizationId = 7,
            segmentId = 0,
            timestampMs = 1767225600000L,
            replayStartTimestampMs = 1767225599000L,
            urls = listOf("https://example.com"),
            errorIds = listOf("11111111-1111-1111-1111-111111111111"),
            traceIds = listOf("trace-1"),
            environment = "production",
            release = "1.0.0",
            platform = "browser",
            userId = "user-1",
            userEmail = "user@example.com",
            userUsername = "user",
            userIpAddress = "127.0.0.1",
            sdkName = "sentry",
            sdkVersion = "1.0.0",
            browserName = "Chrome",
            browserVersion = "120",
            osName = "macOS",
            osVersion = "14",
            deviceName = "Mac",
            deviceFamily = "desktop",
            activity = 1,
            tags = "{}"
        )

    private fun replayRecordingData(): ReplayRecordingInsertData =
        ReplayRecordingInsertData(
            replayId = "44444444-4444-4444-4444-444444444444",
            projectId = 42L,
            organizationId = 7,
            segmentId = 0,
            timestampMs = 1767225600000L,
            recordingData = "[]"
        )

    private fun llmGenerationData(): LlmGenerationInsertData =
        LlmGenerationInsertData(
            generationId = "55555555-5555-5555-5555-555555555555",
            projectId = 42L,
            organizationId = 7,
            traceId = "trace-1",
            spanId = "span-1",
            parentSpanId = "root",
            timestampMs = 1767225600000L,
            durationMs = 500.0,
            name = "chat",
            model = "gpt-4",
            provider = "openai",
            type = "chat",
            input = "{}",
            output = "{}",
            inputTokens = 10,
            outputTokens = 20,
            totalTokens = 30,
            status = "ok",
            userId = "user-1",
            environment = "production",
            release = "1.0.0",
            tags = mapOf("service.name" to "checkout")
        )
}
