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

package com.moneat.featureflags

import com.moneat.config.ClickHouseClient
import com.moneat.featureflags.models.CreateFeatureFlagEnvironmentRequest
import com.moneat.featureflags.models.CreateFeatureFlagRequest
import com.moneat.featureflags.models.FeatureFlagAuditEvents
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigs
import com.moneat.featureflags.models.FeatureFlagEnvironments
import com.moneat.featureflags.models.FeatureFlagSdkKeyRequest
import com.moneat.featureflags.models.FeatureFlagSdkKeys
import com.moneat.featureflags.models.FeatureFlagSegmentRequest
import com.moneat.featureflags.models.FeatureFlagSegments
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantRequest
import com.moneat.featureflags.models.FeatureFlagVariants
import com.moneat.featureflags.models.FeatureFlags
import com.moneat.featureflags.models.UpdateFeatureFlagConfigRequest
import com.moneat.featureflags.models.UpdateFeatureFlagRequest
import com.moneat.featureflags.services.FeatureFlagService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SERVICE_TEST_ORG_ID = 1
private const val SERVICE_TEST_USER_ID = 2
private const val DEFAULT_ENVIRONMENT_COUNT = 3
private const val CREATED_FLAG_CONFIG_COUNT = 3
private const val PRODUCTION_CONFIG_COUNT = 1

class FeatureFlagServiceTest {
    companion object {
        private var db: Database? = null
    }

