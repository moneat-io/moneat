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
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
        transaction {
            SchemaUtils.drop(EmailsSent, NotificationPreferences, Projects, Memberships, Organizations, Users)
            SchemaUtils.create(Users, Organizations, Memberships, Projects, NotificationPreferences, EmailsSent)
        }
    }

    @Test
    fun `onNewIssue respects alert frequency deduplication`() =
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

            val notificationService = NotificationService(EmailService())
            try {
                val event =
                    SentryEvent(
                        event_id = "evt-1",
                        timestamp = Clock.System.now().toEpochMilliseconds() / 1000.0,
                        level = "error",
                        message = "NullPointerException in checkout flow",
                        environment = "production"
                    )

                notificationService.onNewIssue(projectId, "1001", event)
                waitForEmailRows(expectedCount = 1)

                // Second issue alert for the same project/user should be throttled by alert frequency.
                notificationService.onNewIssue(projectId, "1002", event.copy(event_id = "evt-2"))
                waitForEmailRows(expectedCount = 1)

                val sentRows =
                    transaction {
                        EmailsSent
                            .selectAll()
                            .toList()
                            .count { it[EmailsSent.email_type] == "error_alert" }
                            .toLong()
                    }
                assertEquals(1L, sentRows)
            } finally {
                notificationService.shutdown()
            }
        }

    private fun waitForEmailRows(
        expectedCount: Long,
        timeoutMs: Long = 3000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count =
                transaction {
                    EmailsSent
                        .selectAll()
                        .toList()
                        .count { it[EmailsSent.email_type] == "error_alert" }
                        .toLong()
                }
            if (count >= expectedCount) return
            Thread.sleep(50)
        }
        val finalCount =
            transaction {
                EmailsSent
                    .selectAll()
                    .toList()
                    .count { it[EmailsSent.email_type] == "error_alert" }
                    .toLong()
            }
        assertEquals(expectedCount, finalCount)
    }
}
