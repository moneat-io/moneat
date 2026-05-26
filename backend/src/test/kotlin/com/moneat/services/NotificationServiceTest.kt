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

import com.moneat.events.models.SentryEvent
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.services.WorkflowService
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class NotificationServiceTest {
    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_notification_service;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, Projects, NotificationPreferences, EmailsSent)
    }

    @Test
    fun `onNewIssue publishes workflow event for each issue`() =
        runBlocking {
            val organizationId =
                transaction {
                    Organizations.insert {
                        it[name] = "Dedup Org"
                        it[slug] = "dedup-org"
                    } get Organizations.id
                }

            val userId =
                transaction {
                    Users.insert {
                        it[Users.email] = "alerts@moneat.io"
                        it[password_hash] = "hash"
                        it[Users.name] = "Alert User"
                        it[email_verified] = true
                    } get Users.id
                }

            transaction {
                Memberships.insert {
                    it[user_id] = userId
                    it[Memberships.organization_id] = organizationId
                    it[role] = "owner"
                }
            }

            val projectId =
                transaction {
                    Projects.insert {
                        it[organization_id] = organizationId
                        it[name] = "Backend API"
                        it[slug] = "backend-api"
                    } get Projects.id
                }

            transaction {
                NotificationPreferences.insert {
                    it[NotificationPreferences.user_id] = userId
                    it[NotificationPreferences.project_id] = null
                    it[issue_alerts] = true
                    it[error_alerts] = true
                    it[weekly_summary] = true
                    it[alert_frequency_minutes] = 60
                    it[created_at] = Clock.System.now()
                    it[updated_at] = Clock.System.now()
                }
            }

            val workflowService = mockk<WorkflowService>(relaxed = true)
            val notificationService = NotificationService(EmailService(), workflowService)
            try {
                val event =
                    SentryEvent(
                        eventId = "evt-1",
                        timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0,
                        level = "error",
                        message = "NullPointerException in checkout flow",
                        environment = "production"
                    )
                val publishedEvents = mutableListOf<AlertLifecycleEvent>()

                notificationService.onNewIssue(projectId, "1001", event)
                notificationService.onNewIssue(projectId, "1002", event.copy(eventId = "evt-2"))

                coVerify(exactly = 2) {
                    workflowService.publishAlertTriggered(capture(publishedEvents))
                }
                assertEquals("moneat-error-1001", publishedEvents[0].deduplicationKey)
                assertEquals("moneat-error-1002", publishedEvents[1].deduplicationKey)
            } finally {
                notificationService.shutdown()
            }
        }
}
