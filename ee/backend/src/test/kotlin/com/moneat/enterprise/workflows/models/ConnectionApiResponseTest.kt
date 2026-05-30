// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            id = 1,
            type = "slack",
            name = "prod-alerts",
            identifierTags = mapOf("env" to "prod"),
            lastFour = "1234",
            createdAt = "2026-05-29T00:00:00Z",
            updatedAt = "2026-05-29T00:00:00Z"
        )
        val serialized = json.encodeToString(response)
        assertFalse(serialized.contains("secret"), "response must not contain a secret field")
        assertTrue(serialized.contains("last_four"))
        assertTrue(serialized.contains("identifier_tags"))
    }

    @Test
    fun `connection group response only exposes member ids and strategy`() {
        val response = ConnectionGroupResponse(
            id = 5,
            name = "pagerduty-by-env",
            connectionType = "pagerduty",
            memberConnectionIds = listOf(1, 2),
            selectionStrategy = "first_match",
            createdAt = "2026-05-29T00:00:00Z",
            updatedAt = "2026-05-29T00:00:00Z"
        )
        val serialized = json.encodeToString(response)
        assertFalse(serialized.contains("secret"))
        assertTrue(serialized.contains("member_connection_ids"))
        assertTrue(serialized.contains("selection_strategy"))
    }
}
