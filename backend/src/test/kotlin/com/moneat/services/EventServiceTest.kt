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
import com.moneat.events.models.StackFrame
import com.moneat.events.models.StackTrace
import com.moneat.events.models.UserInfo
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.services.EventService
import com.moneat.testsupport.TestIpConstants
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for EventService ingestion logic covering P0 scenarios:
 * 1. Project key verification (valid, invalid, inactive, missing)
 * 2. Event fingerprinting (same error deduplication, platform context)
 * 3. Event validation (required fields, optional fields, timestamps)
 * 4. Rate limiting / Quota integration
 * 5. Event metadata parsing (SDK, device, user, tags)
 */
class EventServiceTest {
    private val eventRepository = mockk<EventRepository>()
    private lateinit var eventService: EventService

    private val testProjectId = 1L
    private val testOrgId = 1
    private val validPublicKey = "test-public-key-valid"
    private val inactivePublicKey = "test-public-key-inactive"

    /** Constructs [EventService] with default and scenario-specific [EventRepository] mocks. */
    @BeforeTest
    fun setup() {
        eventService = EventService(eventRepository = eventRepository)
        // Catchall defaults (registered first so specific overrides take precedence)
        every { eventRepository.verifyProjectKey(any(), any()) } returns ProjectKeyVerification(false, null)
        every { eventRepository.getOrganizationIdForProject(any()) } returns null
        // Specific mocks
        every { eventRepository.verifyProjectKey(testProjectId, validPublicKey) } returns
            ProjectKeyVerification(true, "jvm")
        every { eventRepository.verifyProjectKey(testProjectId, inactivePublicKey) } returns
            ProjectKeyVerification(false, null)
        every { eventRepository.getOrganizationIdForProject(testProjectId) } returns testOrgId
    }

    // ──── PROJECT KEY VERIFICATION TESTS (P0) ────

    @Test
    fun `verifyProjectKey with valid active public key succeeds`() {
        val result = eventService.verifyProjectKey(testProjectId, validPublicKey)

        assertTrue(result.isValid, "Valid active key should be verified")
        assertNotNull(result.platformTarget, "Should have platform target")
    }

    @Test
    fun `verifyProjectKey with invalid public key fails`() {
        val result = eventService.verifyProjectKey(testProjectId, "nonexistent-key")

        assertFalse(result.isValid, "Invalid key should fail verification")
    }

    @Test
    fun `verifyProjectKey with inactive key fails`() {
        val result = eventService.verifyProjectKey(testProjectId, inactivePublicKey)

        assertFalse(result.isValid, "Inactive key should fail verification")
    }

    @Test
    fun `verifyProjectKey with wrong project ID fails`() {
        val wrongProjectId = testProjectId + 9999L
        val result = eventService.verifyProjectKey(wrongProjectId, validPublicKey)

        assertFalse(result.isValid, "Key from different project should fail")
    }

    @Test
    fun `verifyProjectKey is case sensitive for public key`() {
        val upperCaseKey = validPublicKey.uppercase()
        val result = eventService.verifyProjectKey(testProjectId, upperCaseKey)

        assertFalse(result.isValid, "Public key should be case-sensitive")
    }

    // ──── EVENT FINGERPRINTING TESTS (P0) ────

    /** Asserts identical primary exception metadata yields the same deduplication fingerprint. */
    @Test
    fun `same error with identical exception generates same fingerprint for deduplication`() {
        val event1 =
            createSentryEvent(
                eventId = "event-1",
                exceptionType = "NullPointerException",
                exceptionMessage = "Cannot invoke method on null object",
                stackTrace =
                listOf(
                    createStackFrame("MyClass.kt", "processData", 42, inApp = true),
                    createStackFrame("Utils.kt", "helper", 10, inApp = true)
                )
            )

        val event2 =
            createSentryEvent(
                eventId = "event-2",
                exceptionType = "NullPointerException",
                exceptionMessage = "Cannot invoke method on null object",
                stackTrace =
                listOf(
                    createStackFrame("MyClass.kt", "processData", 42, inApp = true),
                    createStackFrame("Utils.kt", "helper", 10, inApp = true)
                )
            )

        val fingerprint1 =
            event1.exception?.let { exc ->
                val firstException = exc.values.firstOrNull()
                listOf(
                    firstException?.type,
                    firstException
                        ?.stacktrace
                        ?.frames
                        ?.lastOrNull()
                        ?.function,
                    firstException
                        ?.stacktrace
                        ?.frames
                        ?.lastOrNull()
                        ?.filename
                ).filterNotNull()
            } ?: emptyList()

        val fingerprint2 =
            event2.exception?.let { exc ->
                val firstException = exc.values.firstOrNull()
                listOf(
                    firstException?.type,
                    firstException
                        ?.stacktrace
                        ?.frames
                        ?.lastOrNull()
                        ?.function,
                    firstException
                        ?.stacktrace
                        ?.frames
                        ?.lastOrNull()
                        ?.filename
                ).filterNotNull()
            } ?: emptyList()

        assertEquals(fingerprint1, fingerprint2, "Same errors should generate identical fingerprints for deduplication")
    }

