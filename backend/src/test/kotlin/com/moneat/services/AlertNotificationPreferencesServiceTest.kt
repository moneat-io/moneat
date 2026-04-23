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

import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.shared.models.AlertNotificationPreferences
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertNotificationPreferencesServiceTest {
    private val service = AlertNotificationPreferencesService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_alert_prefs_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, AlertNotificationPreferences)
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(
        email: String,
        verified: Boolean = true
    ): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[Users.name] = email.substringBefore("@")
                it[email_verified] = verified
            } get Users.id
        }

    private fun addMembership(
        userId: Int,
        orgId: Int
    ) = transaction {
        Memberships.insert {
            it[user_id] = userId
            it[organization_id] = orgId
            it[role] = "member"
        }
    }

    @Test
    fun `getPreferences returns defaults for all alert sources`() {
        val orgId = seedOrg()
        val userId = seedUser("defaults@moneat.io")

        val prefs = service.getPreferences(userId, orgId)

        assertEquals(AlertNotificationPreferencesService.AlertSource.values().size, prefs.size)
        assertTrue(prefs.all { it.emailEnabled })
        assertTrue(prefs.all { it.slackEnabled })
        assertTrue(prefs.all { it.discordEnabled })
    }

    @Test
    fun `updatePreference upserts and isChannelEnabled reflects values`() {
        val orgId = seedOrg()
        val userId = seedUser("update@moneat.io")

        service.updatePreference(
            userId = userId,
            organizationId = orgId,
            alertSource = "HOST_ALERT",
            emailEnabled = false,
            slackEnabled = true,
            discordEnabled = false
        )

        assertFalse(service.isChannelEnabled(userId, orgId, "HOST_ALERT", "email"))
        assertTrue(service.isChannelEnabled(userId, orgId, "HOST_ALERT", "slack"))
        assertFalse(service.isChannelEnabled(userId, orgId, "HOST_ALERT", "discord"))

        // Update same row to verify upsert path.
        service.updatePreference(
            userId = userId,
            organizationId = orgId,
            alertSource = "HOST_ALERT",
            emailEnabled = true,
            slackEnabled = false,
            discordEnabled = true
        )

        assertTrue(service.isChannelEnabled(userId, orgId, "HOST_ALERT", "email"))
        assertFalse(service.isChannelEnabled(userId, orgId, "HOST_ALERT", "slack"))
        assertTrue(service.isChannelEnabled(userId, orgId, "HOST_ALERT", "discord"))
    }

    @Test
    fun `updatePreference rejects unknown alert source`() {
        val orgId = seedOrg()
        val userId = seedUser("invalid@moneat.io")

        assertFailsWith<IllegalArgumentException> {
            service.updatePreference(
                userId = userId,
                organizationId = orgId,
                alertSource = "UNKNOWN_ALERT",
                emailEnabled = true,
                slackEnabled = true,
                discordEnabled = true
            )
        }
    }

    @Test
    fun `getUsersWithChannelEnabled enforces channel preferences and verification`() {
        val orgId = seedOrg()
        val enabledUser = seedUser("enabled@moneat.io", verified = true)
        val disabledUser = seedUser("disabled@moneat.io", verified = true)
        val unverifiedUser = seedUser("unverified@moneat.io", verified = false)

        addMembership(enabledUser, orgId)
        addMembership(disabledUser, orgId)
        addMembership(unverifiedUser, orgId)

        service.updatePreference(
            userId = disabledUser,
            organizationId = orgId,
            alertSource = "HOST_DOWN",
            emailEnabled = false,
            slackEnabled = true,
            discordEnabled = true
        )

        val recipients =
            service.getUsersWithChannelEnabled(
                organizationId = orgId,
                alertSource = "HOST_DOWN",
                channel = "email"
            )

        assertEquals(1, recipients.size)
        assertEquals(enabledUser, recipients.first().first)
        assertEquals("enabled@moneat.io", recipients.first().second)
    }

    @Test
    fun `isChannelEnabled defaults to true when no preference row exists`() {
        val orgId = seedOrg()
        val userId = seedUser("fallback@moneat.io")

        assertTrue(service.isChannelEnabled(userId, orgId, "ERROR_ALERT", "email"))
        assertTrue(service.isChannelEnabled(userId, orgId, "ERROR_ALERT", "slack"))
        assertTrue(service.isChannelEnabled(userId, orgId, "ERROR_ALERT", "discord"))
    }
}
