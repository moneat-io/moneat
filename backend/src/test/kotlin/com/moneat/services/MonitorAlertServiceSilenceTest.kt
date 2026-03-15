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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import com.moneat.testsupport.TestDatabaseHelper

class MonitorAlertServiceSilenceTest {
    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null

        private fun seedUser(email: String = "alert@test.com"): Int = transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
            } get Users.id
        }

        private fun seedOrg(name: String = "Alert Org"): Int = transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }
    }

    private val service = MonitorAlertService()

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = org.jetbrains.exposed.v1.jdbc.Database.connect(
                url = "jdbc:h2:mem:moneat_monitor_silence;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, AlertSilencePeriods)
    }

    // ──── isThresholdTriggered (pure logic) ────

    @Test
    fun `isThresholdTriggered handles greater-than condition`() {
        assertTrue(service.isThresholdTriggered(">", 10.0, 5.0))
        assertFalse(service.isThresholdTriggered(">", 5.0, 5.0))
        assertFalse(service.isThresholdTriggered(">", 4.0, 5.0))
    }

    @Test
    fun `isThresholdTriggered handles less-than condition`() {
        assertTrue(service.isThresholdTriggered("<", 4.0, 5.0))
        assertFalse(service.isThresholdTriggered("<", 5.0, 5.0))
        assertFalse(service.isThresholdTriggered("<", 6.0, 5.0))
    }

    @Test
    fun `isThresholdTriggered handles greater-than-or-equal condition`() {
        assertTrue(service.isThresholdTriggered(">=", 5.0, 5.0))
        assertTrue(service.isThresholdTriggered(">=", 6.0, 5.0))
        assertFalse(service.isThresholdTriggered(">=", 4.0, 5.0))
    }

    @Test
    fun `isThresholdTriggered handles less-than-or-equal condition`() {
        assertTrue(service.isThresholdTriggered("<=", 5.0, 5.0))
        assertTrue(service.isThresholdTriggered("<=", 4.0, 5.0))
        assertFalse(service.isThresholdTriggered("<=", 6.0, 5.0))
    }

    @Test
    fun `isThresholdTriggered handles equality condition`() {
        assertTrue(service.isThresholdTriggered("==", 5.0, 5.0))
        assertFalse(service.isThresholdTriggered("==", 5.001, 5.0))
        assertFalse(service.isThresholdTriggered("==", 4.999, 5.0))
    }

    @Test
    fun `isThresholdTriggered returns false for unknown condition`() {
        assertFalse(service.isThresholdTriggered("!=", 5.0, 5.0))
        assertFalse(service.isThresholdTriggered("", 5.0, 5.0))
        assertFalse(service.isThresholdTriggered("between", 5.0, 5.0))
    }

    @Test
    fun `isThresholdTriggered handles edge values`() {
        assertTrue(service.isThresholdTriggered(">", Double.MAX_VALUE, 0.0))
        assertTrue(service.isThresholdTriggered("<", 0.0, Double.MAX_VALUE))
        assertFalse(service.isThresholdTriggered(">", 0.0, 0.0))
    }

    // ──── isThrottledByInterval (pure logic) ────

    @Test
    fun `isThrottledByInterval returns false when lastTriggeredAt is null`() {
        assertFalse(service.isThrottledByInterval(null))
    }

    @Test
    fun `isThrottledByInterval returns true when within throttle interval`() {
        val now = Clock.System.now()
        val recentTrigger = now - 2.minutes
        assertTrue(service.isThrottledByInterval(recentTrigger, now))
    }

    @Test
    fun `isThrottledByInterval returns false when outside throttle interval`() {
        val now = Clock.System.now()
        val oldTrigger = now - 20.minutes
        assertFalse(service.isThrottledByInterval(oldTrigger, now))
    }

    @Test
    fun `isThrottledByInterval returns true when exactly at boundary minus one second`() {
        val now = Clock.System.now()
        val borderTrigger = now - 9.minutes
        assertTrue(service.isThrottledByInterval(borderTrigger, now))
    }

    @Test
    fun `isThrottledByInterval returns false when past the throttle boundary`() {
        val now = Clock.System.now()
        val oldTrigger = now - 20.minutes
        assertFalse(service.isThrottledByInterval(oldTrigger, now))
    }

    // ──── isAnySilenceActive (DB) ────

    @Test
    fun `isAnySilenceActive returns false when no silence periods exist`() {
        val orgId = seedOrg("No Silence Org")
        assertFalse(service.isAnySilenceActive(orgId))
    }

    @Test
    fun `isAnySilenceActive returns true when active silence period exists`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        transaction {
            AlertSilencePeriods.insert {
                it[organization_id] = orgId
                it[starts_at] = now - 1.hours
                it[ends_at] = now + 1.hours
                it[created_by] = userId
                it[created_at] = now - 1.hours
            }
        }

        assertTrue(service.isAnySilenceActive(orgId))
    }

    @Test
    fun `isAnySilenceActive returns false for expired silence periods`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        transaction {
            AlertSilencePeriods.insert {
                it[organization_id] = orgId
                it[starts_at] = now - 3.hours
                it[ends_at] = now - 1.hours
                it[created_by] = userId
                it[created_at] = now - 3.hours
            }
        }

        assertFalse(service.isAnySilenceActive(orgId))
    }

    @Test
    fun `isAnySilenceActive returns false for future silence periods`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        transaction {
            AlertSilencePeriods.insert {
                it[organization_id] = orgId
                it[starts_at] = now + 1.hours
                it[ends_at] = now + 3.hours
                it[created_by] = userId
                it[created_at] = now
            }
        }

        assertFalse(service.isAnySilenceActive(orgId))
    }

    @Test
    fun `isAnySilenceActive only checks own organization`() {
        val userId = seedUser()
        val orgA = seedOrg("Org A")
        val orgB = seedOrg("Org B")
        val now = Clock.System.now()

        transaction {
            AlertSilencePeriods.insert {
                it[organization_id] = orgA
                it[starts_at] = now - 1.hours
                it[ends_at] = now + 1.hours
                it[created_by] = userId
                it[created_at] = now - 1.hours
            }
        }

        assertFalse(service.isAnySilenceActive(orgB))
        assertTrue(service.isAnySilenceActive(orgA))
    }

    // ──── listSilencePeriods (DB) ────

    @Test
    fun `listSilencePeriods returns empty list when none exist`() {
        val orgId = seedOrg()
        val result = service.listSilencePeriods(orgId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listSilencePeriods returns all periods for org`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        transaction {
            repeat(3) { i ->
                AlertSilencePeriods.insert {
                    it[organization_id] = orgId
                    it[reason] = "Maintenance $i"
                    it[starts_at] = now + i.hours
                    it[ends_at] = now + (i + 1).hours
                    it[created_by] = userId
                    it[created_at] = now
                }
            }
        }

        val result = service.listSilencePeriods(orgId)
        assertEquals(3, result.size)
        assertTrue(result.any { it.reason == "Maintenance 0" })
        assertTrue(result.any { it.reason == "Maintenance 1" })
        assertTrue(result.any { it.reason == "Maintenance 2" })
    }

    @Test
    fun `listSilencePeriods only returns periods for the given org`() {
        val userId = seedUser()
        val orgA = seedOrg("Org A 2")
        val orgB = seedOrg("Org B 2")
        val now = Clock.System.now()

        transaction {
            AlertSilencePeriods.insert {
                it[organization_id] = orgA
                it[starts_at] = now - 1.hours
                it[ends_at] = now + 1.hours
                it[created_by] = userId
                it[created_at] = now
            }
            AlertSilencePeriods.insert {
                it[organization_id] = orgB
                it[starts_at] = now - 1.hours
                it[ends_at] = now + 1.hours
                it[created_by] = userId
                it[created_at] = now
            }
        }

        assertEquals(1, service.listSilencePeriods(orgA).size)
        assertEquals(1, service.listSilencePeriods(orgB).size)
    }

    // ──── createSilencePeriod (DB) ────

    @Test
    fun `createSilencePeriod creates period with all fields`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()
        val startsAt = now + 1.hours
        val endsAt = now + 2.hours

        val request = CreateSilencePeriodRequest(
            reason = "Deployment window",
            startsAt = startsAt.toEpochMilliseconds(),
            endsAt = endsAt.toEpochMilliseconds()
        )

        val result = service.createSilencePeriod(orgId, userId, request)

        assertEquals(orgId, result.organizationId)
        assertEquals("Deployment window", result.reason)
        assertEquals(userId, result.createdBy)
        assertEquals(startsAt.toEpochMilliseconds(), result.startsAt)
        assertEquals(endsAt.toEpochMilliseconds(), result.endsAt)
        assertTrue(result.id > 0)
    }

    @Test
    fun `createSilencePeriod allows null reason`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        val request = CreateSilencePeriodRequest(
            reason = null,
            startsAt = now.toEpochMilliseconds(),
            endsAt = (now + 1.hours).toEpochMilliseconds()
        )

        val result = service.createSilencePeriod(orgId, userId, request)

        assertNotNull(result)
        assertEquals(null, result.reason)
    }

    @Test
    fun `createSilencePeriod is retrievable via listSilencePeriods`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        val request = CreateSilencePeriodRequest(
            reason = "Test retrieval",
            startsAt = now.toEpochMilliseconds(),
            endsAt = (now + 2.hours).toEpochMilliseconds()
        )
        service.createSilencePeriod(orgId, userId, request)

        val periods = service.listSilencePeriods(orgId)
        assertEquals(1, periods.size)
        assertEquals("Test retrieval", periods.first().reason)
    }

    // ──── deleteSilencePeriod (DB) ────

    @Test
    fun `deleteSilencePeriod removes the period`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        val request = CreateSilencePeriodRequest(
            reason = "To delete",
            startsAt = now.toEpochMilliseconds(),
            endsAt = (now + 1.hours).toEpochMilliseconds()
        )
        val created = service.createSilencePeriod(orgId, userId, request)

        val deleted = service.deleteSilencePeriod(created.id, orgId)

        assertTrue(deleted)
        assertTrue(service.listSilencePeriods(orgId).isEmpty())
    }

    @Test
    fun `deleteSilencePeriod returns false for non-existent period`() {
        val orgId = seedOrg()
        val result = service.deleteSilencePeriod(99999, orgId)
        assertFalse(result)
    }

    @Test
    fun `deleteSilencePeriod cannot delete period from another org`() {
        val userId = seedUser()
        val orgA = seedOrg("Org A 3")
        val orgB = seedOrg("Org B 3")
        val now = Clock.System.now()

        val request = CreateSilencePeriodRequest(
            reason = "Org A silence",
            startsAt = now.toEpochMilliseconds(),
            endsAt = (now + 1.hours).toEpochMilliseconds()
        )
        val created = service.createSilencePeriod(orgA, userId, request)

        // Try to delete from orgB - should fail
        val result = service.deleteSilencePeriod(created.id, orgB)
        assertFalse(result)

        // Period still exists for orgA
        assertEquals(1, service.listSilencePeriods(orgA).size)
    }

    @Test
    fun `deleteSilencePeriod does not affect other periods`() {
        val userId = seedUser()
        val orgId = seedOrg()
        val now = Clock.System.now()

        val req1 = CreateSilencePeriodRequest(
            reason = "Keep this",
            startsAt = now.toEpochMilliseconds(),
            endsAt = (now + 1.hours).toEpochMilliseconds()
        )
        val req2 = CreateSilencePeriodRequest(
            reason = "Delete this",
            startsAt = (now + 2.hours).toEpochMilliseconds(),
            endsAt = (now + 3.hours).toEpochMilliseconds()
        )
        service.createSilencePeriod(orgId, userId, req1)
        val toDelete = service.createSilencePeriod(orgId, userId, req2)

        service.deleteSilencePeriod(toDelete.id, orgId)

        val remaining = service.listSilencePeriods(orgId)
        assertEquals(1, remaining.size)
        assertEquals("Keep this", remaining.first().reason)
    }
}
