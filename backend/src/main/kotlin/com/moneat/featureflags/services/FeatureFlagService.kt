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

package com.moneat.featureflags.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.featureflags.models.CreateFeatureFlagEnvironmentRequest
import com.moneat.featureflags.models.CreateFeatureFlagRequest
import com.moneat.featureflags.models.CreateFeatureFlagSdkKeyResponse
import com.moneat.featureflags.models.FeatureFlagAnalyticsResponse
import com.moneat.featureflags.models.FeatureFlagAuditEventResponse
import com.moneat.featureflags.models.FeatureFlagConfigResponse
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigSnapshot
import com.moneat.featureflags.models.FeatureFlagEnvironmentResponse
import com.moneat.featureflags.models.FeatureFlagEnvironmentSnapshot
import com.moneat.featureflags.models.FeatureFlagEnvironments
import com.moneat.featureflags.models.FeatureFlagListResponse
import com.moneat.featureflags.models.FeatureFlagResponse
import com.moneat.featureflags.models.FeatureFlagSdkKeyPrincipal
import com.moneat.featureflags.models.FeatureFlagSdkKeyRequest
import com.moneat.featureflags.models.FeatureFlagSdkKeyResponse
import com.moneat.featureflags.models.FeatureFlagSegmentRequest
import com.moneat.featureflags.models.FeatureFlagSegmentResponse
import com.moneat.featureflags.models.FeatureFlagSegmentSnapshot
import com.moneat.featureflags.models.FeatureFlagTrackingAnalytics
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantAnalytics
import com.moneat.featureflags.models.FeatureFlagVariantRequest
import com.moneat.featureflags.models.FeatureFlagVariantResponse
import com.moneat.featureflags.models.FeatureFlagVariantSnapshot
import com.moneat.featureflags.models.FeatureFlagAuditEvents
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigs
import com.moneat.featureflags.models.FeatureFlags
import com.moneat.featureflags.models.FeatureFlagSdkKeys
import com.moneat.featureflags.models.FeatureFlagSegments
import com.moneat.featureflags.models.FeatureFlagSnapshotFlag
import com.moneat.featureflags.models.FeatureFlagVariants
import com.moneat.featureflags.models.FLAG_KEY_TYPE_CLIENT
import com.moneat.featureflags.models.FLAG_KEY_TYPE_SERVER
import com.moneat.featureflags.models.UpdateFeatureFlagConfigRequest
import com.moneat.featureflags.models.UpdateFeatureFlagRequest
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

private const val SERVER_KEY_PREFIX = "mffsk_"
private const val CLIENT_KEY_PREFIX = "mffpk_"
private const val SDK_KEY_RANDOM_BYTES = 32
private const val SDK_KEY_DISPLAY_PREFIX_LENGTH = 16
private const val CACHE_TTL_SECONDS = 30L
private const val CACHE_TTL_MILLIS = CACHE_TTL_SECONDS * 1_000L
private const val DEFAULT_ANALYTICS_HOURS = 24
private const val ANALYTICS_LIMIT = 25
private const val ETAG_HASH_LENGTH = 24
private const val DEFAULT_RULES_JSON = """{"rules":[]}"""
private val FEATURE_FLAG_KEY_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,254}$")
private val ENVIRONMENT_KEY_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$")

private data class FeatureFlagAuditRecord(
    val organizationId: Int,
    val environmentId: Int?,
    val flagId: Int?,
    val actorUserId: Int,
    val eventType: String,
    val before: String?,
    val after: String?,
    val now: Instant,
)

class FeatureFlagService {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val random = SecureRandom()
    private val snapshotCache = ConcurrentHashMap<String, CachedSnapshot>()

    fun listEnvironments(organizationId: Int): List<FeatureFlagEnvironmentResponse> {
        return transaction {
            ensureDefaultEnvironmentsInTransaction(organizationId)
            FeatureFlagEnvironments
                .selectAll()
                .where { FeatureFlagEnvironments.organizationId eq organizationId }
                .orderBy(FeatureFlagEnvironments.key to SortOrder.ASC)
                .map(::environmentResponse)
        }
    }

