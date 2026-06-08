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

package com.moneat.mcp.tools

import com.moneat.featureflags.models.FeatureFlagAuditEvents
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigs
import com.moneat.featureflags.models.FeatureFlagEnvironments
import com.moneat.featureflags.models.FeatureFlagSdkKeys
import com.moneat.featureflags.models.FeatureFlagSegments
import com.moneat.featureflags.models.FeatureFlagVariants
import com.moneat.featureflags.models.FeatureFlags
import com.moneat.featureflags.services.FeatureFlagService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val TOOL_TEST_ORG_ID = 1
private const val TOOL_TEST_USER_ID = 2
private const val TOOL_TEST_TOKEN_ID = 3
private const val DEFAULT_ENVIRONMENT_COUNT = 3
private const val CREATED_FLAG_COUNT = 1L
private const val VARIANT_COUNT = 2

class FeatureFlagToolTest {
    companion object {
        private var db: Database? = null
    }

    private val context = McpContext(
        organizationId = TOOL_TEST_ORG_ID,
        userId = TOOL_TEST_USER_ID,
        tokenId = TOOL_TEST_TOKEN_ID,
        scopes = setOf("event:read", "project:read", "project:write"),
        sessionId = "feature-flag-tool-test",
    )

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_feature_flag_tool;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
    }

    @Test
    fun `create feature flag tool creates flag configs and audit event`() = runBlocking {
        val registry = registry()
        val result = registry.callTool("create_feature_flag", createFlagArgs(), context)

        assertFalse(result.isError, result.content.first().text.orEmpty())
        val response = toolJson.parseToJsonElement(result.content.first().text.orEmpty()).jsonObject
        assertEquals("checkout.enabled", response["key"]!!.jsonPrimitive.content)
        assertEquals("BOOLEAN", response["valueType"]!!.jsonPrimitive.content)
        assertEquals(VARIANT_COUNT, response["variants"]!!.jsonArray.size)

        transaction {
            assertEquals(
                CREATED_FLAG_COUNT,
                FeatureFlags.selectAll().where { FeatureFlags.key eq "checkout.enabled" }.count()
            )
            assertEquals(DEFAULT_ENVIRONMENT_COUNT.toLong(), FeatureFlagEnvironments.selectAll().count())
            assertEquals(DEFAULT_ENVIRONMENT_COUNT.toLong(), FeatureFlagEnvironmentConfigs.selectAll().count())
            assertEquals(1L, FeatureFlagAuditEvents.selectAll().count())
        }
    }

    @Test
    fun `list feature flags tool returns created flags`() = runBlocking {
        val registry = registry()
        registry.callTool("create_feature_flag", createFlagArgs(), context)

        val result = registry.callTool(
            "list_feature_flags",
            JsonObject(mapOf("environment" to JsonPrimitive("production"))),
            context,
        )

        assertFalse(result.isError, result.content.first().text.orEmpty())
        val response = toolJson.parseToJsonElement(result.content.first().text.orEmpty()).jsonObject
        val flags = response["flags"]!!.jsonArray
        assertEquals(1, flags.size)
        assertEquals("checkout.enabled", flags.first().jsonObject["key"]!!.jsonPrimitive.content)
    }

    // ──── Environments ────

    @Test
    fun `environment tools create and list custom environments`() = runBlocking {
        val registry = registry()

        val create = registry.callTool(
            "create_feature_flag_environment",
            JsonObject(
                mapOf(
                    "key" to JsonPrimitive("qa"),
                    "name" to JsonPrimitive("QA"),
                    "description" to JsonPrimitive("Quality assurance"),
                )
            ),
            context,
        )

        assertFalse(create.isError, create.content.first().text.orEmpty())
        val list = registry.callTool("list_feature_flag_environments", JsonObject(emptyMap()), context)
        val environments = toolJson.parseToJsonElement(list.content.first().text.orEmpty())
            .jsonObject["environments"]!!
            .jsonArray
        assertEquals(DEFAULT_ENVIRONMENT_COUNT + 1, environments.size)
    }

    // ──── Flags ────

    @Test
    fun `flag management tools get update configure and archive flags`() = runBlocking {
        val registry = registry()
        registry.callTool("create_feature_flag", createFlagArgs(), context)

        val get = registry.callTool(
            "get_feature_flag",
            JsonObject(mapOf("flag_key" to JsonPrimitive("checkout.enabled"))),
            context,
        )
        assertFalse(get.isError, get.content.first().text.orEmpty())

        val update = registry.callTool(
            "update_feature_flag",
            JsonObject(
                mapOf(
                    "flag_key" to JsonPrimitive("checkout.enabled"),
                    "name" to JsonPrimitive("Checkout Gate"),
                    "client_visible" to JsonPrimitive(false),
                    "tags" to JsonArray(listOf(JsonPrimitive("release"))),
                )
            ),
            context,
        )
        assertFalse(update.isError, update.content.first().text.orEmpty())
        val updated = toolJson.parseToJsonElement(update.content.first().text.orEmpty()).jsonObject
        assertEquals("Checkout Gate", updated["name"]!!.jsonPrimitive.content)

        val config = registry.callTool(
            "update_feature_flag_config",
            JsonObject(
                mapOf(
                    "flag_key" to JsonPrimitive("checkout.enabled"),
                    "environment" to JsonPrimitive("production"),
                    "enabled" to JsonPrimitive(true),
                    "default_variant_key" to JsonPrimitive("on"),
                    "off_variant_key" to JsonPrimitive("off"),
                    "rules" to JsonObject(mapOf("rules" to JsonArray(emptyList()))),
                )
            ),
            context,
        )
        assertFalse(config.isError, config.content.first().text.orEmpty())
        val configResponse = toolJson.parseToJsonElement(config.content.first().text.orEmpty()).jsonObject
        assertEquals("true", configResponse["enabled"]!!.jsonPrimitive.content)

        val delete = registry.callTool(
            "delete_feature_flag",
            JsonObject(mapOf("flag_key" to JsonPrimitive("checkout.enabled"))),
            context,
        )
        assertFalse(delete.isError, delete.content.first().text.orEmpty())

        val list = registry.callTool("list_feature_flags", JsonObject(emptyMap()), context)
        val flags = toolJson.parseToJsonElement(list.content.first().text.orEmpty()).jsonObject["flags"]!!.jsonArray
        assertEquals(0, flags.size)
    }

    // ──── Segments ────

    @Test
    fun `segment tools upsert list and archive segments`() = runBlocking {
        val registry = registry()
        val segmentArgs = JsonObject(
            mapOf(
                "key" to JsonPrimitive("beta-users"),
                "name" to JsonPrimitive("Beta Users"),
                "conditions" to JsonObject(mapOf("all" to JsonArray(emptyList()))),
            )
        )

        val upsert = registry.callTool("upsert_feature_flag_segment", segmentArgs, context)
        assertFalse(upsert.isError, upsert.content.first().text.orEmpty())

        val list = registry.callTool("list_feature_flag_segments", JsonObject(emptyMap()), context)
        val segments = toolJson.parseToJsonElement(list.content.first().text.orEmpty())
            .jsonObject["segments"]!!
            .jsonArray
        assertEquals(1, segments.size)

        val delete = registry.callTool(
            "delete_feature_flag_segment",
            JsonObject(mapOf("segment_key" to JsonPrimitive("beta-users"))),
            context,
        )
        assertFalse(delete.isError, delete.content.first().text.orEmpty())
    }

    // ──── SDK Keys ────

    @Test
    fun `sdk key tools create list and revoke keys`() = runBlocking {
        val registry = registry()

        val create = registry.callTool(
            "create_feature_flag_sdk_key",
            JsonObject(
                mapOf(
                    "environment_key" to JsonPrimitive("production"),
                    "name" to JsonPrimitive("Server SDK"),
                    "key_type" to JsonPrimitive("server"),
                )
            ),
            context,
        )

        assertFalse(create.isError, create.content.first().text.orEmpty())
        val created = toolJson.parseToJsonElement(create.content.first().text.orEmpty()).jsonObject
        assertEquals("server", created["keyType"]!!.jsonPrimitive.content)

        val list = registry.callTool("list_feature_flag_sdk_keys", JsonObject(emptyMap()), context)
        val keys = toolJson.parseToJsonElement(list.content.first().text.orEmpty()).jsonObject["keys"]!!.jsonArray
        assertEquals(1, keys.size)

        val revoke = registry.callTool(
            "revoke_feature_flag_sdk_key",
            JsonObject(mapOf("sdk_key_id" to created["id"]!!)),
            context,
        )
        assertFalse(revoke.isError, revoke.content.first().text.orEmpty())
    }

    // ──── Audit / Analytics ────

    @Test
    fun `audit and analytics tools return feature flag metadata`() = runBlocking {
        val registry = registry()
        registry.callTool("create_feature_flag", createFlagArgs(), context)

        val audit = registry.callTool(
            "list_feature_flag_audit_events",
            JsonObject(mapOf("limit" to JsonPrimitive(5))),
            context,
        )
        val events = toolJson.parseToJsonElement(audit.content.first().text.orEmpty()).jsonObject["events"]!!.jsonArray
        assertEquals(1, events.size)

        val analytics = registry.callTool("get_feature_flag_analytics", JsonObject(emptyMap()), context)
        assertFalse(analytics.isError, analytics.content.first().text.orEmpty())
    }

    private fun createSchema() {
        transaction {
            SchemaUtils.create(Users, Organizations)
            featureFlagTableStatements.forEach { statement ->
                exec(statement)
            }
        }
    }

    private fun registry(): McpToolRegistry {
        val service = FeatureFlagService()
        return McpToolRegistry().also {
            it.register(ListFeatureFlagEnvironmentsTool(service))
            it.register(CreateFeatureFlagEnvironmentTool(service))
            it.register(ListFeatureFlagsTool(service))
            it.register(CreateFeatureFlagTool(service))
            it.register(GetFeatureFlagTool(service))
            it.register(UpdateFeatureFlagTool(service))
            it.register(DeleteFeatureFlagTool(service))
            it.register(UpdateFeatureFlagConfigTool(service))
            it.register(ListFeatureFlagSegmentsTool(service))
            it.register(UpsertFeatureFlagSegmentTool(service))
            it.register(DeleteFeatureFlagSegmentTool(service))
            it.register(ListFeatureFlagSdkKeysTool(service))
            it.register(CreateFeatureFlagSdkKeyTool(service))
            it.register(RevokeFeatureFlagSdkKeyTool(service))
            it.register(ListFeatureFlagAuditEventsTool(service))
            it.register(GetFeatureFlagAnalyticsTool(service))
        }
    }

    private fun createFlagArgs(): JsonObject =
        JsonObject(
            mapOf(
                "key" to JsonPrimitive("checkout.enabled"),
                "name" to JsonPrimitive("Checkout Enabled"),
                "value_type" to JsonPrimitive("BOOLEAN"),
                "client_visible" to JsonPrimitive(true),
                "tags" to JsonArray(listOf(JsonPrimitive("checkout"), JsonPrimitive("beta"))),
                "default_variant_key" to JsonPrimitive("on"),
                "off_variant_key" to JsonPrimitive("off"),
                "variants" to JsonArray(
                    listOf(
                        variant("off", false),
                        variant("on", true),
                    )
                ),
            )
        )

    private fun variant(key: String, value: Boolean): JsonObject =
        JsonObject(
            mapOf(
                "key" to JsonPrimitive(key),
                "name" to JsonPrimitive(key.replaceFirstChar { it.uppercase() }),
                "value" to JsonPrimitive(value),
            )
        )

    private fun seedActor() {
        transaction {
            Users.insert {
                it[id] = TOOL_TEST_USER_ID
                it[email] = "feature-flag-tool@example.com"
                it[password_hash] = "hash"
            }
            Organizations.insert {
                it[id] = TOOL_TEST_ORG_ID
                it[name] = "Feature Flag Tool Org"
                it[slug] = "feature-flag-tool-org"
            }
        }
    }

    private val featureFlagTableStatements = listOf(
        """
        CREATE TABLE IF NOT EXISTS feature_flag_environments (
            id INT AUTO_INCREMENT PRIMARY KEY,
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
}
