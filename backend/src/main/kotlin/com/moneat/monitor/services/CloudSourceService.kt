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

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.config.isClickHouseError
import com.moneat.monitor.models.CloudSourceCreateRequest
import com.moneat.monitor.models.CloudSourceProviderConfig
import com.moneat.monitor.models.CloudSourceResponse
import com.moneat.monitor.models.CloudSourceSetupPreview
import com.moneat.monitor.models.CloudSourceSyncResource
import com.moneat.monitor.models.CloudSourceSyncResult
import com.moneat.shared.models.CloudSources
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.ClickHouseSqlUtils.mapToSqlMap
import io.ktor.client.statement.bodyAsText
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.Charsets.UTF_8
import kotlin.time.Clock

private const val CLOUD_PROVIDER_AWS = "aws"
private const val CLOUD_PROVIDER_GCP = "gcp"
private const val CLOUD_PROVIDER_AZURE = "azure"
private const val CLOUD_SOURCE_STATUS_HEALTHY = "healthy"
private const val CLOUD_SOURCE_STATUS_SYNCING = "syncing"
private const val CLOUD_SOURCE_STATUS_ERROR = "error"
private const val UNSUPPORTED_CLOUD_PROVIDER = "Unsupported cloud provider"
private const val CLOUD_EXTERNAL_ID_PREFIX = "mnt-ext-"
private const val CLOUD_EXTERNAL_ID_HASH_LENGTH = 24
private const val HEX_BYTE_MASK = 0xff
private const val CLICKHOUSE_ERROR_PREVIEW_LENGTH = 300
private const val CLOUD_SOURCE_DISPLAY_NAME_MAX_LENGTH = 120

data class CloudSourceIdentityConfig(
    val awsPrincipalArn: String? = null,
    val gcpServiceAccount: String? = null,
    val azureApplicationId: String? = null
) {
    companion object {
        fun fromEnv(): CloudSourceIdentityConfig =
            CloudSourceIdentityConfig(
                awsPrincipalArn = EnvConfig.get("CLOUD_AWS_PRINCIPAL_ARN").trimToNull(),
                gcpServiceAccount = EnvConfig.get("CLOUD_GCP_SERVICE_ACCOUNT_EMAIL").trimToNull(),
                azureApplicationId = EnvConfig.get("CLOUD_AZURE_APPLICATION_ID").trimToNull()
            )
    }
}

data class CloudSourceVerificationRequest(
    val organizationId: Int,
    val sourceId: Int,
    val provider: String,
    val displayName: String,
    val config: CloudSourceProviderConfig,
    val externalId: String,
    val collectMetrics: Boolean,
    val collectInventory: Boolean,
    val collectCost: Boolean
)

data class CloudResourceWriteRequest(
    val organizationId: Int,
    val sourceId: Int,
    val resources: List<CloudSourceSyncResource>
)

class InvalidCloudSourceException(message: String = UNSUPPORTED_CLOUD_PROVIDER) : RuntimeException(message)

class CloudSourceConnectorUnavailableException(message: String) : RuntimeException(message)

fun interface CloudSourceVerifier {
    suspend fun verifyAndDiscover(source: CloudSourceVerificationRequest): CloudSourceSyncResult
}

interface CloudResourceWriter {
    suspend fun replaceResources(request: CloudResourceWriteRequest)
    suspend fun deleteResources(organizationId: Int, sourceId: Int)
}