    private lateinit var service: FeatureFlagService

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_feature_flag_service;MODE=MYSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(
            Users,
            Organizations,
            FeatureFlagEnvironments,
            FeatureFlags,
            FeatureFlagVariants,
            FeatureFlagEnvironmentConfigs,
            FeatureFlagSegments,
            FeatureFlagSdkKeys,
            FeatureFlagAuditEvents,
        )
        createSchema()
        seedActor()
        service = FeatureFlagService()
    }

    @Test
    fun `create update and snapshot flag lifecycle`() {
        val flag = service.createFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, booleanFlagRequest())

        assertEquals("checkout.enabled", flag.key)
        assertEquals(CREATED_FLAG_CONFIG_COUNT, flag.configs.size)
        assertEquals(listOf("off", "on"), flag.variants.map { it.key })

        val config = service.updateConfig(
            organizationId = SERVICE_TEST_ORG_ID,
            actorUserId = SERVICE_TEST_USER_ID,
            flagKey = "checkout.enabled",
            environmentKey = "production",
            request = UpdateFeatureFlagConfigRequest(
                enabled = true,
                defaultVariantKey = "on",
                offVariantKey = "off",
                rules = emptyRules(),
            )
        )
        assertNotNull(config)
        assertTrue(config.enabled)
        assertEquals(2, config.version)

        val filtered = service.listFlags(SERVICE_TEST_ORG_ID, "production")
        assertEquals(DEFAULT_ENVIRONMENT_COUNT, filtered.environments.size)
        assertEquals(PRODUCTION_CONFIG_COUNT, filtered.flags.single().configs.size)
        assertEquals("production", filtered.flags.single().configs.single().environmentKey)

        val snapshot = service.getSnapshot(SERVICE_TEST_ORG_ID, "production")
        assertNotNull(snapshot)
        assertTrue(snapshot.etag.startsWith("\"ff-"))
        assertEquals("production", snapshot.environment.key)
        assertEquals("checkout.enabled", snapshot.flags.single().key)
        assertTrue(snapshot.flags.single().config.enabled)

        val events = service.listAuditEvents(SERVICE_TEST_ORG_ID)
        assertEquals(listOf("config.updated", "flag.created"), events.map { it.eventType })
        assertNull(service.getFlag(SERVICE_TEST_ORG_ID, "missing"))
        assertNull(
            service.updateConfig(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, "missing", "production", configRequest())
        )
    }

    @Test
    fun `environment creation backfills existing flags`() {
        service.createFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, booleanFlagRequest())

        val environment = service.createEnvironment(
            SERVICE_TEST_ORG_ID,
            SERVICE_TEST_USER_ID,
            CreateFeatureFlagEnvironmentRequest("qa", "QA", "Release candidate checks")
        )

        assertEquals("qa", environment.key)
        assertEquals(DEFAULT_ENVIRONMENT_COUNT + 1, service.listEnvironments(SERVICE_TEST_ORG_ID).size)
        val flag = service.getFlag(SERVICE_TEST_ORG_ID, "checkout.enabled", "qa")
        assertNotNull(flag)
        assertEquals("qa", flag.configs.single().environmentKey)
    }

    @Test
    fun `segments and sdk keys support full lifecycle`() {
        service.createFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, booleanFlagRequest())
        val updated = service.updateFlag(
            SERVICE_TEST_ORG_ID,
            SERVICE_TEST_USER_ID,
            "checkout.enabled",
            UpdateFeatureFlagRequest(
                name = "Checkout Rollout",
                clientVisible = false,
                tags = listOf("checkout", "internal"),
                variants = booleanVariants(),
            )
        )
        assertNotNull(updated)
        assertEquals("Checkout Rollout", updated.name)
        assertEquals(listOf("checkout", "internal"), updated.tags)
        val blankNameUpdate = service.updateFlag(
            SERVICE_TEST_ORG_ID,
            SERVICE_TEST_USER_ID,
            "checkout.enabled",
            UpdateFeatureFlagRequest(name = "   ")
        )
        assertNotNull(blankNameUpdate)
        assertEquals("Checkout Rollout", blankNameUpdate.name)

        val segment = service.upsertSegment(
            SERVICE_TEST_ORG_ID,
            SERVICE_TEST_USER_ID,
            FeatureFlagSegmentRequest("beta_users", "Beta Users", conditions = emptyRules())
        )
        assertEquals("beta_users", segment.key)
        val renamed = service.upsertSegment(
            SERVICE_TEST_ORG_ID,
            SERVICE_TEST_USER_ID,
            FeatureFlagSegmentRequest("beta_users", "Invited Beta Users", conditions = emptyRules())
        )
        assertEquals("Invited Beta Users", renamed.name)
        assertEquals(1, service.listSegments(SERVICE_TEST_ORG_ID).size)
        assertTrue(service.deleteSegment(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, "beta_users"))
        assertFalse(service.deleteSegment(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, "beta_users"))
        assertTrue(service.listSegments(SERVICE_TEST_ORG_ID).isEmpty())

        val sdkKey = service.createSdkKey(
            SERVICE_TEST_ORG_ID,
            SERVICE_TEST_USER_ID,
            FeatureFlagSdkKeyRequest("production", "Browser SDK", "client")
        )
        assertEquals("client", sdkKey.keyType)
        assertEquals(1, service.listSdkKeys(SERVICE_TEST_ORG_ID).size)
        val principal = service.validateSdkKey(sdkKey.key)
        assertNotNull(principal)
        assertEquals("production", principal.environmentKey)
        assertTrue(service.revokeSdkKey(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, sdkKey.id))
        assertFalse(service.revokeSdkKey(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, sdkKey.id))
        assertNull(service.validateSdkKey(sdkKey.key))

        assertTrue(service.archiveFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, "checkout.enabled"))
        assertFalse(service.archiveFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, "checkout.enabled"))
        assertNull(service.getFlag(SERVICE_TEST_ORG_ID, "checkout.enabled"))
    }

    @Test
    fun `validation rejects invalid feature flag inputs`() {
        assertFailsWith<IllegalArgumentException> {
            service.createFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, booleanFlagRequest(key = " bad key"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, booleanFlagRequest(variants = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createFlag(
                SERVICE_TEST_ORG_ID,
                SERVICE_TEST_USER_ID,
                booleanFlagRequest(defaultVariantKey = "missing")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createFlag(
                SERVICE_TEST_ORG_ID,
                SERVICE_TEST_USER_ID,
                integerFlagRequest(variants = listOf(variant("one", JsonPrimitive("not-an-int"))))
            )
        }

        service.createFlag(SERVICE_TEST_ORG_ID, SERVICE_TEST_USER_ID, booleanFlagRequest())
        assertFailsWith<IllegalArgumentException> {
            service.updateFlag(
                SERVICE_TEST_ORG_ID,
                SERVICE_TEST_USER_ID,
                "checkout.enabled",
                UpdateFeatureFlagRequest(variants = listOf(variant("new", JsonPrimitive(true))))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createSdkKey(
                SERVICE_TEST_ORG_ID,
                SERVICE_TEST_USER_ID,
                FeatureFlagSdkKeyRequest("production", "Bad SDK", "mobile")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createEnvironment(
                SERVICE_TEST_ORG_ID,
                SERVICE_TEST_USER_ID,
                CreateFeatureFlagEnvironmentRequest("bad key", "Bad")
            )
        }
    }

    @Test
    fun `analytics returns empty metrics when clickhouse is unavailable`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.isInitialized() } returns false

            val analytics = service.analytics(SERVICE_TEST_ORG_ID, "production")

            assertEquals(0, analytics.evaluations)
            assertEquals(0, analytics.uniqueTargetingKeys)
            assertTrue(analytics.variants.isEmpty())
            assertTrue(analytics.trackingEvents.isEmpty())
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `analyticsWhere matches the wrapped UInt32 org for negative demo orgs only`() {
        // Demo org (-1) is stored wrapped in the UInt32 column, so it must be matched via toInt32(...).
        val demo = service.analyticsWhere(-1, "production", 24)
        assertTrue(demo.contains("toInt32(organization_id) = -1"))
        assertTrue(demo.contains("environment = 'production'"))

        // Real positive orgs keep the plain, index-friendly predicate.
        val real = service.analyticsWhere(SERVICE_TEST_ORG_ID, null, 24)
        assertTrue(real.contains("organization_id = $SERVICE_TEST_ORG_ID"))
        assertFalse(real.contains("toInt32("))
        assertFalse(real.contains("environment ="))
    }

    private fun createSchema() {
        transaction {
            SchemaUtils.create(Users, Organizations)
            featureFlagTableStatements.forEach { statement ->
                exec(statement)
            }
        }
    }

    private fun seedActor() {
        transaction {
            Users.insert {
                it[id] = SERVICE_TEST_USER_ID
                it[email] = "feature-flag-service@example.com"
                it[password_hash] = "hash"
            }
            Organizations.insert {
                it[id] = SERVICE_TEST_ORG_ID
                it[name] = "Feature Flag Service Org"
                it[slug] = "feature-flag-service-org"
            }
        }
    }

    private fun booleanFlagRequest(
        key: String = "checkout.enabled",
        variants: List<FeatureFlagVariantRequest> = booleanVariants(),
        defaultVariantKey: String = "on",
    ): CreateFeatureFlagRequest =
        CreateFeatureFlagRequest(
            key = key,
            name = "Checkout Enabled",
            description = "Controls the checkout rollout",
            valueType = FeatureFlagValueType.BOOLEAN,
            clientVisible = true,
            tags = listOf("checkout", "beta"),
            variants = variants,
            defaultVariantKey = defaultVariantKey,
            offVariantKey = "off",
        )

    private fun integerFlagRequest(
        variants: List<FeatureFlagVariantRequest>,
    ): CreateFeatureFlagRequest =
        CreateFeatureFlagRequest(
            key = "checkout.limit",
            name = "Checkout Limit",
            valueType = FeatureFlagValueType.INTEGER,
            variants = variants,
        )

    private fun booleanVariants(): List<FeatureFlagVariantRequest> =
        listOf(
            variant("off", JsonPrimitive(false)),
            variant("on", JsonPrimitive(true)),
        )

    private fun variant(key: String, value: JsonPrimitive): FeatureFlagVariantRequest =
        FeatureFlagVariantRequest(
            key = key,
            name = key.replaceFirstChar { it.uppercase() },
            value = value,
        )

    private fun configRequest(): UpdateFeatureFlagConfigRequest =
        UpdateFeatureFlagConfigRequest(enabled = true, defaultVariantKey = "on", offVariantKey = "off")

    private fun emptyRules(): JsonObject = JsonObject(mapOf("rules" to JsonArray(emptyList())))
}

private val featureFlagTableStatements = listOf(
    """
    CREATE TABLE IF NOT EXISTS feature_flag_environments (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        organization_id INT NOT NULL,
        "key" VARCHAR(64) NOT NULL,
        "name" VARCHAR(255) NOT NULL,
        description TEXT NULL,
        version INT DEFAULT 1 NOT NULL,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_flags (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        organization_id INT NOT NULL,
        "key" VARCHAR(255) NOT NULL,
        "name" VARCHAR(255) NOT NULL,
        description TEXT NULL,
        value_type VARCHAR(32) NOT NULL,
        client_visible BOOLEAN DEFAULT FALSE NOT NULL,
        tags TEXT DEFAULT '[]' NOT NULL,
        created_by INT NULL,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL,
        archived_at TIMESTAMP NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_flag_variants (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        flag_id INT NOT NULL,
        "key" VARCHAR(255) NOT NULL,
        "name" VARCHAR(255) NOT NULL,
        value_json TEXT NOT NULL,
        sort_order INT DEFAULT 0 NOT NULL,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_flag_environment_configs (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        flag_id INT NOT NULL,
        environment_id INT NOT NULL,
        enabled BOOLEAN DEFAULT FALSE NOT NULL,
        default_variant_key VARCHAR(255) NULL,
        off_variant_key VARCHAR(255) NULL,
        rules_json TEXT DEFAULT '{"rules":[]}' NOT NULL,
        version INT DEFAULT 1 NOT NULL,
        updated_by INT NULL,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_flag_segments (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        organization_id INT NOT NULL,
        "key" VARCHAR(255) NOT NULL,
        "name" VARCHAR(255) NOT NULL,
        description TEXT NULL,
        conditions_json TEXT DEFAULT '{"all":[]}' NOT NULL,
        created_at TIMESTAMP NOT NULL,
        updated_at TIMESTAMP NOT NULL,
        archived_at TIMESTAMP NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_flag_sdk_keys (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        organization_id INT NOT NULL,
        environment_id INT NOT NULL,
        "name" VARCHAR(255) NOT NULL,
        key_type VARCHAR(16) NOT NULL,
        key_hash VARCHAR(255) NOT NULL,
        key_prefix VARCHAR(16) NOT NULL,
        created_by INT NULL,
        created_at TIMESTAMP NOT NULL,
        last_used_at TIMESTAMP NULL,
        revoked_at TIMESTAMP NULL,
        is_active BOOLEAN DEFAULT TRUE NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_flag_audit_events (
        id INT AUTO_INCREMENT PRIMARY KEY,
        resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
        organization_id INT NOT NULL,
        environment_id INT NULL,
        flag_id INT NULL,
        actor_user_id INT NULL,
        event_type VARCHAR(64) NOT NULL,
        before_json TEXT NULL,
        after_json TEXT NULL,
        created_at TIMESTAMP NOT NULL
    )
    """.trimIndent(),
)