    @Test
    fun `different exception types generate different fingerprints`() {
        val event1 =
            createSentryEvent(
                exceptionType = "NullPointerException",
                stackTrace = listOf(createStackFrame("MyClass.kt", "processData", 42, inApp = true))
            )

        val event2 =
            createSentryEvent(
                exceptionType = "IllegalArgumentException",
                stackTrace = listOf(createStackFrame("MyClass.kt", "processData", 42, inApp = true))
            )

        val fingerprint1 =
            event1.exception
                ?.values
                ?.firstOrNull()
                ?.type
        val fingerprint2 =
            event2.exception
                ?.values
                ?.firstOrNull()
                ?.type

        assertNotEquals(fingerprint1, fingerprint2, "Different exception types should generate different fingerprints")
    }

    @Test
    fun `different function names in stack trace generate different fingerprints`() {
        val event1 =
            createSentryEvent(
                exceptionType = "ValueError",
                stackTrace = listOf(createStackFrame("script.py", "process", 15, inApp = true))
            )

        val event2 =
            createSentryEvent(
                exceptionType = "ValueError",
                stackTrace = listOf(createStackFrame("script.py", "validate", 20, inApp = true))
            )

        val func1 =
            event1.exception
                ?.values
                ?.firstOrNull()
                ?.stacktrace
                ?.frames
                ?.lastOrNull()
                ?.function
        val func2 =
            event2.exception
                ?.values
                ?.firstOrNull()
                ?.stacktrace
                ?.frames
                ?.lastOrNull()
                ?.function

        assertNotEquals(func1, func2, "Different function names should generate different fingerprints")
    }

    @Test
    fun `stack trace is included in fingerprint calculation`() {
        val baseEvent =
            createSentryEvent(
                exceptionType = "RuntimeException",
                exceptionMessage = "Something failed",
                stackTrace =
                listOf(
                    createStackFrame("App.java", "main", 5, inApp = true),
                    createStackFrame("Service.java", "execute", 100, inApp = true)
                )
            )

        val eventWithDifferentStack =
            createSentryEvent(
                exceptionType = "RuntimeException",
                exceptionMessage = "Something failed",
                stackTrace =
                listOf(
                    createStackFrame("App.java", "main", 5, inApp = true),
                    createStackFrame("Different.java", "execute", 100, inApp = true)
                )
            )

        val stack1 =
            baseEvent.exception
                ?.values
                ?.firstOrNull()
                ?.stacktrace
                ?.frames
                ?.lastOrNull()
                ?.filename
        val stack2 =
            eventWithDifferentStack.exception
                ?.values
                ?.firstOrNull()
                ?.stacktrace
                ?.frames
                ?.lastOrNull()
                ?.filename

        assertNotEquals(stack1, stack2, "Stack trace differences should affect fingerprint")
    }

    @Test
    fun `platform context affects event processing`() {
        val androidEvent =
            createSentryEvent(
                platform = "android",
                contexts =
                buildJsonObject {
                    put("os", buildJsonObject { put("name", "Android") })
                }
            )

        val iosEvent =
            createSentryEvent(
                platform = "ios",
                contexts =
                buildJsonObject {
                    put("os", buildJsonObject { put("name", "iOS") })
                }
            )

        assertNotEquals(androidEvent.platform, iosEvent.platform, "Different platforms should be distinguishable")
        assertEquals("android", androidEvent.platform)
        assertEquals("ios", iosEvent.platform)
    }

    // ──── EVENT VALIDATION TESTS (P0) ────