    fun createEnvironment(
        organizationId: Int,
        actorUserId: Int,
        request: CreateFeatureFlagEnvironmentRequest,
    ): FeatureFlagEnvironmentResponse {
        validateEnvironmentKey(request.key)
        val now = Clock.System.now()
        return transaction {
            ensureDefaultEnvironmentsInTransaction(organizationId)
            val id = FeatureFlagEnvironments.insert {
                it[FeatureFlagEnvironments.organizationId] = organizationId
                it[key] = request.key
                it[name] = request.name.trim()
                it[description] = request.description?.trim()?.ifBlank { null }
                it[version] = 1
                it[createdAt] = now
                it[updatedAt] = now
            }[FeatureFlagEnvironments.id]

            FeatureFlags
                .selectAll()
                .where {
                    (FeatureFlags.organizationId eq organizationId) and FeatureFlags.archivedAt.isNull()
                }
                .forEach { flagRow ->
                    val defaultVariant = firstVariantKey(flagRow[FeatureFlags.id])
                    FeatureFlagEnvironmentConfigs.insert {
                        it[flagId] = flagRow[FeatureFlags.id]
                        it[environmentId] = id
                        it[enabled] = false
                        it[defaultVariantKey] = defaultVariant
                        it[offVariantKey] = defaultVariant
                        it[rulesJson] = DEFAULT_RULES_JSON
                        it[version] = 1
                        it[updatedBy] = actorUserId
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }

            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = id,
                    flagId = null,
                    actorUserId = actorUserId,
                    eventType = "environment.created",
                    before = null,
                    after = json.encodeToString(request),
                    now = now
                )
            )
            environmentResponse(
                checkNotNull(
                    FeatureFlagEnvironments.selectAll().where { FeatureFlagEnvironments.id eq id }.firstOrNull()
                )
            )
        }.also {
            invalidateEnvironment(organizationId, request.key)
        }
    }

    fun listFlags(organizationId: Int, environmentKey: String? = null): FeatureFlagListResponse {
        return transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            val flags = FeatureFlags
                .selectAll()
                .where {
                    (FeatureFlags.organizationId eq organizationId) and FeatureFlags.archivedAt.isNull()
                }
                .orderBy(FeatureFlags.key to SortOrder.ASC)
                .map { flagResponse(it, environments, environmentKey) }
            FeatureFlagListResponse(environments.map(::environmentResponse), flags)
        }
    }

    fun createFlag(
        organizationId: Int,
        actorUserId: Int,
        request: CreateFeatureFlagRequest,
    ): FeatureFlagResponse {
        validateFlagKey(request.key)
        val variantKeys = validateVariantSet(request.valueType, request.variants)
        val defaultVariant = request.defaultVariantKey ?: request.variants.first().key
        val offVariant = request.offVariantKey ?: defaultVariant
        validateVariantReference(variantKeys, defaultVariant, "Default")
        validateVariantReference(variantKeys, offVariant, "Off")
        val now = Clock.System.now()
        val environmentKeys = mutableListOf<String>()

        val response = transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            environmentKeys.addAll(environments.map { it[FeatureFlagEnvironments.key] })

            val flagId = FeatureFlags.insert {
                it[FeatureFlags.organizationId] = organizationId
                it[key] = request.key
                it[name] = request.name.trim()
                it[description] = request.description?.trim()?.ifBlank { null }
                it[valueType] = request.valueType.name
                it[clientVisible] = request.clientVisible
                it[tags] = json.encodeToString(request.tags)
                it[createdBy] = actorUserId
                it[createdAt] = now
                it[updatedAt] = now
            }[FeatureFlags.id]

            replaceVariants(flagId, request.variants, now)
            environments.forEach { environment ->
                FeatureFlagEnvironmentConfigs.insert {
                    it[FeatureFlagEnvironmentConfigs.flagId] = flagId
                    it[environmentId] = environment[FeatureFlagEnvironments.id]
                    it[enabled] = false
                    it[defaultVariantKey] = defaultVariant
                    it[offVariantKey] = offVariant
                    it[rulesJson] = DEFAULT_RULES_JSON
                    it[version] = 1
                    it[updatedBy] = actorUserId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                incrementEnvironmentVersionInTransaction(environment[FeatureFlagEnvironments.id], now)
            }

            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = null,
                    flagId = flagId,
                    actorUserId = actorUserId,
                    eventType = "flag.created",
                    before = null,
                    after = json.encodeToString(request),
                    now = now
                )
            )
            flagResponse(loadFlagRow(organizationId, request.key), environments, null)
        }

        environmentKeys.forEach { invalidateEnvironment(organizationId, it) }
        return response
    }

    fun getFlag(organizationId: Int, flagKey: String, environmentKey: String? = null): FeatureFlagResponse? {
        return transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            val row = FeatureFlags
                .selectAll()
                .where {
                    (FeatureFlags.organizationId eq organizationId) and
                        (FeatureFlags.key eq flagKey) and
                        FeatureFlags.archivedAt.isNull()
                }
                .firstOrNull() ?: return@transaction null
            flagResponse(row, environments, environmentKey)
        }
    }

    fun updateFlag(
        organizationId: Int,
        actorUserId: Int,
        flagKey: String,
        request: UpdateFeatureFlagRequest,
    ): FeatureFlagResponse? {
        val now = Clock.System.now()
        val environmentKeys = mutableListOf<String>()
        return transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            environmentKeys.addAll(environments.map { it[FeatureFlagEnvironments.key] })
            val flagRow = findFlagRow(organizationId, flagKey) ?: return@transaction null
            val flagId = flagRow[FeatureFlags.id]
            val valueType = FeatureFlagValueType.valueOf(flagRow[FeatureFlags.valueType])
            val variantKeys = request.variants?.let { validateVariantSet(valueType, it) }
            if (variantKeys != null) {
                validateExistingConfigVariantReferences(flagId, variantKeys)
            }

            FeatureFlags.update({ FeatureFlags.id eq flagId }) {
                request.name?.trim()?.takeIf(String::isNotBlank)?.let { value -> it[name] = value }
                if (request.description != null) {
                    it[description] = request.description.trim().ifBlank { null }
                }
                request.clientVisible?.let { value -> it[clientVisible] = value }
                request.tags?.let { tags -> it[FeatureFlags.tags] = json.encodeToString(tags) }
                it[updatedAt] = now
            }

            if (request.variants != null) {
                replaceVariants(flagId, request.variants, now)
            }

            environments.forEach { environment ->
                incrementEnvironmentVersionInTransaction(environment[FeatureFlagEnvironments.id], now)
            }
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = null,
                    flagId = flagId,
                    actorUserId = actorUserId,
                    eventType = "flag.updated",
                    before = null,
                    after = json.encodeToString(request),
                    now = now
                )
            )
            flagResponse(loadFlagRow(organizationId, flagKey), environments, null)
        }.also {
            environmentKeys.forEach { environmentKey -> invalidateEnvironment(organizationId, environmentKey) }
        }
    }

    fun archiveFlag(organizationId: Int, actorUserId: Int, flagKey: String): Boolean {
        val now = Clock.System.now()
        val environmentKeys = mutableListOf<String>()
        val archived = transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            environmentKeys.addAll(environments.map { it[FeatureFlagEnvironments.key] })
            val flagRow = findFlagRow(organizationId, flagKey) ?: return@transaction false
            val updated = FeatureFlags.update({ FeatureFlags.id eq flagRow[FeatureFlags.id] }) {
                it[archivedAt] = now
                it[updatedAt] = now
            }
            environments.forEach { environment ->
                incrementEnvironmentVersionInTransaction(environment[FeatureFlagEnvironments.id], now)
            }
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = null,
                    flagId = flagRow[FeatureFlags.id],
                    actorUserId = actorUserId,
                    eventType = "flag.archived",
                    before = null,
                    after = "{\"key\":\"$flagKey\"}",
                    now = now
                )
            )
            updated > 0
        }
        if (archived) environmentKeys.forEach { invalidateEnvironment(organizationId, it) }
        return archived
    }

    fun updateConfig(
        organizationId: Int,
        actorUserId: Int,
        flagKey: String,
        environmentKey: String,
        request: UpdateFeatureFlagConfigRequest,
    ): FeatureFlagConfigResponse? {
        val now = Clock.System.now()
        val response = transaction {
            val flagRow = findFlagRow(organizationId, flagKey) ?: return@transaction null
            val environmentRow = findEnvironmentRow(organizationId, environmentKey) ?: return@transaction null
            val configRow = findConfigRow(flagRow[FeatureFlags.id], environmentRow[FeatureFlagEnvironments.id])
                ?: return@transaction null
            val variantKeys = loadVariantKeySet(flagRow[FeatureFlags.id])
            validateVariantReference(variantKeys, request.defaultVariantKey, "Default")
            validateVariantReference(variantKeys, request.offVariantKey, "Off")

            FeatureFlagEnvironmentConfigs.update(
                { FeatureFlagEnvironmentConfigs.id eq configRow[FeatureFlagEnvironmentConfigs.id] }
            ) {
                request.enabled?.let { value -> it[enabled] = value }
                if (request.defaultVariantKey != null) it[defaultVariantKey] = request.defaultVariantKey
                if (request.offVariantKey != null) it[offVariantKey] = request.offVariantKey
                request.rules?.let { value -> it[rulesJson] = json.encodeToString(value) }
                it[version] = configRow[FeatureFlagEnvironmentConfigs.version] + 1
                it[updatedBy] = actorUserId
                it[updatedAt] = now
            }
            incrementEnvironmentVersionInTransaction(environmentRow[FeatureFlagEnvironments.id], now)
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = environmentRow[FeatureFlagEnvironments.id],
                    flagId = flagRow[FeatureFlags.id],
                    actorUserId = actorUserId,
                    eventType = "config.updated",
                    before = configSnapshotJson(configRow),
                    after = json.encodeToString(request),
                    now = now
                )
            )
            val updatedRow =
                checkNotNull(
                    findConfigRow(
                        flagId = flagRow[FeatureFlags.id],
                        environmentId = environmentRow[FeatureFlagEnvironments.id],
                    )
                )
            configResponse(updatedRow, environmentRow)
        }
        if (response != null) invalidateEnvironment(organizationId, environmentKey)
        return response
    }

    fun listSegments(organizationId: Int): List<FeatureFlagSegmentResponse> {
        return transaction {
            FeatureFlagSegments
                .selectAll()
                .where {
                    (FeatureFlagSegments.organizationId eq organizationId) and FeatureFlagSegments.archivedAt.isNull()
                }
                .orderBy(FeatureFlagSegments.key to SortOrder.ASC)
                .map(::segmentResponse)
        }
    }

    fun upsertSegment(
        organizationId: Int,
        actorUserId: Int,
        request: FeatureFlagSegmentRequest,
    ): FeatureFlagSegmentResponse {
        validateFlagKey(request.key)
        val now = Clock.System.now()
        val environmentKeys = mutableListOf<String>()
        val response = transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            environmentKeys.addAll(environments.map { it[FeatureFlagEnvironments.key] })
            val existing = FeatureFlagSegments
                .selectAll()
                .where {
                    (FeatureFlagSegments.organizationId eq organizationId) and
                        (FeatureFlagSegments.key eq request.key) and
                        FeatureFlagSegments.archivedAt.isNull()
                }
                .firstOrNull()

            val id =
                if (existing == null) {
                    FeatureFlagSegments.insert {
                        it[FeatureFlagSegments.organizationId] = organizationId
                        it[key] = request.key
                        it[name] = request.name.trim()
                        it[description] = request.description?.trim()?.ifBlank { null }
                        it[conditionsJson] = json.encodeToString(request.conditions)
                        it[createdAt] = now
                        it[updatedAt] = now
                    }[FeatureFlagSegments.id]
                } else {
                    FeatureFlagSegments.update({ FeatureFlagSegments.id eq existing[FeatureFlagSegments.id] }) {
                        it[name] = request.name.trim()
                        it[description] = request.description?.trim()?.ifBlank { null }
                        it[conditionsJson] = json.encodeToString(request.conditions)
                        it[updatedAt] = now
                    }
                    existing[FeatureFlagSegments.id]
                }

            environments.forEach { environment ->
                incrementEnvironmentVersionInTransaction(environment[FeatureFlagEnvironments.id], now)
            }
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = null,
                    flagId = null,
                    actorUserId = actorUserId,
                    eventType = if (existing == null) "segment.created" else "segment.updated",
                    before = existing?.let(::segmentSnapshotJson),
                    after = json.encodeToString(request),
                    now = now
                )
            )
            val segmentRow =
                checkNotNull(
                    FeatureFlagSegments
                        .selectAll()
                        .where { FeatureFlagSegments.id eq id }
                        .firstOrNull()
                )
            segmentResponse(segmentRow)
        }
        environmentKeys.forEach { invalidateEnvironment(organizationId, it) }
        return response
    }

    fun deleteSegment(organizationId: Int, actorUserId: Int, segmentKey: String): Boolean {
        val now = Clock.System.now()
        val environmentKeys = mutableListOf<String>()
        val deleted = transaction {
            val environments = ensureDefaultEnvironmentsInTransaction(organizationId)
            environmentKeys.addAll(environments.map { it[FeatureFlagEnvironments.key] })
            val row = FeatureFlagSegments
                .selectAll()
                .where {
                    (FeatureFlagSegments.organizationId eq organizationId) and
                        (FeatureFlagSegments.key eq segmentKey) and
                        FeatureFlagSegments.archivedAt.isNull()
                }
                .firstOrNull() ?: return@transaction false
            FeatureFlagSegments.update({ FeatureFlagSegments.id eq row[FeatureFlagSegments.id] }) {
                it[archivedAt] = now
                it[updatedAt] = now
            }
            environments.forEach { environment ->
                incrementEnvironmentVersionInTransaction(environment[FeatureFlagEnvironments.id], now)
            }
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = null,
                    flagId = null,
                    actorUserId = actorUserId,
                    eventType = "segment.archived",
                    before = segmentSnapshotJson(row),
                    after = null,
                    now = now
                )
            )
            true
        }
        if (deleted) environmentKeys.forEach { invalidateEnvironment(organizationId, it) }
        return deleted
    }

    fun listSdkKeys(organizationId: Int): List<FeatureFlagSdkKeyResponse> {
        return transaction {
            ensureDefaultEnvironmentsInTransaction(organizationId)
            val environments = FeatureFlagEnvironments
                .selectAll()
                .where { FeatureFlagEnvironments.organizationId eq organizationId }
                .associateBy({ it[FeatureFlagEnvironments.id] }, { it[FeatureFlagEnvironments.key] })
            FeatureFlagSdkKeys
                .selectAll()
                .where {
                    (FeatureFlagSdkKeys.organizationId eq organizationId) and (FeatureFlagSdkKeys.isActive eq true)
                }
                .orderBy(FeatureFlagSdkKeys.createdAt to SortOrder.DESC)
                .map { row ->
                    FeatureFlagSdkKeyResponse(
                        id = row[FeatureFlagSdkKeys.id],
                        environmentKey = environments[row[FeatureFlagSdkKeys.environmentId]].orEmpty(),
                        name = row[FeatureFlagSdkKeys.name],
                        keyType = row[FeatureFlagSdkKeys.keyType],
                        keyPrefix = row[FeatureFlagSdkKeys.keyPrefix],
                        createdAt = row[FeatureFlagSdkKeys.createdAt].toString(),
                        lastUsedAt = row[FeatureFlagSdkKeys.lastUsedAt]?.toString(),
                    )
                }
        }
    }

    fun createSdkKey(
        organizationId: Int,
        actorUserId: Int,
        request: FeatureFlagSdkKeyRequest,
    ): CreateFeatureFlagSdkKeyResponse {
        val keyType = request.keyType.lowercase()
        require(keyType == FLAG_KEY_TYPE_SERVER || keyType == FLAG_KEY_TYPE_CLIENT) {
            "keyType must be server or client"
        }
        val rawKey = generateSdkKey(keyType)
        val now = Clock.System.now()

        return transaction {
            ensureDefaultEnvironmentsInTransaction(organizationId)
            val environment = findEnvironmentRow(organizationId, request.environmentKey)
                ?: throw IllegalArgumentException("Environment not found")
            val id = FeatureFlagSdkKeys.insert {
                it[FeatureFlagSdkKeys.organizationId] = organizationId
                it[environmentId] = environment[FeatureFlagEnvironments.id]
                it[name] = request.name.trim()
                it[FeatureFlagSdkKeys.keyType] = keyType
                it[keyHash] = hashKey(rawKey)
                it[keyPrefix] = rawKey.take(SDK_KEY_DISPLAY_PREFIX_LENGTH)
                it[createdBy] = actorUserId
                it[createdAt] = now
                it[isActive] = true
            }[FeatureFlagSdkKeys.id]
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = environment[FeatureFlagEnvironments.id],
                    flagId = null,
                    actorUserId = actorUserId,
                    eventType = "sdk_key.created",
                    before = null,
                    after = json.encodeToString(request.copy(name = request.name.trim(), keyType = keyType)),
                    now = now
                )
            )
            CreateFeatureFlagSdkKeyResponse(
                id = id,
                environmentKey = environment[FeatureFlagEnvironments.key],
                name = request.name.trim(),
                keyType = keyType,
                keyPrefix = rawKey.take(SDK_KEY_DISPLAY_PREFIX_LENGTH),
                key = rawKey,
                createdAt = now.toString(),
            )
        }
    }

    fun revokeSdkKey(organizationId: Int, actorUserId: Int, keyId: Int): Boolean {
        val now = Clock.System.now()
        return transaction {
            val row = FeatureFlagSdkKeys
                .selectAll()
                .where {
                    (FeatureFlagSdkKeys.organizationId eq organizationId) and
                        (FeatureFlagSdkKeys.id eq keyId) and
                        (FeatureFlagSdkKeys.isActive eq true)
                }
                .firstOrNull() ?: return@transaction false
            FeatureFlagSdkKeys.update({ FeatureFlagSdkKeys.id eq keyId }) {
                it[isActive] = false
                it[revokedAt] = now
            }
            audit(
                FeatureFlagAuditRecord(
                    organizationId = organizationId,
                    environmentId = row[FeatureFlagSdkKeys.environmentId],
                    flagId = null,
                    actorUserId = actorUserId,
                    eventType = "sdk_key.revoked",
                    before = null,
                    after = "{\"keyPrefix\":\"${row[FeatureFlagSdkKeys.keyPrefix]}\"}",
                    now = now
                )
            )
            true
        }
    }

    fun validateSdkKey(rawKey: String): FeatureFlagSdkKeyPrincipal? {
        if (!rawKey.startsWith(SERVER_KEY_PREFIX) && !rawKey.startsWith(CLIENT_KEY_PREFIX)) return null
        if (rawKey.length < SDK_KEY_DISPLAY_PREFIX_LENGTH) return null
        val keyHash = hashKey(rawKey)
        val keyPrefix = rawKey.take(SDK_KEY_DISPLAY_PREFIX_LENGTH)
        val now = Clock.System.now()

        return transaction {
            val keyRow = FeatureFlagSdkKeys
                .selectAll()
                .where {
                    (FeatureFlagSdkKeys.keyHash eq keyHash) and
                        (FeatureFlagSdkKeys.keyPrefix eq keyPrefix) and
                        (FeatureFlagSdkKeys.isActive eq true)
                }
                .firstOrNull() ?: return@transaction null
            val environment = FeatureFlagEnvironments
                .selectAll()
                .where { FeatureFlagEnvironments.id eq keyRow[FeatureFlagSdkKeys.environmentId] }
                .firstOrNull() ?: return@transaction null

            FeatureFlagSdkKeys.update({ FeatureFlagSdkKeys.id eq keyRow[FeatureFlagSdkKeys.id] }) {
                it[lastUsedAt] = now
            }

            FeatureFlagSdkKeyPrincipal(
                organizationId = keyRow[FeatureFlagSdkKeys.organizationId],
                environmentId = keyRow[FeatureFlagSdkKeys.environmentId],
                environmentKey = environment[FeatureFlagEnvironments.key],
                keyType = keyRow[FeatureFlagSdkKeys.keyType],
                keyPrefix = keyRow[FeatureFlagSdkKeys.keyPrefix],
            )
        }
    }

    fun getSnapshot(organizationId: Int, environmentKey: String): FeatureFlagEnvironmentConfigSnapshot? {
        val cacheKey = snapshotCacheKey(organizationId, environmentKey)
        snapshotCache[cacheKey]?.takeIf { it.expiresAtEpochMs > nowMs() }?.let { return it.snapshot }
        redisGetSnapshot(cacheKey)?.let { snapshot ->
            snapshotCache[cacheKey] = CachedSnapshot(snapshot, nowMs() + CACHE_TTL_MILLIS)
            return snapshot
        }

        val snapshot = loadSnapshot(organizationId, environmentKey) ?: return null
        snapshotCache[cacheKey] = CachedSnapshot(snapshot, nowMs() + CACHE_TTL_MILLIS)
        redisSetSnapshot(cacheKey, snapshot)
        return snapshot
    }

    fun listAuditEvents(organizationId: Int, limit: Int = ANALYTICS_LIMIT): List<FeatureFlagAuditEventResponse> {
        return transaction {
            val rows = FeatureFlagAuditEvents
                .selectAll()
                .where { FeatureFlagAuditEvents.organizationId eq organizationId }
                .orderBy(FeatureFlagAuditEvents.createdAt to SortOrder.DESC)
                .limit(limit)
                .toList()
            val environmentIds = rows.mapNotNull { it[FeatureFlagAuditEvents.environmentId] }.distinct()
            val flagIds = rows.mapNotNull { it[FeatureFlagAuditEvents.flagId] }.distinct()
            val environments = if (environmentIds.isEmpty()) {
                emptyMap()
            } else {
                FeatureFlagEnvironments.selectAll()
                    .where { FeatureFlagEnvironments.id inList environmentIds }
                    .associateBy({ it[FeatureFlagEnvironments.id] }, { it[FeatureFlagEnvironments.key] })
            }
            val flags = if (flagIds.isEmpty()) {
                emptyMap()
            } else {
                FeatureFlags.selectAll()
                    .where { FeatureFlags.id inList flagIds }
                    .associateBy({ it[FeatureFlags.id] }, { it[FeatureFlags.key] })
            }
            rows.map { row ->
                FeatureFlagAuditEventResponse(
                    id = row[FeatureFlagAuditEvents.id],
                    environmentKey = row[FeatureFlagAuditEvents.environmentId]?.let(environments::get),
                    flagKey = row[FeatureFlagAuditEvents.flagId]?.let(flags::get),
                    actorUserId = row[FeatureFlagAuditEvents.actorUserId],
                    eventType = row[FeatureFlagAuditEvents.eventType],
                    before = row[FeatureFlagAuditEvents.beforeJson]?.let(::parseElement),
                    after = row[FeatureFlagAuditEvents.afterJson]?.let(::parseElement),
                    createdAt = row[FeatureFlagAuditEvents.createdAt].toString(),
                )
            }
        }
    }

    suspend fun analytics(
        organizationId: Int,
        environmentKey: String?,
        hours: Int = DEFAULT_ANALYTICS_HOURS,
    ): FeatureFlagAnalyticsResponse {
        if (!ClickHouseClient.isInitialized()) {
            return emptyAnalytics()
        }
        return suspendRunCatching {
            val where = analyticsWhere(organizationId, environmentKey, hours)
            val total = firstJsonRow(
                ClickHouseClient.executeWithFormat(
                    "SELECT count() AS evaluations, uniqExact(targeting_key) AS uniqueTargetingKeys " +
                        "FROM feature_flag_evaluations WHERE $where",
                    "JSONEachRow"
                )
            )
            val variants = parseJsonRows(
                ClickHouseClient.executeWithFormat(
                    "SELECT flag_key, variant_key, count() AS evaluations, " +
                        "uniqExact(targeting_key) AS uniqueTargetingKeys " +
                        "FROM feature_flag_evaluations WHERE $where " +
                        "GROUP BY flag_key, variant_key ORDER BY evaluations DESC LIMIT $ANALYTICS_LIMIT",
                    "JSONEachRow"
                )
            ).map { row ->
                FeatureFlagVariantAnalytics(
                    flagKey = readString(row["flag_key"]).orEmpty(),
                    variantKey = readString(row["variant_key"]).orEmpty(),
                    evaluations = readLong(row["evaluations"]),
                    uniqueTargetingKeys = readLong(row["uniqueTargetingKeys"]),
                )
            }
            val tracking = parseJsonRows(
                ClickHouseClient.executeWithFormat(
                    "SELECT event_name, flag_key, variant_key, count() AS events, " +
                        "uniqExact(targeting_key) AS uniqueTargetingKeys, sum(value) AS totalValue " +
                        "FROM feature_flag_tracking_events WHERE $where " +
                        "GROUP BY event_name, flag_key, variant_key ORDER BY events DESC LIMIT $ANALYTICS_LIMIT",
                    "JSONEachRow"
                )
            ).map { row ->
                FeatureFlagTrackingAnalytics(
                    eventName = readString(row["event_name"]).orEmpty(),
                    flagKey = readString(row["flag_key"])?.ifBlank { null },
                    variantKey = readString(row["variant_key"])?.ifBlank { null },
                    events = readLong(row["events"]),
                    uniqueTargetingKeys = readLong(row["uniqueTargetingKeys"]),
                    totalValue = readDouble(row["totalValue"]),
                )
            }

            FeatureFlagAnalyticsResponse(
                evaluations = readLong(total["evaluations"]),
                uniqueTargetingKeys = readLong(total["uniqueTargetingKeys"]),
                variants = variants,
                trackingEvents = tracking,
            )
        }.getOrDefault(emptyAnalytics())
    }

    private fun loadSnapshot(organizationId: Int, environmentKey: String): FeatureFlagEnvironmentConfigSnapshot? {
        return transaction {
            val environment = findEnvironmentRow(organizationId, environmentKey) ?: return@transaction null
            val flags = FeatureFlags
                .selectAll()
                .where {
                    (FeatureFlags.organizationId eq organizationId) and FeatureFlags.archivedAt.isNull()
                }
                .mapNotNull { flagRow ->
                    val configRow = findConfigRow(flagRow[FeatureFlags.id], environment[FeatureFlagEnvironments.id])
                        ?: return@mapNotNull null
                    FeatureFlagSnapshotFlag(
                        id = flagRow[FeatureFlags.id],
                        key = flagRow[FeatureFlags.key],
                        valueType = FeatureFlagValueType.valueOf(flagRow[FeatureFlags.valueType]),
                        clientVisible = flagRow[FeatureFlags.clientVisible],
                        variants = loadVariantSnapshots(flagRow[FeatureFlags.id]),
                        config = com.moneat.featureflags.models.FeatureFlagConfigSnapshot(
                            enabled = configRow[FeatureFlagEnvironmentConfigs.enabled],
                            defaultVariantKey = configRow[FeatureFlagEnvironmentConfigs.defaultVariantKey],
                            offVariantKey = configRow[FeatureFlagEnvironmentConfigs.offVariantKey],
                            rules = parseElement(configRow[FeatureFlagEnvironmentConfigs.rulesJson]),
                            version = configRow[FeatureFlagEnvironmentConfigs.version],
                        )
                    )
                }
            val segments = FeatureFlagSegments
                .selectAll()
                .where {
                    (FeatureFlagSegments.organizationId eq organizationId) and FeatureFlagSegments.archivedAt.isNull()
                }
                .map { segment ->
                    FeatureFlagSegmentSnapshot(
                        key = segment[FeatureFlagSegments.key],
                        conditions = parseElement(segment[FeatureFlagSegments.conditionsJson])
                    )
                }
            val environmentSnapshot = FeatureFlagEnvironmentSnapshot(
                id = environment[FeatureFlagEnvironments.id],
                key = environment[FeatureFlagEnvironments.key],
                name = environment[FeatureFlagEnvironments.name],
                version = environment[FeatureFlagEnvironments.version],
            )
            val provisional = FeatureFlagEnvironmentConfigSnapshot(
                organizationId = organizationId,
                environment = environmentSnapshot,
                etag = "",
                flags = flags,
                segments = segments,
            )
            provisional.copy(etag = etagForSnapshot(provisional))
        }
    }

    private fun flagResponse(
        row: ResultRow,
        environments: List<ResultRow>,
        environmentKey: String?,
    ): FeatureFlagResponse {
        val flagId = row[FeatureFlags.id]
        val selectedEnvironments = if (environmentKey == null) {
            environments
        } else {
            environments.filter { it[FeatureFlagEnvironments.key] == environmentKey }
        }
        return FeatureFlagResponse(
            id = flagId,
            key = row[FeatureFlags.key],
            name = row[FeatureFlags.name],
            description = row[FeatureFlags.description],
            valueType = FeatureFlagValueType.valueOf(row[FeatureFlags.valueType]),
            clientVisible = row[FeatureFlags.clientVisible],
            tags = parseTags(row[FeatureFlags.tags]),
            variants = loadVariantResponses(flagId),
            configs = selectedEnvironments.mapNotNull { environment ->
                findConfigRow(flagId, environment[FeatureFlagEnvironments.id])?.let { configResponse(it, environment) }
            },
            createdAt = row[FeatureFlags.createdAt].toString(),
            updatedAt = row[FeatureFlags.updatedAt].toString(),
        )
    }

    private fun configResponse(configRow: ResultRow, environmentRow: ResultRow): FeatureFlagConfigResponse {
        return FeatureFlagConfigResponse(
            environmentKey = environmentRow[FeatureFlagEnvironments.key],
            environmentName = environmentRow[FeatureFlagEnvironments.name],
            enabled = configRow[FeatureFlagEnvironmentConfigs.enabled],
            defaultVariantKey = configRow[FeatureFlagEnvironmentConfigs.defaultVariantKey],
            offVariantKey = configRow[FeatureFlagEnvironmentConfigs.offVariantKey],
            rules = parseElement(configRow[FeatureFlagEnvironmentConfigs.rulesJson]),
            version = configRow[FeatureFlagEnvironmentConfigs.version],
            updatedAt = configRow[FeatureFlagEnvironmentConfigs.updatedAt].toString(),
        )
    }

    private fun loadVariantResponses(flagId: Int): List<FeatureFlagVariantResponse> {
        return FeatureFlagVariants
            .selectAll()
            .where { FeatureFlagVariants.flagId eq flagId }
            .orderBy(FeatureFlagVariants.sortOrder to SortOrder.ASC)
            .map { row ->
                FeatureFlagVariantResponse(
                    id = row[FeatureFlagVariants.id],
                    key = row[FeatureFlagVariants.key],
                    name = row[FeatureFlagVariants.name],
                    value = parseElement(row[FeatureFlagVariants.valueJson]),
                    sortOrder = row[FeatureFlagVariants.sortOrder],
                )
            }
    }

    private fun loadVariantSnapshots(flagId: Int): List<FeatureFlagVariantSnapshot> {
        return FeatureFlagVariants
            .selectAll()
            .where { FeatureFlagVariants.flagId eq flagId }
            .orderBy(FeatureFlagVariants.sortOrder to SortOrder.ASC)
            .map { row ->
                FeatureFlagVariantSnapshot(
                    key = row[FeatureFlagVariants.key],
                    name = row[FeatureFlagVariants.name],
                    value = parseElement(row[FeatureFlagVariants.valueJson]),
                    sortOrder = row[FeatureFlagVariants.sortOrder],
                )
            }
    }

    private fun replaceVariants(flagId: Int, variants: List<FeatureFlagVariantRequest>, now: kotlin.time.Instant) {
        FeatureFlagVariants.deleteWhere { FeatureFlagVariants.flagId eq flagId }
        variants.forEachIndexed { index, variant ->
            FeatureFlagVariants.insert {
                it[FeatureFlagVariants.flagId] = flagId
                it[key] = variant.key
                it[name] = variant.name?.trim()?.ifBlank { null } ?: variant.key
                it[valueJson] = json.encodeToString(variant.value)
                it[sortOrder] = index
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    private fun ensureDefaultEnvironmentsInTransaction(organizationId: Int): List<ResultRow> {
        val now = Clock.System.now()
        defaultEnvironments().forEach { (key, name) ->
            if (!defaultEnvironmentExists(organizationId, key)) {
                insertDefaultEnvironment(organizationId, key, name, now)
            }
        }
        return FeatureFlagEnvironments
            .selectAll()
            .where { FeatureFlagEnvironments.organizationId eq organizationId }
            .orderBy(FeatureFlagEnvironments.key to SortOrder.ASC)
            .toList()
    }

    private fun defaultEnvironmentExists(organizationId: Int, key: String): Boolean {
        return FeatureFlagEnvironments
            .selectAll()
            .where {
                (FeatureFlagEnvironments.organizationId eq organizationId) and
                    (FeatureFlagEnvironments.key eq key)
            }
            .any()
    }

    private fun insertDefaultEnvironment(organizationId: Int, key: String, name: String, now: Instant) {
        try {
            FeatureFlagEnvironments.insert {
                it[FeatureFlagEnvironments.organizationId] = organizationId
                it[FeatureFlagEnvironments.key] = key
                it[FeatureFlagEnvironments.name] = name
                it[version] = 1
                it[createdAt] = now
                it[updatedAt] = now
            }
        } catch (e: ExposedSQLException) {
            if (!defaultEnvironmentExists(organizationId, key)) throw e
        }
    }

    private fun findFlagRow(organizationId: Int, flagKey: String): ResultRow? {
        return FeatureFlags
            .selectAll()
            .where {
                (FeatureFlags.organizationId eq organizationId) and
                    (FeatureFlags.key eq flagKey) and
                    FeatureFlags.archivedAt.isNull()
            }
            .firstOrNull()
    }

    private fun loadFlagRow(organizationId: Int, flagKey: String): ResultRow {
        return checkNotNull(findFlagRow(organizationId, flagKey)) {
            "Feature flag disappeared after write"
        }
    }

    private fun findEnvironmentRow(organizationId: Int, environmentKey: String): ResultRow? {
        return FeatureFlagEnvironments
            .selectAll()
            .where {
                (FeatureFlagEnvironments.organizationId eq organizationId) and
                    (FeatureFlagEnvironments.key eq environmentKey)
            }
            .firstOrNull()
    }

    private fun findConfigRow(flagId: Int, environmentId: Int): ResultRow? {
        return FeatureFlagEnvironmentConfigs
            .selectAll()
            .where {
                (FeatureFlagEnvironmentConfigs.flagId eq flagId) and
                    (FeatureFlagEnvironmentConfigs.environmentId eq environmentId)
            }
            .firstOrNull()
    }

    private fun firstVariantKey(flagId: Int): String? {
        return FeatureFlagVariants
            .selectAll()
            .where { FeatureFlagVariants.flagId eq flagId }
            .orderBy(FeatureFlagVariants.sortOrder to SortOrder.ASC)
            .firstOrNull()
            ?.get(FeatureFlagVariants.key)
    }

    private fun incrementEnvironmentVersionInTransaction(environmentId: Int, now: kotlin.time.Instant) {
        FeatureFlagEnvironments.update({ FeatureFlagEnvironments.id eq environmentId }) {
            it.update(version, version + 1)
            it[updatedAt] = now
        }
    }

    private fun invalidateEnvironment(organizationId: Int, environmentKey: String) {
        val cacheKey = snapshotCacheKey(organizationId, environmentKey)
        snapshotCache.remove(cacheKey)
        if (RedisConfig.isConnected()) {
            runCatching {
                RedisConfig.sync().del(cacheKey)
                RedisConfig.sync().publish(
                    "feature-flags:changes",
                    """{"organizationId":$organizationId,"environment":"$environmentKey"}"""
                )
            }
        }
    }

    private fun redisGetSnapshot(cacheKey: String): FeatureFlagEnvironmentConfigSnapshot? {
        if (!RedisConfig.isConnected()) return null
        return runCatching {
            RedisConfig.sync().get(cacheKey)?.let {
                json.decodeFromString<FeatureFlagEnvironmentConfigSnapshot>(it)
            }
        }.getOrNull()
    }

    private fun redisSetSnapshot(cacheKey: String, snapshot: FeatureFlagEnvironmentConfigSnapshot) {
        if (!RedisConfig.isConnected()) return
        runCatching {
            RedisConfig.sync().setex(cacheKey, CACHE_TTL_SECONDS, json.encodeToString(snapshot))
        }
    }

    private fun etagForSnapshot(snapshot: FeatureFlagEnvironmentConfigSnapshot): String {
        val source = json.encodeToString(snapshot.copy(etag = ""))
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "\"ff-${hash.take(ETAG_HASH_LENGTH)}\""
    }

    private fun generateSdkKey(keyType: String): String {
        val bytes = ByteArray(SDK_KEY_RANDOM_BYTES)
        random.nextBytes(bytes)
        val prefix = if (keyType == FLAG_KEY_TYPE_CLIENT) CLIENT_KEY_PREFIX else SERVER_KEY_PREFIX
        return prefix + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun audit(record: FeatureFlagAuditRecord) {
        FeatureFlagAuditEvents.insert {
            it[FeatureFlagAuditEvents.organizationId] = record.organizationId
            it[FeatureFlagAuditEvents.environmentId] = record.environmentId
            it[FeatureFlagAuditEvents.flagId] = record.flagId
            it[FeatureFlagAuditEvents.actorUserId] = record.actorUserId
            it[FeatureFlagAuditEvents.eventType] = record.eventType
            it[beforeJson] = record.before
            it[afterJson] = record.after
            it[createdAt] = record.now
        }
    }

    private fun validateFlagKey(key: String) {
        require(FEATURE_FLAG_KEY_REGEX.matches(key)) {
            "Key must start with an alphanumeric character and contain only letters, numbers, '.', ':', '_' or '-'"
        }
    }

    private fun validateEnvironmentKey(key: String) {
        require(ENVIRONMENT_KEY_REGEX.matches(key)) {
            "Environment key must start with an alphanumeric character and contain only letters, numbers, '_' or '-'"
        }
    }

    private fun validateVariant(type: FeatureFlagValueType, variant: FeatureFlagVariantRequest) {
        validateFlagKey(variant.key)
        val primitive = variant.value as? JsonPrimitive
        val valid = when (type) {
            FeatureFlagValueType.BOOLEAN -> primitive?.contentOrNull?.let { it == "true" || it == "false" } == true
            FeatureFlagValueType.STRING -> primitive?.isString == true
            FeatureFlagValueType.INTEGER -> primitive?.contentOrNull?.toIntOrNull() != null
            FeatureFlagValueType.DOUBLE -> primitive?.contentOrNull?.toDoubleOrNull() != null
            FeatureFlagValueType.OBJECT -> variant.value is JsonObject
        }
        require(valid) { "Variant ${variant.key} value does not match $type" }
    }

    private fun validateVariantSet(
        type: FeatureFlagValueType,
        variants: List<FeatureFlagVariantRequest>,
    ): Set<String> {
        require(variants.isNotEmpty()) { "At least one variant is required" }
        val keys = mutableSetOf<String>()
        variants.forEach { variant ->
            validateVariant(type, variant)
            require(keys.add(variant.key)) { "Variant keys must be unique" }
        }
        return keys
    }

    private fun validateVariantReference(variantKeys: Set<String>, variantKey: String?, label: String) {
        if (variantKey == null) return
        require(variantKey in variantKeys) { "$label variant '$variantKey' must exist in variants" }
    }

    private fun validateExistingConfigVariantReferences(flagId: Int, variantKeys: Set<String>) {
        FeatureFlagEnvironmentConfigs
            .selectAll()
            .where { FeatureFlagEnvironmentConfigs.flagId eq flagId }
            .forEach { config ->
                validateVariantReference(
                    variantKeys,
                    config[FeatureFlagEnvironmentConfigs.defaultVariantKey],
                    "Existing default"
                )
                validateVariantReference(
                    variantKeys,
                    config[FeatureFlagEnvironmentConfigs.offVariantKey],
                    "Existing off"
                )
            }
    }

    private fun loadVariantKeySet(flagId: Int): Set<String> {
        return FeatureFlagVariants
            .selectAll()
            .where { FeatureFlagVariants.flagId eq flagId }
            .map { it[FeatureFlagVariants.key] }
            .toSet()
    }

    private fun parseElement(raw: String): JsonElement {
        return runCatching { json.parseToJsonElement(raw) }.getOrDefault(JsonNull)
    }

    private fun parseTags(raw: String): List<String> {
        return (parseElement(raw) as? JsonArray)
            ?.mapNotNull { readString(it) }
            .orEmpty()
    }

    private fun segmentResponse(row: ResultRow): FeatureFlagSegmentResponse {
        return FeatureFlagSegmentResponse(
            id = row[FeatureFlagSegments.id],
            key = row[FeatureFlagSegments.key],
            name = row[FeatureFlagSegments.name],
            description = row[FeatureFlagSegments.description],
            conditions = parseElement(row[FeatureFlagSegments.conditionsJson]),
            createdAt = row[FeatureFlagSegments.createdAt].toString(),
            updatedAt = row[FeatureFlagSegments.updatedAt].toString(),
        )
    }

    private fun environmentResponse(row: ResultRow): FeatureFlagEnvironmentResponse {
        return FeatureFlagEnvironmentResponse(
            id = row[FeatureFlagEnvironments.id],
            key = row[FeatureFlagEnvironments.key],
            name = row[FeatureFlagEnvironments.name],
            description = row[FeatureFlagEnvironments.description],
            version = row[FeatureFlagEnvironments.version],
            createdAt = row[FeatureFlagEnvironments.createdAt].toString(),
            updatedAt = row[FeatureFlagEnvironments.updatedAt].toString(),
        )
    }

    private fun defaultEnvironments(): List<Pair<String, String>> = listOf(
        "production" to "Production",
        "staging" to "Staging",
        "development" to "Development",
    )

    private fun configSnapshotJson(row: ResultRow): String {
        val enabled = row[FeatureFlagEnvironmentConfigs.enabled]
        val version = row[FeatureFlagEnvironmentConfigs.version]
        return """{"enabled":$enabled,"version":$version}"""
    }

    private fun segmentSnapshotJson(row: ResultRow): String {
        return """{"key":"${row[FeatureFlagSegments.key]}","conditions":${row[FeatureFlagSegments.conditionsJson]}}"""
    }

    private fun snapshotCacheKey(organizationId: Int, environmentKey: String): String {
        return "feature_flags:snapshot:$organizationId:$environmentKey"
    }

    internal fun analyticsWhere(organizationId: Int, environmentKey: String?, hours: Int): String {
        // organization_id is UInt32, so a negative demo org (-1) is stored wrapped (4294967295) by the
        // ingest path. Match it via toInt32(...) for negative orgs; keep the plain (index-friendly) form
        // for real positive orgs so the primary-key prefix on organization_id stays usable.
        val orgPredicate =
            if (organizationId < 0) {
                "toInt32(organization_id) = $organizationId"
            } else {
                "organization_id = $organizationId"
            }
        val predicates = mutableListOf(
            orgPredicate,
            "event_time >= now() - INTERVAL ${hours.coerceAtLeast(1)} HOUR",
        )
        if (!environmentKey.isNullOrBlank()) {
            predicates.add("environment = '${escapeSql(environmentKey)}'")
        }
        return predicates.joinToString(" AND ")
    }

    private fun parseJsonRows(raw: String): List<JsonObject> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseElement(it) as? JsonObject }
            .toList()
    }

    private fun firstJsonRow(raw: String): JsonObject {
        return parseJsonRows(raw).firstOrNull() ?: JsonObject(emptyMap())
    }

    private fun emptyAnalytics(): FeatureFlagAnalyticsResponse {
        return FeatureFlagAnalyticsResponse(
            evaluations = 0,
            uniqueTargetingKeys = 0,
            variants = emptyList(),
            trackingEvents = emptyList(),
        )
    }

    private fun readString(element: JsonElement?): String? {
        return (element as? JsonPrimitive)?.contentOrNull
    }

    private fun readLong(element: JsonElement?): Long {
        return (element as? JsonPrimitive)?.longOrNull ?: 0L
    }

    private fun readDouble(element: JsonElement?): Double {
        return (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}

private data class CachedSnapshot(
    val snapshot: FeatureFlagEnvironmentConfigSnapshot,
    val expiresAtEpochMs: Long,
)
