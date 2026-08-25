// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall

import com.moneat.enterprise.incidents.config.IncidentCustomFieldDefinition
import com.moneat.enterprise.incidents.config.IncidentCustomFieldOption
import com.moneat.enterprise.incidents.config.IncidentFormDefinition
import com.moneat.enterprise.incidents.config.IncidentFormFieldDefinition
import com.moneat.enterprise.incidents.models.IncidentCustomFieldValueType
import com.moneat.enterprise.incidents.models.IncidentFormStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
    fun `declaration modal renders configured fields`() {
        val form = IncidentFormDefinition(
            id = "declaration-form",
            stage = IncidentFormStage.DECLARATION,
            version = 1,
            name = "Incident declaration",
            fields = listOf(
                IncidentFormFieldDefinition(
                    id = "impact-field",
                    field = IncidentCustomFieldDefinition(
                        id = "impact",
                        key = "customer_impact",
                        version = 1,
                        name = "Customer impact",
                        valueType = IncidentCustomFieldValueType.SELECT,
                        options = listOf(
                            IncidentCustomFieldOption("customer", "customer", "Customer", 1),
                            IncidentCustomFieldOption("internal", "internal", "Internal", 2),
                        ),
                    ),
                    position = 1,
                    visible = true,
                    required = true,
                    defaultValue = JsonPrimitive("customer"),
                    helpText = "Who is affected?",
                ),
                IncidentFormFieldDefinition(
                    id = "accounts-field",
                    field = IncidentCustomFieldDefinition(
                        id = "accounts",
                        key = "named_accounts",
                        version = 1,
                        name = "Named accounts",
                        valueType = IncidentCustomFieldValueType.MULTI_SELECT,
                        options = listOf(
                            IncidentCustomFieldOption("acme", "acme", "Acme", 1),
                        ),
                    ),
                    position = 2,
                    visible = true,
                    required = false,
                ),
            ),
        )

        val blocks = slackIncidentDeclarationView(null, form)["blocks"]?.jsonArray

        assertEquals(5, blocks?.size)
        assertEquals("field_customer_impact", blocks?.get(3)?.jsonObject?.get("block_id")?.toString()?.trim('"'))
        assertEquals("static_select", blocks?.get(3)?.jsonObject?.get("element")?.jsonObject?.get("type")
            ?.toString()?.trim('"'))
        assertEquals(false, blocks?.get(3)?.jsonObject?.get("optional")?.toString()?.toBoolean())
        assertEquals("Who is affected?", blocks?.get(3)?.jsonObject?.get("hint")?.jsonObject?.get("text")
            ?.toString()?.trim('"'))
        assertEquals("field_named_accounts", blocks?.get(4)?.jsonObject?.get("block_id")?.toString()?.trim('"'))
        assertEquals("multi_static_select", blocks?.get(4)?.jsonObject?.get("element")?.jsonObject?.get("type")
            ?.toString()?.trim('"'))
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

    @Test
    fun `alert-card interactions require a linked Slack identity`() = runBlocking {
        val module = OnCallModule()
        val response = module.handleSlackInbound(
            "interactions",
            "payload=%7B%22type%22%3A%22block_actions%22%2C%22actions%22%3A" +
                "%5B%7B%22action_id%22%3A%22acknowledge_alert%22%2C%22value%22%3A%22episode%22%7D%5D%7D",
            "delivery-1",
        )

        assertTrue(response?.contains("not linked") == true)
    }
}
