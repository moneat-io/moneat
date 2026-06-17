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

package com.moneat.connectors

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.secrets.PurposeScopedSecretCipher
import com.moneat.secrets.SecretVaultPurpose
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val CONNECTOR_RANDOM_TOKEN_BYTES = 32
private val connectorTokenRandom = SecureRandom()

class ConnectorServiceException(
    val status: HttpStatusCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface ConnectorSecretCipher {
    val activeKeyId: String
    fun encrypt(plaintext: String, organizationId: Int): String
    fun decrypt(envelope: String, organizationId: Int): String
}

class PurposeConnectorSecretCipher(
    private val delegate: PurposeScopedSecretCipher,
) : ConnectorSecretCipher {
    override val activeKeyId: String = delegate.activeKeyId

    override fun encrypt(plaintext: String, organizationId: Int): String =
        delegate.encrypt(plaintext, organizationId)

    override fun decrypt(envelope: String, organizationId: Int): String =
        delegate.decrypt(envelope, organizationId)
}

data class AcceptedConnectorWebhook(
    val accepted: Boolean,
    val duplicate: Boolean,
)

class ConnectorService(
    private val projectIdResolver: ProjectIdResolver = ProjectIdResolver(),
    private val revenueCatClient: RevenueCatProviderClient = RevenueCatClient(),
    private val googleAdsClient: GoogleAdsProviderClient = GoogleAdsClient(),
    private val secretCipherFactory: () -> ConnectorSecretCipher = {
        PurposeConnectorSecretCipher(PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.DATA_IMPORT))
    },
    private val enqueueConnectorEvent: (String) -> Unit = { payload ->
        IngestionQueueClient.enqueue(IngestionPipeline.CONNECTOR_EVENTS, CONNECTOR_EVENT_QUEUE_KEY, payload)
    },
    private val importService: ConnectorImportService = ConnectorImportService(
        googleAdsClient = googleAdsClient,
        secretCipherFactory = secretCipherFactory,
    ),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun listInstallations(
        organizationId: Int,
        providerId: String? = null,
    ): ConnectorInstallationsResponse =
        transaction {
            ConnectorInstallationsResponse(
                ConnectorInstallations
                    .selectAll()
                    .where {
                        var predicate =
                            (ConnectorInstallations.organizationId eq organizationId) and
                                ConnectorInstallations.deletedAt.isNull()
                        if (!providerId.isNullOrBlank()) {
                            predicate = predicate and (ConnectorInstallations.provider eq providerId.trim())
                        }
                        predicate
                    }
                    .orderBy(ConnectorInstallations.createdAt, SortOrder.DESC)
                    .map { row -> row.toInstallationResponse() }
            )
        }

    fun getInstallation(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorInstallationResponse =
        transaction {
            val row = installationRowByResourceId(organizationId, installationResourceId)
            row.toInstallationResponse()
        }

    fun createInstallation(
        organizationId: Int,
        userId: Int,
        request: CreateConnectorInstallationRequest,
    ): ConnectorInstallationResponse =
        when (request.providerId) {
            RevenueCatClient.PROVIDER_ID -> createRevenueCatInstallation(organizationId, userId, request)
            GoogleAdsClient.PROVIDER_ID -> createGoogleAdsInstallation(organizationId, userId, request)
            else -> throw badRequest("Unsupported connector provider: ${request.providerId}")
        }

    private fun createRevenueCatInstallation(
        organizationId: Int,
        userId: Int,
        request: CreateConnectorInstallationRequest,
    ): ConnectorInstallationResponse {
        ensureRevenueCatInstallRequest(request)
        val secret = normalizedSecret(request.secret)
        val requestedProjectId = request.externalAccount.projectId.trim()
        val project = withRevenueCatErrorMapping {
            revenueCatClient.resolveProject(secret, requestedProjectId)
        }
        val apps = withRevenueCatErrorMapping {
            revenueCatClient.listApps(secret, project.id)
        }
        val cipher = secretCipherFactory()
        val encryptedSecret = cipher.encrypt(secret, organizationId)
        val webhookToken = generateWebhookToken()
        val now = Clock.System.now()

        return transaction {
            val installationId =
                ConnectorInstallations.insert {
                    it[ConnectorInstallations.organizationId] = organizationId
                    it[provider] = RevenueCatClient.PROVIDER_ID
                    it[name] = request.name.trim().ifBlank {
                        project.name?.let { projectName -> "RevenueCat $projectName" } ?: "RevenueCat"
                    }
                    it[credentialType] = "api_key"
                    it[authProfileId] = RevenueCatClient.AUTH_PROFILE_PROJECT_API_KEY
                    it[externalProjectId] = project.id
                    it[externalProjectName] = project.name
                    it[externalProjectDiscoveredAt] = now
                    it[authPermissionsSummary] = stringMapJson(mapOf("scope" to "project_api_key"))
                    it[status] = STATUS_HEALTHY
                    it[statusReason] = "RevenueCat API key validated"
                    it[lastTestedAt] = now
                    it[lastTestResult] = "success"
                    it[lastSuccessfulProviderCallAt] = now
                    it[lastError] = null
                    it[apiSecretCiphertext] = encryptedSecret
                    it[apiSecretKeyId] = cipher.activeKeyId
                    it[apiSecretLastFour] = secret.takeLast(API_SECRET_SUFFIX_CHARS)
                    it[webhookTokenHash] = sha256Hex(webhookToken)
                    it[webhookTokenPrefix] = webhookToken.take(WEBHOOK_TOKEN_PREFIX_CHARS)
                    it[webhookTokenCreatedAt] = now
                    it[enabled] = true
                    it[createdBy] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }[ConnectorInstallations.id]
            upsertRevenueCatApps(
                organizationId = organizationId,
                installationId = installationId,
                projectId = project.id,
                apps = apps,
                now = now,
            )
            installationRowById(organizationId, installationId)
                .toInstallationResponse(webhookToken = webhookToken)
        }
    }

    private fun createGoogleAdsInstallation(
        organizationId: Int,
        userId: Int,
        request: CreateConnectorInstallationRequest,
    ): ConnectorInstallationResponse {
        ensureGoogleAdsInstallRequest(request)
        val credential = parseGoogleAdsCredential(request.secret)
        val customerId = googleAdsCustomerId(request)
        val managerCustomerId = googleAdsManagerCustomerId(request)
        val customer = withGoogleAdsErrorMapping {
            googleAdsClient.validateCustomer(credential, customerId, managerCustomerId)
        }
        val resources = withGoogleAdsErrorMapping {
            googleAdsDiscoveredAccounts(credential, customer)
        }
        val cipher = secretCipherFactory()
        val serializedCredential = json.encodeToString(credential)
        val encryptedSecret = cipher.encrypt(serializedCredential, organizationId)
        val now = Clock.System.now()

        return transaction {
            val installationId =
                ConnectorInstallations.insert {
                    it[ConnectorInstallations.organizationId] = organizationId
                    it[provider] = GoogleAdsClient.PROVIDER_ID
                    it[name] = request.name.trim().ifBlank {
                        customer.descriptiveName?.let { accountName -> "Google Ads $accountName" } ?: "Google Ads"
                    }
                    it[credentialType] = "oauth_refresh_token"
                    it[authProfileId] = GoogleAdsClient.AUTH_PROFILE_MANAGER_OAUTH
                    it[externalProjectId] = customer.customerId
                    it[externalProjectName] = customer.descriptiveName
                    it[externalProjectDiscoveredAt] = now
                    it[authPermissionsSummary] = stringMapJson(
                        mapOf(
                            "scope" to GoogleAdsClient.OAUTH_SCOPE,
                            "loginCustomerId" to customer.loginCustomerId.orEmpty(),
                        )
                    )
                    it[status] = STATUS_HEALTHY
                    it[statusReason] = "Google Ads OAuth grant validated"
                    it[lastTestedAt] = now
                    it[lastTestResult] = "success"
                    it[lastSuccessfulProviderCallAt] = now
                    it[lastError] = null
                    it[apiSecretCiphertext] = encryptedSecret
                    it[apiSecretKeyId] = cipher.activeKeyId
                    it[apiSecretLastFour] = credential.refreshToken.takeLast(API_SECRET_SUFFIX_CHARS)
                    it[enabled] = true
                    it[createdBy] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }[ConnectorInstallations.id]
            upsertGoogleAdsCustomers(
                organizationId = organizationId,
                installationId = installationId,
                externalProjectId = customer.customerId,
                accounts = resources,
                now = now,
            )
            installationRowById(organizationId, installationId).toInstallationResponse()
        }
    }

    fun deleteInstallation(
        organizationId: Int,
        installationResourceId: String,
    ) {
        val now = Clock.System.now()
        transaction {
            val row = installationRowByResourceId(organizationId, installationResourceId)
            ConnectorInstallations.update({ ConnectorInstallations.id eq row[ConnectorInstallations.id] }) {
                it[enabled] = false
                it[deletedAt] = now
                it[updatedAt] = now
            }
            deactivateBindingsForInstallation(row[ConnectorInstallations.id], null, BINDING_REMOVED, now)
        }
    }

    fun rotateApiCredential(
        organizationId: Int,
        installationResourceId: String,
        request: RotateConnectorApiCredentialRequest,
    ): ConnectorInstallationResponse {
        val row =
            transaction { installationRowByResourceId(organizationId, installationResourceId).toInstallationRecord() }
        return when (row.provider) {
            RevenueCatClient.PROVIDER_ID -> rotateRevenueCatCredential(organizationId, row, request)
            GoogleAdsClient.PROVIDER_ID -> rotateGoogleAdsCredential(organizationId, row, request)
            else -> throw badRequest("Unsupported connector provider: ${row.provider}")
        }
    }

    private fun rotateRevenueCatCredential(
        organizationId: Int,
        row: ConnectorInstallationRecord,
        request: RotateConnectorApiCredentialRequest,
    ): ConnectorInstallationResponse {
        val secret = normalizedSecret(request.secret)
        val projectId = row.externalProjectId ?: throw badRequest("Connector installation has no external project")
        withRevenueCatErrorMapping {
            revenueCatClient.resolveProject(secret, projectId)
        }
        val apps = withRevenueCatErrorMapping {
            revenueCatClient.listApps(secret, projectId)
        }
        val cipher = secretCipherFactory()
        val encryptedSecret = cipher.encrypt(secret, organizationId)
        val now = Clock.System.now()

        return transaction {
            ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                it[apiSecretCiphertext] = encryptedSecret
                it[apiSecretKeyId] = cipher.activeKeyId
                it[apiSecretLastFour] = secret.takeLast(API_SECRET_SUFFIX_CHARS)
                it[status] = STATUS_HEALTHY
                it[statusReason] = "RevenueCat API key validated"
                it[lastTestedAt] = now
                it[lastTestResult] = "success"
                it[lastSuccessfulProviderCallAt] = now
                it[lastError] = null
                it[updatedAt] = now
            }
            upsertRevenueCatApps(organizationId, row.id, projectId, apps, now)
            installationRowById(organizationId, row.id).toInstallationResponse()
        }
    }

    private fun rotateGoogleAdsCredential(
        organizationId: Int,
        row: ConnectorInstallationRecord,
        request: RotateConnectorApiCredentialRequest,
    ): ConnectorInstallationResponse {
        val credential = parseGoogleAdsCredential(request.secret)
        val customerId = row.externalProjectId ?: throw badRequest("Connector installation has no Google Ads customer")
        val customer = withGoogleAdsErrorMapping {
            googleAdsClient.validateCustomer(credential, customerId, credential.loginCustomerId)
        }
        val accounts = withGoogleAdsErrorMapping {
            googleAdsDiscoveredAccounts(credential, customer)
        }
        val cipher = secretCipherFactory()
        val encryptedSecret = cipher.encrypt(json.encodeToString(credential), organizationId)
        val now = Clock.System.now()

        return transaction {
            ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                it[apiSecretCiphertext] = encryptedSecret
                it[apiSecretKeyId] = cipher.activeKeyId
                it[apiSecretLastFour] = credential.refreshToken.takeLast(API_SECRET_SUFFIX_CHARS)
                it[status] = STATUS_HEALTHY
                it[statusReason] = "Google Ads OAuth grant validated"
                it[externalProjectName] = customer.descriptiveName
                it[externalProjectDiscoveredAt] = now
                it[lastTestedAt] = now
                it[lastTestResult] = "success"
                it[lastSuccessfulProviderCallAt] = now
                it[lastError] = null
                it[updatedAt] = now
            }
            upsertGoogleAdsCustomers(organizationId, row.id, customer.customerId, accounts, now)
            installationRowById(organizationId, row.id).toInstallationResponse()
        }
    }

    fun rotateWebhookToken(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorInstallationResponse {
        val webhookToken = generateWebhookToken()
        val now = Clock.System.now()
        return transaction {
            val row = installationRowByResourceId(organizationId, installationResourceId)
            if (row[ConnectorInstallations.provider] != RevenueCatClient.PROVIDER_ID) {
                throw badRequest("Connector does not use webhook tokens")
            }
            ConnectorInstallations.update({ ConnectorInstallations.id eq row[ConnectorInstallations.id] }) {
                it[webhookTokenHash] = sha256Hex(webhookToken)
                it[webhookTokenPrefix] = webhookToken.take(WEBHOOK_TOKEN_PREFIX_CHARS)
                it[webhookTokenRotatedAt] = now
                it[updatedAt] = now
            }
            installationRowById(organizationId, row[ConnectorInstallations.id])
                .toInstallationResponse(webhookToken = webhookToken)
        }
    }

    fun testInstallation(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorInstallationResponse {
        val row =
            transaction { installationRowByResourceId(organizationId, installationResourceId).toInstallationRecord() }
        return when (row.provider) {
            RevenueCatClient.PROVIDER_ID -> testRevenueCatInstallation(organizationId, row)
            GoogleAdsClient.PROVIDER_ID -> testGoogleAdsInstallation(organizationId, row)
            else -> throw badRequest("Unsupported connector provider: ${row.provider}")
        }
    }

    private fun testRevenueCatInstallation(
        organizationId: Int,
        row: ConnectorInstallationRecord,
    ): ConnectorInstallationResponse {
        val projectId = row.externalProjectId ?: throw badRequest("Connector installation has no external project")
        val secret = decryptApiSecret(row)
        val now = Clock.System.now()
        return try {
            val project = withRevenueCatErrorMapping {
                revenueCatClient.resolveProject(secret, projectId)
            }
            val apps = withRevenueCatErrorMapping {
                revenueCatClient.listApps(secret, project.id)
            }
            transaction {
                ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                    it[status] = STATUS_HEALTHY
                    it[statusReason] = "RevenueCat API key validated"
                    it[externalProjectName] = project.name
                    it[externalProjectDiscoveredAt] = now
                    it[lastTestedAt] = now
                    it[lastTestResult] = "success"
                    it[lastSuccessfulProviderCallAt] = now
                    it[lastError] = null
                    it[updatedAt] = now
                }
                upsertRevenueCatApps(organizationId, row.id, project.id, apps, now)
                installationRowById(organizationId, row.id).toInstallationResponse()
            }
        } catch (error: ConnectorServiceException) {
            transaction {
                ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                    it[status] = STATUS_DEGRADED
                    it[statusReason] = error.message
                    it[lastTestedAt] = now
                    it[lastTestResult] = "failed"
                    it[lastError] = error.message
                    it[updatedAt] = now
                }
                installationRowById(organizationId, row.id).toInstallationResponse()
            }
        }
    }

    private fun testGoogleAdsInstallation(
        organizationId: Int,
        row: ConnectorInstallationRecord,
    ): ConnectorInstallationResponse {
        val customerId = row.externalProjectId ?: throw badRequest("Connector installation has no Google Ads customer")
        val credential = parseGoogleAdsCredential(decryptApiSecret(row))
        val now = Clock.System.now()
        return try {
            val customer = withGoogleAdsErrorMapping {
                googleAdsClient.validateCustomer(credential, customerId, credential.loginCustomerId)
            }
            val accounts = withGoogleAdsErrorMapping {
                googleAdsDiscoveredAccounts(credential, customer)
            }
            transaction {
                ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                    it[status] = STATUS_HEALTHY
                    it[statusReason] = "Google Ads OAuth grant validated"
                    it[externalProjectName] = customer.descriptiveName
                    it[externalProjectDiscoveredAt] = now
                    it[lastTestedAt] = now
                    it[lastTestResult] = "success"
                    it[lastSuccessfulProviderCallAt] = now
                    it[lastError] = null
                    it[updatedAt] = now
                }
                upsertGoogleAdsCustomers(organizationId, row.id, customer.customerId, accounts, now)
                installationRowById(organizationId, row.id).toInstallationResponse()
            }
        } catch (error: ConnectorServiceException) {
            transaction {
                ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                    it[status] = STATUS_DEGRADED
                    it[statusReason] = error.message
                    it[lastTestedAt] = now
                    it[lastTestResult] = "failed"
                    it[lastError] = error.message
                    it[updatedAt] = now
                }
                installationRowById(organizationId, row.id).toInstallationResponse()
            }
        }
    }

    fun refreshExternalResources(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorExternalResourcesResponse {
        val row =
            transaction { installationRowByResourceId(organizationId, installationResourceId).toInstallationRecord() }
        return when (row.provider) {
            RevenueCatClient.PROVIDER_ID -> refreshRevenueCatExternalResources(organizationId, row)
            GoogleAdsClient.PROVIDER_ID -> refreshGoogleAdsExternalResources(organizationId, row)
            else -> throw badRequest("Unsupported connector provider: ${row.provider}")
        }
    }

    private fun refreshRevenueCatExternalResources(
        organizationId: Int,
        row: ConnectorInstallationRecord,
    ): ConnectorExternalResourcesResponse {
        val projectId = row.externalProjectId ?: throw badRequest("Connector installation has no external project")
        val apps = withRevenueCatErrorMapping {
            revenueCatClient.listApps(decryptApiSecret(row), projectId)
        }
        val now = Clock.System.now()
        return transaction {
            upsertRevenueCatApps(organizationId, row.id, projectId, apps, now)
            resourcesForInstallation(organizationId, row.id)
        }
    }

    private fun refreshGoogleAdsExternalResources(
        organizationId: Int,
        row: ConnectorInstallationRecord,
    ): ConnectorExternalResourcesResponse {
        val customerId = row.externalProjectId ?: throw badRequest("Connector installation has no Google Ads customer")
        val credential = parseGoogleAdsCredential(decryptApiSecret(row))
        val customer = withGoogleAdsErrorMapping {
            googleAdsClient.validateCustomer(credential, customerId, credential.loginCustomerId)
        }
        val accounts = withGoogleAdsErrorMapping {
            googleAdsDiscoveredAccounts(credential, customer)
        }
        val now = Clock.System.now()
        return transaction {
            upsertGoogleAdsCustomers(organizationId, row.id, customer.customerId, accounts, now)
            ConnectorInstallations.update({ ConnectorInstallations.id eq row.id }) {
                it[status] = STATUS_HEALTHY
                it[statusReason] = "Google Ads accounts refreshed"
                it[externalProjectName] = customer.descriptiveName
                it[externalProjectDiscoveredAt] = now
                it[lastSuccessfulProviderCallAt] = now
                it[lastError] = null
                it[updatedAt] = now
            }
            resourcesForInstallation(organizationId, row.id)
        }
    }

    fun listExternalResources(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorExternalResourcesResponse =
        transaction {
            val row = installationRowByResourceId(organizationId, installationResourceId)
            resourcesForInstallation(organizationId, row[ConnectorInstallations.id])
        }

    fun listBindings(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorBindingsResponse =
        transaction {
            val row = installationRowByResourceId(organizationId, installationResourceId)
            bindingsForInstallation(organizationId, row[ConnectorInstallations.id])
        }

    fun listImportRuns(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorImportRunsResponse =
        importService.listImportRuns(organizationId, installationResourceId)

    fun enqueueSync(
        organizationId: Int,
        userId: Int,
        installationResourceId: String,
        request: ConnectorSyncRequest,
    ): ConnectorImportRunResponse =
        importService.enqueueSync(organizationId, userId, installationResourceId, request)

    suspend fun processImportRun(importRunId: Long) =
        importService.processImportRun(importRunId)

    fun upsertBindings(
        organizationId: Int,
        userId: Int,
        installationResourceId: String,
        request: UpsertConnectorBindingRequest,
    ): ConnectorBindingsResponse {
        ensureDistinctBindingInputs(request.bindings)
        val now = Clock.System.now()
        transaction {
            val installation =
                installationRowByResourceId(organizationId, installationResourceId).toInstallationRecord()
            val desiredBindings = request.bindings.map { input -> input.bindingKey() }.toSet()
            deactivateBindingsForInstallation(installation.id, desiredBindings, BINDING_REMOVED, now)
            request.bindings.forEach { input ->
                upsertBinding(organizationId, userId, installation, input, now)
            }
        }
        return listBindings(organizationId, installationResourceId)
    }

    fun webhookSetup(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorWebhookSetupResponse {
        val row =
            transaction { installationRowByResourceId(organizationId, installationResourceId).toInstallationRecord() }
        ensureProvider(row, RevenueCatClient.PROVIDER_ID)
        val projectId = row.externalProjectId ?: throw badRequest("Connector installation has no external project")
        val webhookUrl = webhookUrl(row.resourceId)
        val observed = runCatching {
            revenueCatClient.listWebhookIntegrations(decryptApiSecret(row), projectId, webhookUrl)
        }.getOrElse { error ->
            emptyList<ConnectorObservedWebhookIntegration>() to
                listOf("Moneat could not inspect RevenueCat webhook integrations: ${error.message}")
        }
        val warnings = observed.second + webhookDriftWarnings(observed.first)
        return ConnectorWebhookSetupResponse(
            installationId = row.resourceId.toString(),
            providerId = RevenueCatClient.PROVIDER_ID,
            webhookUrl = webhookUrl,
            authorizationHeaderName = "Authorization",
            authorizationHeaderValue = null,
            authorizationHeaderPrefix = row.webhookTokenPrefix,
            recommendedEventTypes = RECOMMENDED_REVENUECAT_EVENTS,
            recommendedEnvironment = "production_and_sandbox",
            observedIntegrations = observed.first,
            warnings = warnings,
        )
    }

    fun acceptRevenueCatWebhook(
        installationResourceId: String,
        authorizationHeader: String?,
        body: ByteArray,
        requestHeaders: Map<String, String>,
    ): AcceptedConnectorWebhook {
        val rawPayload = body.toString(Charsets.UTF_8)
        val parsed = parseRevenueCatWebhook(rawPayload)
        val token = parseBearerToken(authorizationHeader)
        val payloadHash = sha256Hex(body)
        val now = Clock.System.now()
        val accepted = transaction {
            val installation = publicInstallationRowByResourceId(installationResourceId).toInstallationRecord()
            ensureProvider(installation, RevenueCatClient.PROVIDER_ID)
            if (!installation.enabled) {
                throw ConnectorServiceException(HttpStatusCode.Gone, "Connector installation is disabled")
            }
            val expectedHash = installation.webhookTokenHash
                ?: throw ConnectorServiceException(HttpStatusCode.Unauthorized, "Webhook token is not configured")
            if (!secureEquals(expectedHash, sha256Hex(token))) {
                throw ConnectorServiceException(HttpStatusCode.Unauthorized, "Invalid RevenueCat webhook token")
            }

            val rawEventId =
                ConnectorInboundEventsRaw.insert {
                    it[ConnectorInboundEventsRaw.organizationId] = installation.organizationId
                    it[ConnectorInboundEventsRaw.installationId] = installation.id
                    it[ConnectorInboundEventsRaw.provider] = RevenueCatClient.PROVIDER_ID
                    it[ConnectorInboundEventsRaw.providerEventId] = parsed.eventId
                    it[ConnectorInboundEventsRaw.payloadSha256] = payloadHash
                    it[ConnectorInboundEventsRaw.requestHeaders] = stringMapJson(requestHeaders)
                    it[ConnectorInboundEventsRaw.rawPayload] = rawPayload
                    it[ConnectorInboundEventsRaw.receivedAt] = now
                    it[ConnectorInboundEventsRaw.providerEventTimestampMs] = parsed.eventTimestampMs
                    it[ConnectorInboundEventsRaw.eventType] = parsed.eventType
                    it[ConnectorInboundEventsRaw.environment] = parsed.environment
                    it[ConnectorInboundEventsRaw.externalProjectId] = installation.externalProjectId
                    it[ConnectorInboundEventsRaw.externalResourceId] = parsed.appId
                    it[ConnectorInboundEventsRaw.authTokenPrefix] = installation.webhookTokenPrefix
                }[ConnectorInboundEventsRaw.id]

            val receipt = receiptRow(installation.id, parsed.eventId)
            if (receipt != null) {
                ConnectorEventReceipts.update({ ConnectorEventReceipts.id eq receipt[ConnectorEventReceipts.id] }) {
                    it[lastSeenAt] = now
                    it[updatedAt] = now
                }
                val state = receipt[ConnectorEventReceipts.state]
                val shouldRetry = state in RETRYABLE_RECEIPT_STATES
                WebhookAcceptTransaction(
                    rawEventId = receipt[ConnectorEventReceipts.rawEventId],
                    duplicate = true,
                    shouldEnqueue = shouldRetry,
                )
            } else {
                ConnectorEventReceipts.insert {
                    it[ConnectorEventReceipts.organizationId] = installation.organizationId
                    it[ConnectorEventReceipts.installationId] = installation.id
                    it[ConnectorEventReceipts.provider] = RevenueCatClient.PROVIDER_ID
                    it[ConnectorEventReceipts.providerEventId] = parsed.eventId
                    it[ConnectorEventReceipts.payloadSha256] = payloadHash
                    it[ConnectorEventReceipts.rawEventId] = rawEventId
                    it[ConnectorEventReceipts.receivedAt] = now
                    it[ConnectorEventReceipts.providerEventTimestampMs] = parsed.eventTimestampMs
                    it[ConnectorEventReceipts.firstSeenAt] = now
                    it[ConnectorEventReceipts.lastSeenAt] = now
                    it[ConnectorEventReceipts.state] = RECEIPT_RECEIVED
                    it[ConnectorEventReceipts.createdAt] = now
                    it[ConnectorEventReceipts.updatedAt] = now
                }
                WebhookAcceptTransaction(rawEventId = rawEventId, duplicate = false, shouldEnqueue = true)
            }
        }

        if (accepted.shouldEnqueue) {
            try {
                enqueueConnectorEvent(accepted.rawEventId.toString())
                transaction {
                    ConnectorEventReceipts.update({ ConnectorEventReceipts.rawEventId eq accepted.rawEventId }) {
                        it[state] = RECEIPT_QUEUED
                        it[updatedAt] = Clock.System.now()
                    }
                    updateInstallationWebhookAccepted(accepted.rawEventId)
                }
            } catch (error: Throwable) {
                transaction {
                    ConnectorEventReceipts.update({ ConnectorEventReceipts.rawEventId eq accepted.rawEventId }) {
                        it[state] = RECEIPT_FAILED_RETRYABLE
                        it[lastErrorCode] = "enqueue_failed"
                        it[lastErrorMessage] = error.message
                        it[updatedAt] = Clock.System.now()
                    }
                }
                throw ConnectorServiceException(
                    HttpStatusCode.ServiceUnavailable,
                    "RevenueCat webhook was accepted but could not be queued",
                    error,
                )
            }
        }
        return AcceptedConnectorWebhook(accepted = true, duplicate = accepted.duplicate)
    }

    suspend fun processRawEvent(rawEventId: Long) {
        val raw = transaction { rawEventRecord(rawEventId) }
        if (raw.receiptState == RECEIPT_APPLIED) return
        val claimedAt = Clock.System.now()
        transaction {
            ConnectorEventReceipts.update({ ConnectorEventReceipts.id eq raw.receiptId }) {
                it[state] = RECEIPT_PROCESSING
                it[workerClaimedAt] = claimedAt
                it[attemptCount] = raw.attemptCount + 1
                it[updatedAt] = claimedAt
            }
        }
        try {
            val fact = normalizeRevenueCatFact(raw)
            insertSubscriptionFact(fact)
            transaction {
                ConnectorEventReceipts.update({ ConnectorEventReceipts.id eq raw.receiptId }) {
                    it[state] = RECEIPT_APPLIED
                    it[appliedAt] = Clock.System.now()
                    it[lastErrorCode] = null
                    it[lastErrorMessage] = null
                    it[updatedAt] = Clock.System.now()
                }
                ConnectorInstallations.update({ ConnectorInstallations.id eq raw.installationId }) {
                    it[status] = STATUS_HEALTHY
                    it[statusReason] = "RevenueCat events are being processed"
                    it[lastError] = null
                    it[updatedAt] = Clock.System.now()
                }
            }
        } catch (error: Throwable) {
            transaction {
                ConnectorEventReceipts.update({ ConnectorEventReceipts.id eq raw.receiptId }) {
                    it[state] = RECEIPT_FAILED_RETRYABLE
                    it[lastErrorCode] = error::class.simpleName ?: "processing_failed"
                    it[lastErrorMessage] = error.message
                    it[updatedAt] = Clock.System.now()
                }
                ConnectorInstallations.update({ ConnectorInstallations.id eq raw.installationId }) {
                    it[status] = STATUS_DEGRADED
                    it[statusReason] = "RevenueCat event processing failed"
                    it[lastError] = error.message
                    it[updatedAt] = Clock.System.now()
                }
            }
            throw error
        }
    }

    fun connectorInstallationStates(
        organizationId: Int,
    ): Map<String, Pair<ConnectorConnectionSummary, ConnectorProviderStateDetail>> =
        buildMap {
            revenueCatState(organizationId).let { state ->
                val summary = state.first
                val detail = state.second
                if (summary != null && detail != null) {
                    put(RevenueCatClient.PROVIDER_ID, summary to detail)
                }
            }
            googleAdsState(organizationId).let { state ->
                val summary = state.first
                val detail = state.second
                if (summary != null && detail != null) {
                    put(GoogleAdsClient.PROVIDER_ID, summary to detail)
                }
            }
        }

    fun revenueCatState(organizationId: Int): Pair<ConnectorConnectionSummary?, ConnectorProviderStateDetail?> =
        transaction {
            val installation =
                ConnectorInstallations
                    .selectAll()
                    .where {
                        (ConnectorInstallations.organizationId eq organizationId) and
                            (ConnectorInstallations.provider eq RevenueCatClient.PROVIDER_ID) and
                            ConnectorInstallations.deletedAt.isNull()
                    }
                    .orderBy(ConnectorInstallations.createdAt, SortOrder.DESC)
                    .firstOrNull()
                    ?: return@transaction null to null
            val record = installation.toInstallationRecord()
            val mappedResources = activeBindingCount(organizationId, record.id)
            val failedReceipts = failedReceiptCount(record.id)
            val unmappedEvents = unmappedEventCount(record.id)
            val lastRaw = lastRawEventAt(record.id)
            val lastApplied = lastAppliedAt(record.id)
            val environmentCounts = environmentCounts(record.id)
            val processingLagSeconds = processingLagSeconds(lastRaw, lastApplied)
            val health = connectorHealth(record, failedReceipts, unmappedEvents)
            val stateCounts = ConnectorStateCounts(
                mappedResources = mappedResources,
                unmappedEvents = unmappedEvents,
                failedReceipts = failedReceipts,
                lastRaw = lastRaw,
            )
            val message = connectorStateMessage(record, health, stateCounts)
            val detail = ConnectorProviderStateDetail(
                installationId = record.resourceId.toString(),
                status = if (lastRaw == null && record.status == STATUS_HEALTHY) {
                    STATUS_AWAITING_TRAFFIC
                } else {
                    record.status
                },
                health = health,
                message = message,
                mappedResources = mappedResources,
                unmappedEvents = unmappedEvents,
                failedReceipts = failedReceipts,
                sandboxEvents = environmentCounts.sandbox,
                productionEvents = environmentCounts.production,
                lastAcceptedWebhookAt = lastRaw?.toString(),
                lastAppliedAt = lastApplied?.toString(),
                processingLagSeconds = processingLagSeconds,
            )
            val summary = ConnectorConnectionSummary(
                providerId = RevenueCatClient.PROVIDER_ID,
                connected = true,
                health = health,
                detail = message,
                lastCheckedAt = record.lastTestedAt?.toString(),
            )
            summary to detail
        }

    fun googleAdsState(organizationId: Int): Pair<ConnectorConnectionSummary?, ConnectorProviderStateDetail?> =
        importService.googleAdsState(organizationId)

    private fun ensureRevenueCatInstallRequest(request: CreateConnectorInstallationRequest) {
        if (request.providerId != RevenueCatClient.PROVIDER_ID) {
            throw badRequest("Only RevenueCat connector installation is available in this release")
        }
        if (request.authProfileId != RevenueCatClient.AUTH_PROFILE_PROJECT_API_KEY) {
            throw badRequest("RevenueCat requires the project_api_key auth profile")
        }
        if (request.externalAccount.projectId.isBlank()) {
            throw badRequest("RevenueCat project ID is required")
        }
    }

    private fun ensureGoogleAdsInstallRequest(request: CreateConnectorInstallationRequest) {
        if (request.authProfileId != GoogleAdsClient.AUTH_PROFILE_MANAGER_OAUTH) {
            throw badRequest("Google Ads requires the manager_oauth auth profile")
        }
        googleAdsCustomerId(request)
        parseGoogleAdsCredential(request.secret)
    }

    private fun googleAdsCustomerId(request: CreateConnectorInstallationRequest): String =
        GoogleAdsClient.normalizeCustomerId(request.externalAccount.customerId ?: request.externalAccount.projectId)
            ?: throw badRequest("Google Ads customer ID is required")

    private fun googleAdsManagerCustomerId(request: CreateConnectorInstallationRequest): String? =
        GoogleAdsClient.normalizeCustomerId(request.externalAccount.managerCustomerId)

    private fun parseGoogleAdsCredential(secret: String): GoogleAdsOAuthCredential {
        val normalized = normalizedSecret(secret)
        val credential =
            if (normalized.startsWith("{")) {
                runCatching { json.decodeFromString<GoogleAdsOAuthCredential>(normalized) }
                    .getOrElse { throw badRequest("Google Ads OAuth credential must be valid JSON") }
            } else {
                GoogleAdsOAuthCredential(refreshToken = normalized)
            }
        val refreshToken = credential.refreshToken.trim()
        if (refreshToken.isBlank()) {
            throw badRequest("Google Ads refresh token is required")
        }
        val loginCustomerId = GoogleAdsClient.normalizeCustomerId(credential.loginCustomerId)
        return credential.copy(refreshToken = refreshToken, loginCustomerId = loginCustomerId)
    }

    private fun googleAdsDiscoveredAccounts(
        credential: GoogleAdsOAuthCredential,
        selectedCustomer: GoogleAdsCustomerAccount,
    ): List<GoogleAdsCustomerAccount> {
        val directAccounts = googleAdsClient.listAccessibleCustomers(credential)
        val managedAccounts =
            if (selectedCustomer.manager) {
                googleAdsClient.listCustomerClients(credential, selectedCustomer.customerId)
            } else {
                emptyList()
            }
        return (listOf(selectedCustomer) + directAccounts + managedAccounts)
            .distinctBy { account -> account.customerId }
    }

    private fun normalizedSecret(secret: String): String =
        secret.trim().takeIf { it.isNotBlank() } ?: throw badRequest("API key is required")

    private fun decryptApiSecret(record: ConnectorInstallationRecord): String {
        val ciphertext = record.apiSecretCiphertext ?: throw badRequest("Connector installation has no API credential")
        return secretCipherFactory().decrypt(ciphertext, record.organizationId)
    }

    private fun upsertRevenueCatApps(
        organizationId: Int,
        installationId: Int,
        projectId: String,
        apps: List<RevenueCatApp>,
        now: Instant,
    ) {
        apps.forEach { app ->
            val existing =
                ConnectorExternalResources
                    .selectAll()
                    .where {
                        (ConnectorExternalResources.installationId eq installationId) and
                            (ConnectorExternalResources.externalResourceType eq RevenueCatClient.RESOURCE_TYPE_APP) and
                            (ConnectorExternalResources.externalResourceId eq app.id)
                    }
                    .firstOrNull()
            val metadata = stringMapJson(mapOf("platform" to app.platform.orEmpty()))
            if (existing == null) {
                ConnectorExternalResources.insert {
                    it[ConnectorExternalResources.organizationId] = organizationId
                    it[ConnectorExternalResources.installationId] = installationId
                    it[externalProjectId] = projectId
                    it[externalResourceType] = RevenueCatClient.RESOURCE_TYPE_APP
                    it[externalResourceId] = app.id
                    it[displayName] = app.name
                    it[providerMetadata] = metadata
                    it[lastSeenAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                ConnectorExternalResources.update({
                    ConnectorExternalResources.id eq existing[ConnectorExternalResources.id]
                }) {
                    it[externalProjectId] = projectId
                    it[displayName] = app.name
                    it[providerMetadata] = metadata
                    it[lastSeenAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    private fun upsertGoogleAdsCustomers(
        organizationId: Int,
        installationId: Int,
        externalProjectId: String,
        accounts: List<GoogleAdsCustomerAccount>,
        now: Instant,
    ) {
        accounts.forEach { account ->
            val existing =
                ConnectorExternalResources
                    .selectAll()
                    .where {
                        (ConnectorExternalResources.installationId eq installationId) and
                            (ConnectorExternalResources.externalResourceType eq googleAdsResourceType(account)) and
                            (ConnectorExternalResources.externalResourceId eq account.customerId)
                    }
                    .firstOrNull()
            val metadata = googleAdsAccountMetadata(account)
            if (existing == null) {
                ConnectorExternalResources.insert {
                    it[ConnectorExternalResources.organizationId] = organizationId
                    it[ConnectorExternalResources.installationId] = installationId
                    it[ConnectorExternalResources.externalProjectId] = externalProjectId
                    it[externalResourceType] = googleAdsResourceType(account)
                    it[externalResourceId] = account.customerId
                    it[displayName] = account.descriptiveName ?: account.customerId
                    it[providerMetadata] = metadata
                    it[lastSeenAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } else {
                ConnectorExternalResources.update({
                    ConnectorExternalResources.id eq existing[ConnectorExternalResources.id]
                }) {
                    it[ConnectorExternalResources.externalProjectId] = externalProjectId
                    it[displayName] = account.descriptiveName ?: account.customerId
                    it[providerMetadata] = metadata
                    it[lastSeenAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    private fun googleAdsResourceType(account: GoogleAdsCustomerAccount): String =
        if (account.manager) GoogleAdsClient.RESOURCE_TYPE_MANAGER else GoogleAdsClient.RESOURCE_TYPE_CUSTOMER

    private fun googleAdsAccountMetadata(account: GoogleAdsCustomerAccount): String =
        stringMapJson(
            buildMap {
                put("resourceName", account.resourceName)
                put("manager", account.manager.toString())
                account.testAccount?.let { value -> put("testAccount", value.toString()) }
                account.status?.let { value -> put("status", value) }
                account.currencyCode?.let { value -> put("currencyCode", value) }
                account.timeZone?.let { value -> put("timeZone", value) }
                account.level?.let { value -> put("level", value.toString()) }
                account.loginCustomerId?.let { value -> put("loginCustomerId", value) }
            }
        )

    private fun resourcesForInstallation(
        organizationId: Int,
        installationId: Int,
    ): ConnectorExternalResourcesResponse =
        ConnectorExternalResourcesResponse(
            ConnectorExternalResources
                .selectAll()
                .where {
                    (ConnectorExternalResources.organizationId eq organizationId) and
                        (ConnectorExternalResources.installationId eq installationId)
                }
                .orderBy(ConnectorExternalResources.displayName, SortOrder.ASC)
                .map { row -> row.toResourceResponse() }
        )

    private fun bindingsForInstallation(
        organizationId: Int,
        installationId: Int,
    ): ConnectorBindingsResponse =
        ConnectorBindingsResponse(
            ConnectorUseBindings
                .selectAll()
                .where {
                    (ConnectorUseBindings.organizationId eq organizationId) and
                        (ConnectorUseBindings.installationId eq installationId)
                }
                .orderBy(ConnectorUseBindings.createdAt, SortOrder.DESC)
                .map { row -> row.toBindingResponse() }
        )

    private fun ensureDistinctBindingInputs(bindings: List<ConnectorBindingInput>) {
        val duplicateKey = bindings
            .groupingBy { input -> input.bindingKey() }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .firstOrNull()
        if (duplicateKey != null) {
            throw badRequest("External resource ${duplicateKey.externalResourceId} appears more than once")
        }
    }

    private fun deactivateBindingsForInstallation(
        installationId: Int,
        keepBindings: Set<BindingExternalKey>?,
        status: String,
        now: Instant,
    ) {
        val activeBindings =
            ConnectorUseBindings
                .selectAll()
                .where {
                    (ConnectorUseBindings.installationId eq installationId) and
                        (ConnectorUseBindings.status eq BINDING_ACTIVE) and
                        ConnectorUseBindings.effectiveTo.isNull()
                }
                .toList()
        activeBindings
            .filter { row ->
                keepBindings == null || row.bindingKey() !in keepBindings
            }
            .forEach { row ->
                ConnectorUseBindings.update({ ConnectorUseBindings.id eq row[ConnectorUseBindings.id] }) {
                    it[ConnectorUseBindings.status] = status
                    it[effectiveTo] = now
                    it[updatedAt] = now
                }
            }
    }

    private fun upsertBinding(
        organizationId: Int,
        userId: Int,
        installation: ConnectorInstallationRecord,
        input: ConnectorBindingInput,
        now: Instant,
    ) {
        val allowedExternalResourceTypes = allowedBindingExternalResourceTypes(installation.provider)
        if (input.externalResourceType !in allowedExternalResourceTypes) {
            throw badRequest(
                "Connector bindings must use ${allowedExternalResourceTypes.joinToString()} external resources"
            )
        }
        if (input.localResourceType != LOCAL_RESOURCE_PROJECT) {
            throw badRequest("Connector resources can only be mapped to Moneat projects")
        }
        val localResourceUuid =
            input.localResourceId.toUuidOrNull() ?: throw badRequest("localResourceId must be a project UUID")
        val projectId = projectIdResolver.resolve(input.localResourceId, organizationId)
            ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Moneat project was not found")
        val externalResource =
            ConnectorExternalResources
                .selectAll()
                .where {
                    (ConnectorExternalResources.installationId eq installation.id) and
                        (ConnectorExternalResources.externalResourceType eq input.externalResourceType) and
                        (ConnectorExternalResources.externalResourceId eq input.externalResourceId)
                }
                .firstOrNull()
                ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "External connector resource was not found")
        val duplicateActive =
            ConnectorUseBindings
                .selectAll()
                .where {
                    (ConnectorUseBindings.organizationId eq organizationId) and
                        (ConnectorUseBindings.externalResourceType eq input.externalResourceType) and
                        (ConnectorUseBindings.externalResourceId eq input.externalResourceId) and
                        (ConnectorUseBindings.status eq BINDING_ACTIVE) and
                        ConnectorUseBindings.effectiveTo.isNull() and
                        (ConnectorUseBindings.installationId neq installation.id)
                }
                .count() > 0
        if (duplicateActive) {
            throw badRequest("External connector resource is already mapped through another installation")
        }

        val activeBinding =
            ConnectorUseBindings
                .selectAll()
                .where {
                    (ConnectorUseBindings.installationId eq installation.id) and
                        (ConnectorUseBindings.externalResourceType eq input.externalResourceType) and
                        (ConnectorUseBindings.externalResourceId eq input.externalResourceId) and
                        (ConnectorUseBindings.status eq BINDING_ACTIVE) and
                        ConnectorUseBindings.effectiveTo.isNull()
                }
                .firstOrNull()
        val version = activeBinding?.get(ConnectorUseBindings.bindingVersion)?.plus(1) ?: 1
        if (activeBinding != null && activeBinding[ConnectorUseBindings.localResourceId] == localResourceUuid) {
            ConnectorUseBindings.update({ ConnectorUseBindings.id eq activeBinding[ConnectorUseBindings.id] }) {
                it[updatedBy] = userId
                it[updatedAt] = now
            }
            return
        }
        if (activeBinding != null) {
            ConnectorUseBindings.update({ ConnectorUseBindings.id eq activeBinding[ConnectorUseBindings.id] }) {
                it[status] = BINDING_REPLACED
                it[effectiveTo] = now
                it[updatedBy] = userId
                it[updatedAt] = now
            }
        }
        ConnectorUseBindings.insert {
            it[ConnectorUseBindings.organizationId] = organizationId
            it[ConnectorUseBindings.installationId] = installation.id
            it[externalProjectId] = externalResource[ConnectorExternalResources.externalProjectId]
            it[externalResourceType] = input.externalResourceType
            it[externalResourceId] = input.externalResourceId
            it[localResourceType] = input.localResourceType
            it[localResourceId] = localResourceUuid
            it[localResourceNumericId] = projectId
            it[status] = BINDING_ACTIVE
            it[effectiveFrom] = now
            it[bindingVersion] = version
            it[createdBy] = userId
            it[updatedBy] = userId
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun allowedBindingExternalResourceTypes(provider: String): Set<String> =
        when (provider) {
            RevenueCatClient.PROVIDER_ID -> setOf(RevenueCatClient.RESOURCE_TYPE_APP)
            GoogleAdsClient.PROVIDER_ID -> setOf(
                GoogleAdsClient.RESOURCE_TYPE_CUSTOMER,
                GoogleAdsClient.RESOURCE_TYPE_MANAGER,
            )
            else -> throw badRequest("Unsupported connector provider: $provider")
        }

    private fun webhookUrl(installationResourceId: Uuid): String =
        EnvConfig.get("BACKEND_URL", "https://api.moneat.io")
            .trimEnd('/') + "/api/connectors/revenuecat/$installationResourceId/webhook"

    private fun webhookDriftWarnings(
        observedIntegrations: List<ConnectorObservedWebhookIntegration>,
    ): List<String> {
        if (observedIntegrations.isEmpty()) return emptyList()
        val matching = observedIntegrations.filter { integration -> integration.matchesExpectedUrl }
        return buildList {
            if (matching.isEmpty()) {
                add("No inspected RevenueCat webhook integration points to this Moneat webhook URL.")
            }
            if (matching.size > 1) {
                add("More than one RevenueCat webhook integration points to this Moneat webhook URL.")
            }
            matching.filter { integration -> integration.enabled == false }.forEach { integration ->
                add("RevenueCat webhook ${integration.id} is disabled.")
            }
            matching.filter { integration -> integration.eventTypes.isNotEmpty() }.forEach { integration ->
                val missingEvents = RECOMMENDED_REVENUECAT_EVENTS.filterNot { it in integration.eventTypes }
                if (missingEvents.isNotEmpty()) {
                    add("RevenueCat webhook ${integration.id} is missing events: ${missingEvents.joinToString()}.")
                }
            }
        }
    }

    private fun parseRevenueCatWebhook(rawPayload: String): ParsedRevenueCatWebhook {
        val root =
            runCatching { json.parseToJsonElement(rawPayload).jsonObject }
                .getOrElse { throw badRequest("RevenueCat webhook body must be valid JSON") }
        val event = root["event"]?.jsonObjectOrNull() ?: root
        val eventId = event.string("id")
            ?: event.string("event_id")
            ?: throw badRequest("RevenueCat webhook event id is required")
        val eventType = event.string("type") ?: event.string("event_type") ?: "UNKNOWN"
        val appId = event.string("app_id") ?: event.string("appId")
        val timestampMs =
            event.long("event_timestamp_ms") ?: event.long("eventTimestampMs") ?: event.long("timestamp_ms")
        return ParsedRevenueCatWebhook(eventId, eventType, appId, event.string("environment"), timestampMs)
    }

    private fun parseBearerToken(authorizationHeader: String?): String {
        val header = authorizationHeader?.trim().orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) {
            throw ConnectorServiceException(HttpStatusCode.Unauthorized, "Missing RevenueCat webhook bearer token")
        }
        return header.substringAfter(' ').trim().takeIf { it.isNotBlank() }
            ?: throw ConnectorServiceException(HttpStatusCode.Unauthorized, "Missing RevenueCat webhook bearer token")
    }

    private fun updateInstallationWebhookAccepted(rawEventId: Long) {
        val raw =
            ConnectorInboundEventsRaw
                .selectAll()
                .where { ConnectorInboundEventsRaw.id eq rawEventId }
                .firstOrNull()
                ?: return
        ConnectorInstallations.update({ ConnectorInstallations.id eq raw[ConnectorInboundEventsRaw.installationId] }) {
            it[status] = STATUS_HEALTHY
            it[statusReason] = "RevenueCat webhook accepted"
            it[lastError] = null
            it[updatedAt] = Clock.System.now()
        }
    }

    private fun rawEventRecord(rawEventId: Long): ConnectorRawEventRecord {
        val raw =
            ConnectorInboundEventsRaw
                .selectAll()
                .where { ConnectorInboundEventsRaw.id eq rawEventId }
                .firstOrNull()
                ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Raw connector event not found")
        val receipt =
            ConnectorEventReceipts
                .selectAll()
                .where { ConnectorEventReceipts.rawEventId eq rawEventId }
                .firstOrNull()
                ?: throw IllegalStateException("Raw connector event has no receipt")
        val binding =
            raw[ConnectorInboundEventsRaw.externalResourceId]?.let { appId ->
                ConnectorUseBindings
                    .selectAll()
                    .where {
                        (ConnectorUseBindings.installationId eq raw[ConnectorInboundEventsRaw.installationId]) and
                            (ConnectorUseBindings.externalResourceType eq RevenueCatClient.RESOURCE_TYPE_APP) and
                            (ConnectorUseBindings.externalResourceId eq appId) and
                            (ConnectorUseBindings.status eq BINDING_ACTIVE) and
                            ConnectorUseBindings.effectiveTo.isNull()
                    }
                    .firstOrNull()
            }
        val installation =
            ConnectorInstallations
                .selectAll()
                .where { ConnectorInstallations.id eq raw[ConnectorInboundEventsRaw.installationId] }
                .first()
        return ConnectorRawEventRecord(
            rawEventId = raw[ConnectorInboundEventsRaw.id],
            rawResourceId = raw[ConnectorInboundEventsRaw.resourceId],
            organizationId = raw[ConnectorInboundEventsRaw.organizationId],
            installationId = raw[ConnectorInboundEventsRaw.installationId],
            installationResourceId = installation[ConnectorInstallations.resourceId],
            externalProjectId = raw[ConnectorInboundEventsRaw.externalProjectId],
            externalResourceId = raw[ConnectorInboundEventsRaw.externalResourceId],
            providerEventId = raw[ConnectorInboundEventsRaw.providerEventId],
            rawPayload = raw[ConnectorInboundEventsRaw.rawPayload],
            receivedAt = raw[ConnectorInboundEventsRaw.receivedAt],
            receiptId = receipt[ConnectorEventReceipts.id],
            receiptState = receipt[ConnectorEventReceipts.state],
            attemptCount = receipt[ConnectorEventReceipts.attemptCount],
            mappedProjectId = binding?.get(ConnectorUseBindings.localResourceNumericId),
        )
    }

    private fun normalizeRevenueCatFact(record: ConnectorRawEventRecord): AppSubscriptionEventFact {
        val root = json.parseToJsonElement(record.rawPayload).jsonObject
        val event = root["event"]?.jsonObjectOrNull() ?: root
        val eventTimestampMs =
            event.long("event_timestamp_ms")
                ?: event.long("eventTimestampMs")
                ?: event.long("timestamp_ms")
                ?: record.receivedAt.toEpochMilliseconds()
        val eventTimestamp = Instant.fromEpochMilliseconds(eventTimestampMs)
        val transferredFrom =
            event.stringArray("transferred_from") + event.stringArray("transferred_from_app_user_ids")
        val transferredTo =
            event.stringArray("transferred_to") + event.stringArray("transferred_to_app_user_ids")
        return AppSubscriptionEventFact(
            eventId = record.rawResourceId,
            organizationId = record.organizationId.toLong(),
            projectId = record.mappedProjectId,
            installationId = record.installationId,
            installationResourceId = record.installationResourceId,
            rawEventId = record.rawEventId,
            providerApiVersion = root.string("api_version") ?: root.string("apiVersion"),
            providerEventId = record.providerEventId,
            receivedAt = record.receivedAt,
            eventTimestamp = eventTimestamp,
            eventTimestampMs = eventTimestampMs,
            eventType = event.string("type") ?: event.string("event_type") ?: "UNKNOWN",
            eventCategory = event.string("event_category") ?: event.string("category"),
            revenueCatProjectId = record.externalProjectId,
            revenueCatAppId = event.string("app_id") ?: record.externalResourceId,
            store = event.string("store"),
            environment = event.string("environment"),
            appUserId = event.string("app_user_id"),
            originalAppUserId = event.string("original_app_user_id"),
            aliases = event.stringArray("aliases"),
            transferredFrom = transferredFrom,
            transferredTo = transferredTo,
            customerLookupKeys = event.stringArray("customer_lookup_keys"),
            currency = event.string("currency"),
            priceUsd = event.double("price"),
            priceInPurchasedCurrency = event.double("price_in_purchased_currency"),
            taxPercentage = event.double("tax_percentage"),
            commissionPercentage = event.double("commission_percentage"),
            proceedsEstimateUsd = event.double("proceeds_estimate"),
            countryCode = event.string("country_code"),
            isFamilyShare = event.boolean("is_family_share") == true,
            storeProductId = event.string("product_id") ?: event.string("store_product_id"),
            newStoreProductId = event.string("new_product_id") ?: event.string("new_store_product_id"),
            entitlementIds = event.stringArray("entitlement_ids") + event.stringArray("entitlements"),
            periodType = event.string("period_type"),
            purchasedAt = event.instantFromMillis("purchased_at_ms"),
            expirationAt = event.instantFromMillis("expiration_at_ms"),
            gracePeriodExpirationAt = event.instantFromMillis("grace_period_expiration_at_ms"),
            autoResumeAt = event.instantFromMillis("auto_resume_at_ms"),
            transactionId = event.string("transaction_id"),
            originalTransactionId = event.string("original_transaction_id"),
            cancelReason = event.string("cancel_reason") ?: event.string("cancellation_reason"),
            expirationReason = event.string("expiration_reason"),
            presentedOfferingId = event.string("presented_offering_id"),
            offerCode = event.string("offer_code"),
            isTrialConversion = event.boolean("is_trial_conversion") == true,
            renewalNumber = event.long("renewal_number"),
        )
    }

    private suspend fun insertSubscriptionFact(fact: AppSubscriptionEventFact) {
        val sql = """
            INSERT INTO `${ClickHouseClient.getDatabase()}`.app_subscription_events (
                event_id,
                organization_id,
                project_id,
                installation_id,
                installation_resource_id,
                raw_event_id,
                provider,
                provider_api_version,
                provider_event_id,
                received_at,
                event_timestamp,
                event_timestamp_ms,
                event_type,
                event_category,
                revenuecat_project_id,
                revenuecat_app_id,
                store,
                environment,
                app_user_id,
                original_app_user_id,
                aliases,
                transferred_from,
                transferred_to,
                customer_lookup_keys,
                currency,
                price_usd,
                price_in_purchased_currency,
                tax_percentage,
                commission_percentage,
                proceeds_estimate_usd,
                country_code,
                is_family_share,
                store_product_id,
                new_store_product_id,
                entitlement_ids,
                period_type,
                purchased_at,
                expiration_at,
                grace_period_expiration_at,
                auto_resume_at,
                transaction_id,
                original_transaction_id,
                cancel_reason,
                expiration_reason,
                presented_offering_id,
                offer_code,
                is_trial_conversion,
                renewal_number
            ) VALUES (
                ${fact.eventId.sqlUuid()},
                ${fact.organizationId},
                ${fact.projectId.sqlLong()},
                ${fact.installationId},
                ${fact.installationResourceId.sqlUuid()},
                ${fact.rawEventId},
                'revenuecat',
                ${fact.providerApiVersion.sqlString()},
                ${fact.providerEventId.sqlString()},
                ${fact.receivedAt.sqlDateTime64()},
                ${fact.eventTimestamp.sqlDateTime64()},
                ${fact.eventTimestampMs},
                ${fact.eventType.sqlString()},
                ${fact.eventCategory.sqlString()},
                ${fact.revenueCatProjectId.sqlString()},
                ${fact.revenueCatAppId.sqlString()},
                ${fact.store.sqlString()},
                ${fact.environment.sqlString()},
                ${fact.appUserId.sqlString()},
                ${fact.originalAppUserId.sqlString()},
                ${fact.aliases.sqlStringArray()},
                ${fact.transferredFrom.sqlStringArray()},
                ${fact.transferredTo.sqlStringArray()},
                ${fact.customerLookupKeys.sqlStringArray()},
                ${fact.currency.sqlString()},
                ${fact.priceUsd.sqlDouble()},
                ${fact.priceInPurchasedCurrency.sqlDouble()},
                ${fact.taxPercentage.sqlDouble()},
                ${fact.commissionPercentage.sqlDouble()},
                ${fact.proceedsEstimateUsd.sqlDouble()},
                ${fact.countryCode.sqlString()},
                ${fact.isFamilyShare.sqlBoolean()},
                ${fact.storeProductId.sqlString()},
                ${fact.newStoreProductId.sqlString()},
                ${fact.entitlementIds.sqlStringArray()},
                ${fact.periodType.sqlString()},
                ${fact.purchasedAt.sqlNullableDateTime64()},
                ${fact.expirationAt.sqlNullableDateTime64()},
                ${fact.gracePeriodExpirationAt.sqlNullableDateTime64()},
                ${fact.autoResumeAt.sqlNullableDateTime64()},
                ${fact.transactionId.sqlString()},
                ${fact.originalTransactionId.sqlString()},
                ${fact.cancelReason.sqlString()},
                ${fact.expirationReason.sqlString()},
                ${fact.presentedOfferingId.sqlString()},
                ${fact.offerCode.sqlString()},
                ${fact.isTrialConversion.sqlBoolean()},
                ${fact.renewalNumber.sqlLong()}
            )
        """.trimIndent()
        ClickHouseClient.executeWithFormat(sql, "")
    }

    private fun installationRowByResourceId(
        organizationId: Int,
        installationResourceId: String,
    ): ResultRow {
        val resourceId = installationResourceId.toUuidOrNull()
            ?: throw ConnectorServiceException(HttpStatusCode.BadRequest, "installationId must be a UUID")
        return ConnectorInstallations
            .selectAll()
            .where {
                (ConnectorInstallations.organizationId eq organizationId) and
                    (ConnectorInstallations.resourceId eq resourceId) and
                    ConnectorInstallations.deletedAt.isNull()
            }
            .firstOrNull()
            ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")
    }

    private fun publicInstallationRowByResourceId(installationResourceId: String): ResultRow {
        val resourceId = installationResourceId.toUuidOrNull()
            ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")
        return ConnectorInstallations
            .selectAll()
            .where {
                (ConnectorInstallations.resourceId eq resourceId) and
                    ConnectorInstallations.deletedAt.isNull()
            }
            .firstOrNull()
            ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")
    }

    private fun installationRowById(
        organizationId: Int,
        installationId: Int,
    ): ResultRow =
        ConnectorInstallations
            .selectAll()
            .where {
                (ConnectorInstallations.organizationId eq organizationId) and
                    (ConnectorInstallations.id eq installationId) and
                    ConnectorInstallations.deletedAt.isNull()
            }
            .first()

    private fun receiptRow(
        installationId: Int,
        providerEventId: String,
    ): ResultRow? =
        ConnectorEventReceipts
            .selectAll()
            .where {
                (ConnectorEventReceipts.installationId eq installationId) and
                    (ConnectorEventReceipts.providerEventId eq providerEventId)
            }
            .firstOrNull()

    private fun activeBindingCount(
        organizationId: Int,
        installationId: Int,
    ): Int =
        ConnectorUseBindings
            .selectAll()
            .where {
                (ConnectorUseBindings.organizationId eq organizationId) and
                    (ConnectorUseBindings.installationId eq installationId) and
                    (ConnectorUseBindings.status eq BINDING_ACTIVE) and
                    ConnectorUseBindings.effectiveTo.isNull()
            }
            .count()
            .toInt()

    private fun failedReceiptCount(installationId: Int): Int =
        ConnectorEventReceipts
            .selectAll()
            .where {
                (ConnectorEventReceipts.installationId eq installationId) and
                    (ConnectorEventReceipts.state eq RECEIPT_FAILED_RETRYABLE)
            }
            .count()
            .toInt()

    private fun unmappedEventCount(installationId: Int): Int {
        val mapped =
            ConnectorUseBindings
                .selectAll()
                .where {
                    (ConnectorUseBindings.installationId eq installationId) and
                        (ConnectorUseBindings.status eq BINDING_ACTIVE) and
                        ConnectorUseBindings.effectiveTo.isNull()
                }
                .map { row -> row[ConnectorUseBindings.externalResourceId] }
                .toSet()
        return ConnectorInboundEventsRaw
            .selectAll()
            .where { ConnectorInboundEventsRaw.installationId eq installationId }
            .mapNotNull { row -> row[ConnectorInboundEventsRaw.externalResourceId] }
            .count { appId -> appId !in mapped }
    }

    private fun environmentCounts(installationId: Int): RevenueCatEnvironmentCounts {
        val environments =
            ConnectorInboundEventsRaw
                .selectAll()
                .where { ConnectorInboundEventsRaw.installationId eq installationId }
                .mapNotNull { row -> row[ConnectorInboundEventsRaw.environment]?.lowercase() }
        return RevenueCatEnvironmentCounts(
            sandbox = environments.count { environment -> environment == "sandbox" }.toLong(),
            production = environments.count { environment -> environment == "production" }.toLong(),
        )
    }

    private fun lastRawEventAt(installationId: Int): Instant? =
        ConnectorInboundEventsRaw
            .selectAll()
            .where { ConnectorInboundEventsRaw.installationId eq installationId }
            .orderBy(ConnectorInboundEventsRaw.receivedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(ConnectorInboundEventsRaw.receivedAt)

    private fun lastAppliedAt(installationId: Int): Instant? =
        ConnectorEventReceipts
            .selectAll()
            .where {
                (ConnectorEventReceipts.installationId eq installationId) and
                    (ConnectorEventReceipts.state eq RECEIPT_APPLIED)
            }
            .orderBy(ConnectorEventReceipts.appliedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(ConnectorEventReceipts.appliedAt)

    private fun processingLagSeconds(
        lastRaw: Instant?,
        lastApplied: Instant?,
    ): Long? {
        if (lastRaw == null) return null
        if (lastApplied == null || lastApplied < lastRaw) {
            return max(0L, (Clock.System.now() - lastRaw).inWholeSeconds)
        }
        return null
    }

    private fun connectorHealth(
        record: ConnectorInstallationRecord,
        failedReceipts: Int,
        unmappedEvents: Int,
    ): String =
        when {
            !record.enabled -> STATUS_DEGRADED
            record.status == STATUS_DEGRADED -> STATUS_DEGRADED
            failedReceipts > 0 -> STATUS_DEGRADED
            unmappedEvents > 0 -> "needs_mapping"
            record.status == STATUS_HEALTHY -> STATUS_HEALTHY
            else -> "unknown"
        }

    private fun connectorStateMessage(
        record: ConnectorInstallationRecord,
        health: String,
        counts: ConnectorStateCounts,
    ): String =
        when {
            !record.enabled -> "RevenueCat connector is disabled"
            counts.failedReceipts > 0 -> "${counts.failedReceipts} RevenueCat events failed processing"
            counts.unmappedEvents > 0 -> "${counts.unmappedEvents} RevenueCat events are from unmapped apps"
            counts.mappedResources == 0 -> "Connect RevenueCat apps to Moneat projects"
            counts.lastRaw == null && health == STATUS_HEALTHY -> "Connected, awaiting RevenueCat webhook traffic"
            record.lastError != null -> record.lastError
            else -> "Connected"
        }

    private fun ensureProvider(
        record: ConnectorInstallationRecord,
        providerId: String,
    ) {
        if (record.provider != providerId) {
            throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")
        }
    }

    private fun <T> withRevenueCatErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (error: RevenueCatClientException) {
            throw ConnectorServiceException(revenueCatStatus(error), error.message.orEmpty(), error)
        }

    private fun <T> withGoogleAdsErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (error: GoogleAdsClientException) {
            throw ConnectorServiceException(googleAdsStatus(error), error.message.orEmpty(), error)
        }

    private fun revenueCatStatus(error: RevenueCatClientException): HttpStatusCode =
        when (error.code) {
            "revenuecat_unauthorized" -> HttpStatusCode.Unauthorized
            "revenuecat_forbidden" -> HttpStatusCode.Forbidden
            "revenuecat_not_found", "project_not_found" -> HttpStatusCode.NotFound
            "revenuecat_rate_limited" -> HttpStatusCode.TooManyRequests
            else -> if (error.retryable) HttpStatusCode.BadGateway else HttpStatusCode.BadRequest
        }

    private fun googleAdsStatus(error: GoogleAdsClientException): HttpStatusCode =
        when (error.code) {
            "google_ads_unauthorized" -> HttpStatusCode.Unauthorized
            "google_ads_forbidden" -> HttpStatusCode.Forbidden
            "google_ads_not_found", "google_ads_customer_not_found" -> HttpStatusCode.NotFound
            "google_ads_rate_limited" -> HttpStatusCode.TooManyRequests
            else -> if (error.retryable) HttpStatusCode.BadGateway else HttpStatusCode.BadRequest
        }

    private fun badRequest(message: String): ConnectorServiceException =
        ConnectorServiceException(HttpStatusCode.BadRequest, message)

    private fun ResultRow.toInstallationRecord(): ConnectorInstallationRecord =
        ConnectorInstallationRecord(
            id = this[ConnectorInstallations.id],
            resourceId = this[ConnectorInstallations.resourceId],
            organizationId = this[ConnectorInstallations.organizationId],
            provider = this[ConnectorInstallations.provider],
            externalProjectId = this[ConnectorInstallations.externalProjectId],
            enabled = this[ConnectorInstallations.enabled],
            status = this[ConnectorInstallations.status],
            webhookTokenHash = this[ConnectorInstallations.webhookTokenHash],
            webhookTokenPrefix = this[ConnectorInstallations.webhookTokenPrefix],
            apiSecretCiphertext = this[ConnectorInstallations.apiSecretCiphertext],
            lastTestedAt = this[ConnectorInstallations.lastTestedAt],
            lastError = this[ConnectorInstallations.lastError],
        )

    private fun ResultRow.toInstallationResponse(webhookToken: String? = null): ConnectorInstallationResponse =
        ConnectorInstallationResponse(
            id = this[ConnectorInstallations.resourceId].toString(),
            providerId = this[ConnectorInstallations.provider],
            name = this[ConnectorInstallations.name],
            credentialType = this[ConnectorInstallations.credentialType],
            authProfileId = this[ConnectorInstallations.authProfileId],
            externalProjectId = this[ConnectorInstallations.externalProjectId],
            externalProjectName = this[ConnectorInstallations.externalProjectName],
            status = this[ConnectorInstallations.status],
            statusReason = this[ConnectorInstallations.statusReason],
            enabled = this[ConnectorInstallations.enabled],
            apiSecretLastFour = this[ConnectorInstallations.apiSecretLastFour],
            webhookTokenPrefix = this[ConnectorInstallations.webhookTokenPrefix],
            webhookToken = webhookToken,
            lastTestedAt = this[ConnectorInstallations.lastTestedAt]?.toString(),
            lastTestResult = this[ConnectorInstallations.lastTestResult],
            lastSuccessfulProviderCallAt = this[ConnectorInstallations.lastSuccessfulProviderCallAt]?.toString(),
            lastError = this[ConnectorInstallations.lastError],
            createdAt = this[ConnectorInstallations.createdAt].toString(),
            updatedAt = this[ConnectorInstallations.updatedAt].toString(),
        )

    private fun ResultRow.toResourceResponse(): ConnectorExternalResourceResponse =
        ConnectorExternalResourceResponse(
            id = this[ConnectorExternalResources.resourceId].toString(),
            installationId = installationResourceId(this[ConnectorExternalResources.installationId]).toString(),
            externalProjectId = this[ConnectorExternalResources.externalProjectId],
            externalResourceType = this[ConnectorExternalResources.externalResourceType],
            externalResourceId = this[ConnectorExternalResources.externalResourceId],
            displayName = this[ConnectorExternalResources.displayName],
            providerMetadata = stringMapFromJson(this[ConnectorExternalResources.providerMetadata]),
            lastSeenAt = this[ConnectorExternalResources.lastSeenAt].toString(),
        )

    private fun ResultRow.toBindingResponse(): ConnectorBindingResponse =
        ConnectorBindingResponse(
            id = this[ConnectorUseBindings.resourceId].toString(),
            installationId = installationResourceId(this[ConnectorUseBindings.installationId]).toString(),
            externalProjectId = this[ConnectorUseBindings.externalProjectId],
            externalResourceType = this[ConnectorUseBindings.externalResourceType],
            externalResourceId = this[ConnectorUseBindings.externalResourceId],
            localResourceType = this[ConnectorUseBindings.localResourceType],
            localResourceId = this[ConnectorUseBindings.localResourceId].toString(),
            status = this[ConnectorUseBindings.status],
            bindingVersion = this[ConnectorUseBindings.bindingVersion],
            effectiveFrom = this[ConnectorUseBindings.effectiveFrom].toString(),
            effectiveTo = this[ConnectorUseBindings.effectiveTo]?.toString(),
        )

    private fun installationResourceId(installationId: Int): Uuid =
        ConnectorInstallations
            .selectAll()
            .where { ConnectorInstallations.id eq installationId }
            .first()[ConnectorInstallations.resourceId]

    private fun stringMapJson(map: Map<String, String>): String =
        json.encodeToString(JsonObject(map.mapValues { (_, value) -> JsonPrimitive(value) }))

    private fun stringMapFromJson(value: String): Map<String, String> =
        runCatching {
            json.parseToJsonElement(value).jsonObject.mapValues { (_, element) ->
                element.jsonPrimitive.contentOrNull.orEmpty()
            }
        }.getOrElse { emptyMap() }

    private fun ConnectorBindingInput.bindingKey(): BindingExternalKey =
        BindingExternalKey(externalResourceType, externalResourceId)

    private fun ResultRow.bindingKey(): BindingExternalKey =
        BindingExternalKey(
            externalResourceType = this[ConnectorUseBindings.externalResourceType],
            externalResourceId = this[ConnectorUseBindings.externalResourceId],
        )

    companion object {
        const val CONNECTOR_EVENT_QUEUE_KEY = "moneat:connectors:events:queue"
        const val CONNECTOR_EVENT_DLQ_KEY = "moneat:connectors:events:dlq"
        const val CONNECTOR_IMPORT_QUEUE_KEY = "moneat:connectors:imports:queue"
        const val CONNECTOR_IMPORT_DLQ_KEY = "moneat:connectors:imports:dlq"
        private const val API_SECRET_SUFFIX_CHARS = 4
        private const val WEBHOOK_TOKEN_PREFIX_CHARS = 12
        private const val LOCAL_RESOURCE_PROJECT = "project"
        private const val STATUS_HEALTHY = "healthy"
        private const val STATUS_DEGRADED = "degraded"
        private const val STATUS_AWAITING_TRAFFIC = "awaiting_traffic"
        private const val RECEIPT_RECEIVED = "received"
        private const val RECEIPT_QUEUED = "queued"
        private const val RECEIPT_PROCESSING = "processing"
        private const val RECEIPT_APPLIED = "applied"
        private const val RECEIPT_FAILED_RETRYABLE = "failed_retryable"
        private const val BINDING_ACTIVE = "active"
        private const val BINDING_REPLACED = "replaced"
        private const val BINDING_REMOVED = "removed"
        private val RETRYABLE_RECEIPT_STATES = setOf(RECEIPT_RECEIVED, RECEIPT_FAILED_RETRYABLE)
        private val RECOMMENDED_REVENUECAT_EVENTS =
            listOf(
                "INITIAL_PURCHASE",
                "NON_RENEWING_PURCHASE",
                "RENEWAL",
                "PRODUCT_CHANGE",
                "CANCELLATION",
                "UNCANCELLATION",
                "BILLING_ISSUE",
                "SUBSCRIPTION_PAUSED",
                "EXPIRATION",
                "TRANSFER",
                "REFUND",
                "TEMPORARY_ENTITLEMENT_GRANT",
            )
    }
}

private data class ConnectorInstallationRecord(
    val id: Int,
    val resourceId: Uuid,
    val organizationId: Int,
    val provider: String,
    val externalProjectId: String?,
    val enabled: Boolean,
    val status: String,
    val webhookTokenHash: String?,
    val webhookTokenPrefix: String?,
    val apiSecretCiphertext: String?,
    val lastTestedAt: Instant?,
    val lastError: String?,
)

private data class ParsedRevenueCatWebhook(
    val eventId: String,
    val eventType: String,
    val appId: String?,
    val environment: String?,
    val eventTimestampMs: Long?,
)

private data class WebhookAcceptTransaction(
    val rawEventId: Long,
    val duplicate: Boolean,
    val shouldEnqueue: Boolean,
)

private data class ConnectorRawEventRecord(
    val rawEventId: Long,
    val rawResourceId: Uuid,
    val organizationId: Int,
    val installationId: Int,
    val installationResourceId: Uuid,
    val externalProjectId: String?,
    val externalResourceId: String?,
    val providerEventId: String,
    val rawPayload: String,
    val receivedAt: Instant,
    val receiptId: Int,
    val receiptState: String,
    val attemptCount: Int,
    val mappedProjectId: Long?,
)

private data class ConnectorStateCounts(
    val mappedResources: Int,
    val unmappedEvents: Int,
    val failedReceipts: Int,
    val lastRaw: Instant?,
)

private data class RevenueCatEnvironmentCounts(
    val sandbox: Long,
    val production: Long,
)

private data class BindingExternalKey(
    val externalResourceType: String,
    val externalResourceId: String,
)

private data class AppSubscriptionEventFact(
    val eventId: Uuid,
    val organizationId: Long,
    val projectId: Long?,
    val installationId: Int,
    val installationResourceId: Uuid,
    val rawEventId: Long,
    val providerApiVersion: String?,
    val providerEventId: String,
    val receivedAt: Instant,
    val eventTimestamp: Instant,
    val eventTimestampMs: Long,
    val eventType: String,
    val eventCategory: String?,
    val revenueCatProjectId: String?,
    val revenueCatAppId: String?,
    val store: String?,
    val environment: String?,
    val appUserId: String?,
    val originalAppUserId: String?,
    val aliases: List<String>,
    val transferredFrom: List<String>,
    val transferredTo: List<String>,
    val customerLookupKeys: List<String>,
    val currency: String?,
    val priceUsd: Double?,
    val priceInPurchasedCurrency: Double?,
    val taxPercentage: Double?,
    val commissionPercentage: Double?,
    val proceedsEstimateUsd: Double?,
    val countryCode: String?,
    val isFamilyShare: Boolean,
    val storeProductId: String?,
    val newStoreProductId: String?,
    val entitlementIds: List<String>,
    val periodType: String?,
    val purchasedAt: Instant?,
    val expirationAt: Instant?,
    val gracePeriodExpirationAt: Instant?,
    val autoResumeAt: Instant?,
    val transactionId: String?,
    val originalTransactionId: String?,
    val cancelReason: String?,
    val expirationReason: String?,
    val presentedOfferingId: String?,
    val offerCode: String?,
    val isTrialConversion: Boolean,
    val renewalNumber: Long?,
)

private fun generateWebhookToken(): String {
    val bytes = ByteArray(CONNECTOR_RANDOM_TOKEN_BYTES)
    connectorTokenRandom.nextBytes(bytes)
    return "mrc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Hex(value: String): String =
    sha256Hex(value.toByteArray(Charsets.UTF_8))

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

private fun secureEquals(
    expected: String,
    actual: String,
): Boolean =
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

private fun JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.long(key: String): Long? =
    string(key)?.toLongOrNull()

private fun JsonObject.double(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull ?: string(key)?.toDoubleOrNull()

private fun JsonObject.boolean(key: String): Boolean? =
    string(key)?.toBooleanStrictOrNull()

private fun JsonObject.stringArray(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    return when (element) {
        is JsonArray -> element.jsonArray.mapNotNull { item -> item.jsonPrimitive.contentOrNull }
        else -> element.jsonPrimitive.contentOrNull?.let(::listOf).orEmpty()
    }
}

private fun JsonObject.instantFromMillis(key: String): Instant? =
    long(key)?.let { millis -> Instant.fromEpochMilliseconds(millis) }

private fun Uuid.sqlUuid(): String =
    "'$this'"

private fun String?.sqlString(): String =
    this?.let { value -> "'${escapeSql(value)}'" } ?: "NULL"

private fun Long?.sqlLong(): String =
    this?.toString() ?: "NULL"

private fun Double?.sqlDouble(): String =
    this?.takeIf { value -> value.isFinite() }?.toString() ?: "NULL"

private fun Boolean.sqlBoolean(): Int =
    if (this) 1 else 0

private fun List<String>.sqlStringArray(): String =
    if (isEmpty()) {
        "[]"
    } else {
        "[" + joinToString(",") { value -> "'${escapeSql(value)}'" } + "]"
    }

// DateTime64(3) columns: pass epoch millis via fromUnixTimestamp64Milli rather than a
// string. Instant.toString() emits ISO-8601 with a `Z`, which ClickHouse's
// toDateTime64(string, 3) does not reliably parse.
private fun Instant.sqlDateTime64(): String =
    "fromUnixTimestamp64Milli(${toEpochMilliseconds()}, 'UTC')"

private fun Instant?.sqlNullableDateTime64(): String =
    this?.sqlDateTime64() ?: "NULL"
