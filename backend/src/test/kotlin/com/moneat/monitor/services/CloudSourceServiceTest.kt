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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
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
        const val GCP_PROVIDER = "gcp"
        const val AZURE_PROVIDER = "azure"
        const val AWS_ACCOUNT_ID = "123456789012"
        const val AWS_ROLE_NAME = "MoneatIntegrationRole"
        const val AWS_PRINCIPAL_ARN = "arn:aws:iam::499432741914:root"
        const val GCP_PROJECT_ID = "moneat-prod"
        const val GCP_SERVICE_ACCOUNT = "moneat-cloud@moneat.iam.gserviceaccount.com"
        const val AZURE_TENANT_ID = "00000000-0000-0000-0000-000000000002"
        const val AZURE_SUBSCRIPTION_ID = "00000000-0000-0000-0000-000000000003"
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
        verifier.failure = null
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

        val statement = Json.parseToJsonElement(preview.snippet)
            .jsonObject
            .getValue("Statement")
            .jsonArray
            .single()
            .jsonObject
        val principal = statement.getValue("Principal").jsonObject.getValue("AWS").jsonPrimitive.content
        val condition = statement
            .getValue("Condition")
            .jsonObject
            .getValue("StringEquals")
            .jsonObject
            .getValue("sts:ExternalId")
            .jsonPrimitive
            .content

        assertEquals(AWS_PRINCIPAL_ARN, principal)
        assertEquals("sts:AssumeRole", statement.getValue("Action").jsonPrimitive.content)
        assertEquals(preview.externalId, condition)
    }

    @Test
    fun `setup preview supports all cloud providers and fallback principals`() {
        val fallbackService = CloudSourceService(
            verifier = verifier,
            resourceWriter = writer,
            identityConfig = CloudSourceIdentityConfig()
        )

        val gcp = fallbackService.setupPreview(organizationId = TEST_ORGANIZATION_ID, provider = " GCP ")
        val azure = fallbackService.setupPreview(organizationId = TEST_ORGANIZATION_ID, provider = AZURE_PROVIDER)

        assertEquals(GCP_PROVIDER, gcp.provider)
        assertTrue(gcp.principal.contains("<moneat-project>"))
        assertTrue(gcp.snippet.contains("gcloud projects add-iam-policy-binding"))
        assertEquals(AZURE_PROVIDER, azure.provider)
        assertEquals("<moneat-application-id>", azure.principal)
        assertTrue(azure.snippet.contains("az role assignment create"))
    }

    @Test
    fun `setup preview rejects unsupported providers`() {
        val error = assertFailsWith<InvalidCloudSourceException> {
            service.setupPreview(organizationId = TEST_ORGANIZATION_ID, provider = "oracle")
        }

        assertEquals("Unsupported cloud provider", error.message)
    }

    // ──── Managed identity verifier ────

    @Test
    fun `managed identity verifier discovers account resources for each provider`() = runBlocking {
        val managedVerifier = ManagedIdentityCloudSourceVerifier(
            CloudSourceIdentityConfig(
                awsPrincipalArn = AWS_PRINCIPAL_ARN,
                gcpServiceAccount = GCP_SERVICE_ACCOUNT,
                azureApplicationId = AZURE_APPLICATION_ID
            )
        )

        val aws = managedVerifier.verifyAndDiscover(verificationRequest()).resources.single()
        val gcp = managedVerifier.verifyAndDiscover(
            verificationRequest(
                provider = GCP_PROVIDER,
                config = CloudSourceProviderConfig(projectId = GCP_PROJECT_ID)
            )
        ).resources.single()
        val azure = managedVerifier.verifyAndDiscover(
            verificationRequest(
                provider = AZURE_PROVIDER,
                config = CloudSourceProviderConfig(
                    tenantId = AZURE_TENANT_ID,
                    subscriptionId = AZURE_SUBSCRIPTION_ID
                )
            )
        ).resources.single()

        assertEquals("aws:account:$AWS_ACCOUNT_ID", aws.resourceId)
        assertEquals(AWS_ROLE_NAME, aws.metadata["Role"])
        assertEquals("gcp:project:$GCP_PROJECT_ID", gcp.resourceId)
        assertEquals(GCP_PROJECT_ID, gcp.metadata["Project"])
        assertEquals("azure:subscription:$AZURE_SUBSCRIPTION_ID", azure.resourceId)
        assertEquals(AZURE_TENANT_ID, azure.metadata["Tenant"])
    }

    @Test
    fun `managed identity verifier reports missing config and connectors`() = runBlocking {
        val missingConfig = assertFailsWith<InvalidCloudSourceException> {
            ManagedIdentityCloudSourceVerifier().verifyAndDiscover(
                verificationRequest(config = CloudSourceProviderConfig(roleName = AWS_ROLE_NAME))
            )
        }
        val missingConnector = assertFailsWith<CloudSourceConnectorUnavailableException> {
            ManagedIdentityCloudSourceVerifier(CloudSourceIdentityConfig()).verifyAndDiscover(verificationRequest())
        }

        assertEquals("AWS account ID is required", missingConfig.message)
        assertEquals("Cloud connector is missing CLOUD_AWS_PRINCIPAL_ARN", missingConnector.message)
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

    @Test
    fun `create source trims names and rejects missing provider config`() = runBlocking {
        val missingName = assertFailsWith<InvalidCloudSourceException> {
            service.createSource(
                organizationId = TEST_ORGANIZATION_ID,
                userId = TEST_USER_ID,
                request = createRequest(displayName = "   ")
            )
        }
        val missingProject = assertFailsWith<InvalidCloudSourceException> {
            service.createSource(
                organizationId = TEST_ORGANIZATION_ID,
                userId = TEST_USER_ID,
                request = createRequest(
                    provider = GCP_PROVIDER,
                    config = CloudSourceProviderConfig()
                )
            )
        }

        assertEquals("Display name is required", missingName.message)
        assertEquals("GCP project ID is required", missingProject.message)
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

    // ──── Sync source ────

    @Test
    fun `sync source refreshes an existing source from stored config`() = runBlocking {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        val response = service.createSource(orgId, userId, createRequest(displayName = "  Production AWS  "))
        verifier.result = CloudSourceSyncResult(
            resources = listOf(
                CloudSourceSyncResource(
                    resourceId = CATALOG_RESOURCE_ID,
                    name = CATALOG_RESOURCE_NAME,
                    resourceType = "ec2_instance",
                    provider = AWS_PROVIDER,
                    account = AWS_ACCOUNT_ID,
                    region = "us-east-1",
                    health = CLOUD_SOURCE_STATUS_HEALTHY
                )
            )
        )

        val synced = service.syncSource(orgId, response.id)

        assertEquals(DISPLAY_NAME, synced.displayName)
        assertEquals(CLOUD_SOURCE_STATUS_HEALTHY, synced.status)
        assertEquals(CATALOG_RESOURCE_ID, writer.writes.last().resources.single().resourceId)
    }

    @Test
    fun `sync source marks status error when verification fails`() = runBlocking {
        val orgId = seedOrg()
        val userId = seedUser()
        seedMembership(userId, orgId)
        val response = service.createSource(orgId, userId, createRequest())
        verifier.failure = RuntimeException("provider unavailable")

        val error = assertFailsWith<RuntimeException> {
            service.syncSource(orgId, response.id)
        }

        assertEquals("provider unavailable", error.message)
        val (status, lastError) = sourceStatusAndError(response.id)
        assertEquals("error", status)
        assertEquals("provider unavailable", lastError)
    }

    private fun createRequest(
        provider: String = AWS_PROVIDER,
        displayName: String = DISPLAY_NAME,
        config: CloudSourceProviderConfig = CloudSourceProviderConfig(
            accountId = AWS_ACCOUNT_ID,
            roleName = AWS_ROLE_NAME
        ),
        collectLogs: Boolean = false,
    ): CloudSourceCreateRequest =
        CloudSourceCreateRequest(
            provider = provider,
            displayName = displayName,
            config = config,
            collectMetrics = true,
            collectInventory = true,
            collectCost = true,
            collectLogs = collectLogs
        )

    private fun verificationRequest(
        provider: String = AWS_PROVIDER,
        config: CloudSourceProviderConfig = CloudSourceProviderConfig(
            accountId = AWS_ACCOUNT_ID,
            roleName = AWS_ROLE_NAME
        ),
    ): CloudSourceVerificationRequest =
        CloudSourceVerificationRequest(
            organizationId = TEST_ORGANIZATION_ID,
            sourceId = 1,
            provider = provider,
            displayName = DISPLAY_NAME,
            config = config,
            externalId = "mnt-ext-test",
            collectMetrics = true,
            collectInventory = true,
            collectCost = true
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

    private fun sourceStatusAndError(sourceId: Int): Pair<String, String?> =
        transaction {
            val row = CloudSources
                .selectAll()
                .where { CloudSources.id eq sourceId }
                .single()
            row[CloudSources.status] to row[CloudSources.last_error]
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
    var failure: RuntimeException? = null

    override suspend fun verifyAndDiscover(source: CloudSourceVerificationRequest): CloudSourceSyncResult {
        failure?.let { throw it }
        return result
    }
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
