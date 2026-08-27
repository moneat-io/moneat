// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertEpisodeContext
import com.moneat.alerts.services.AlertFanoutContext
import com.moneat.alerts.services.AlertRouteExecutionOutcome
import com.moneat.alerts.services.AlertRouteExecutionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AlertRouteSlackCardServiceTest {
    @Test
    fun `card contains alert context and shared action ids`() {
        val episodeId = Uuid.random()
        val context = AlertFanoutContext(
            event = AlertLifecycleEvent(
                title = "Checkout latency",
                description = "Requests are timing out",
                priority = AlertPriority.P1,
                status = AlertStatus.FIRING,
                source = AlertSource.UPTIME_MONITOR,
                deduplicationKey = "checkout:latency",
                organizationId = 7,
                moneatUrl = "https://moneat.example/alerts/$episodeId",
            ),
            episodeDecision = null,
            episode = AlertEpisodeContext(
                id = 1,
                resourceId = episodeId,
                organizationId = 7,
                source = "UPTIME_MONITOR",
                deduplicationKey = "checkout:latency",
                title = "Checkout latency",
                description = "Requests are timing out",
                priority = "P1",
                episodeSeq = 1,
                episodeKey = "checkout:latency#1",
                status = "FIRING",
                openedAt = Instant.parse("2026-08-25T00:00:00Z"),
                lastSeenAt = Instant.parse("2026-08-25T00:00:00Z"),
                resolvedAt = null,
                lastNotificationAt = null,
                notificationCount = 1,
                suppressedAt = null,
                suppressedByUserId = null,
                suppressReason = null,
            ),
            deliverySilenced = false,
        )
        val outcome = AlertRouteExecutionOutcome(
            state = AlertRouteExecutionState.MATCHED,
            matchedRouteId = "route-1",
            matchedRouteRevision = 3,
            groupId = "group-1",
        )

        val payload = Json.parseToJsonElement(
            AlertRouteSlackCardService().cardPayload(context, outcome, "C123"),
        ).jsonObject
        val blocks = payload["blocks"]!!.jsonArray
        val actions = blocks.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "actions" }
            .flatMap { it.jsonObject["elements"]!!.jsonArray }
            .mapNotNull { element ->
                element.jsonObject["action_id"]?.jsonPrimitive?.content?.let { actionId ->
                    actionId to element.jsonObject["value"]?.jsonPrimitive?.content
                }
            }.toMap()

        assertEquals("C123", payload["channel"]?.jsonPrimitive?.content)
        assertTrue("declare_incident" in actions)
        assertTrue("acknowledge_alert" in actions)
        assertEquals(episodeId.toString(), actions["confirm_grouping"])
        assertEquals(episodeId.toString(), actions["unrelated_alert"])
        assertEquals(episodeId.toString(), actions["merge_alert_group"])
        assertTrue(payload.toString().contains("checkout:latency"))
    }
}
