// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushNotificationServiceTest {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
    }

    @Test
    fun `critical iOS messages request the APNs critical default sound`() {
        val message =
            buildOnCallMessage(
                device = PushNotificationService.RegisteredDevice("ExponentPushToken[ios]", "IOS"),
                content =
                    OnCallMessageContent(
                        title = "[P0] Host down",
                        body = "Tap to view incident details",
                        data = mapOf("incidentId" to "7f9a9ea6-83a1-4aaf-a028-6839f3ad45d6"),
                        isCritical = true,
                        categoryId = "INCIDENT_ALERT",
                    ),
            )

        val sound = message.sound as JsonObject
        assertEquals(JsonPrimitive(true), sound["critical"])
        assertEquals(JsonPrimitive("default"), sound["name"])
        assertEquals(JsonPrimitive(1.0), sound["volume"])
        assertEquals("critical", message.channelId)
        assertEquals("critical", message.interruptionLevel)
        assertEquals("INCIDENT_ALERT", message.categoryId)
        assertTrue(
            json.encodeToString(message).contains(
                "\"sound\":{\"critical\":true,\"name\":\"default\",\"volume\":1.0}",
            ),
        )
    }

    @Test
    fun `critical Android messages retain ordinary sound for the critical channel`() {
        val message =
            buildOnCallMessage(
                device = PushNotificationService.RegisteredDevice("ExponentPushToken[android]", "ANDROID"),
                content =
                    OnCallMessageContent(
                        title = "[P0] Host down",
                        body = "Tap to view incident details",
                        data = emptyMap(),
                        isCritical = true,
                        categoryId = null,
                    ),
            )

        assertEquals(JsonPrimitive("default"), message.sound)
        assertEquals("critical", message.channelId)
        assertEquals("critical", message.interruptionLevel)
    }

    @Test
    fun `non-critical iOS messages retain the standard notification sound`() {
        val message =
            buildOnCallMessage(
                device = PushNotificationService.RegisteredDevice("ExponentPushToken[ios]", "IOS"),
                content =
                    OnCallMessageContent(
                        title = "[P2] Elevated latency",
                        body = "Tap to view incident details",
                        data = emptyMap(),
                        isCritical = false,
                        categoryId = "INCIDENT_ALERT",
                    ),
            )

        assertEquals(JsonPrimitive("default"), message.sound)
        assertEquals("default", message.channelId)
        assertNull(message.interruptionLevel)
    }
}
