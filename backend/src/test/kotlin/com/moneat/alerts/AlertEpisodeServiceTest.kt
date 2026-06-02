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

package com.moneat.alerts

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertEpisodeService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AlertEpisodeServiceTest {
    companion object {
        private var db: Database? = null
    }

    private val service = AlertEpisodeService()
    private var orgId: Int = 0
    private var userId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_alert_episodes;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(AlertEpisodes, Users, Organizations)
        transaction {
            SchemaUtils.create(Users, Organizations, AlertEpisodes)
        }
        userId = seedUser()
        orgId = seedOrganization()
    }

    @Test
    fun `initial firing opens episode and duplicate firing is not due before 24 hours`() {
        val now = Instant.parse("2026-06-02T12:00:00Z")

        val first = service.recordFiring(alertEvent(), now)
        val duplicate = service.recordFiring(alertEvent(), now + 1.hours)

        assertNotNull(first)
        assertTrue(first.shouldPublish)
        assertEquals(1, first.notificationSequence)
        assertEquals("initial", first.notificationKind)
        assertEquals("moneat-host-alert-1#1", first.episode.episodeKey)
        assertNotNull(duplicate)
        assertFalse(duplicate.shouldPublish)
        assertEquals(1, duplicate.notificationSequence)
        assertEquals("reminder", duplicate.notificationKind)
        assertEquals(first.episode.id, duplicate.episode.id)
    }

    @Test
    fun `firing reminder is due after 24 hours while episode remains open`() {
        val now = Instant.parse("2026-06-02T12:00:00Z")

        service.recordFiring(alertEvent(), now)
        val reminder = service.recordFiring(alertEvent(), now + 25.hours)

        assertNotNull(reminder)
        assertTrue(reminder.shouldPublish)
        assertEquals(2, reminder.notificationSequence)
        assertEquals("reminder", reminder.notificationKind)
        assertEquals("moneat-host-alert-1#1", reminder.episode.episodeKey)
    }

    @Test
    fun `suppressed episode does not publish until unsuppressed`() {
        val now = Instant.parse("2026-06-02T12:00:00Z")
        val first = service.recordFiring(alertEvent(), now)

        assertNotNull(first)
        val suppressed = service.suppressEpisode(orgId, first.episode.id, userId, "Acked", now + 1.hours)
        val whileSuppressed = service.recordFiring(alertEvent(), now + 25.hours)
        val unsuppressed = service.unsuppressEpisode(orgId, first.episode.id, now + 26.hours)
        val afterUnsuppress = service.recordFiring(alertEvent(), now + 27.hours)

        assertNotNull(suppressed)
        assertNotNull(suppressed.suppressedAt)
        assertEquals(userId, suppressed.suppressedByUserId)
        assertNotNull(whileSuppressed)
        assertFalse(whileSuppressed.shouldPublish)
        assertNotNull(unsuppressed)
        assertNull(unsuppressed.suppressedAt)
        assertNotNull(afterUnsuppress)
        assertTrue(afterUnsuppress.shouldPublish)
        assertEquals(2, afterUnsuppress.notificationSequence)
    }

    @Test
    fun `resolved episode closes and next firing opens a new sequence`() {
        val now = Instant.parse("2026-06-02T12:00:00Z")
        val first = service.recordFiring(alertEvent(), now)

        val resolved = service.recordResolved(alertEvent().copy(status = AlertStatus.RESOLVED), now + 1.hours)
        val reopened = service.recordFiring(alertEvent(), now + 2.hours)

        assertNotNull(first)
        assertNotNull(resolved)
        assertTrue(resolved.shouldPublish)
        assertEquals("resolved", resolved.notificationKind)
        assertEquals("RESOLVED", resolved.episode.status)
        assertNotNull(reopened)
        assertTrue(reopened.shouldPublish)
        assertEquals(2, reopened.episode.episodeSeq)
        assertEquals("moneat-host-alert-1#2", reopened.episode.episodeKey)
    }

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "episode@moneat.io"
                it[password_hash] = "hash"
                it[name] = "Episode User"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrganization(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Episode Org"
                it[slug] = "episode-org"
            } get Organizations.id
        }

    private fun alertEvent(
        source: AlertSource = AlertSource.HOST_ALERT,
        deduplicationKey: String = "moneat-host-alert-1"
    ): AlertLifecycleEvent =
        AlertLifecycleEvent(
            title = "CPU saturation",
            description = "CPU has crossed the threshold",
            severity = AlertSeverity.CRITICAL,
            status = AlertStatus.FIRING,
            source = source,
            deduplicationKey = deduplicationKey,
            organizationId = orgId,
            moneatUrl = "https://moneat.io/hosts/1"
        )
}
