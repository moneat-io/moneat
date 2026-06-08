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

package com.moneat.services

import com.moneat.events.models.ExceptionInfo
import com.moneat.events.models.ExceptionValue
import com.moneat.events.models.SentryEvent
import com.moneat.events.models.StackFrame
import com.moneat.events.models.StackTrace
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertStatus
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.services.WorkflowService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class NotificationServiceRoutingTest {
    companion object {
        private var db: Database? = null
    }

    private val emailService = mockk<EmailService>(relaxed = true)
    private val workflowService = mockk<WorkflowService>(relaxed = true)

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_notification_routing;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
        )
    }

    private fun seedOrg(name: String = "Routing Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedProject(orgId: Int, name: String = "Backend"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Projects.id
        }

    private fun buildEvent(
        eventId: String = "evt-1",
        message: String? = "Test error",
        level: String? = "error",
        environment: String? = "production"
    ): SentryEvent =
        SentryEvent(
            eventId = eventId,
            timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0,
            level = level,
            message = message,
            environment = environment
        )

    private fun buildEventWithException(): SentryEvent =
        SentryEvent(
            eventId = "evt-exc-1",
            timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0,
            level = "fatal",
            message = null,
            environment = "production",
            exception = ExceptionInfo(
                values = listOf(
                    ExceptionValue(
                        type = "NullPointerException",
                        value = "Cannot invoke method on null",
                        stacktrace = StackTrace(
                            frames = listOf(
                                StackFrame(
                                    filename = "UserService.kt",
                                    function = "getUser",
                                    lineno = 42,
                                    inApp = true
                                ),
                                StackFrame(
                                    filename = "UserRoute.kt",
                                    function = "handleGet",
                                    lineno = 15,
                                    inApp = true
                                )
                            )
                        )
                    )
                )
            )
        )

    @Test
    fun `onNewIssue publishes alert workflow event`() =
        runBlocking {
            val orgId = seedOrg("Workflow Route Org")
            val projectId = seedProject(orgId, "WorkflowProject")
            val eventSlot = slot<AlertLifecycleEvent>()
            val service = NotificationService(emailService, workflowService)

            try {
                service.onNewIssue(projectId, "2001", buildEvent())

                coVerify(exactly = 1) {
                    workflowService.publishAlertTriggered(capture(eventSlot))
                }
            } finally {
                service.shutdown()
            }

            val event = eventSlot.captured
            assertEquals("New Issue: Test error", event.title)
            assertTrue(event.description.contains("WorkflowProject reported ERROR"))
            assertEquals(AlertPriority.P1, event.priority)
            assertEquals(AlertStatus.FIRING, event.status)
            assertEquals(AlertSource.ERROR_ALERT, event.source)
            assertEquals("moneat-error-2001", event.deduplicationKey)
            assertEquals("https://moneat.io/issues/2001", event.moneatUrl)
            assertEquals(orgId, event.organizationId)
        }

    @Test
    fun `onNewIssue uses exception value when message is null`() =
        runBlocking {
            val orgId = seedOrg("Exception Org")
            val projectId = seedProject(orgId, "ExceptionProject")
            val eventSlot = slot<AlertLifecycleEvent>()
            val service = NotificationService(emailService, workflowService)

            try {
                service.onNewIssue(projectId, "4001", buildEventWithException())

                coVerify(exactly = 1) {
                    workflowService.publishAlertTriggered(capture(eventSlot))
                }
            } finally {
                service.shutdown()
            }

            val event = eventSlot.captured
            assertEquals("New Issue: Cannot invoke method on null", event.title)
            assertTrue(event.description.contains("ExceptionProject reported FATAL"))
            assertEquals(AlertPriority.P0, event.priority)
            assertEquals(orgId, event.organizationId)
        }

    @Test
    fun `onNewIssue returns early when project does not exist`() =
        runBlocking {
            val service = NotificationService(emailService, workflowService)

            try {
                service.onNewIssue(99999L, "5001", buildEvent())

                coVerify(exactly = 0) {
                    workflowService.publishAlertTriggered(any())
                }
            } finally {
                service.shutdown()
            }
        }

    @Test
    fun `onNewIssue swallows workflow publication failure`() =
        runBlocking {
            val orgId = seedOrg("Failure Org")
            val projectId = seedProject(orgId, "FailureProject")
            coEvery {
                workflowService.publishAlertTriggered(any())
            } throws RuntimeException("workflow queue unavailable")
            val service = NotificationService(emailService, workflowService)

            try {
                service.onNewIssue(projectId, "6001", buildEvent())

                coVerify(exactly = 1) {
                    workflowService.publishAlertTriggered(any())
                }
            } finally {
                service.shutdown()
            }
        }
}
