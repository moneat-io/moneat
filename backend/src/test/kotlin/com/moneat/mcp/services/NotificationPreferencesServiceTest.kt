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

package com.moneat.mcp.services

import com.moneat.shared.models.NotificationPreferences
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class NotificationPreferencesServiceTest {
    companion object {
        private var db: Database? = null
        private const val USER_ID = 1
        private const val PROJECT_ID = 10L
    }

    private val service = NotificationPreferencesService()

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:mcp_notification_preferences;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Projects, NotificationPreferences)
        seedUser()
    }

    @Test
    fun `getPreferences returns defaults when no row exists`() {
        val prefs = service.getPreferences(USER_ID)

        assertTrue(prefs.global.issueAlerts)
        assertTrue(prefs.global.errorAlerts)
        assertTrue(prefs.global.weeklySummary)
        assertEquals(30, prefs.global.alertFrequencyMinutes)
        assertTrue(prefs.projects.isEmpty())
    }

    @Test
    fun `updatePreferences inserts and updates global row`() {
        val inserted = service.updatePreferences(
            userId = USER_ID,
            issueAlerts = false,
            errorAlerts = true,
            weeklySummary = false,
            alertFrequencyMinutes = 15,
        )
        val updated = service.updatePreferences(
            userId = USER_ID,
            issueAlerts = true,
            errorAlerts = false,
            weeklySummary = true,
            alertFrequencyMinutes = 45,
        )
        val fetched = service.getPreferences(USER_ID)

        assertFalse(inserted.issueAlerts)
        assertEquals(15, inserted.alertFrequencyMinutes)
        assertTrue(updated.issueAlerts)
        assertFalse(updated.errorAlerts)
        assertEquals(45, fetched.global.alertFrequencyMinutes)
    }

    @Test
    fun `getPreferences includes project overrides with names`() {
        seedProjectPreference()

        val prefs = service.getPreferences(USER_ID)

        assertEquals(1, prefs.projects.size)
        assertEquals(ProjectIdResolver().resourceIdFor(PROJECT_ID), prefs.projects.single().projectId)
        assertEquals("Backend", prefs.projects.single().projectName)
        assertFalse(prefs.projects.single().issueAlerts)
        assertFalse(prefs.projects.single().weeklySummary)
        assertEquals(5, prefs.projects.single().alertFrequencyMinutes)
    }

    @Test
    fun `updatePreferences rejects non-positive alert frequency`() {
        val error = assertFailsWith<IllegalArgumentException> {
            service.updatePreferences(
                userId = USER_ID,
                issueAlerts = true,
                errorAlerts = true,
                weeklySummary = true,
                alertFrequencyMinutes = 0,
            )
        }

        assertTrue(error.message!!.contains("alertFrequencyMinutes"))
    }

    private fun seedUser() {
        transaction {
            Users.insert {
                it[id] = USER_ID
                it[email] = "mcp-prefs@example.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            }
        }
    }

    private fun seedProjectPreference() {
        val now = Clock.System.now()
        transaction {
            Projects.insert {
                it[id] = PROJECT_ID
                it[organization_id] = 1
                it[name] = "Backend"
                it[slug] = "backend"
            }
            NotificationPreferences.insert {
                it[user_id] = USER_ID
                it[project_id] = PROJECT_ID
                it[issue_alerts] = false
                it[error_alerts] = true
                it[weekly_summary] = false
                it[alert_frequency_minutes] = 5
                it[created_at] = now
                it[updated_at] = now
            }
        }
    }
}
