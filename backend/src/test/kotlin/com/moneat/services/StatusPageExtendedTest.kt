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

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.statuspage.models.AddCustomDomainRequest
import com.moneat.statuspage.models.AddMonitorsRequest
import com.moneat.statuspage.models.CreateIncidentRequest
import com.moneat.statuspage.models.CreateIncidentUpdateRequest
import com.moneat.statuspage.models.CreateStatusPageRequest
import com.moneat.statuspage.models.MonitorAssignment
import com.moneat.statuspage.models.StatusPageCustomDomains
import com.moneat.statuspage.models.StatusPageIncidentUpdates
import com.moneat.statuspage.models.StatusPageIncidents
import com.moneat.statuspage.models.StatusPageMonitors
import com.moneat.statuspage.models.StatusPages
import com.moneat.statuspage.models.UpdateIncidentRequest
import com.moneat.statuspage.models.UpdateStatusPageRequest
import com.moneat.statuspage.routes.statusPageRoutes
import com.moneat.statuspage.services.StatusPageService
import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import com.moneat.uptime.models.UptimeMonitors
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class StatusPageExtendedTest {
    private var orgId: Int = 0

    companion object {
        private var db: Database? = null
        private const val SAME_SLUG = "same-slug"
        private const val TITLE_X_JSON = """{"title":"x"}"""

        @JvmStatic
        @BeforeAll
        fun setupKoin() {
            startTestKoin()
        }

        @JvmStatic
        @AfterAll
        fun teardownKoin() {
            stopTestKoin()
        }
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_status_ext;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users, Organizations, Memberships, StatusPages, StatusPageIncidents,
            UptimeMonitors, StatusPageMonitors, StatusPageIncidentUpdates, StatusPageCustomDomains
        )
        transaction {
            orgId = Organizations.insert {
                it[name] = "Ext Test Org"
                it[slug] = "ext-test-org"
            }[Organizations.id]
        }
    }

    private fun Application.installAuth() {
        install(ContentNegotiation) { json() }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(
                    JWT.require(Algorithm.HMAC256(RouteTestSupport.TEST_JWT_SECRET))
                        .withIssuer("moneat").withAudience("moneat-users").build()
                )
                validate { JWTPrincipal(it.payload) }
            }
        }
    }

    private fun token(userId: Int): String =
        JWT.create()
            .withIssuer("moneat").withAudience("moneat-users")
            .withClaim("userId", userId)
            .sign(Algorithm.HMAC256(RouteTestSupport.TEST_JWT_SECRET))

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "ext-test-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedUserAndOrg(): Pair<Int, Int> {
        val localOrgId = transaction {
            Organizations.insert {
                it[name] = "Ext Route Org"
                it[slug] = "ext-route-org-${System.nanoTime()}"
            } get Organizations.id
        }
        val userId = seedUser()
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = localOrgId
                it[role] = "owner"
            }
        }
        return Pair(userId, localOrgId)
    }

    private fun seedStatusPage(
        targetOrgId: Int,
        slug: String = "ext-slug-${System.nanoTime()}"
    ): UUID {
        val pageId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            StatusPages.insert {
                it[id] = pageId
                it[organizationId] = targetOrgId
                it[name] = "Ext Test Page"
                it[StatusPages.slug] = slug
                it[isPublic] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return pageId
    }

    private fun seedMonitor(targetOrgId: Int): UUID {
        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[organizationId] = targetOrgId
                it[name] = "Ext Monitor ${System.nanoTime()}"
                it[type] = "http"
                it[url] = "https://ext.example.com/health"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return monitorId
    }

    // ──── Service: addCustomDomain ────

    @Test
    fun `addCustomDomain creates domain with verification token`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Domain Page", slug = "domain-page")
        )
        val pageId = UUID.fromString(page.id)

        val domain = service.addCustomDomain(
            pageId,
            orgId,
            AddCustomDomainRequest(domain = "status.example.com")
        )

        assertEquals("status.example.com", domain.domain)
        assertFalse(domain.verified)
        assertNull(domain.verifiedAt)
        assertFalse(domain.sslProvisioned)
        assertTrue(domain.verificationToken.isNotBlank())
        assertEquals(64, domain.verificationToken.length)
    }

    @Test
    fun `addCustomDomain rejects invalid domain format`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Bad Domain Page", slug = "bad-domain-page")
        )
        val pageId = UUID.fromString(page.id)

        assertFailsWith<IllegalArgumentException> {
            service.addCustomDomain(
                pageId,
                orgId,
                AddCustomDomainRequest(domain = "-invalid.com")
            )
        }
    }

    @Test
    fun `addCustomDomain rejects duplicate domain`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Dup Domain Page", slug = "dup-domain-page")
        )
        val pageId = UUID.fromString(page.id)

        service.addCustomDomain(pageId, orgId, AddCustomDomainRequest(domain = "unique.example.com"))

        assertFailsWith<IllegalArgumentException> {
            service.addCustomDomain(pageId, orgId, AddCustomDomainRequest(domain = "unique.example.com"))
        }
    }

    @Test
    fun `addCustomDomain fails for non-existent page`() {
        val service = StatusPageService()

        assertFailsWith<IllegalArgumentException> {
            service.addCustomDomain(
                UUID.randomUUID(),
                orgId,
                AddCustomDomainRequest(domain = "orphan.example.com")
            )
        }
    }

    // ──── Service: removeCustomDomain ────

    @Test
    fun `removeCustomDomain removes existing domain`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Remove Domain Page", slug = "remove-domain-page")
        )
        val pageId = UUID.fromString(page.id)

        val domain = service.addCustomDomain(
            pageId,
            orgId,
            AddCustomDomainRequest(domain = "removable.example.com")
        )

        assertTrue(service.removeCustomDomain(pageId, orgId, domain.id))
    }

    @Test
    fun `removeCustomDomain returns false for non-existent domain`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "No Domain Page", slug = "no-domain-page")
        )
        val pageId = UUID.fromString(page.id)

        assertFalse(service.removeCustomDomain(pageId, orgId, 99999))
    }

    @Test
    fun `removeCustomDomain returns false for wrong org`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Wrong Org Domain", slug = "wrong-org-domain")
        )
        val pageId = UUID.fromString(page.id)

        val domain = service.addCustomDomain(
            pageId,
            orgId,
            AddCustomDomainRequest(domain = "wrongorg.example.com")
        )

        val otherOrgId = transaction {
            Organizations.insert {
                it[name] = "Other Domain Org"
                it[slug] = "other-domain-org"
            } get Organizations.id
        }

        assertFalse(service.removeCustomDomain(pageId, otherOrgId, domain.id))
    }

    // ──── Service: verifyCustomDomain ────

    @Test
    fun `verifyCustomDomain returns null for non-existent page`() {
        val service = StatusPageService()

        assertNull(service.verifyCustomDomain(UUID.randomUUID(), orgId, 1))
    }

    @Test
    fun `verifyCustomDomain returns null for wrong org`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Verify Wrong Org", slug = "verify-wrong-org")
        )
        val pageId = UUID.fromString(page.id)

        val domain = service.addCustomDomain(
            pageId,
            orgId,
            AddCustomDomainRequest(domain = "verifywrong.example.com")
        )

        val otherOrgId = transaction {
            Organizations.insert {
                it[name] = "Verify Other Org"
                it[slug] = "verify-other-org"
            } get Organizations.id
        }

        assertNull(service.verifyCustomDomain(pageId, otherOrgId, domain.id))
    }

    @Test
    fun `verifyCustomDomain returns null for non-existent domain id`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Verify Missing", slug = "verify-missing")
        )
        val pageId = UUID.fromString(page.id)

        assertNull(service.verifyCustomDomain(pageId, orgId, 99999))
    }

    // ──── Service: updateStatusPage slug validation ────

    @Test
    fun `updateStatusPage rejects invalid slug format`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Slug Test", slug = "slug-test")
        )
        val pageId = UUID.fromString(page.id)

        assertFailsWith<IllegalArgumentException> {
            service.updateStatusPage(pageId, orgId, UpdateStatusPageRequest(slug = "Bad Slug!"))
        }
    }

    @Test
    fun `updateStatusPage rejects duplicate slug`() {
        val service = StatusPageService()
        service.createStatusPage(orgId, CreateStatusPageRequest(name = "First", slug = "first-slug"))
        val second = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Second", slug = "second-slug")
        )
        val secondId = UUID.fromString(second.id)

        assertFailsWith<IllegalArgumentException> {
            service.updateStatusPage(secondId, orgId, UpdateStatusPageRequest(slug = "first-slug"))
        }
    }

    @Test
    fun `updateStatusPage allows keeping same slug`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Same Slug", slug = SAME_SLUG)
        )
        val pageId = UUID.fromString(page.id)

        val updated = service.updateStatusPage(pageId, orgId, UpdateStatusPageRequest(slug = SAME_SLUG))
        assertNotNull(updated)
        assertEquals(SAME_SLUG, updated.slug)
    }

    @Test
    fun `updateStatusPage updates branding fields`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Branding", slug = "branding-test")
        )
        val pageId = UUID.fromString(page.id)

        val updated = service.updateStatusPage(
            pageId,
            orgId,
            UpdateStatusPageRequest(
                primaryColor = "#FF0000",
                darkMode = true,
                showUptimeHistory = false,
                historyDays = 30
            )
        )

        assertNotNull(updated)
        assertEquals("#FF0000", updated.primaryColor)
        assertTrue(updated.darkMode)
        assertFalse(updated.showUptimeHistory)
        assertEquals(30, updated.historyDays)
    }

    // ──── Service: createStatusPage full fields ────

    @Test
    fun `createStatusPage stores all branding fields`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(
                name = "Full Branding",
                slug = "full-branding",
                description = "A fully branded page",
                logoUrl = "https://example.com/logo.png",
                faviconUrl = "https://example.com/favicon.ico",
                primaryColor = "#00FF00",
                darkMode = true,
                showUptimeHistory = false,
                historyDays = 60,
                isPublic = false
            )
        )

        assertEquals("Full Branding", page.name)
        assertEquals("full-branding", page.slug)
        assertEquals("A fully branded page", page.description)
        assertEquals("https://example.com/logo.png", page.logoUrl)
        assertEquals("https://example.com/favicon.ico", page.faviconUrl)
        assertEquals("#00FF00", page.primaryColor)
        assertTrue(page.darkMode)
        assertFalse(page.showUptimeHistory)
        assertEquals(60, page.historyDays)
        assertFalse(page.isPublic)
    }

    // ──── Service: updateIncident resolvedAt ────

    @Test
    fun `updateIncident sets resolvedAt when status is resolved`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Resolve Test", slug = "resolve-test")
        )
        val pageId = UUID.fromString(page.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "Down", status = "investigating", message = "Looking")
        )
        val incidentId = UUID.fromString(incident.id)

        val updated = service.updateIncident(
            pageId,
            orgId,
            incidentId,
            UpdateIncidentRequest(status = "resolved")
        )

        assertNotNull(updated)
        assertEquals("resolved", updated.status)
        assertNotNull(updated.resolvedAt)
    }

    @Test
    fun `updateIncident sets resolvedAt when status is completed`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Complete Test", slug = "complete-test")
        )
        val pageId = UUID.fromString(page.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(
                title = "Maintenance",
                status = "scheduled",
                type = "maintenance",
                message = "Planned work"
            )
        )
        val incidentId = UUID.fromString(incident.id)

        val updated = service.updateIncident(
            pageId,
            orgId,
            incidentId,
            UpdateIncidentRequest(status = "completed")
        )

        assertNotNull(updated)
        assertEquals("completed", updated.status)
        assertNotNull(updated.resolvedAt)
    }

    @Test
    fun `updateIncident does not set resolvedAt for non-terminal status`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "No Resolve", slug = "no-resolve")
        )
        val pageId = UUID.fromString(page.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "Issue", status = "investigating", message = "Looking")
        )
        val incidentId = UUID.fromString(incident.id)

        val updated = service.updateIncident(
            pageId,
            orgId,
            incidentId,
            UpdateIncidentRequest(status = "identified")
        )

        assertNotNull(updated)
        assertEquals("identified", updated.status)
        assertNull(updated.resolvedAt)
    }

    // ──── Service: createIncidentUpdate resolvedAt ────

    @Test
    fun `createIncidentUpdate sets resolvedAt when status is resolved`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Update Resolve", slug = "update-resolve")
        )
        val pageId = UUID.fromString(page.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "Outage", status = "investigating", message = "Starting")
        )
        val incidentId = UUID.fromString(incident.id)

        val updated = service.createIncidentUpdate(
            pageId,
            orgId,
            incidentId,
            CreateIncidentUpdateRequest(status = "resolved", message = "Fixed now")
        )

        assertNotNull(updated)
        assertEquals("resolved", updated.status)
        assertNotNull(updated.resolvedAt)
    }

    // ──── Service: createIncident with maintenance ────

    @Test
    fun `createIncident with maintenance type and impact`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Maint Page", slug = "maint-page")
        )
        val pageId = UUID.fromString(page.id)

        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(
                title = "DB Upgrade",
                status = "scheduled",
                type = "maintenance",
                impact = "minor",
                message = "Scheduled database upgrade",
                scheduledStartAt = "2025-06-01T00:00:00Z",
                scheduledEndAt = "2025-06-01T04:00:00Z"
            )
        )

        assertNotNull(incident)
        assertEquals("DB Upgrade", incident.title)
        assertEquals("maintenance", incident.type)
        assertEquals("minor", incident.impact)
        assertNotNull(incident.scheduledStartAt)
        assertNotNull(incident.scheduledEndAt)
    }

    @Test
    fun `createIncident includes initial update in response`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Update Page", slug = "update-check-page")
        )
        val pageId = UUID.fromString(page.id)

        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(
                title = "Network Issue",
                status = "investigating",
                message = "Network connectivity degraded"
            )
        )

        assertNotNull(incident)
        assertTrue(incident.updates.isNotEmpty())
        assertEquals("investigating", incident.updates.first().status)
        assertEquals("Network connectivity degraded", incident.updates.first().message)
    }

    // ──── Service: addMonitors replaces existing ────

    @Test
    fun `addMonitors replaces existing monitors`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Replace Monitor", slug = "replace-monitor")
        )
        val pageId = UUID.fromString(page.id)

        val monitor1 = seedMonitor(orgId)
        val monitor2 = seedMonitor(orgId)

        service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(monitors = listOf(MonitorAssignment(monitor1.toString())))
        )

        val replaced = service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(monitors = listOf(MonitorAssignment(monitor2.toString())))
        )

        assertEquals(1, replaced.size)
        assertEquals(monitor2.toString(), replaced.first().monitorId)
    }

    @Test
    fun `addMonitors fails for monitor not in org`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Wrong Org Mon", slug = "wrong-org-mon")
        )
        val pageId = UUID.fromString(page.id)

        val otherOrgId = transaction {
            Organizations.insert {
                it[name] = "Monitor Other Org"
                it[slug] = "monitor-other-org"
            } get Organizations.id
        }
        val otherMonitor = seedMonitor(otherOrgId)

        assertFailsWith<IllegalArgumentException> {
            service.addMonitors(
                pageId,
                orgId,
                AddMonitorsRequest(
                    monitors = listOf(MonitorAssignment(otherMonitor.toString()))
                )
            )
        }
    }

    @Test
    fun `addMonitors with empty list clears all monitors`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Clear Monitors", slug = "clear-monitors")
        )
        val pageId = UUID.fromString(page.id)

        val monitorId = seedMonitor(orgId)
        service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(monitors = listOf(MonitorAssignment(monitorId.toString())))
        )

        val cleared = service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(monitors = emptyList())
        )

        assertTrue(cleared.isEmpty())
    }

    @Test
    fun `addMonitors preserves sort order and display name`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Sort Page", slug = "sort-page")
        )
        val pageId = UUID.fromString(page.id)

        val m1 = seedMonitor(orgId)
        val m2 = seedMonitor(orgId)

        val monitors = service.addMonitors(
            pageId,
            orgId,
            AddMonitorsRequest(
                monitors = listOf(
                    MonitorAssignment(m1.toString(), displayName = "Primary", sortOrder = 2),
                    MonitorAssignment(m2.toString(), displayName = "Secondary", sortOrder = 1)
                )
            )
        )

        assertEquals(2, monitors.size)
        assertEquals("Secondary", monitors[0].displayName)
        assertEquals(1, monitors[0].sortOrder)
        assertEquals("Primary", monitors[1].displayName)
        assertEquals(2, monitors[1].sortOrder)
    }

    // ──── Service: getPublicStatusPageByDomain ────

    @Test
    fun `getPublicStatusPageByDomain returns null for unverified domain`() = runBlocking {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Unverified Domain", slug = "unverified-domain")
        )
        val pageId = UUID.fromString(page.id)

        service.addCustomDomain(pageId, orgId, AddCustomDomainRequest(domain = "unverified.example.com"))

        val result = service.getPublicStatusPageByDomain("unverified.example.com")
        assertNull(result)
    }

    @Test
    fun `getPublicStatusPageByDomain returns null for unknown domain`() = runBlocking {
        val service = StatusPageService()
        val result = service.getPublicStatusPageByDomain("nonexistent.example.com")
        assertNull(result)
    }

    @Test
    fun `getPublicStatusPage returns null for non-existent slug`() = runBlocking {
        val service = StatusPageService()
        val result = service.getPublicStatusPage("slug-does-not-exist")
        assertNull(result)
    }

    // ──── Service: deleteStatusPage cascades ────

    @Test
    fun `deleteStatusPage returns false for wrong org`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Wrong Org Del", slug = "wrong-org-del")
        )
        val pageId = UUID.fromString(page.id)

        val otherOrgId = transaction {
            Organizations.insert {
                it[name] = "Del Other Org"
                it[slug] = "del-other-org"
            } get Organizations.id
        }

        assertFalse(service.deleteStatusPage(pageId, otherOrgId))
        assertNotNull(service.getStatusPage(pageId, orgId))
    }

    @Test
    fun `deleteStatusPage returns false for non-existent page`() {
        val service = StatusPageService()
        assertFalse(service.deleteStatusPage(UUID.randomUUID(), orgId))
    }

    // ──── Service: listIncidents ordering ────

    @Test
    fun `listIncidents returns incidents in descending creation order`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Order Test", slug = "order-test")
        )
        val pageId = UUID.fromString(page.id)

        service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "First", status = "investigating", message = "m1")
        )
        service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(title = "Second", status = "investigating", message = "m2")
        )

        val incidents = service.listIncidents(pageId, orgId)
        assertEquals(2, incidents.size)
        assertEquals("Second", incidents[0].title)
        assertEquals("First", incidents[1].title)
    }

    // ──── Service: updateIncident impact ────

    @Test
    fun `updateIncident updates impact field`() {
        val service = StatusPageService()
        val page = service.createStatusPage(
            orgId,
            CreateStatusPageRequest(name = "Impact Test", slug = "impact-test")
        )
        val pageId = UUID.fromString(page.id)
        val incident = service.createIncident(
            pageId,
            orgId,
            CreateIncidentRequest(
                title = "Impact",
                status = "investigating",
                impact = "none",
                message = "Starting"
            )
        )
        val incidentId = UUID.fromString(incident.id)

        val updated = service.updateIncident(
            pageId,
            orgId,
            incidentId,
            UpdateIncidentRequest(impact = "major")
        )

        assertNotNull(updated)
        assertEquals("major", updated.impact)
    }

    // ──── Route: public status page by slug ────

    @Test
    fun `public status page returns 200 for public page`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val slug = "public-route-${System.nanoTime()}"
        seedStatusPage(orgId, slug)

        val response = client.get("/public/status/$slug")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Ext Test Page"))
    }

    @Test
    fun `public status page returns 404 for non-existent slug`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }

        val response = client.get("/public/status/no-such-slug")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `public status page returns 404 for private page`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val slug = "private-route-${System.nanoTime()}"
        val pageId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            StatusPages.insert {
                it[id] = pageId
                it[organizationId] = orgId
                it[name] = "Private Page"
                it[StatusPages.slug] = slug
                it[isPublic] = false
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val response = client.get("/public/status/$slug")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Route: public status page by domain ────

    @Test
    fun `public status page by domain returns 404 for unknown domain`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }

        val response = client.get("/public/status/domain/unknown.example.com")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Route: verify custom domain ────

    @Test
    fun `verify domain returns 400 for invalid parameters`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.post("/v1/status-pages/not-uuid/domains/abc/verify") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `verify domain returns 403 when user has no org`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()
        val pageId = UUID.randomUUID()

        val response = client.post("/v1/status-pages/$pageId/domains/1/verify") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `verify domain returns 404 when domain not found`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        val response = client.post("/v1/status-pages/$pageId/domains/99999/verify") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Route: remove monitor happy path ────

    @Test
    fun `remove monitor returns 204 when successfully removed`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)
        val monitorId = seedMonitor(localOrgId)

        // Add monitor to status page via service
        val service = StatusPageService()
        service.addMonitors(
            pageId,
            localOrgId,
            AddMonitorsRequest(monitors = listOf(MonitorAssignment(monitorId.toString())))
        )

        val response = client.delete("/v1/status-pages/$pageId/monitors/$monitorId") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `remove monitor returns 404 when monitor not on page`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        val response = client.delete("/v1/status-pages/$pageId/monitors/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Route: remove monitor invalid UUID ────

    @Test
    fun `remove monitor returns 400 for invalid monitor uuid`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.delete("/v1/status-pages/${UUID.randomUUID()}/monitors/not-uuid") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Route: add domain invalid params ────

    @Test
    fun `add domain returns 400 for invalid page uuid`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.post("/v1/status-pages/not-uuid/domains") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"domain":"test.example.com"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Route: remove domain invalid params ────

    @Test
    fun `remove domain returns 400 for invalid page uuid`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.delete("/v1/status-pages/not-uuid/domains/1") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `remove domain returns 404 when not found`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        val response = client.delete("/v1/status-pages/$pageId/domains/99999") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Route: update incident invalid UUIDs ────

    @Test
    fun `update incident returns 400 for invalid page uuid`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.put("/v1/status-pages/not-uuid/incidents/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody(TITLE_X_JSON)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `update incident returns 400 for invalid incident uuid`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.put("/v1/status-pages/${UUID.randomUUID()}/incidents/not-uuid") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody(TITLE_X_JSON)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `update incident returns 403 when user has no org`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response = client.put("/v1/status-pages/${UUID.randomUUID()}/incidents/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody(TITLE_X_JSON)
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ──── Route: incident updates invalid params ────

    @Test
    fun `post incident update returns 403 when user has no org`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val userId = seedUser()

        val response =
            client.post("/v1/status-pages/${UUID.randomUUID()}/incidents/${UUID.randomUUID()}/updates") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"resolved","message":"done"}""")
            }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `post incident update returns 404 when incident not found`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        val response =
            client.post("/v1/status-pages/$pageId/incidents/${UUID.randomUUID()}/updates") {
                header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"resolved","message":"done"}""")
            }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ──── Route: add monitors happy path ────

    @Test
    fun `add monitors returns 200 with monitor list`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)
        val monitorId = seedMonitor(localOrgId)

        val response = client.post("/v1/status-pages/$pageId/monitors") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"monitors":[{"monitorId":"$monitorId","displayName":"API"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("API"))
    }

    // ──── Route: get incidents happy path with data ────

    @Test
    fun `get incidents returns 200 with incident data`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        // Create an incident first
        client.post("/v1/status-pages/$pageId/incidents") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Test Outage","status":"investigating","message":"m"}""")
        }

        val response = client.get("/v1/status-pages/$pageId/incidents") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Test Outage"))
    }

    // ──── Route: update status page with invalid slug ────

    @Test
    fun `update status page returns 400 for invalid slug via route`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        val response = client.put("/v1/status-pages/$pageId") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"slug":"INVALID SLUG!"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ──── Route: add domain with invalid domain format ────

    @Test
    fun `add domain returns 400 for invalid domain format via route`() = testApplication {
        application {
            installAuth()
            routing { statusPageRoutes() }
        }
        val (userId, localOrgId) = seedUserAndOrg()
        val pageId = seedStatusPage(localOrgId)

        val response = client.post("/v1/status-pages/$pageId/domains") {
            header(HttpHeaders.Authorization, "Bearer ${token(userId)}")
            contentType(ContentType.Application.Json)
            setBody("""{"domain":"-bad-domain"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
