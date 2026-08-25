// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackIncidentDeclarationViewTest {
    @Test
    fun `declaration modal preserves context and offers supported severities`() {
        val view = slackIncidentDeclarationView("Checkout API is timing out")
        val blocks = view["blocks"]?.jsonArray

        assertEquals("moneat_incident_declaration", view["callback_id"]?.toString()?.trim('"'))
        assertEquals("Checkout API is timing out", blocks?.first()?.jsonObject?.get("element")
            ?.jsonObject?.get("initial_value")?.toString()?.trim('"'))
        assertEquals(3, blocks?.size)
        assertNotNull(blocks?.last()?.jsonObject?.get("element")?.jsonObject?.get("options"))
    }

    @Test
    fun `view value reader handles plain and selected option inputs`() {
        val values = Json.parseToJsonElement(
            """
            {
              "title": {"value": {"type": "plain_text_input", "value": "Database outage"}},
              "severity": {"value": {"type": "static_select", "selected_option": {"value": "SEV-1"}}}
            }
            """,
        ).jsonObject

        assertEquals("Database outage", slackViewValue(values, "title"))
        assertEquals("SEV-1", slackViewValue(values, "severity"))
    }

    @Test
    fun `view errors keep the modal open for mobile correction`() {
        val response = Json.parseToJsonElement(slackViewErrors(mapOf("title" to "Required"))).jsonObject

        assertEquals("errors", response["response_action"]?.toString()?.trim('"'))
        assertEquals("Required", response["errors"]?.jsonObject?.get("title")?.toString()?.trim('"'))
    }

    @Test
    fun `event and mention deliveries do not open incident forms`() = runBlocking {
        val module = OnCallModule()

        assertNull(module.handleSlackInbound("events", "", null))
        assertNull(module.handleSlackInbound("mentions", "", null))
    }

    @Test
    fun `slash and interaction requests require workspace identity`() = runBlocking {
        val module = OnCallModule()

        val slashResponse = module.handleSlackInbound("commands", "", null)
        val interactionResponse = module.handleSlackInbound(
            "interactions",
            "payload=%7B%22type%22%3A%22view_submission%22%7D",
            null,
        )

        assertTrue(slashResponse?.contains("workspace and user context") == true)
        assertTrue(interactionResponse?.contains("Link your Slack identity") == true)
    }
}
