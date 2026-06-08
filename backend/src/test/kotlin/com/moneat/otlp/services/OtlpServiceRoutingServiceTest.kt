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

package com.moneat.otlp.services

import com.moneat.otlp.models.CreateOtlpServiceMappingRequest
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.OtelObservedServices
import com.moneat.shared.models.OtelServiceProjectMappings
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtlpServiceRoutingServiceTest {

    private val service = OtlpServiceRoutingService()

    private lateinit var orgOne: OrgProjects
    private lateinit var orgTwo: OrgProjects

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_otlp_service_routing;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Projects,
            OtelServiceProjectMappings,
            OtelObservedServices
        )
        orgOne = seedOrgWithProjects("Org One", "org-one", "API", "Worker")
        orgTwo = seedOrgWithProjects("Org Two", "org-two", "External")
    }

    // ──── Mapping resolution ────

    @Test
    fun `resolveProjectIds maps service namespace and name to a project`() {
        val mapping = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "checkout-api",
                serviceNamespace = "checkout",
                projectId = orgOne.projects.first()
            )
        )

        assertNotNull(mapping)

        val projectIds = service.resolveProjectIds(
            organizationId = orgOne.id,
            services = listOf(
                OtlpServiceDescriptor(
                    serviceNamespace = "checkout",
                    serviceName = "checkout-api",
                    environment = "production"
                )
            ),
            signalType = OtlpSignalType.TRACES
        )
        val identity = OtlpServiceIdentity(serviceNamespace = "checkout", serviceName = "checkout-api")

        assertEquals(orgOne.projects.first(), projectIds[identity])

        val observed = service.listObservedServices(orgOne.id).single()
        assertEquals(mapping.id, observed.mappingId)
        assertEquals(orgOne.projects.first(), observed.projectId)
        assertEquals("API", observed.projectName)
        assertEquals("production", observed.lastEnvironment)
        assertFalse(observed.seenLogs)
        assertTrue(observed.seenTraces)
        assertFalse(observed.seenMetrics)
    }

    @Test
    fun `resolveProjectIds records unmapped services without rejecting telemetry`() {
        val projectIds = service.resolveProjectIds(
            organizationId = orgOne.id,
            services = listOf(
                OtlpServiceDescriptor(
                    serviceNamespace = null,
                    serviceName = "background-worker",
                    environment = "staging"
                )
            ),
            signalType = OtlpSignalType.LOGS
        )
        val identity = OtlpServiceIdentity(serviceNamespace = "", serviceName = "background-worker")

        assertTrue(projectIds.containsKey(identity))
        assertNull(projectIds[identity])

        val observed = service.listObservedServices(orgOne.id).single()
        assertEquals("", observed.serviceNamespace)
        assertEquals("background-worker", observed.serviceName)
        assertNull(observed.projectId)
        assertEquals("staging", observed.lastEnvironment)
        assertTrue(observed.seenLogs)
        assertFalse(observed.seenTraces)
        assertFalse(observed.seenMetrics)
    }

    @Test
    fun `resolveProjectIds preserves first non-null environment for duplicate services`() {
        val projectIds = service.resolveProjectIds(
            organizationId = orgOne.id,
            services = listOf(
                OtlpServiceDescriptor(
                    serviceNamespace = "checkout",
                    serviceName = "checkout-api",
                    environment = null
                ),
                OtlpServiceDescriptor(
                    serviceNamespace = "checkout",
                    serviceName = "checkout-api",
                    environment = "production"
                )
            ),
            signalType = OtlpSignalType.METRICS
        )
        val identity = OtlpServiceIdentity(serviceNamespace = "checkout", serviceName = "checkout-api")

        assertTrue(projectIds.containsKey(identity))
        assertNull(projectIds[identity])

        val observed = service.listObservedServices(orgOne.id).single()
        assertEquals("production", observed.lastEnvironment)
        assertFalse(observed.seenLogs)
        assertFalse(observed.seenTraces)
        assertTrue(observed.seenMetrics)
    }

    @Test
    fun `resolveProjectIds marks feedback on an existing observed service`() {
        val serviceDescriptor = OtlpServiceDescriptor(
            serviceNamespace = "checkout",
            serviceName = "checkout-api",
            environment = "production"
        )

        service.resolveProjectIds(
            organizationId = orgOne.id,
            services = listOf(serviceDescriptor),
            signalType = OtlpSignalType.TRACES
        )
        service.resolveProjectIds(
            organizationId = orgOne.id,
            services = listOf(serviceDescriptor),
            signalType = OtlpSignalType.FEEDBACK
        )

        val observed = service.listObservedServices(orgOne.id).single()
        assertTrue(observed.seenTraces)
        assertTrue(observed.seenFeedback)
    }

    @Test
    fun `upsertMapping only accepts a valid service and project in the organization`() {
        val unknownServiceMapping = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "unknown_service:java",
                serviceNamespace = null,
                projectId = orgOne.projects.first()
            )
        )
        val crossOrgMapping = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "checkout-api",
                serviceNamespace = "checkout",
                projectId = orgTwo.projects.first()
            )
        )

        assertNull(unknownServiceMapping)
        assertNull(crossOrgMapping)
    }

    @Test
    fun `upsertMapping updates existing mapping for the same service identity`() {
        val first = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "checkout-api",
                serviceNamespace = "checkout",
                projectId = orgOne.projects.first()
            )
        )
        val second = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "checkout-api",
                serviceNamespace = "checkout",
                projectId = orgOne.projects.last()
            )
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.id, second.id)
        assertEquals(orgOne.projects.last(), second.projectId)
        assertEquals("Worker", second.projectName)
    }

    @Test
    fun `upsertMapping accepts project resource ID`() {
        val resourceId = projectResourceId(orgOne.projects.first())
        val mapping = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "checkout-api",
                serviceNamespace = "checkout",
                projectResourceId = resourceId
            )
        )

        assertNotNull(mapping)
        assertEquals(orgOne.projects.first(), mapping.projectId)
        assertEquals(resourceId, mapping.projectResourceId)

        service.resolveProjectIds(
            organizationId = orgOne.id,
            services = listOf(
                OtlpServiceDescriptor(
                    serviceNamespace = "checkout",
                    serviceName = "checkout-api",
                    environment = "production"
                )
            ),
            signalType = OtlpSignalType.TRACES
        )

        val observed = service.listObservedServices(orgOne.id).single()
        assertEquals(resourceId, observed.projectResourceId)
    }

    @Test
    fun `deleteMapping removes only mappings in the organization`() {
        val mapping = service.upsertMapping(
            organizationId = orgOne.id,
            request = CreateOtlpServiceMappingRequest(
                serviceName = "checkout-api",
                serviceNamespace = "checkout",
                projectId = orgOne.projects.first()
            )
        )

        assertNotNull(mapping)
        assertFalse(service.deleteMapping(orgTwo.id, mapping.id))
        assertTrue(service.deleteMapping(orgOne.id, mapping.id))
        assertFalse(service.deleteMapping(orgOne.id, mapping.id))
    }

    private fun seedOrgWithProjects(
        orgName: String,
        orgSlug: String,
        vararg projectNames: String,
    ): OrgProjects = transaction {
        val orgId = Organizations.insert {
            it[name] = orgName
            it[slug] = orgSlug
        } get Organizations.id
        val projects = projectNames.map { projectName ->
            Projects.insert {
                it[organization_id] = orgId
                it[name] = projectName
                it[slug] = projectName.lowercase().replace(" ", "-")
                it[framework] = "otel"
            } get Projects.id
        }
        OrgProjects(orgId, projects)
    }

    private fun projectResourceId(projectId: Long): String = transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .first()[Projects.resource_id]
            .toString()
    }

    private data class OrgProjects(
        val id: Int,
        val projects: List<Long>,
    )
}
