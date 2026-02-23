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

import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.shared.models.AlertSilencePeriods
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class MonitorAlertServiceTest {
    private val service = MonitorAlertService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_monitor_alert_service;MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            try {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    AlertSilencePeriods
                )
            } catch (_: Exception) {
                // Tables already exist, which is fine
            }

            AlertSilencePeriods.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedOrg(name: String = "Alert Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String = "alert-user@moneat.io"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[Users.name] = "Alert User"
                it[email_verified] = true
            } get Users.id
        }

    @Test
    fun `isThresholdTriggered evaluates all supported operators`() {
        assertTrue(service.isThresholdTriggered(">", 90.0, 80.0))
        assertTrue(service.isThresholdTriggered("<", 10.0, 20.0))
        assertTrue(service.isThresholdTriggered(">=", 10.0, 10.0))
        assertTrue(service.isThresholdTriggered("<=", 10.0, 10.0))
        assertTrue(service.isThresholdTriggered("==", 42.0, 42.0))

        assertFalse(service.isThresholdTriggered(">", 5.0, 10.0))
        assertFalse(service.isThresholdTriggered("invalid", 5.0, 10.0))
    }

    @Test
    fun `isThrottledByInterval enforces minimum alert interval`() {
        val now = Clock.System.now()

        assertFalse(service.isThrottledByInterval(lastTriggeredAt = null, now = now))
        assertTrue(service.isThrottledByInterval(lastTriggeredAt = now - 5.minutes, now = now))
        assertFalse(service.isThrottledByInterval(lastTriggeredAt = now - 20.minutes, now = now))
    }

    @Test
    fun `silence period lifecycle create list delete`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val nowMs = Clock.System.now().toEpochMilliseconds()

        val created =
            service.createSilencePeriod(
                organizationId = orgId,
                userId = userId,
                request =
                CreateSilencePeriodRequest(
                    reason = "Maintenance window",
                    startsAt = nowMs - 60_000,
                    endsAt = nowMs + 60_000
                )
            )

        assertTrue(service.isAnySilenceActive(orgId))
        val listed = service.listSilencePeriods(orgId)
        assertEquals(1, listed.size)
        assertEquals(created.id, listed.first().id)
        assertEquals("Maintenance window", listed.first().reason)

        assertTrue(service.deleteSilencePeriod(created.id, orgId))
        assertFalse(service.isAnySilenceActive(orgId))
    }
}
