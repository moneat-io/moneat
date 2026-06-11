// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the security contract that connection API responses never carry secret
 * material — only type, name, identifier tags, and the last four characters.
 */
class ConnectionApiResponseTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `connection response never serializes a secret`() {
        val response = ConnectionResponse(
            id = "11111111-1111-1111-1111-111111111111",
            type = "slack",
            name = "prod-alerts",
            identifierTags = mapOf("env" to "prod"),
            lastFour = "1234",
            createdAt = "2026-05-29T00:00:00Z",
            updatedAt = "2026-05-29T00:00:00Z"
        )
        val serialized = json.encodeToString(response)
        val payload = json.parseToJsonElement(serialized).jsonObject
        assertFalse("secret" in payload, "response must not contain a secret field")
        assertTrue("last_four" in payload)
        assertTrue("identifier_tags" in payload)
    }

    @Test
    fun `connection group response only exposes member ids and strategy`() {
        val response = ConnectionGroupResponse(
            id = "22222222-2222-2222-2222-222222222222",
            name = "pagerduty-by-env",
            connectionType = "pagerduty",
            memberConnectionIds = listOf(
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444"
            ),
            selectionStrategy = "first_match",
            createdAt = "2026-05-29T00:00:00Z",
            updatedAt = "2026-05-29T00:00:00Z"
        )
        val serialized = json.encodeToString(response)
        val payload = json.parseToJsonElement(serialized).jsonObject
        assertFalse("secret" in payload)
        assertTrue("member_connection_ids" in payload)
        assertTrue("selection_strategy" in payload)
    }
}
