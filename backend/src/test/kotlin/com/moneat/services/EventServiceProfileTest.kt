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

import com.moneat.events.models.EnvelopeItem
import com.moneat.events.models.SentryEnvelope
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.ProfileInsertData
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.services.EventService
import com.moneat.events.services.ReleaseService
import com.moneat.notifications.services.NotificationService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.UsageRecords
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventServiceProfileTest {
    companion object {
        private var db: Database? = null
        private const val PROJECT = 9001L
        private const val ORG = 42
    }

    private lateinit var eventRepository: EventRepository
    private lateinit var eventService: EventService
    private lateinit var profileDir: java.nio.file.Path

    private var savedProfilePath: String? = null
    private var savedMaxProfileBytes: String? = null

    @BeforeTest
    fun setup() {
        savedProfilePath = System.getProperty("PROFILE_STORAGE_PATH")
        savedMaxProfileBytes = System.getProperty("PROFILE_MAX_PAYLOAD_BYTES")

        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_event_profile;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver"
                )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects, Subscriptions, UsageRecords)

        profileDir = createTempDirectory("moneat-profile-test")
        System.setProperty("PROFILE_STORAGE_PATH", profileDir.toString())
        System.setProperty("PROFILE_MAX_PAYLOAD_BYTES", "512")

        eventRepository = mockk(relaxed = true)
        every { eventRepository.verifyProjectKey(PROJECT, "k") } returns ProjectKeyVerification(true, "jvm")
        every { eventRepository.getOrganizationIdForProject(PROJECT) } returns ORG

        eventService =
            EventService(
                notificationService = mockk<NotificationService>(relaxed = true),
                eventRepository = eventRepository,
                releaseService = mockk<ReleaseService>(relaxed = true),
            )
    }

    @AfterTest
    fun tearDown() {
        if (savedProfilePath == null) {
            System.clearProperty("PROFILE_STORAGE_PATH")
        } else {
            System.setProperty("PROFILE_STORAGE_PATH", savedProfilePath!!)
        }
        if (savedMaxProfileBytes == null) {
            System.clearProperty("PROFILE_MAX_PAYLOAD_BYTES")
        } else {
            System.setProperty("PROFILE_MAX_PAYLOAD_BYTES", savedMaxProfileBytes!!)
        }
        File(profileDir.toString()).deleteRecursively()
    }

    @Test
    fun `processEnvelope profile item stores profile`() =
        runBlocking {
            val slot = slot<ProfileInsertData>()
            coEvery { eventRepository.insertProfile(capture(slot)) } returns true

            val payload =
                """
                {
                  "event_id": "550e8400-e29b-41d4-a716-446655440000",
                  "transaction_name": "GET /api",
                  "platform": "node",
                  "environment": "staging",
                  "release": "2.0.0",
                  "duration_ns": 5000000,
                  "runtime": { "name": "node", "version": "20" }
                }
                """.trimIndent()

            eventService.processEnvelope(
                PROJECT,
                SentryEnvelope(
                    eventId = "550e8400-e29b-41d4-a716-446655440000",
                    items = listOf(EnvelopeItem(type = "profile", payload = payload)),
                ),
            )

            coVerify(atLeast = 1) { eventRepository.insertProfile(any()) }
            assertTrue(slot.isCaptured)
            assertTrue(slot.captured.storageKey.endsWith(".profile.json"))
            assertEquals("GET /api", slot.captured.service)
        }

    @Test
    fun `processEnvelope skips oversized profile payloads`() =
        runBlocking {
            val huge = "z".repeat(800)
            eventService.processEnvelope(
                PROJECT,
                SentryEnvelope(eventId = "big", items = listOf(EnvelopeItem("profile", huge))),
            )
            coVerify(exactly = 0) { eventRepository.insertProfile(any()) }
        }

    @Test
    fun `isNewIssue returns true when issue has single event`() =
        runBlocking {
            coEvery { eventRepository.getEventCountForIssue(PROJECT, "fresh-issue") } returns 1L
            val fn = EventService::class.declaredFunctions.single { it.name == "isNewIssue" }
            fn.isAccessible = true
            val result = fn.callSuspend(eventService, PROJECT, "fresh-issue") as Boolean
            assertTrue(result)
        }

    @Test
    fun `isNewIssue returns false when multiple events exist`() =
        runBlocking {
            coEvery { eventRepository.getEventCountForIssue(PROJECT, "old-issue") } returns 4L
            val fn = EventService::class.declaredFunctions.single { it.name == "isNewIssue" }
            fn.isAccessible = true
            val result = fn.callSuspend(eventService, PROJECT, "old-issue") as Boolean
            assertFalse(result)
        }
}