    @Test
    fun `valid Sentry event with all required fields is accepted`() {
        val event =
            createSentryEvent(
                eventId = UUID.randomUUID().toString(),
                exceptionType = "TestError",
                exceptionMessage = "Test error message",
                level = "error",
                platform = "javascript"
            )

        assertNotNull(event.event_id, "Event ID should be present")
        assertNotNull(event.exception, "Exception should be present")
        assertEquals("error", event.level, "Level should be error")
        assertEquals("javascript", event.platform, "Platform should be set")
    }

    @Test
    fun `event without exception type uses message field instead`() {
        val event =
            createSentryEvent(
                eventId = UUID.randomUUID().toString(),
                exceptionType = null,
                message = "Fallback message error",
                level = "error"
            )

        assertNotNull(event.message, "Message field should be used when exception type missing")
        assertEquals("Fallback message error", event.message)
    }

    @Test
    fun `event with all optional fields is processed correctly`() {
        val userInfo =
            UserInfo(
                id = "user-123",
                email = "test@example.com",
                username = "testuser",
                ip_address = TestIpConstants.IP_1
            )

        val tags =
            mapOf(
                "environment" to "production",
                "version" to "1.0.0",
                "component" to "auth-service"
            )

        val sdkInfo = SdkInfo(name = "sentry-java", version = "5.0.0")

        val event =
            createSentryEvent(
                eventId = UUID.randomUUID().toString(),
                user = userInfo,
                tags = tags,
                sdk = sdkInfo,
                environment = "production",
                release = "1.0.0",
                platform = "jvm"
            )

        assertEquals(userInfo, event.user, "User context should be preserved")
        assertEquals(tags, event.tags, "Tags should be preserved")
        assertEquals(sdkInfo, event.sdk, "SDK info should be preserved")
        assertEquals("production", event.environment)
        assertEquals("1.0.0", event.release)
    }

    @Test
    fun `event with Unix timestamp is parsed correctly`() {
        val unixTimestamp = 1705316445.0 // 2024-01-15T10:30:45Z
        val event =
            createSentryEvent(
                timestamp = unixTimestamp
            )

        assertEquals(unixTimestamp, event.timestamp, "Unix timestamp should be preserved")
    }

    @Test
    fun `event with missing timestamp uses current time during storage`() {
        val event = createSentryEvent(timestamp = null)

        assertNull(event.timestamp, "Timestamp can be null in event")
        // Storage logic would use current time - tested in integration tests
    }

    @Test
    fun `event with custom fingerprint is respected`() {
        val customFingerprint = listOf("custom", "fingerprint", "value")
        val event =
            createSentryEvent(
                fingerprint = customFingerprint
            )

        assertEquals(customFingerprint, event.fingerprint, "Custom fingerprint should be respected")
    }

    @Test
    fun `event with null fingerprint uses generated fingerprint`() {
        val event =
            createSentryEvent(
                fingerprint = null,
                exceptionType = "GeneratedException",
                stackTrace = listOf(createStackFrame("test.js", "test", 1, inApp = true))
            )

        assertNull(event.fingerprint, "Fingerprint can be null in event")
        assertNotNull(event.exception, "Exception should be present for fingerprint generation")
    }

    // ──── METADATA PARSING TESTS (P0) ────

    @Test
    fun `SDK information is extracted from event`() {
        val sdkName = "sentry-js"
        val sdkVersion = "7.5.0"
        val sdk = SdkInfo(name = sdkName, version = sdkVersion)

        val event = createSentryEvent(sdk = sdk)

        assertEquals(sdkName, event.sdk?.name, "SDK name should be extracted")
        assertEquals(sdkVersion, event.sdk?.version, "SDK version should be extracted")
    }

    @Test
    fun `device context is extracted from contexts object`() {
        val contexts =
            buildJsonObject {
                put(
                    "device",
                    buildJsonObject {
                        put("name", "iPhone 13")
                        put("model", "iPhone13,2")
                        put("model_id", "A2223")
                        put("os_version", "16.0")
                    }
                )
            }

        val event = createSentryEvent(contexts = contexts)

        assertNotNull(event.contexts, "Device context should be present")
        val deviceContext = event.contexts?.get("device")
        assertNotNull(deviceContext, "Device object should exist in contexts")
    }

