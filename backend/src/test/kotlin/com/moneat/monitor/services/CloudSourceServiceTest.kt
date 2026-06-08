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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudSourceServiceTest {
    private val verifier = RecordingCloudSourceVerifier()
    private val writer = RecordingCloudResourceWriter()
    private lateinit var service: CloudSourceService

    private companion object {
        const val DATABASE_URL = "jdbc:h2:mem:moneat_cloud_sources;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        const val AWS_PROVIDER = "aws"
        const val AWS_ACCOUNT_ID = "123456789012"
        const val AWS_ROLE_NAME = "MoneatIntegrationRole"
        const val AWS_PRINCIPAL_ARN = "arn:aws:iam::499432741914:root"
        const val GCP_SERVICE_ACCOUNT = "moneat-cloud@moneat.iam.gserviceaccount.com"
        const val AZURE_APPLICATION_ID = "00000000-0000-0000-0000-000000000001"
        const val DISPLAY_NAME = "Production AWS"
        const val DISPLAY_NAME_MAX_LENGTH = 120
        const val DISPLAY_NAME_OVER_LIMIT_LENGTH = DISPLAY_NAME_MAX_LENGTH + 1
        const val CLOUD_SOURCE_STATUS_HEALTHY = "healthy"
        const val TEST_ORGANIZATION_ID = 1
        const val TEST_USER_ID = 2
        const val MISSING_SOURCE_ID = 404
        const val CATALOG_RESOURCE_ID = "aws:i-123"
        const val CATALOG_RESOURCE_NAME = "checkout-node"
    }

    @BeforeTest
    fun setup() {
        val db = Database.connect(
            url = DATABASE_URL,
            driver = "org.h2.Driver"
        )
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, CloudSources)
        verifier.result = CloudSourceSyncResult(resources = emptyList())
        writer.writes.clear()
        writer.deletes.clear()
        service = CloudSourceService(
            verifier = verifier,
            resourceWriter = writer,
            identityConfig = CloudSourceIdentityConfig(
                awsPrincipalArn = AWS_PRINCIPAL_ARN,
                gcpServiceAccount = GCP_SERVICE_ACCOUNT,
                azureApplicationId = AZURE_APPLICATION_ID
            )
        )
    }

    // ──── Setup preview ────

    @Test
    fun `setup preview returns managed identity snippet`() {
        val preview = service.setupPreview(organizationId = TEST_ORGANIZATION_ID, provider = AWS_PROVIDER)

        assertEquals(AWS_PROVIDER, preview.provider)
        assertTrue(preview.externalId.startsWith("mnt-ext-"))
        assertTrue(preview.snippet.contains("sts:ExternalId"))
        assertTrue(preview.snippet.contains(AWS_PRINCIPAL_ARN))
    }

    // ──── Validation ────

    @Test
    fun `create source rejects cloud logs`() = runBlocking {
        val error = assertFailsWith<InvalidCloudSourceException> {
            service.createSource(
                organizationId = TEST_ORGANIZATION_ID,
                userId = TEST_USER_ID,
                request = createRequest(collectLogs = true)
            )
        }

        assertEquals("Cloud logs require a dedicated setup flow", error.message)
    }

    @Test
    fun `create source rejects display names over the storage limit`() = runBlocking {
        val error = assertFailsWith<InvalidCloudSourceException> {
            service.createSource(
                organizationId = TEST_ORGANIZATION_ID,
                userId = TEST_USER_ID,
                request = createRequest(displayName = "a".repeat(DISPLAY_NAME_OVER_LIMIT_LENGTH))
            )
        }

        assertEquals("Display name must be at most $DISPLAY_NAME_MAX_LENGTH characters", error.message)
    }

    // ──── Create source ────

    @Test
    fun `create source verifies saves and writes discovered resources`() = runBlocking {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        verifier.result = CloudSourceSyncResult(
            resources = listOf(
                CloudSourceSyncResource(
                    resourceId = CATALOG_RESOURCE_ID,
                    name = CATALOG_RESOURCE_NAME,
                    resourceType = "ec2_instance",
                    provider = AWS_PROVIDER,
                    account = AWS_ACCOUNT_ID,
                    region = "us-east-1",
                    health = CLOUD_SOURCE_STATUS_HEALTHY,
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

        assertEquals(AWS_PROVIDER, response.provider)
        assertEquals(CLOUD_SOURCE_STATUS_HEALTHY, response.status)
        assertEquals(1, writer.writes.size)
        assertEquals(CATALOG_RESOURCE_ID, writer.writes.single().resources.single().resourceId)
    }

    // ──── Delete source ────

    @Test
    fun `delete source removes stored source and catalog resources`() = runBlocking {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        val response = service.createSource(
            organizationId = orgId,
            userId = userId,
            request = createRequest()
        )

        val deleted = service.deleteSource(orgId, response.id)

        assertTrue(deleted)
        assertEquals(listOf(orgId to response.id), writer.deletes)
        assertEquals(0, countSources())
    }

    @Test
    fun `delete source returns false for missing sources and skips catalog cleanup`() = runBlocking {
        val deleted = service.deleteSource(TEST_ORGANIZATION_ID, sourceId = MISSING_SOURCE_ID)

        assertFalse(deleted)
        assertTrue(writer.deletes.isEmpty())
    }

    private fun createRequest(
        displayName: String = DISPLAY_NAME,
        collectLogs: Boolean = false,
    ): CloudSourceCreateRequest =
        CloudSourceCreateRequest(
            provider = AWS_PROVIDER,
            displayName = displayName,
            config = CloudSourceProviderConfig(
                accountId = AWS_ACCOUNT_ID,
                roleName = AWS_ROLE_NAME
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

    private fun countSources(): Long =
        transaction {
            CloudSources.selectAll().count()
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
    val deletes = mutableListOf<Pair<Int, Int>>()

    override suspend fun replaceResources(request: CloudResourceWriteRequest) {
        writes += request
    }

    override suspend fun deleteResources(organizationId: Int, sourceId: Int) {
        deletes += organizationId to sourceId
    }
}
