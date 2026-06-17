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
import com.moneat.shared.services.toUuidOrNull
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val AD_SPEND_INSERT_BATCH_SIZE = 500
private const val DEFAULT_GOOGLE_ADS_IMPORT_DAYS = 30
private const val MAX_GOOGLE_ADS_IMPORT_DAYS = 92L
private const val GOOGLE_ADS_DEFAULT_API_VERSION = "v24"
private const val GOOGLE_ADS_SPEND_IMPORT_TYPE = "ad_spend"

const val CONNECTOR_IMPORT_STATUS_QUEUED: String = "queued"
const val CONNECTOR_IMPORT_STATUS_RUNNING: String = "running"
const val CONNECTOR_IMPORT_STATUS_SUCCEEDED: String = "succeeded"
const val CONNECTOR_IMPORT_STATUS_FAILED_RETRYABLE: String = "failed_retryable"

class ConnectorImportService(
    private val googleAdsClient: GoogleAdsProviderClient = GoogleAdsClient(),
    private val secretCipherFactory: () -> ConnectorSecretCipher = {
        PurposeConnectorSecretCipher(PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.DATA_IMPORT))
    },
    private val enqueueConnectorImport: (String) -> Unit = { payload ->
        IngestionQueueClient.enqueue(
            IngestionPipeline.CONNECTOR_IMPORTS,
            ConnectorService.CONNECTOR_IMPORT_QUEUE_KEY,
            payload,
        )
    },
    private val insertAdSpendFacts: suspend (List<AppAdSpendFact>) -> Unit = { facts ->
        insertAdSpendFactsIntoClickHouse(facts)
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun listImportRuns(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorImportRunsResponse =
        transaction {
            val installation = installationRowByResourceId(organizationId, installationResourceId)
            ConnectorImportRunsResponse(importRunsForInstallation(installation[ConnectorInstallations.id]))
        }

    fun enqueueSync(
        organizationId: Int,
        userId: Int,
        installationResourceId: String,
        request: ConnectorSyncRequest,
    ): ConnectorImportRunResponse {
        val dates = googleAdsImportDateRange(request)
        val runId =
            transaction {
                val installation = installationRecordByResourceId(organizationId, installationResourceId)
                ensureProvider(installation, GoogleAdsClient.PROVIDER_ID)
                ensureGoogleAdsExternalCustomer(installation)
                val now = Clock.System.now()
                ConnectorImportRuns.insert {
                    it[ConnectorImportRuns.organizationId] = organizationId
                    it[ConnectorImportRuns.installationId] = installation.id
                    it[provider] = GoogleAdsClient.PROVIDER_ID
                    it[importType] = GOOGLE_ADS_SPEND_IMPORT_TYPE
                    it[externalProjectId] = installation.externalProjectId
                    it[externalResourceId] = installation.externalProjectId
                    it[dateStart] = dates.start
                    it[dateEnd] = dates.end
                    it[status] = CONNECTOR_IMPORT_STATUS_QUEUED
                    it[rowsImported] = 0
                    it[requestedBy] = userId
                    it[queuedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }[ConnectorImportRuns.id]
            }
        try {
            enqueueConnectorImport(runId.toString())
        } catch (error: Throwable) {
            transaction {
                ConnectorImportRuns.update({ ConnectorImportRuns.id eq runId }) {
                    it[status] = CONNECTOR_IMPORT_STATUS_FAILED_RETRYABLE
                    it[lastErrorCode] = "enqueue_failed"
                    it[lastErrorMessage] = error.message
                    it[updatedAt] = Clock.System.now()
                }
            }
            throw ConnectorServiceException(
                HttpStatusCode.ServiceUnavailable,
                "Google Ads sync was created but could not be queued",
                error,
            )
        }
        return transaction { importRunRowById(runId).toImportRunResponse() }
    }

    suspend fun processImportRun(importRunId: Long) {
        val run = transaction { importRunRecord(importRunId) }
        if (run.status == CONNECTOR_IMPORT_STATUS_SUCCEEDED) return
        if (run.provider != GoogleAdsClient.PROVIDER_ID || run.importType != GOOGLE_ADS_SPEND_IMPORT_TYPE) {
            throw ConnectorServiceException(HttpStatusCode.BadRequest, "Unsupported connector import run")
        }
        val startedAt = Clock.System.now()
        transaction {
            ConnectorImportRuns.update({ ConnectorImportRuns.id eq run.id }) {
                it[status] = CONNECTOR_IMPORT_STATUS_RUNNING
                it[ConnectorImportRuns.startedAt] = startedAt
                it[attemptCount] = run.attemptCount + 1
                it[lastErrorCode] = null
                it[lastErrorMessage] = null
                it[updatedAt] = startedAt
            }
        }
        try {
            val facts = importGoogleAdsSpend(run, startedAt)
            insertAdSpendFacts(facts)
            markRunSucceeded(run, facts.size)
        } catch (error: Throwable) {
            markRunFailed(run, error)
            throw error
        }
    }

    fun latestImportRun(installationId: Int): ConnectorImportStateRecord? =
        ConnectorImportRuns
            .selectAll()
            .where { ConnectorImportRuns.installationId eq installationId }
            .orderBy(ConnectorImportRuns.createdAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                ConnectorImportStateRecord(
                    status = row[ConnectorImportRuns.status],
                    rowsImported = row[ConnectorImportRuns.rowsImported],
                    startedAt = row[ConnectorImportRuns.startedAt],
                    finishedAt = row[ConnectorImportRuns.finishedAt],
                    lastErrorMessage = row[ConnectorImportRuns.lastErrorMessage],
                )
            }

    fun importLagSeconds(lastImport: ConnectorImportStateRecord?): Long? {
        if (lastImport?.status != CONNECTOR_IMPORT_STATUS_RUNNING) return null
        val startedAt = lastImport.startedAt ?: return null
        return max(0L, (Clock.System.now() - startedAt).inWholeSeconds)
    }

    fun googleAdsState(organizationId: Int): Pair<ConnectorConnectionSummary?, ConnectorProviderStateDetail?> =
        transaction {
            val installation =
                ConnectorInstallations
                    .selectAll()
                    .where {
                        (ConnectorInstallations.organizationId eq organizationId) and
                            (ConnectorInstallations.provider eq GoogleAdsClient.PROVIDER_ID) and
                            ConnectorInstallations.deletedAt.isNull()
                    }
                    .orderBy(ConnectorInstallations.createdAt, SortOrder.DESC)
                    .firstOrNull()
                    ?: return@transaction null to null
            val record = installation.toImportInstallationStateRecord()
            val resourceCount = externalResourceCount(record.id)
            val mappedResources = activeBindingCount(organizationId, record.id)
            val lastImport = latestImportRun(record.id)
            val health = googleAdsHealth(record, lastImport)
            val message = googleAdsStateMessage(record, resourceCount, lastImport)
            val detail = ConnectorProviderStateDetail(
                installationId = record.resourceId.toString(),
                status = record.status,
                health = health,
                message = message,
                mappedResources = mappedResources,
                unmappedEvents = 0,
                failedReceipts = 0,
                sandboxEvents = 0,
                productionEvents = 0,
                lastAcceptedWebhookAt = null,
                lastAppliedAt = null,
                processingLagSeconds = null,
                lastImportStatus = lastImport?.status,
                lastImportFinishedAt = lastImport?.finishedAt?.toString(),
                lastImportRows = lastImport?.rowsImported,
                importLagSeconds = importLagSeconds(lastImport),
            )
            val summary = ConnectorConnectionSummary(
                providerId = GoogleAdsClient.PROVIDER_ID,
                connected = true,
                health = health,
                detail = message,
                lastCheckedAt = record.lastTestedAt?.toString(),
            )
            summary to detail
        }

    private fun googleAdsHealth(
        record: ConnectorInstallationImportStateRecord,
        lastImport: ConnectorImportStateRecord?,
    ): String =
        when {
            !record.enabled -> CONNECTOR_HEALTH_DEGRADED
            record.status == CONNECTOR_HEALTH_DEGRADED -> CONNECTOR_HEALTH_DEGRADED
            lastImport?.status == CONNECTOR_IMPORT_STATUS_FAILED_RETRYABLE -> CONNECTOR_HEALTH_DEGRADED
            lastImport?.status == CONNECTOR_IMPORT_STATUS_RUNNING -> CONNECTOR_HEALTH_SYNCING
            record.status == CONNECTOR_HEALTH_HEALTHY -> CONNECTOR_HEALTH_HEALTHY
            else -> CONNECTOR_HEALTH_UNKNOWN
        }

    private fun googleAdsStateMessage(
        record: ConnectorInstallationImportStateRecord,
        resourceCount: Int,
        lastImport: ConnectorImportStateRecord?,
    ): String =
        when {
            !record.enabled -> "Google Ads connector is disabled"
            lastImport?.status == CONNECTOR_IMPORT_STATUS_RUNNING -> "Google Ads spend sync is running"
            lastImport?.status == CONNECTOR_IMPORT_STATUS_QUEUED -> "Google Ads spend sync is queued"
            lastImport?.status == CONNECTOR_IMPORT_STATUS_FAILED_RETRYABLE -> {
                lastImport.lastErrorMessage ?: "Google Ads spend sync failed"
            }
            record.lastError != null -> record.lastError
            record.status == CONNECTOR_HEALTH_DEGRADED -> "Google Ads API validation failed"
            lastImport?.status == CONNECTOR_IMPORT_STATUS_SUCCEEDED -> {
                "Imported ${lastImport.rowsImported} Google Ads spend rows"
            }
            resourceCount == 0 -> "Connected, no Google Ads accounts discovered yet"
            resourceCount == 1 -> "Connected to 1 Google Ads account"
            else -> "Connected to $resourceCount Google Ads accounts"
        }

    private fun markRunSucceeded(
        run: ConnectorImportRunRecord,
        rowCount: Int,
    ) {
        val finishedAt = Clock.System.now()
        transaction {
            ConnectorImportRuns.update({ ConnectorImportRuns.id eq run.id }) {
                it[status] = CONNECTOR_IMPORT_STATUS_SUCCEEDED
                it[rowsImported] = rowCount
                it[ConnectorImportRuns.finishedAt] = finishedAt
                it[lastErrorCode] = null
                it[lastErrorMessage] = null
                it[updatedAt] = finishedAt
            }
            ConnectorInstallations.update({ ConnectorInstallations.id eq run.installationId }) {
                it[status] = "healthy"
                it[statusReason] = "Google Ads spend imported"
                it[lastSuccessfulProviderCallAt] = finishedAt
                it[lastError] = null
                it[updatedAt] = finishedAt
            }
        }
    }

    private fun markRunFailed(
        run: ConnectorImportRunRecord,
        error: Throwable,
    ) {
        val failedAt = Clock.System.now()
        transaction {
            ConnectorImportRuns.update({ ConnectorImportRuns.id eq run.id }) {
                it[status] = CONNECTOR_IMPORT_STATUS_FAILED_RETRYABLE
                it[finishedAt] = failedAt
                it[lastErrorCode] = error::class.simpleName ?: "import_failed"
                it[lastErrorMessage] = error.message
                it[updatedAt] = failedAt
            }
            ConnectorInstallations.update({ ConnectorInstallations.id eq run.installationId }) {
                it[status] = "degraded"
                it[statusReason] = "Google Ads spend import failed"
                it[lastError] = error.message
                it[updatedAt] = failedAt
            }
        }
    }

    private fun importGoogleAdsSpend(
        run: ConnectorImportRunRecord,
        pulledAt: Instant,
    ): List<AppAdSpendFact> {
        val installation = transaction { installationRecordById(run.organizationId, run.installationId) }
        ensureProvider(installation, GoogleAdsClient.PROVIDER_ID)
        val customerId = installation.externalProjectId
            ?: throw badRequest("Connector installation has no Google Ads customer")
        val credential = parseGoogleAdsCredential(decryptApiSecret(installation))
        val rows = withGoogleAdsErrorMapping {
            googleAdsClient.fetchSpendReport(
                credential = credential,
                customerId = customerId,
                loginCustomerId = credential.loginCustomerId,
                startDate = run.dateStart,
                endDate = run.dateEnd,
            )
        }
        val mappedProjectId = transaction { activeGoogleAdsProjectMapping(installation.id, customerId) }
        return rows.map { row -> row.toFact(run, installation, mappedProjectId, pulledAt) }
    }

    private fun GoogleAdsSpendReportRow.toFact(
        run: ConnectorImportRunRecord,
        installation: ConnectorInstallationImportRecord,
        mappedProjectId: Long?,
        pulledAt: Instant,
    ): AppAdSpendFact {
        val rowHash = googleAdsSpendRowHash(run, this)
        val providerApiVersion = EnvConfig.get("GOOGLE_ADS_API_VERSION", GOOGLE_ADS_DEFAULT_API_VERSION)
        return AppAdSpendFact(
            factId = deterministicUuid(rowHash),
            organizationId = run.organizationId.toLong(),
            projectId = mappedProjectId,
            installationId = installation.id,
            installationResourceId = installation.resourceId,
            importRunId = run.id,
            importRunResourceId = run.resourceId,
            providerApiVersion = providerApiVersion,
            googleAdsCustomerId = customerId,
            googleAdsLoginCustomerId = loginCustomerId,
            reportDate = reportDate,
            device = device,
            geoTargetCountry = geoTargetCountry,
            campaignId = campaignId,
            campaignName = campaignName,
            campaignStatus = campaignStatus,
            campaignType = campaignType,
            campaignSubType = campaignSubType,
            biddingStrategyType = biddingStrategyType,
            adGroupId = adGroupId,
            adGroupName = adGroupName,
            adGroupStatus = adGroupStatus,
            currencyCode = currencyCode,
            timeZone = timeZone,
            impressions = impressions,
            clicks = clicks,
            costMicros = costMicros,
            conversions = conversions,
            conversionsValue = conversionsValue,
            allConversions = allConversions,
            allConversionsValue = allConversionsValue,
            rowIdentityHash = rowHash,
            pulledAt = pulledAt,
        )
    }

    private fun ensureGoogleAdsExternalCustomer(installation: ConnectorInstallationImportRecord) {
        val customerId = installation.externalProjectId
            ?: throw badRequest("Connector installation has no Google Ads customer")
        val exists =
            ConnectorExternalResources
                .selectAll()
                .where {
                    (ConnectorExternalResources.installationId eq installation.id) and
                        (ConnectorExternalResources.externalResourceId eq customerId)
                }
                .count() > 0
        if (!exists) {
            throw ConnectorServiceException(HttpStatusCode.NotFound, "External Google Ads customer was not found")
        }
    }

    private fun googleAdsImportDateRange(request: ConnectorSyncRequest): GoogleAdsImportDateRange {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val end = request.endDate?.let { value -> parseImportDate(value, "endDate") } ?: today
        val start = request.startDate?.let { value ->
            parseImportDate(value, "startDate")
        } ?: end.minus(DatePeriod(days = DEFAULT_GOOGLE_ADS_IMPORT_DAYS - 1))
        if (start > end) {
            throw badRequest("startDate must be on or before endDate")
        }
        if (inclusiveDayCount(start, end) > MAX_GOOGLE_ADS_IMPORT_DAYS) {
            throw badRequest("Google Ads sync range cannot exceed $MAX_GOOGLE_ADS_IMPORT_DAYS days")
        }
        return GoogleAdsImportDateRange(start, end)
    }

    private fun parseImportDate(
        value: String,
        fieldName: String,
    ): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: IllegalArgumentException) {
            throw badRequest("$fieldName must be an ISO date")
        }

    private fun inclusiveDayCount(
        start: LocalDate,
        end: LocalDate,
    ): Long {
        val javaStart = java.time.LocalDate.parse(start.toString())
        val javaEnd = java.time.LocalDate.parse(end.toString())
        return ChronoUnit.DAYS.between(javaStart, javaEnd) + 1
    }

    private fun parseGoogleAdsCredential(secret: String): GoogleAdsOAuthCredential {
        val normalized = secret
            .trim()
            .takeIf { it.isNotBlank() }
            ?: throw badRequest("Google Ads refresh token is required")
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

    private fun decryptApiSecret(record: ConnectorInstallationImportRecord): String {
        val ciphertext = record.apiSecretCiphertext ?: throw badRequest("Connector installation has no API credential")
        return secretCipherFactory().decrypt(ciphertext, record.organizationId)
    }

    private fun importRunsForInstallation(installationId: Int): List<ConnectorImportRunResponse> =
        ConnectorImportRuns
            .selectAll()
            .where { ConnectorImportRuns.installationId eq installationId }
            .orderBy(ConnectorImportRuns.createdAt, SortOrder.DESC)
            .limit(IMPORT_RUN_LIST_LIMIT)
            .map { row -> row.toImportRunResponse() }

    private fun externalResourceCount(installationId: Int): Int =
        ConnectorExternalResources
            .selectAll()
            .where { ConnectorExternalResources.installationId eq installationId }
            .count()
            .toInt()

    private fun activeBindingCount(
        organizationId: Int,
        installationId: Int,
    ): Int =
        ConnectorUseBindings
            .selectAll()
            .where {
                (ConnectorUseBindings.organizationId eq organizationId) and
                    (ConnectorUseBindings.installationId eq installationId) and
                    (ConnectorUseBindings.status eq LOCAL_RESOURCE_ACTIVE) and
                    ConnectorUseBindings.effectiveTo.isNull()
            }
            .count()
            .toInt()

    private fun activeGoogleAdsProjectMapping(
        installationId: Int,
        customerId: String,
    ): Long? =
        activeProjectMapping(installationId, GoogleAdsClient.RESOURCE_TYPE_CUSTOMER, customerId)
            ?: activeProjectMapping(installationId, GoogleAdsClient.RESOURCE_TYPE_MANAGER, customerId)

    private fun activeProjectMapping(
        installationId: Int,
        externalResourceType: String,
        externalResourceId: String,
    ): Long? =
        ConnectorUseBindings
            .selectAll()
            .where {
                (ConnectorUseBindings.installationId eq installationId) and
                    (ConnectorUseBindings.externalResourceType eq externalResourceType) and
                    (ConnectorUseBindings.externalResourceId eq externalResourceId) and
                    (ConnectorUseBindings.status eq LOCAL_RESOURCE_ACTIVE) and
                    ConnectorUseBindings.effectiveTo.isNull()
            }
            .firstOrNull()
            ?.get(ConnectorUseBindings.localResourceNumericId)

    private fun importRunRowById(importRunId: Long): ResultRow =
        ConnectorImportRuns
            .selectAll()
            .where { ConnectorImportRuns.id eq importRunId }
            .firstOrNull()
            ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector import run not found")

    private fun importRunRecord(importRunId: Long): ConnectorImportRunRecord {
        val run = importRunRowById(importRunId)
        val installation =
            ConnectorInstallations
                .selectAll()
                .where { ConnectorInstallations.id eq run[ConnectorImportRuns.installationId] }
                .firstOrNull()
                ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")
        return ConnectorImportRunRecord(
            id = run[ConnectorImportRuns.id],
            resourceId = run[ConnectorImportRuns.resourceId],
            organizationId = run[ConnectorImportRuns.organizationId],
            installationId = run[ConnectorImportRuns.installationId],
            installationResourceId = installation[ConnectorInstallations.resourceId],
            provider = run[ConnectorImportRuns.provider],
            importType = run[ConnectorImportRuns.importType],
            externalProjectId = run[ConnectorImportRuns.externalProjectId],
            externalResourceId = run[ConnectorImportRuns.externalResourceId],
            dateStart = run[ConnectorImportRuns.dateStart],
            dateEnd = run[ConnectorImportRuns.dateEnd],
            status = run[ConnectorImportRuns.status],
            attemptCount = run[ConnectorImportRuns.attemptCount],
        )
    }

    private fun installationRecordByResourceId(
        organizationId: Int,
        installationResourceId: String,
    ): ConnectorInstallationImportRecord =
        installationRowByResourceId(organizationId, installationResourceId).toImportInstallationRecord()

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

    private fun installationRecordById(
        organizationId: Int,
        installationId: Int,
    ): ConnectorInstallationImportRecord =
        ConnectorInstallations
            .selectAll()
            .where {
                (ConnectorInstallations.organizationId eq organizationId) and
                    (ConnectorInstallations.id eq installationId) and
                    ConnectorInstallations.deletedAt.isNull()
            }
            .firstOrNull()
            ?.toImportInstallationRecord()
            ?: throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")

    private fun ResultRow.toImportInstallationRecord(): ConnectorInstallationImportRecord =
        ConnectorInstallationImportRecord(
            id = this[ConnectorInstallations.id],
            resourceId = this[ConnectorInstallations.resourceId],
            organizationId = this[ConnectorInstallations.organizationId],
            provider = this[ConnectorInstallations.provider],
            externalProjectId = this[ConnectorInstallations.externalProjectId],
            apiSecretCiphertext = this[ConnectorInstallations.apiSecretCiphertext],
        )

    private fun ResultRow.toImportInstallationStateRecord(): ConnectorInstallationImportStateRecord =
        ConnectorInstallationImportStateRecord(
            id = this[ConnectorInstallations.id],
            resourceId = this[ConnectorInstallations.resourceId],
            enabled = this[ConnectorInstallations.enabled],
            status = this[ConnectorInstallations.status],
            lastTestedAt = this[ConnectorInstallations.lastTestedAt],
            lastError = this[ConnectorInstallations.lastError],
        )

    private fun ResultRow.toImportRunResponse(): ConnectorImportRunResponse =
        ConnectorImportRunResponse(
            id = this[ConnectorImportRuns.resourceId].toString(),
            installationId = installationResourceId(this[ConnectorImportRuns.installationId]).toString(),
            providerId = this[ConnectorImportRuns.provider],
            importType = this[ConnectorImportRuns.importType],
            externalProjectId = this[ConnectorImportRuns.externalProjectId],
            externalResourceId = this[ConnectorImportRuns.externalResourceId],
            startDate = this[ConnectorImportRuns.dateStart].toString(),
            endDate = this[ConnectorImportRuns.dateEnd].toString(),
            status = this[ConnectorImportRuns.status],
            rowsImported = this[ConnectorImportRuns.rowsImported],
            queuedAt = this[ConnectorImportRuns.queuedAt].toString(),
            startedAt = this[ConnectorImportRuns.startedAt]?.toString(),
            finishedAt = this[ConnectorImportRuns.finishedAt]?.toString(),
            attemptCount = this[ConnectorImportRuns.attemptCount],
            lastErrorCode = this[ConnectorImportRuns.lastErrorCode],
            lastErrorMessage = this[ConnectorImportRuns.lastErrorMessage],
        )

    private fun installationResourceId(installationId: Int): Uuid =
        ConnectorInstallations
            .selectAll()
            .where { ConnectorInstallations.id eq installationId }
            .first()[ConnectorInstallations.resourceId]

    private fun ensureProvider(
        record: ConnectorInstallationImportRecord,
        providerId: String,
    ) {
        if (record.provider != providerId) {
            throw ConnectorServiceException(HttpStatusCode.NotFound, "Connector installation not found")
        }
    }

    private fun <T> withGoogleAdsErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (error: GoogleAdsClientException) {
            throw ConnectorServiceException(googleAdsStatus(error), error.message.orEmpty(), error)
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

    private companion object {
        const val IMPORT_RUN_LIST_LIMIT = 25
        const val LOCAL_RESOURCE_ACTIVE = "active"
        const val CONNECTOR_HEALTH_HEALTHY = "healthy"
        const val CONNECTOR_HEALTH_DEGRADED = "degraded"
        const val CONNECTOR_HEALTH_SYNCING = "syncing"
        const val CONNECTOR_HEALTH_UNKNOWN = "unknown"
    }
}

data class ConnectorImportStateRecord(
    val status: String,
    val rowsImported: Int,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val lastErrorMessage: String?,
)

data class AppAdSpendFact(
    val factId: Uuid,
    val organizationId: Long,
    val projectId: Long?,
    val installationId: Int,
    val installationResourceId: Uuid,
    val importRunId: Long,
    val importRunResourceId: Uuid,
    val providerApiVersion: String?,
    val googleAdsCustomerId: String,
    val googleAdsLoginCustomerId: String?,
    val reportDate: LocalDate,
    val device: String?,
    val geoTargetCountry: String?,
    val campaignId: String?,
    val campaignName: String?,
    val campaignStatus: String?,
    val campaignType: String?,
    val campaignSubType: String?,
    val biddingStrategyType: String?,
    val adGroupId: String?,
    val adGroupName: String?,
    val adGroupStatus: String?,
    val currencyCode: String?,
    val timeZone: String?,
    val impressions: Long,
    val clicks: Long,
    val costMicros: Long,
    val conversions: Double,
    val conversionsValue: Double,
    val allConversions: Double,
    val allConversionsValue: Double,
    val rowIdentityHash: String,
    val pulledAt: Instant,
)

private data class GoogleAdsImportDateRange(
    val start: LocalDate,
    val end: LocalDate,
)

private data class ConnectorInstallationImportRecord(
    val id: Int,
    val resourceId: Uuid,
    val organizationId: Int,
    val provider: String,
    val externalProjectId: String?,
    val apiSecretCiphertext: String?,
)

private data class ConnectorInstallationImportStateRecord(
    val id: Int,
    val resourceId: Uuid,
    val enabled: Boolean,
    val status: String,
    val lastTestedAt: Instant?,
    val lastError: String?,
)

private data class ConnectorImportRunRecord(
    val id: Long,
    val resourceId: Uuid,
    val organizationId: Int,
    val installationId: Int,
    val installationResourceId: Uuid,
    val provider: String,
    val importType: String,
    val externalProjectId: String?,
    val externalResourceId: String?,
    val dateStart: LocalDate,
    val dateEnd: LocalDate,
    val status: String,
    val attemptCount: Int,
)

private suspend fun insertAdSpendFactsIntoClickHouse(facts: List<AppAdSpendFact>) {
    if (facts.isEmpty()) return
    facts.chunked(AD_SPEND_INSERT_BATCH_SIZE).forEach { batch ->
        val values = batch.joinToString(",\n") { fact -> fact.sqlValues() }
        val sql = """
            INSERT INTO `${ClickHouseClient.getDatabase()}`.app_ad_spend_facts (
                fact_id,
                organization_id,
                project_id,
                installation_id,
                installation_resource_id,
                import_run_id,
                import_run_resource_id,
                provider,
                provider_api_version,
                google_ads_customer_id,
                google_ads_login_customer_id,
                report_date,
                device,
                geo_target_country,
                campaign_id,
                campaign_name,
                campaign_status,
                campaign_type,
                campaign_sub_type,
                bidding_strategy_type,
                ad_group_id,
                ad_group_name,
                ad_group_status,
                currency_code,
                time_zone,
                impressions,
                clicks,
                cost_micros,
                conversions,
                conversions_value,
                all_conversions,
                all_conversions_value,
                row_identity_hash,
                pulled_at
            ) VALUES
            $values
        """.trimIndent()
        ClickHouseClient.executeWithFormat(sql, "")
    }
}

private fun AppAdSpendFact.sqlValues(): String =
    """
    (
        ${factId.sqlUuid()},
        $organizationId,
        ${projectId.sqlLong()},
        $installationId,
        ${installationResourceId.sqlUuid()},
        $importRunId,
        ${importRunResourceId.sqlUuid()},
        'google_ads',
        ${providerApiVersion.sqlString()},
        ${googleAdsCustomerId.sqlString()},
        ${googleAdsLoginCustomerId.sqlString()},
        ${reportDate.sqlDate()},
        ${device.sqlString()},
        ${geoTargetCountry.sqlString()},
        ${campaignId.sqlString()},
        ${campaignName.sqlString()},
        ${campaignStatus.sqlString()},
        ${campaignType.sqlString()},
        ${campaignSubType.sqlString()},
        ${biddingStrategyType.sqlString()},
        ${adGroupId.sqlString()},
        ${adGroupName.sqlString()},
        ${adGroupStatus.sqlString()},
        ${currencyCode.sqlString()},
        ${timeZone.sqlString()},
        ${impressions.coerceAtLeast(0L)},
        ${clicks.coerceAtLeast(0L)},
        $costMicros,
        ${conversions.sqlDouble()},
        ${conversionsValue.sqlDouble()},
        ${allConversions.sqlDouble()},
        ${allConversionsValue.sqlDouble()},
        ${rowIdentityHash.sqlString()},
        ${pulledAt.sqlDateTime64()}
    )
    """.trimIndent()

private fun googleAdsSpendRowHash(
    run: ConnectorImportRunRecord,
    row: GoogleAdsSpendReportRow,
): String =
    sha256Hex(
        listOf(
            run.organizationId.toString(),
            run.installationId.toString(),
            row.customerId,
            row.reportDate.toString(),
            row.campaignId.orEmpty(),
            row.adGroupId.orEmpty(),
            row.geoTargetCountry.orEmpty(),
            row.device.orEmpty(),
        ).joinToString("|")
    )

private fun deterministicUuid(seed: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString())

private fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun Uuid.sqlUuid(): String =
    "'$this'"

private fun LocalDate.sqlDate(): String =
    "toDate('$this')"

private fun String?.sqlString(): String =
    this?.let { value -> "'${escapeSql(value)}'" } ?: "NULL"

private fun Long?.sqlLong(): String =
    this?.toString() ?: "NULL"

private fun Double?.sqlDouble(): String =
    this?.takeIf { value -> value.isFinite() }?.toString() ?: "NULL"

private fun Instant.sqlDateTime64(): String =
    "fromUnixTimestamp64Milli(${toEpochMilliseconds()}, 'UTC')"