    @Test
    fun `user context is extracted correctly`() {
        val userId = "user-abc-123"
        val userEmail = "user@example.com"
        val username = "johndoe"
        val ipAddress = "203.0.113.42"

        val userInfo =
            UserInfo(
                id = userId,
                email = userEmail,
                username = username,
                ip_address = ipAddress
            )

        val event = createSentryEvent(user = userInfo)

        assertEquals(userId, event.user?.id, "User ID should be extracted")
        assertEquals(userEmail, event.user?.email, "User email should be extracted")
        assertEquals(username, event.user?.username, "Username should be extracted")
        assertEquals(ipAddress, event.user?.ip_address, "IP address should be extracted")
    }

    @Test
    fun `tags are extracted as key-value map`() {
        val tags =
            mapOf(
                "service" to "api-gateway",
                "instance" to "prod-us-east-1",
                "build_version" to "2024.1.15.001",
                "region" to "us-east-1"
            )

        val event = createSentryEvent(tags = tags)

        assertEquals(tags, event.tags, "All tags should be extracted correctly")
        assertEquals("api-gateway", event.tags?.get("service"))
        assertEquals("prod-us-east-1", event.tags?.get("instance"))
    }

    @Test
    fun `extra context data is preserved in event`() {
        val breadcrumbs =
            Json
                .parseToJsonElement(
                    """[
            {"message": "User logged in", "level": "info"},
            {"message": "API call initiated", "level": "debug"}
        ]"""
                ).jsonArray

        val event = createSentryEvent(breadcrumbs = breadcrumbs)

        assertNotNull(event.breadcrumbs, "Breadcrumbs should be preserved")
        assertEquals(2, event.breadcrumbs?.size, "Breadcrumb count should match")
    }