class ManagedIdentityCloudSourceVerifier(
    private val identityConfig: CloudSourceIdentityConfig = CloudSourceIdentityConfig.fromEnv(),
) : CloudSourceVerifier {
    override suspend fun verifyAndDiscover(source: CloudSourceVerificationRequest): CloudSourceSyncResult {
        val resource = when (source.provider) {
            CLOUD_PROVIDER_AWS -> awsAccountResource(source)
            CLOUD_PROVIDER_GCP -> gcpProjectResource(source)
            CLOUD_PROVIDER_AZURE -> azureSubscriptionResource(source)
            else -> throw InvalidCloudSourceException()
        }
        return CloudSourceSyncResult(resources = listOf(resource))
    }

    private fun awsAccountResource(source: CloudSourceVerificationRequest): CloudSourceSyncResource {
        val accountId = source.config.accountId.required("AWS account ID")
        val roleName = source.config.roleName.required("AWS role name")
        identityConfig.awsPrincipalArn.requiredConnector("CLOUD_AWS_PRINCIPAL_ARN")
        return CloudSourceSyncResource(
            resourceId = "aws:account:$accountId",
            name = source.displayName,
            resourceType = "aws_account",
            provider = CLOUD_PROVIDER_AWS,
            account = accountId,
            region = "global",
            health = CLOUD_SOURCE_STATUS_HEALTHY,
            metadata = mapOf("Role" to roleName)
        )
    }

    private fun gcpProjectResource(source: CloudSourceVerificationRequest): CloudSourceSyncResource {
        val projectId = source.config.projectId.required("GCP project ID")
        identityConfig.gcpServiceAccount.requiredConnector("CLOUD_GCP_SERVICE_ACCOUNT_EMAIL")
        return CloudSourceSyncResource(
            resourceId = "gcp:project:$projectId",
            name = source.displayName,
            resourceType = "gcp_project",
            provider = CLOUD_PROVIDER_GCP,
            account = projectId,
            region = "global",
            health = CLOUD_SOURCE_STATUS_HEALTHY,
            metadata = mapOf("Project" to projectId)
        )
    }

    private fun azureSubscriptionResource(source: CloudSourceVerificationRequest): CloudSourceSyncResource {
        val subscriptionId = source.config.subscriptionId.required("Azure subscription ID")
        val tenantId = source.config.tenantId.required("Azure tenant ID")
        identityConfig.azureApplicationId.requiredConnector("CLOUD_AZURE_APPLICATION_ID")
        return CloudSourceSyncResource(
            resourceId = "azure:subscription:$subscriptionId",
            name = source.displayName,
            resourceType = "azure_subscription",
            provider = CLOUD_PROVIDER_AZURE,
            account = subscriptionId,
            region = "global",
            health = CLOUD_SOURCE_STATUS_HEALTHY,
            metadata = mapOf("Tenant" to tenantId)
        )
    }
}

class ClickHouseCloudResourceWriter : CloudResourceWriter {
    override suspend fun replaceResources(request: CloudResourceWriteRequest) {
        deleteResources(request.organizationId, request.sourceId)
        if (request.resources.isEmpty()) return

        val db = ClickHouseClient.getDatabase()
        val rows = request.resources.joinToString(",\n") { resource ->
            """(
                ${request.organizationId},
                ${request.sourceId},
                '${escapeSql(resource.resourceId)}',
                '${escapeSql(resource.name)}',
                '${escapeSql(resource.resourceType)}',
                '${escapeSql(resource.provider)}',
                '${escapeSql(resource.account)}',
                '${escapeSql(resource.region)}',
                '${escapeSql(resource.health)}',
                ${mapToSqlMap(resource.tags)},
                ${mapToSqlMap(resource.metadata)},
                ${resource.cpuPercent},
                ${resource.memPercent},
                ${resource.monthlyUsd},
                ${resource.costTrendPct},
                now64(3),
                now64(3),
                now64(3)
            )"""
        }
        val sql = """
            INSERT INTO `$db`.cloud_resources_latest (
                organization_id, cloud_source_id, resource_id, name, resource_type, provider, account, region,
                health, tags, metadata, cpu_percent, mem_percent, monthly_usd, cost_trend_pct, first_seen,
                last_seen, collected_at
            ) VALUES $rows
        """.trimIndent()
        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            throw CloudSourceConnectorUnavailableException(
                "Cloud resource catalog write failed: ${body.take(CLICKHOUSE_ERROR_PREVIEW_LENGTH)}"
            )
        }
    }

    override suspend fun deleteResources(organizationId: Int, sourceId: Int) {
        val db = ClickHouseClient.getDatabase()
        val sql = """
            ALTER TABLE `$db`.cloud_resources_latest
            DELETE WHERE organization_id = toUInt64($organizationId)
              AND cloud_source_id = toUInt64($sourceId)
        """.trimIndent()
        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            throw CloudSourceConnectorUnavailableException(
                "Cloud resource catalog delete failed: ${body.take(CLICKHOUSE_ERROR_PREVIEW_LENGTH)}"
            )
        }
    }
}

