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

import com.moneat.models.CreateStatusPageRequest
import com.moneat.models.Organizations
import com.moneat.models.StatusPageCustomDomains
import com.moneat.models.StatusPageIncidentUpdates
import com.moneat.models.StatusPageIncidents
import com.moneat.models.StatusPageMonitors
import com.moneat.models.StatusPages
import com.moneat.models.UptimeMonitors
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatusPageServiceTest {
    private var orgId: Int = 0

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_status_pages;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations,
                    StatusPages,
                    UptimeMonitors,
                    StatusPageMonitors,
                    StatusPageIncidents,
                    StatusPageIncidentUpdates,
                    StatusPageCustomDomains
                )
            }
            dbInitialized = true
        }

        transaction {
            StatusPageCustomDomains.deleteAll()
            StatusPageIncidentUpdates.deleteAll()
            StatusPageIncidents.deleteAll()
            StatusPageMonitors.deleteAll()
            StatusPages.deleteAll()
            UptimeMonitors.deleteAll()
            Organizations.deleteAll()

            orgId =
                Organizations.insert {
                    it[name] = "Status Org"
                    it[slug] = "status-org"
                }[Organizations.id]
        }
    }

    @Test
    fun `createStatusPage validates slug format`() {
        val service = StatusPageService()

        assertFailsWith<IllegalArgumentException> {
            service.createStatusPage(
                organizationId = orgId,
                request = CreateStatusPageRequest(name = "Main", slug = "Invalid Slug!")
            )
        }
    }

    @Test
    fun `createStatusPage enforces unique slug`() {
        val service = StatusPageService()

        service.createStatusPage(
            organizationId = orgId,
            request = CreateStatusPageRequest(name = "Main", slug = "main-status")
        )

        assertFailsWith<IllegalArgumentException> {
            service.createStatusPage(
                organizationId = orgId,
                request = CreateStatusPageRequest(name = "Secondary", slug = "main-status")
            )
        }
    }

    @Test
    fun `getPublicStatusPage returns null for private pages`() =
        runBlocking {
            val service = StatusPageService()
            service.createStatusPage(
                organizationId = orgId,
                request = CreateStatusPageRequest(name = "Private", slug = "private-page", isPublic = false)
            )

            val result = service.getPublicStatusPage("private-page")
            assertNull(result)
        }

    @Test
    fun `getPublicStatusPage returns public page with empty monitors and incidents`() =
        runBlocking {
            val service = StatusPageService()
            service.createStatusPage(
                organizationId = orgId,
                request = CreateStatusPageRequest(name = "Public", slug = "public-page", isPublic = true)
            )

            val result = service.getPublicStatusPage("public-page")
            assertNotNull(result)
            assertEquals("Public", result.name)
            assertEquals(0, result.monitors.size)
            assertEquals(0, result.activeIncidents.size)
            assertEquals(0, result.scheduledMaintenance.size)
        }
}