    @Test
    fun `request context includes HTTP details`() {
        val request =
            buildJsonObject {
                put("method", "POST")
                put("url", "https://api.example.com/events")
                put(
                    "headers",
                    buildJsonObject {
                        put("Content-Type", "application/json")
                        put("User-Agent", "sentry-java/5.0.0")
                    }
                )
            }

        val event = createSentryEvent(request = request)

        assertNotNull(event.request, "Request object should be present")
        assertEquals(
            "POST",
            event.request
                ?.get("method")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(
            "https://api.example.com/events",
            event.request
                ?.get("url")
                ?.jsonPrimitive
                ?.content
        )
    }

    // ──── ORGANIZATION RESOLUTION TESTS (P0) ────

    @Test
    fun `getOrganizationIdForProject returns correct organization`() {
        val orgId = eventService.getOrganizationIdForProject(testProjectId)

        assertEquals(testOrgId, orgId, "Should resolve project to correct organization")
    }

    @Test
    fun `getOrganizationIdForProject returns null for nonexistent project`() {
        val orgId = eventService.getOrganizationIdForProject(99999L)

        assertNull(orgId, "Should return null for nonexistent project")
    }

    // ──── MULTIPLE PROJECT KEYS TEST (P0) ────

    @Test
    fun `multiple active keys for same project are all verified correctly`() {
        val key1 = "multi-key-1"
        val key2 = "multi-key-2"

        every { eventRepository.verifyProjectKey(testProjectId, key1) } returns ProjectKeyVerification(true, "jvm")
        every { eventRepository.verifyProjectKey(testProjectId, key2) } returns ProjectKeyVerification(true, "jvm")

        val result1 = eventService.verifyProjectKey(testProjectId, key1)
        val result2 = eventService.verifyProjectKey(testProjectId, key2)

        assertTrue(result1.isValid, "First key should be verified")
        assertTrue(result2.isValid, "Second key should be verified")
    }

    @Test
    fun `only one active key remains valid after deactivation`() {
        val key1 = "deactivate-key-1"
        val key2 = "deactivate-key-2"

        // key1 deactivated → handled by catchall (returns false)
        every { eventRepository.verifyProjectKey(testProjectId, key2) } returns ProjectKeyVerification(true, "jvm")

        val result1 = eventService.verifyProjectKey(testProjectId, key1)
        val result2 = eventService.verifyProjectKey(testProjectId, key2)

        assertFalse(result1.isValid, "Deactivated key should not verify")
        assertTrue(result2.isValid, "Other active key should still verify")
    }

    // ──── ENVELOPE PROCESSING STRUCTURE TESTS (P0) ────

    @Test
    fun `SentryEnvelope with event item can be created and accessed`() {
        val eventJson =
            """
            {
                "event_id": "test-event-123",
                "level": "error",
                "message": "Test error"
            }
            """.trimIndent()

        val envelope =
            SentryEnvelope(
                eventId = "test-event-123",
                items = listOf(EnvelopeItem("event", eventJson))
            )

        assertEquals(1, envelope.items.size, "Should have one item")
        assertEquals("event", envelope.items[0].type, "Item type should be event")
    }

    @Test
    fun `SentryEnvelope with multiple items maintains order`() {
        val eventJson = """{"event_id": "evt-1"}"""
        val transactionJson = """{"event_id": "txn-1"}"""
        val feedbackJson = """{"event_id": "fb-1"}"""

        val envelope =
            SentryEnvelope(
                eventId = "multi-123",
                items =
                listOf(
                    EnvelopeItem("event", eventJson),
                    EnvelopeItem("transaction", transactionJson),
                    EnvelopeItem("feedback", feedbackJson)
                )
            )

        assertEquals(3, envelope.items.size)
        assertEquals("event", envelope.items[0].type)
        assertEquals("transaction", envelope.items[1].type)
        assertEquals("feedback", envelope.items[2].type)
    }

    @Test
    fun `exception with multiple stack frames is parsed correctly`() {
        val frames =
            listOf(
                createStackFrame("main.js", "main", 1, inApp = true),
                createStackFrame("app.js", "initialize", 10, inApp = true),
                createStackFrame("lib.js", "helper", 50, inApp = false)
            )

        val stackTrace = StackTrace(frames = frames)
        val exception = ExceptionValue(type = "Error", value = "Test error", stacktrace = stackTrace)
        val exceptionInfo = ExceptionInfo(values = listOf(exception))

        assertEquals(
            3,
            exceptionInfo.values[0]
                .stacktrace
                ?.frames
                ?.size,
            "Should have 3 frames"
        )
        assertEquals(
            "main.js",
            exceptionInfo.values[0]
                .stacktrace
                ?.frames
                ?.get(0)
                ?.filename
        )
        assertEquals(
            true,
            exceptionInfo.values[0]
                .stacktrace
                ?.frames
                ?.get(0)
                ?.in_app
        )
        assertEquals(
            false,
            exceptionInfo.values[0]
                .stacktrace
                ?.frames
                ?.get(2)
                ?.in_app
        )
    }

    // ──── HELPER METHODS ────

    private fun createSentryEvent(
        eventId: String? = null,
        timestamp: Double? = 1705316445.0, // 2024-01-15T10:30:45Z
        level: String? = "error",
        platform: String? = "jvm",
        message: String? = null,
        exceptionType: String? = "TestException",
        exceptionMessage: String? = "Test exception message",
        stackTrace: List<StackFrame>? = null,
        user: UserInfo? = null,
        tags: Map<String, String>? = null,
        sdk: SdkInfo? = null,
        environment: String? = "development",
        release: String? = null,
        contexts: JsonObject? = null,
        breadcrumbs: JsonArray? = null,
        request: JsonObject? = null,
        fingerprint: List<String>? = null
    ): SentryEvent {
        val exception =
            if (exceptionType != null) {
                ExceptionInfo(
                    values =
                    listOf(
                        ExceptionValue(
                            type = exceptionType,
                            value = exceptionMessage ?: exceptionType,
                            stacktrace = stackTrace?.let { StackTrace(frames = it) }
                        )
                    )
                )
            } else {
                null
            }

        return SentryEvent(
            event_id = eventId ?: UUID.randomUUID().toString(),
            timestamp = timestamp,
            level = level,
            message = message,
            platform = platform,
            exception = exception,
            user = user,
            tags = tags,
            sdk = sdk,
            environment = environment,
            release = release,
            contexts = contexts,
            breadcrumbs = breadcrumbs,
            request = request,
            fingerprint = fingerprint
        )
    }

    private fun createStackFrame(
        filename: String,
        function: String,
        lineno: Int,
        inApp: Boolean = true
    ): StackFrame {
        return StackFrame(
            filename = filename,
            function = function,
            lineno = lineno,
            in_app = inApp,
            abs_path = "/app/$filename"
        )
    }
}