class CloudSourceService(
    private val verifier: CloudSourceVerifier = ManagedIdentityCloudSourceVerifier(),
    private val resourceWriter: CloudResourceWriter = ClickHouseCloudResourceWriter(),
    private val identityConfig: CloudSourceIdentityConfig = CloudSourceIdentityConfig.fromEnv(),
) {
    fun setupPreview(organizationId: Int, provider: String): CloudSourceSetupPreview {
        val normalizedProvider = provider.normalizedProvider()
        val externalId = externalIdForOrganization(organizationId)
        return when (normalizedProvider) {
            CLOUD_PROVIDER_AWS -> awsPreview(externalId)
            CLOUD_PROVIDER_GCP -> gcpPreview(externalId)
            CLOUD_PROVIDER_AZURE -> azurePreview(externalId)
            else -> throw InvalidCloudSourceException()
        }
    }

    fun listSources(organizationId: Int): List<CloudSourceResponse> =
        transaction {
            CloudSources
                .selectAll()
                .where { CloudSources.organization_id eq organizationId }
                .orderBy(CloudSources.created_at to SortOrder.DESC)
                .map(::rowToResponse)
        }

    suspend fun createSource(
        organizationId: Int,
        userId: Int,
        request: CloudSourceCreateRequest,
    ): CloudSourceResponse {
        val normalizedRequest = validateRequest(request)
        val now = Clock.System.now()
        val externalId = externalIdForOrganization(organizationId)
        val sourceId = transaction {
            CloudSources.insert {
                it[CloudSources.organization_id] = organizationId
                it[CloudSources.provider] = normalizedRequest.provider
                it[CloudSources.display_name] = normalizedRequest.displayName.trim()
                it[CloudSources.account_id] = normalizedRequest.config.accountId.trimToNull()
                it[CloudSources.role_name] = normalizedRequest.config.roleName.trimToNull()
                it[CloudSources.project_id] = normalizedRequest.config.projectId.trimToNull()
                it[CloudSources.tenant_id] = normalizedRequest.config.tenantId.trimToNull()
                it[CloudSources.subscription_id] = normalizedRequest.config.subscriptionId.trimToNull()
                it[CloudSources.billing_export_table] = normalizedRequest.config.billingExportTable.trimToNull()
                it[CloudSources.external_id] = externalId
                it[CloudSources.collect_metrics] = normalizedRequest.collectMetrics
                it[CloudSources.collect_inventory] = normalizedRequest.collectInventory
                it[CloudSources.collect_cost] = normalizedRequest.collectCost
                it[CloudSources.collect_logs] = false
                it[CloudSources.status] = CLOUD_SOURCE_STATUS_SYNCING
                it[CloudSources.last_sync_at] = null
                it[CloudSources.last_error] = null
                it[CloudSources.created_by] = userId
                it[CloudSources.created_at] = now
                it[CloudSources.updated_at] = now
            } get CloudSources.id
        }

        return syncSourceInternal(
            organizationId = organizationId,
            sourceId = sourceId,
            request = normalizedRequest,
            externalId = externalId
        )
    }

    suspend fun syncSource(organizationId: Int, sourceId: Int): CloudSourceResponse {
        val existing = transaction {
            CloudSources
                .selectAll()
                .where { (CloudSources.id eq sourceId) and (CloudSources.organization_id eq organizationId) }
                .firstOrNull()
                ?.let(::rowToCreateRequest)
        } ?: throw InvalidCloudSourceException("Cloud source not found")

        transaction {
            CloudSources.update({
                (CloudSources.id eq sourceId) and (CloudSources.organization_id eq organizationId)
            }) {
                it[CloudSources.status] = CLOUD_SOURCE_STATUS_SYNCING
                it[CloudSources.updated_at] = Clock.System.now()
            }
        }

        return syncSourceInternal(
            organizationId = organizationId,
            sourceId = sourceId,
            request = existing,
            externalId = externalIdForOrganization(organizationId)
        )
    }

    suspend fun deleteSource(organizationId: Int, sourceId: Int): Boolean {
        val exists = transaction {
            CloudSources
                .selectAll()
                .where { (CloudSources.id eq sourceId) and (CloudSources.organization_id eq organizationId) }
                .firstOrNull() != null
        }
        if (!exists) return false

        resourceWriter.deleteResources(organizationId, sourceId)
        return transaction {
            CloudSources.deleteWhere {
                (CloudSources.id eq sourceId) and (CloudSources.organization_id eq organizationId)
            } > 0
        }
    }

    private suspend fun syncSourceInternal(
        organizationId: Int,
        sourceId: Int,
        request: CloudSourceCreateRequest,
        externalId: String,
    ): CloudSourceResponse {
        try {
            val verificationRequest = CloudSourceVerificationRequest(
                organizationId = organizationId,
                sourceId = sourceId,
                provider = request.provider,
                displayName = request.displayName.trim(),
                config = request.config,
                externalId = externalId,
                collectMetrics = request.collectMetrics,
                collectInventory = request.collectInventory,
                collectCost = request.collectCost
            )
            val syncResult = verifier.verifyAndDiscover(verificationRequest)
            resourceWriter.replaceResources(
                CloudResourceWriteRequest(
                    organizationId = organizationId,
                    sourceId = sourceId,
                    resources = syncResult.resources
                )
            )
            val now = Clock.System.now()
            return transaction {
                CloudSources.update({ CloudSources.id eq sourceId }) {
                    it[CloudSources.status] = CLOUD_SOURCE_STATUS_HEALTHY
                    it[CloudSources.last_sync_at] = now
                    it[CloudSources.last_error] = null
                    it[CloudSources.updated_at] = now
                }
                requireNotNull(
                    CloudSources
                        .selectAll()
                        .where { CloudSources.id eq sourceId }
                        .firstOrNull()
                ) { "Cloud source not found after sync" }.let(::rowToResponse)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            markSourceError(sourceId, error.message ?: "Cloud source sync failed")
            throw error
        }
    }

    private fun markSourceError(sourceId: Int, message: String) {
        transaction {
            CloudSources.update({ CloudSources.id eq sourceId }) {
                it[CloudSources.status] = CLOUD_SOURCE_STATUS_ERROR
                it[CloudSources.last_error] = message
                it[CloudSources.updated_at] = Clock.System.now()
            }
        }
    }

    private fun validateRequest(request: CloudSourceCreateRequest): CloudSourceCreateRequest {
        val provider = request.provider.normalizedProvider()
        if (request.collectLogs) {
            throw InvalidCloudSourceException("Cloud logs require a dedicated setup flow")
        }
        val displayName = request.displayName.trim()
        if (displayName.isEmpty()) {
            throw InvalidCloudSourceException("Display name is required")
        }
        if (displayName.length > CLOUD_SOURCE_DISPLAY_NAME_MAX_LENGTH) {
            throw InvalidCloudSourceException(
                "Display name must be at most $CLOUD_SOURCE_DISPLAY_NAME_MAX_LENGTH characters"
            )
        }
        validateProviderConfig(provider, request.config)
        return request.copy(provider = provider, displayName = displayName, collectLogs = false)
    }

    private fun validateProviderConfig(provider: String, config: CloudSourceProviderConfig) {
        when (provider) {
            CLOUD_PROVIDER_AWS -> {
                config.accountId.required("AWS account ID")
                config.roleName.required("AWS role name")
            }
            CLOUD_PROVIDER_GCP -> config.projectId.required("GCP project ID")
            CLOUD_PROVIDER_AZURE -> {
                config.tenantId.required("Azure tenant ID")
                config.subscriptionId.required("Azure subscription ID")
            }
            else -> throw InvalidCloudSourceException()
        }
    }

    private fun awsPreview(externalId: String): CloudSourceSetupPreview {
        val principal = identityConfig.awsPrincipalArn ?: "arn:aws:iam::<moneat-account-id>:root"
        val externalIdConditionKey = listOf("sts", "ExternalId").joinToString(":")
        val snippet = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Principal": {"AWS": "$principal"},
                  "Action": "sts:AssumeRole",
                  "Condition": {"StringEquals": {"$externalIdConditionKey": "$externalId"}}
                }
              ]
            }
        """.trimIndent()
        return CloudSourceSetupPreview(
            provider = CLOUD_PROVIDER_AWS,
            externalId = externalId,
            principal = principal,
            snippetLabel = "Trust policy",
            snippetLanguage = "json",
            snippet = snippet
        )
    }

    private fun gcpPreview(externalId: String): CloudSourceSetupPreview {
        val principal = identityConfig.gcpServiceAccount ?: "moneat-cloud@<moneat-project>.iam.gserviceaccount.com"
        val snippet = """
            gcloud projects add-iam-policy-binding PROJECT_ID \
              --member="serviceAccount:$principal" \
              --role="roles/viewer" \
              --condition="expression=request.auth.claims.external_id == '$externalId',title=moneat"
        """.trimIndent()
        return CloudSourceSetupPreview(
            provider = CLOUD_PROVIDER_GCP,
            externalId = externalId,
            principal = principal,
            snippetLabel = "gcloud",
            snippetLanguage = "bash",
            snippet = snippet
        )
    }

    private fun azurePreview(externalId: String): CloudSourceSetupPreview {
        val principal = identityConfig.azureApplicationId ?: "<moneat-application-id>"
        val snippet = """
            az role assignment create \
              --assignee "$principal" \
              --role Reader \
              --scope /subscriptions/SUBSCRIPTION_ID
        """.trimIndent()
        return CloudSourceSetupPreview(
            provider = CLOUD_PROVIDER_AZURE,
            externalId = externalId,
            principal = principal,
            snippetLabel = "Azure CLI",
            snippetLanguage = "bash",
            snippet = snippet
        )
    }

    private fun rowToCreateRequest(row: ResultRow): CloudSourceCreateRequest =
        CloudSourceCreateRequest(
            provider = row[CloudSources.provider],
            displayName = row[CloudSources.display_name],
            config = CloudSourceProviderConfig(
                accountId = row[CloudSources.account_id],
                roleName = row[CloudSources.role_name],
                projectId = row[CloudSources.project_id],
                tenantId = row[CloudSources.tenant_id],
                subscriptionId = row[CloudSources.subscription_id],
                billingExportTable = row[CloudSources.billing_export_table]
            ),
            collectMetrics = row[CloudSources.collect_metrics],
            collectInventory = row[CloudSources.collect_inventory],
            collectCost = row[CloudSources.collect_cost],
            collectLogs = row[CloudSources.collect_logs]
        )
}

private fun rowToResponse(row: ResultRow): CloudSourceResponse =
    CloudSourceResponse(
        id = row[CloudSources.id],
        provider = row[CloudSources.provider],
        displayName = row[CloudSources.display_name],
        status = row[CloudSources.status],
        config = CloudSourceProviderConfig(
            accountId = row[CloudSources.account_id],
            roleName = row[CloudSources.role_name],
            projectId = row[CloudSources.project_id],
            tenantId = row[CloudSources.tenant_id],
            subscriptionId = row[CloudSources.subscription_id],
            billingExportTable = row[CloudSources.billing_export_table]
        ),
        collectMetrics = row[CloudSources.collect_metrics],
        collectInventory = row[CloudSources.collect_inventory],
        collectCost = row[CloudSources.collect_cost],
        collectLogs = row[CloudSources.collect_logs],
        externalId = row[CloudSources.external_id],
        lastSyncAt = row[CloudSources.last_sync_at]?.toString(),
        lastError = row[CloudSources.last_error],
        createdAt = row[CloudSources.created_at].toString(),
        updatedAt = row[CloudSources.updated_at].toString()
    )

private fun externalIdForOrganization(organizationId: Int): String {
    val digest = MessageDigest
        .getInstance("SHA-256")
        .digest("moneat-cloud-source:$organizationId".toByteArray(UTF_8))
    val hex = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and HEX_BYTE_MASK) }
    return CLOUD_EXTERNAL_ID_PREFIX + hex.take(CLOUD_EXTERNAL_ID_HASH_LENGTH)
}

private fun String?.trimToNull(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.required(label: String): String =
    trimToNull() ?: throw InvalidCloudSourceException("$label is required")

private fun String?.requiredConnector(envName: String): String =
    trimToNull()
        ?: throw CloudSourceConnectorUnavailableException("Cloud connector is missing $envName")

private fun String.normalizedProvider(): String =
    when (lowercase().trim()) {
        CLOUD_PROVIDER_AWS -> CLOUD_PROVIDER_AWS
        CLOUD_PROVIDER_GCP -> CLOUD_PROVIDER_GCP
        CLOUD_PROVIDER_AZURE -> CLOUD_PROVIDER_AZURE
        else -> throw InvalidCloudSourceException()
    }
