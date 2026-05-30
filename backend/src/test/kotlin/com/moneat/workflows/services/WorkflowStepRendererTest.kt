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

package com.moneat.workflows.services

import com.moneat.workflows.models.WorkflowStepConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowStepRendererTest {

    private val renderer = WorkflowStepRenderer()

    // ──── sampleScopeForTrigger ────

    @Test
    fun `firing alert sample scope carries high-severity firing fields`() {
        val scope = renderer.sampleScopeForTrigger(ALERT_TRIGGERED_TRIGGER)
        assertEquals("FIRING", scope[ALERT_STATUS_REFERENCE])
        assertEquals("HIGH", scope[ALERT_SEVERITY_REFERENCE])
        assertEquals("[P1]", scope[ALERT_PRIORITY_REFERENCE])
        assertEquals("12.00", scope[ALERT_CURRENT_VALUE_REFERENCE])
    }

    @Test
    fun `resolved alert sample scope flips status severity and value`() {
        val scope = renderer.sampleScopeForTrigger(ALERT_RESOLVED_TRIGGER)
        assertEquals("RESOLVED", scope[ALERT_STATUS_REFERENCE])
        assertEquals("LOW", scope[ALERT_SEVERITY_REFERENCE])
        assertEquals("0.00", scope[ALERT_CURRENT_VALUE_REFERENCE])
    }

    @Test
    fun `non-alert triggers produce their dedicated sample scopes`() {
        assertTrue(renderer.sampleScopeForTrigger(MANUAL_TRIGGER).containsKey("workflow.actor_id"))
        assertTrue(renderer.sampleScopeForTrigger(API_TRIGGER).containsKey("workflow.caller"))
        assertTrue(renderer.sampleScopeForTrigger(WEBHOOK_TRIGGER).containsKey("webhook.payload"))
        assertEquals("created", renderer.sampleScopeForTrigger(INCIDENT_CREATED_TRIGGER)["incident.status"])
        assertEquals("resolved", renderer.sampleScopeForTrigger(INCIDENT_RESOLVED_TRIGGER)["incident.status"])
        assertTrue(renderer.sampleScopeForTrigger(SECURITY_SIGNAL_TRIGGER).containsKey("security.rule_id"))
    }

    // ──── channelForStep / priorityLabelForSeverity ────

    @Test
    fun `channelForStep maps known steps and falls back to workflow`() {
        assertEquals(EMAIL_CHANNEL, renderer.channelForStep(EMAIL_ORG_STEP))
        assertEquals(SLACK_CHANNEL, renderer.channelForStep(SLACK_STEP))
        assertEquals(DISCORD_CHANNEL, renderer.channelForStep(DISCORD_STEP))
        assertEquals("workflow", renderer.channelForStep("moneat.logs.search"))
    }

    @Test
    fun `priorityLabelForSeverity maps each severity`() {
        assertEquals("[P0]", renderer.priorityLabelForSeverity("CRITICAL"))
        assertEquals("[P1]", renderer.priorityLabelForSeverity("high"))
        assertEquals("[P2]", renderer.priorityLabelForSeverity("MEDIUM"))
        assertEquals("[P3]", renderer.priorityLabelForSeverity("LOW"))
        assertEquals("", renderer.priorityLabelForSeverity(null))
        assertEquals("", renderer.priorityLabelForSeverity("INFO"))
    }

    // ──── renderStepPreview: freeform ────

    @Test
    fun `freeform email preview interpolates subject and body`() {
        val preview =
            renderer.renderStepPreview(
                WorkflowStepConfig(
                    EMAIL_ORG_STEP,
                    mapOf("subject" to "Re: {{alert.title}}", "body" to "Body {{alert.status}}")
                ),
                mapOf(ALERT_TITLE_REFERENCE to "Outage", ALERT_STATUS_REFERENCE to "FIRING")
            )
        assertEquals(EMAIL_CHANNEL, preview.channel)
        assertEquals("Re: Outage", preview.subject)
        assertEquals("Body FIRING", preview.body)
        assertNotNull(preview.htmlBody)
    }

    @Test
    fun `freeform slack preview uses message param`() {
        val preview =
            renderer.renderStepPreview(
                WorkflowStepConfig(SLACK_STEP, mapOf("message" to "hi {{alert.title}}")),
                mapOf(ALERT_TITLE_REFERENCE to "Outage")
            )
        assertEquals(SLACK_CHANNEL, preview.channel)
        assertEquals("hi Outage", preview.body)
    }

    @Test
    fun `freeform discord preview uses title and message`() {
        val preview =
            renderer.renderStepPreview(
                WorkflowStepConfig(DISCORD_STEP, mapOf("title" to "T", "message" to "M")),
                emptyMap()
            )
        assertEquals(DISCORD_CHANNEL, preview.channel)
        assertEquals("T", preview.title)
        assertEquals("M", preview.body)
        assertEquals(DEFAULT_WORKFLOW_TITLE, preview.footer)
    }

    @Test
    fun `unknown step yields generic workflow preview`() {
        val preview = renderer.renderStepPreview(WorkflowStepConfig("moneat.logs.search"), emptyMap())
        assertEquals("workflow", preview.channel)
        assertEquals("moneat.logs.search", preview.title)
    }

    // ──── renderStepPreview: alert lifecycle ────

    private fun lifecycleStep(name: String): WorkflowStepConfig =
        WorkflowStepConfig(name, mapOf(FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT))

    @Test
    fun `lifecycle slack preview builds firing title fields and red color`() {
        val preview =
            renderer.renderStepPreview(
                lifecycleStep(SLACK_STEP),
                mapOf(
                    ALERT_DISPLAY_TITLE_REFERENCE to "Worker failures",
                    ALERT_STATUS_REFERENCE to "FIRING",
                    ALERT_SEVERITY_REFERENCE to "HIGH",
                    ALERT_SOURCE_REFERENCE to "DASHBOARD_ALERT",
                    ALERT_CONDITION_REFERENCE to ">",
                    ALERT_THRESHOLD_REFERENCE to "5.00",
                    ALERT_URL_REFERENCE to "https://moneat.io/x"
                )
            )
        assertEquals("[P1] Worker failures", preview.title)
        assertEquals("#E01E5A", preview.color)
        assertEquals(VIEW_CTA_LABEL, preview.ctaLabel)
        assertEquals("Dashboard alert", preview.footer)
        assertTrue(preview.fields.any { it.label == "Threshold" && it.value == "> 5.00" })
        assertTrue(preview.fields.any { it.label == "Status" && it.value == "Firing" })
        // Slack lifecycle previews carry no html body.
        assertNull(preview.htmlBody)
    }

    @Test
    fun `lifecycle resolved email preview is green prefixed and has html`() {
        val preview =
            renderer.renderStepPreview(
                lifecycleStep(EMAIL_ORG_STEP),
                mapOf(
                    ALERT_DISPLAY_TITLE_REFERENCE to "Worker failures",
                    ALERT_STATUS_REFERENCE to "RESOLVED",
                    ALERT_SEVERITY_REFERENCE to "LOW"
                )
            )
        // Priority prefix is retained ahead of the "Resolved" marker.
        assertEquals("[P3] Resolved: Worker failures", preview.title)
        assertEquals("#2EB67D", preview.color)
        assertEquals("[Moneat] [P3] Resolved: Worker failures", preview.subject)
        val htmlBody = preview.htmlBody
        assertNotNull(htmlBody)
        assertTrue(htmlBody.contains("Added by Moneat"))
    }

    @Test
    fun `lifecycle medium severity uses yellow and derives title from raw alert title`() {
        val preview =
            renderer.renderStepPreview(
                lifecycleStep(SLACK_STEP),
                mapOf(
                    ALERT_TITLE_REFERENCE to "Dashboard Warning: Disk pressure",
                    ALERT_STATUS_REFERENCE to "FIRING",
                    ALERT_SEVERITY_REFERENCE to "MEDIUM"
                )
            )
        assertEquals("#ECB22E", preview.color)
        // Title prefix is stripped and priority derived from severity.
        assertEquals("[P2] Disk pressure", preview.title)
        // No URL means no CTA.
        assertNull(preview.ctaUrl)
    }

    @Test
    fun `lifecycle falls back to default body and humanizes unknown source`() {
        val preview =
            renderer.renderStepPreview(
                lifecycleStep(SLACK_STEP),
                mapOf(
                    ALERT_DISPLAY_TITLE_REFERENCE to "Thing",
                    ALERT_STATUS_REFERENCE to "FIRING",
                    ALERT_SOURCE_REFERENCE to "CUSTOM_SOURCE"
                )
            )
        assertEquals("Moneat detected an alert lifecycle event.", preview.body)
        // Unknown source is humanized with each word capitalized.
        assertEquals("Custom Source", preview.footer)
    }

    @Test
    fun `lifecycle resolved without priority prefixes only Resolved and maps known source`() {
        val preview =
            renderer.renderStepPreview(
                lifecycleStep(SLACK_STEP),
                mapOf(
                    ALERT_DISPLAY_TITLE_REFERENCE to "Latency",
                    ALERT_STATUS_REFERENCE to "RESOLVED",
                    ALERT_SOURCE_REFERENCE to "UPTIME_MONITOR"
                )
            )
        assertEquals("Resolved: Latency", preview.title)
        assertEquals("Uptime monitor", preview.footer)
        assertEquals("#2EB67D", preview.color)
    }

    // ──── interpolate / extension helpers ────

    @Test
    fun `interpolate replaces every reference token`() {
        assertEquals(
            "a-b",
            interpolate("{{x}}-{{y}}", mapOf("x" to "a", "y" to "b"))
        )
    }

    @Test
    fun `usesAlertLifecycleFormat detects the format param`() {
        val lifecycle = WorkflowStepConfig(SLACK_STEP, mapOf(FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT))
        assertTrue(lifecycle.usesAlertLifecycleFormat())
        assertFalse(WorkflowStepConfig(SLACK_STEP).usesAlertLifecycleFormat())
    }

    @Test
    fun `escapeHtml and preformattedHtml escape markup`() {
        assertEquals("&lt;a&gt;&amp;&quot;", "<a>&\"".escapeHtml())
        assertTrue("<x>".preformattedHtml().contains("&lt;x&gt;"))
    }
}
