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

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertEpisodeContext
import com.moneat.alerts.services.AlertEpisodeDecision
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.shared.services.organizationResourceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class WorkflowAlertPublicationCoverageTest {
    @Test
    fun `firing workflow publication failure fails open for provider fanout`() = runBlocking {
        val episodeService = mockk<AlertEpisodeService>()
        every { episodeService.recordFiring(any(), any()) } returns alertDecision()
        mockkStatic("com.moneat.shared.services.PublicResourceIdsKt")
        try {
            every { organizationResourceId(any<Int>()) } returns "org-resource"
            val service = spyk(WorkflowService(alertEpisodeService = episodeService))
            coEvery { service.publishTrigger(any()) } throws IllegalStateException("workflow unavailable")

            assertTrue(service.publishAlertTriggered(alertEvent()))
            coVerify(exactly = 1) { service.publishTrigger(any()) }
        } finally {
            unmockkStatic("com.moneat.shared.services.PublicResourceIdsKt")
        }
    }

    @Test
    fun `resolved workflow publication failure fails open for provider fanout`() = runBlocking {
        val episodeService = mockk<AlertEpisodeService>()
        every { episodeService.recordResolved(any(), any()) } returns alertDecision()
        mockkStatic("com.moneat.shared.services.PublicResourceIdsKt")
        try {
            every { organizationResourceId(any<Int>()) } returns "org-resource"
            val service = spyk(WorkflowService(alertEpisodeService = episodeService))
            coEvery { service.publishTrigger(any()) } throws IllegalStateException("workflow unavailable")

            assertTrue(service.publishAlertTriggered(alertEvent().copy(status = AlertStatus.RESOLVED)))
            coVerify(exactly = 1) { service.publishTrigger(any()) }
        } finally {
            unmockkStatic("com.moneat.shared.services.PublicResourceIdsKt")
        }
    }

    private fun alertDecision(): AlertEpisodeDecision {
        val now = Clock.System.now()
        return AlertEpisodeDecision(
            episode = AlertEpisodeContext(
                id = 1,
                resourceId = Uuid.random(),
                organizationId = 1,
                source = AlertSource.HOST_ALERT.name,
                deduplicationKey = "coverage-alert",
                episodeSeq = 1,
                episodeKey = "coverage-alert:1",
                status = "firing",
                openedAt = now,
                lastSeenAt = now,
                resolvedAt = null,
                lastNotificationAt = null,
                notificationCount = 1,
                suppressedAt = null,
                suppressedByUserId = null,
                suppressReason = null,
            ),
            shouldPublish = true,
            notificationSequence = 1,
            notificationKind = "initial",
        )
    }

    private fun alertEvent(): AlertLifecycleEvent =
        AlertLifecycleEvent(
            title = "Coverage alert",
            description = "The workflow publication path must fail open",
            priority = AlertPriority.P1,
            status = AlertStatus.FIRING,
            source = AlertSource.HOST_ALERT,
            deduplicationKey = "coverage-alert",
            organizationId = Int.MAX_VALUE,
            moneatUrl = "https://moneat.io/alerts/coverage",
        )
}
