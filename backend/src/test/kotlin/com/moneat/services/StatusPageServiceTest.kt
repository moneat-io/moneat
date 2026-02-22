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

import com.moneat.models.AddMonitorsRequest
import com.moneat.models.CreateIncidentRequest
import com.moneat.models.CreateIncidentUpdateRequest
import com.moneat.models.CreateStatusPageRequest
import com.moneat.models.Memberships
import com.moneat.models.MonitorAssignment
import com.moneat.models.Organizations
import com.moneat.models.StatusPageCustomDomains
import com.moneat.models.StatusPageIncidentUpdates
import com.moneat.models.StatusPageIncidents
import com.moneat.models.StatusPageMonitors
import com.moneat.models.StatusPages
import com.moneat.models.UpdateIncidentRequest
import com.moneat.models.UpdateStatusPageRequest
import com.moneat.models.UptimeMonitors
import com.moneat.models.Users
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusPageServiceTest {
    private var orgId: Int = 0

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_status_pages;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction(db!!) {
                SchemaUtils.create(
                    Users,
                    Organizations,
                    Memberships,
                    StatusPages,
                    UptimeMonitors,
                    StatusPageMonitors,
                    StatusPageIncidents,
                    StatusPageIncidentUpdates,
                    StatusPageCustomDomains
                )
            }
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        transaction {
            StatusPageCustomDomains.deleteAll()
            StatusPageIncidentUpdates.deleteAll()
            StatusPageIncidents.deleteAll()
            StatusPageMonitors.deleteAll()
            StatusPages.deleteAll()
            UptimeMonitors.deleteAll()
            Memberships.deleteAll()
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

    // ==================== listStatusPages ====================

    @Test
    fun `listStatusPages returns all pages for org`() {
        val service = StatusPageService()
        service.createStatusPage(orgId, CreateStatusPageRequest(name = "Page 1", slug = "page-1"))
        service.createStatusPage(orgId, CreateStatusPageRequest(name = "Page 2", slug = "page-2"))

        val pages = service.listStatusPages(orgId)
        assertEquals(2, pages.size)
        val names = pages.map { it.name }.toSet()
        assertTrue(names.contains("Page 1"))
        assertTrue(names.contains("Page 2"))
    }

    @Test
    fun `listStatusPages returns empty for org with no pages`() {
        val service = StatusPageService()
        assertTrue(service.listStatusPages(orgId).isEmpty())
    }

    @Test
    fun `listStatusPages does not return pages from other orgs`() {
        val service = StatusPageService()
        val otherOrgId = transaction {
            Organizations.insert {
                it[name] = "Other Org"
                it[slug] = "other-org"
            } get Organizations.id
        }
        service.createStatusPage(orgId, CreateStatusPageRequest(name = "My Page", slug = "my-page"))
        service.createStatusPage(otherOrgId, CreateStatusPageRequest(name = "Their Page", slug = "their-page"))

        val pages = service.listStatusPages(orgId)
        assertEquals(1, pages.size)
        assertEquals("My Page", pages.first().name)
    }

    // ==================== getStatusPage ====================

    @Test
    fun `getStatusPage returns null when page does not exist`() {
        val service = StatusPageService()
        assertNull(service.getStatusPage(java.util.UUID.randomUUID(), orgId))
    }

    @Test
    fun `getStatusPage returns page detail for existing page`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "My Status", slug = "my-status", description = "Status for my org")
        )
        val pageId = java.util.UUID.fromString(created.id)

        val detail = service.getStatusPage(pageId, orgId)
        assertNotNull(detail)
        assertEquals("My Status", detail.name)
        assertEquals("my-status", detail.slug)
        assertEquals("Status for my org", detail.description)
        assertTrue(detail.monitors.isEmpty())
        assertTrue(detail.customDomains.isEmpty())
    }

    @Test
    fun `getStatusPage returns null when page belongs to different org`() {
        val service = StatusPageService()
        val created = service.createStatusPage(orgId, CreateStatusPageRequest(name = "Page", slug = "page"))
        val pageId = java.util.UUID.fromString(created.id)

        val otherOrgId = transaction {
            Organizations.insert {
                it[name] = "Another Org"
                it[slug] = "another-org"
            } get Organizations.id
        }
        assertNull(service.getStatusPage(pageId, otherOrgId))
    }

    // ==================== updateStatusPage ====================

    @Test
    fun `updateStatusPage updates name and description`() {
        val service = StatusPageService()
        val created = service.createStatusPage(orgId, CreateStatusPageRequest(name = "Old Name", slug = "old-slug"))
        val pageId = java.util.UUID.fromString(created.id)

        val updated = service.updateStatusPage(
            pageId,
            orgId,
            UpdateStatusPageRequest(name = "New Name", description = "Updated")
        )
        assertNotNull(updated)
        assertEquals("New Name", updated.name)
        assertEquals("Updated", updated.description)
    }

    @Test
    fun `updateStatusPage returns null for non-existent page`() {
        val service = StatusPageService()
        assertNull(service.updateStatusPage(java.util.UUID.randomUUID(), orgId, UpdateStatusPageRequest(name = "X")))
    }

    @Test
    fun `updateStatusPage updates isPublic flag`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Toggle", slug = "toggle", isPublic = true)
        )
        val pageId = java.util.UUID.fromString(created.id)

        val updated = service.updateStatusPage(pageId, orgId, UpdateStatusPageRequest(isPublic = false))
        assertNotNull(updated)
        assertFalse(updated.isPublic)
    }

    // ==================== deleteStatusPage ====================

    @Test
    fun `deleteStatusPage removes the page`() {
        val service = StatusPageService()
        val created = service.createStatusPage(orgId, CreateStatusPageRequest(name = "To Delete", slug = "to-delete"))
        val pageId = java.util.UUID.fromString(created.id)

        service.deleteStatusPage(pageId, orgId)
        assertNull(service.getStatusPage(pageId, orgId))
    }

    @Test
    fun `deleteStatusPage does not remove other pages`() {
        val service = StatusPageService()
        val page1 = service.createStatusPage(orgId, CreateStatusPageRequest(name = "Keep", slug = "keep"))
        val page2 = service.createStatusPage(orgId, CreateStatusPageRequest(name = "Delete Me", slug = "delete-me"))

        service.deleteStatusPage(java.util.UUID.fromString(page2.id), orgId)

        val remaining = service.listStatusPages(orgId)
        assertEquals(1, remaining.size)
        assertEquals("Keep", remaining.first().name)
    }

    // ==================== listIncidents ====================

    @Test
    fun `listIncidents returns empty when no incidents`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Incidents Page", slug = "incidents-page")
        )
        val pageId = java.util.UUID.fromString(created.id)

        val incidents = service.listIncidents(pageId, orgId)
        assertTrue(incidents.isEmpty())
    }

    @Test
    fun `listIncidents returns null for non-existent page`() {
        val service = StatusPageService()
        assertFailsWith<Exception> {
            service.listIncidents(java.util.UUID.randomUUID(), orgId)
        }
    }

    // ==================== createIncident ====================

    @Test
    fun `createIncident creates incident with initial update`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Incident Test", slug = "incident-test")
        )
        val pageId = java.util.UUID.fromString(created.id)

        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(
                title = "API Outage",
                status = "investigating",
                message = "We are investigating the issue"
            )
        )

        assertNotNull(incident)
        assertEquals("API Outage", incident.title)
        assertEquals("investigating", incident.status)
        assertNotNull(incident.id)
    }

    @Test
    fun `createIncident fails for non-existent page`() {
        val service = StatusPageService()
        assertFailsWith<Exception> {
            service.createIncident(
                java.util.UUID.randomUUID(),
                orgId,
                CreateIncidentRequest(title = "Outage", status = "investigating", message = "test")
            )
        }
    }

    // ==================== updateIncident ====================

    @Test
    fun `updateIncident updates incident title and status`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Update Test", slug = "update-test")
        )
        val pageId = java.util.UUID.fromString(created.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "Initial Outage", status = "investigating", message = "Investigating")
        )
        val incidentId = java.util.UUID.fromString(incident.id)

        val updated = service.updateIncident(
            pageId,
            orgId,
            incidentId,
            UpdateIncidentRequest(title = "Updated Outage", status = "identified")
        )

        assertNotNull(updated)
        assertEquals("Updated Outage", updated.title)
        assertEquals("identified", updated.status)
    }

    @Test
    fun `updateIncident returns null for non-existent incident`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "No Incident Page", slug = "no-incident")
        )
        val pageId = java.util.UUID.fromString(created.id)

        assertNull(
            service.updateIncident(pageId, orgId, java.util.UUID.randomUUID(), UpdateIncidentRequest(title = "X"))
        )
    }

    // ==================== createIncidentUpdate ====================

    @Test
    fun `createIncidentUpdate adds update and changes incident status`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Update Log Test", slug = "update-log")
        )
        val pageId = java.util.UUID.fromString(created.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "Down", status = "investigating", message = "Looking into it")
        )
        val incidentId = java.util.UUID.fromString(incident.id)

        val updated = service.createIncidentUpdate(
            pageId,
            orgId,
            incidentId,
            CreateIncidentUpdateRequest(status = "resolved", message = "Issue is resolved")
        )

        assertNotNull(updated)
        assertEquals("resolved", updated.status)
    }

    @Test
    fun `createIncidentUpdate returns null for non-existent incident`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "No Update Page", slug = "no-update-page")
        )
        val pageId = java.util.UUID.fromString(created.id)

        assertNull(
            service.createIncidentUpdate(
                pageId,
                orgId,
                java.util.UUID.randomUUID(),
                CreateIncidentUpdateRequest(status = "resolved", message = "done")
            )
        )
    }

    // ==================== addMonitors / removeMonitor ====================

    @Test
    fun `addMonitors adds monitors to status page`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Monitor Page", slug = "monitor-page")
        )
        val pageId = java.util.UUID.fromString(created.id)

        val monitorId = java.util.UUID.randomUUID()
        val now = kotlin.time.Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[organizationId] = orgId
                it[name] = "API Monitor"
                it[type] = "http"
                it[url] = "https://api.example.com/health"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val monitors = service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(
                monitors = listOf(MonitorAssignment(monitorId = monitorId.toString(), displayName = "API"))
            )
        )

        assertEquals(1, monitors.size)
        assertEquals("API Monitor", monitors.first().monitorName)
        assertEquals("API", monitors.first().displayName)
    }

    @Test
    fun `removeMonitor removes monitor from status page`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Remove Monitor Page", slug = "remove-monitor")
        )
        val pageId = java.util.UUID.fromString(created.id)

        val monitorId = java.util.UUID.randomUUID()
        val now = kotlin.time.Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[organizationId] = orgId
                it[name] = "Test Monitor"
                it[type] = "http"
                it[url] = "https://test.example.com"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(monitors = listOf(MonitorAssignment(monitorId.toString())))
        )

        val removed = service.removeMonitor(pageId, orgId, monitorId)
        assertTrue(removed)

        val detail = service.getStatusPage(pageId, orgId)
        assertTrue(detail!!.monitors.isEmpty())
    }

    @Test
    fun `removeMonitor returns false for non-existent monitor`() {
        val service = StatusPageService()
        val created = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "No Monitor Page", slug = "no-monitor")
        )
        val pageId = java.util.UUID.fromString(created.id)

        assertFalse(service.removeMonitor(pageId, orgId, java.util.UUID.randomUUID()))
    }
}
