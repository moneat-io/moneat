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

package com.moneat.monitor.services

import com.moneat.monitor.models.CloudSourceCreateRequest
import com.moneat.monitor.models.CloudSourceProviderConfig
import com.moneat.monitor.models.CloudSourceSyncResource
import com.moneat.monitor.models.CloudSourceSyncResult
import com.moneat.shared.models.CloudSources
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudSourceServiceTest {
    private val verifier = RecordingCloudSourceVerifier()
    private val writer = RecordingCloudResourceWriter()
    private lateinit var service: CloudSourceService

    @BeforeTest
    fun setup() {
        val db = Database.connect(
            url = "jdbc:h2:mem:moneat_cloud_sources;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, CloudSources)
        service = CloudSourceService(
            verifier = verifier,
            resourceWriter = writer,
            identityConfig = CloudSourceIdentityConfig(
                awsPrincipalArn = "arn:aws:iam::499432741914:root",
                gcpServiceAccount = "moneat-cloud@moneat.iam.gserviceaccount.com",
                azureApplicationId = "00000000-0000-0000-0000-000000000001"
            )
        )
    }

    @Test
    fun `setup preview returns managed identity snippet`() {
        val preview = service.setupPreview(organizationId = 10, provider = "aws")

        assertEquals("aws", preview.provider)
        assertTrue(preview.externalId.startsWith("mnt-ext-"))
        assertTrue(preview.snippet.contains("sts:ExternalId"))
        assertTrue(preview.snippet.contains("arn:aws:iam::499432741914:root"))
    }

    @Test
    fun `create source rejects cloud logs`() = runBlocking {
        val error = assertFailsWith<InvalidCloudSourceException> {
            service.createSource(
                organizationId = 1,
                userId = 2,
                request = createRequest(collectLogs = true)
            )
        }

        assertEquals("Cloud logs require a dedicated setup flow", error.message)
    }

    @Test
    fun `create source verifies saves and writes discovered resources`() = runBlocking {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        verifier.result = CloudSourceSyncResult(
            resources = listOf(
                CloudSourceSyncResource(
                    resourceId = "aws:i-123",
                    name = "checkout-node",
                    resourceType = "ec2_instance",
                    provider = "aws",
                    account = "123456789012",
                    region = "us-east-1",
                    health = "healthy",
                    tags = mapOf("env" to "prod"),
                    metadata = mapOf("Instance type" to "m7i.large"),
                    cpuPercent = 18.0,
                    memPercent = 0.0,
                    monthlyUsd = 42.5,
                    costTrendPct = 3.2
                )
            )
        )

        val response = service.createSource(
            organizationId = orgId,
            userId = userId,
            request = createRequest()
        )

        assertEquals("aws", response.provider)
        assertEquals("healthy", response.status)
        assertEquals(1, writer.writes.size)
        assertEquals("aws:i-123", writer.writes.single().resources.single().resourceId)
    }

    private fun createRequest(collectLogs: Boolean = false): CloudSourceCreateRequest =
        CloudSourceCreateRequest(
            provider = "aws",
            displayName = "Production AWS",
            config = CloudSourceProviderConfig(
                accountId = "123456789012",
                roleName = "MoneatIntegrationRole"
            ),
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = collectLogs
        )

    private fun seedUser(): Int =
        transaction {
            Users.insert {
                it[email] = "cloud-source-${System.nanoTime()}@test.com"
                it[password_hash] = "hash"
                it[email_verified] = true
            } get Users.id
        }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Cloud Source Org"
                it[slug] = "cloud-source-${System.nanoTime()}"
            } get Organizations.id
        }

    private fun seedMembership(userId: Int, orgId: Int) {
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = orgId
                it[role] = "owner"
            }
        }
    }
}

private class RecordingCloudSourceVerifier : CloudSourceVerifier {
    var result: CloudSourceSyncResult = CloudSourceSyncResult(resources = emptyList())

    override suspend fun verifyAndDiscover(source: CloudSourceVerificationRequest): CloudSourceSyncResult = result
}

private class RecordingCloudResourceWriter : CloudResourceWriter {
    val writes = mutableListOf<CloudResourceWriteRequest>()

    override suspend fun replaceResources(request: CloudResourceWriteRequest) {
        writes += request
    }
}
