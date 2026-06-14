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

import com.moneat.featureflags.models.FLAG_KEY_TYPE_CLIENT
import com.moneat.featureflags.models.FLAG_KEY_TYPE_SERVER
import com.moneat.featureflags.models.FeatureFlagConfigSnapshot
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigSnapshot
import com.moneat.featureflags.models.FeatureFlagEnvironmentSnapshot
import com.moneat.featureflags.models.FeatureFlagSegmentSnapshot
import com.moneat.featureflags.models.FeatureFlagSnapshotFlag
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantSnapshot
import com.moneat.featureflags.services.FeatureFlagEvaluator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val ORGANIZATION_ID = 42
private const val FLAG_KEY = "checkout.enabled"
private const val ENVIRONMENT_KEY = "production"

class FeatureFlagEvaluatorTest {
    private val evaluator = FeatureFlagEvaluator()

    @Test
    fun `disabled flag returns off variant with disabled reason`() {
        val snapshot = snapshot(
            config = FeatureFlagConfigSnapshot(
                enabled = false,
                defaultVariantKey = "on",
                offVariantKey = "off",
                rules = buildJsonObject { put("rules", JsonArray(emptyList())) },
                version = 1,
            )
        )

        val response = evaluator.evaluate(snapshot, FLAG_KEY, context("user-1"), null, FLAG_KEY_TYPE_SERVER)

        assertEquals("DISABLED", response.reason)
        assertEquals("off", response.variant)
        assertEquals(false, response.value.jsonPrimitive.boolean)
    }

    @Test
    fun `client key cannot evaluate server-only flag`() {
        val snapshot = snapshot(clientVisible = false)

        val response = evaluator.evaluate(snapshot, FLAG_KEY, context("user-1"), null, FLAG_KEY_TYPE_CLIENT)

        assertEquals("ERROR", response.reason)
        assertEquals("FLAG_NOT_FOUND", response.errorCode)
    }

    @Test
    fun `segment reference matches targeting rule`() {
        val rules = buildJsonObject {
            put(
                "rules",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", JsonPrimitive("beta-rule"))
                            put(
                                "conditions",
                                buildJsonObject {
                                    put(
                                        "all",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("segment", JsonPrimitive("beta"))
                                                }
                                            )
                                        )
                                    )
                                }
                            )
                            put("serve", buildJsonObject { put("variant", JsonPrimitive("on")) })
                        }
                    )
                )
            )
        }
        val segment = FeatureFlagSegmentSnapshot(
            key = "beta",
            conditions = buildJsonObject {
                put(
                    "all",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("attribute", JsonPrimitive("email"))
                                put("op", JsonPrimitive("ends_with"))
                                put("value", JsonPrimitive("@moneat.test"))
                            }
                        )
                    )
                )
            }
        )
        val snapshot = snapshot(
            config = FeatureFlagConfigSnapshot(true, "off", "off", rules, 2),
            segments = listOf(segment)
        )

        val response = evaluator.evaluate(
            snapshot,
            FLAG_KEY,
            context("user-1", "email" to JsonPrimitive("dev@moneat.test")),
            null,
            FLAG_KEY_TYPE_SERVER
        )

        assertEquals("TARGETING_MATCH", response.reason)
        assertEquals("on", response.variant)
    }

    @Test
    fun `rollout bucket is stable and environment scoped`() {
        val first = evaluator.bucket(ORGANIZATION_ID, ENVIRONMENT_KEY, FLAG_KEY, "r1", "user-1")
        val second = evaluator.bucket(ORGANIZATION_ID, ENVIRONMENT_KEY, FLAG_KEY, "r1", "user-1")
        val differentEnvironment = evaluator.bucket(ORGANIZATION_ID, "staging", FLAG_KEY, "r1", "user-1")

        assertEquals(first, second)
        assertNotEquals(first, differentEnvironment)
    }

    @Test
    fun `type mismatch maps to OpenFeature error`() {
        val snapshot = snapshot()

        val response = evaluator.evaluate(
            snapshot,
            FLAG_KEY,
            context("user-1"),
            FeatureFlagValueType.STRING,
            FLAG_KEY_TYPE_SERVER
        )

        assertEquals("ERROR", response.reason)
        assertEquals("TYPE_MISMATCH", response.errorCode)
    }

    @Test
    fun `bulk evaluation preserves requested keys and returns missing flag failures`() {
        val snapshot = snapshot()

        val responses = evaluator.evaluateAll(
            snapshot = snapshot,
            context = context("user-1"),
            keyType = FLAG_KEY_TYPE_SERVER,
            flagKeys = listOf(FLAG_KEY, "missing.flag")
        )

        assertEquals(listOf(FLAG_KEY, "missing.flag"), responses.map { it.key })
        assertEquals("FLAG_NOT_FOUND", responses[1].errorCode)
        assertEquals("Feature flag was not found", responses[1].errorDetails)
    }

    @Test
    fun `rules require a targeting key before evaluating conditions`() {
        val response = evaluator.evaluate(
            snapshot = snapshot(
                config = enabledConfig(rulesWithCondition(condition("country", "eq", stringValue("US"))))
            ),
            flagKey = FLAG_KEY,
            context = buildJsonObject { put("country", stringValue("US")) },
            expectedType = null,
            keyType = FLAG_KEY_TYPE_SERVER,
        )

        assertEquals("ERROR", response.reason)
        assertEquals("TARGETING_KEY_MISSING", response.errorCode)
        assertEquals("off", response.variant)
    }

    @Test
    fun `fallthrough serve is used when no rule matches`() {
        val response = evaluateWithRules(
            rulesWithCondition(
                condition("country", "eq", stringValue("US")),
                fallthrough = buildJsonObject { put("variant", stringValue("on")) },
            ),
            context("user-1", "country" to stringValue("CA")),
        )

        assertEquals("DEFAULT", response.reason)
        assertEquals("on", response.variant)
    }

    @Test
    fun `rollout serve returns split variant when allocation includes bucket`() {
        val response = evaluateWithRules(
            rulesWithCondition(
                condition("country", "eq", stringValue("US")),
                serve = buildJsonObject {
                    put("type", stringValue("rollout"))
                    put(
                        "allocations",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("variant", stringValue("on"))
                                    put("weight", JsonPrimitive(100))
                                }
                            )
                        )
                    )
                }
            ),
            context("user-1", "country" to stringValue("US")),
        )

        assertEquals("SPLIT", response.reason)
        assertEquals("on", response.variant)
    }

    @Test
    fun `rollout serve falls back to default without allocations`() {
        val response = evaluateWithRules(
            rulesWithCondition(
                condition("country", "eq", stringValue("US")),
                serve = buildJsonObject { put("type", stringValue("rollout")) },
            ),
            context("user-1", "country" to stringValue("US")),
        )

        assertEquals("DEFAULT", response.reason)
        assertEquals("off", response.variant)
    }

    @Test
    fun `rollout bucketing survives snapshot serialization`() {
        val rules = rulesWithCondition(
            condition("country", "eq", stringValue("US")),
            serve = rolloutServe(
                RolloutAllocationFixture("on", 50),
                RolloutAllocationFixture("off", 50),
            ),
        )
        val snapshot = snapshot(config = enabledConfig(rules))
        val restored = Json.decodeFromString<FeatureFlagEnvironmentConfigSnapshot>(
            Json.encodeToString(snapshot)
        )
        val context = context("user-serialize", "country" to stringValue("US"))

        val original = evaluator.evaluate(snapshot, FLAG_KEY, context, null, FLAG_KEY_TYPE_SERVER)
        val decoded = evaluator.evaluate(restored, FLAG_KEY, context, null, FLAG_KEY_TYPE_SERVER)

        assertEquals(original.variant, decoded.variant)
        assertEquals(original.reason, decoded.reason)
    }

    @Test
    fun `attribute targeting supports comparison operators`() {
        val matchingCases = listOf(
            condition("country", "exists") to context("user-1", "country" to stringValue("US")),
            condition("missing", "not_exists") to context("user-1", "country" to stringValue("US")),
            condition("age", "eq", JsonPrimitive(42)) to context("user-1", "age" to JsonPrimitive(42.0)),
            condition("country", "neq", stringValue("CA")) to context("user-1", "country" to stringValue("US")),
            condition("country", "in", JsonArray(listOf(stringValue("US"), stringValue("CA")))) to
                context("user-1", "country" to stringValue("US")),
            condition("country", "not_in", JsonArray(listOf(stringValue("GB"), stringValue("CA")))) to
                context("user-1", "country" to stringValue("US")),
            condition("email", "contains", stringValue("@moneat")) to
                context("user-1", "email" to stringValue("dev@moneat.test")),
            condition("email", "starts_with", stringValue("dev")) to
                context("user-1", "email" to stringValue("dev@moneat.test")),
            condition("score", "gt", JsonPrimitive(10)) to context("user-1", "score" to JsonPrimitive(11)),
            condition("score", "gte", JsonPrimitive(10)) to context("user-1", "score" to JsonPrimitive(10)),
            condition("score", "lt", JsonPrimitive(10)) to context("user-1", "score" to JsonPrimitive(9)),
            condition("score", "lte", JsonPrimitive(10)) to context("user-1", "score" to JsonPrimitive(10)),
            condition("app.version", "semver_gt", stringValue("1.2.2")) to
                context("user-1", "app" to buildJsonObject { put("version", stringValue("1.2.3")) }),
            condition("app.version", "semver_gte", stringValue("1.2.3")) to
                context("user-1", "app" to buildJsonObject { put("version", stringValue("1.2.3")) }),
            condition("app.version", "semver_lt", stringValue("1.2.4")) to
                context("user-1", "app" to buildJsonObject { put("version", stringValue("1.2.3")) }),
            condition("app.version", "semver_lte", stringValue("1.2.3")) to
                context("user-1", "app" to buildJsonObject { put("version", stringValue("1.2.3")) }),
            condition("country", "eq", stringValue("US"), operatorField = "operator") to
                context("user-1", "country" to stringValue("US")),
        )

        matchingCases.forEach { (condition, context) ->
            val response = evaluateWithRules(rulesWithCondition(condition), context)
            assertEquals("on", response.variant, condition.toString())
        }
    }

    @Test
    fun `non matching conditions return default variant`() {
        val nonMatchingCases = listOf(
            condition("country", "exists") to context("user-1"),
            condition("country", "not_exists") to context("user-1", "country" to stringValue("US")),
            condition("country", "in", stringValue("US")) to context("user-1", "country" to stringValue("US")),
            condition("country", "not_in", stringValue("US")) to context("user-1", "country" to stringValue("US")),
            condition("email", "contains", stringValue("@moneat")) to context("user-1", "email" to JsonPrimitive(5)),
            condition("email", "contains") to context("user-1", "email" to stringValue("dev@moneat.test")),
            condition("email", "starts_with") to context("user-1", "email" to stringValue("dev@moneat.test")),
            condition("email", "ends_with") to context("user-1", "email" to stringValue("dev@moneat.test")),
            condition("score", "gt", stringValue("not-a-number")) to context("user-1", "score" to JsonPrimitive(11)),
            condition("app.version", "semver_gt", stringValue("1.2.3")) to
                context("user-1", "app" to buildJsonObject { put("version", stringValue("bad")) }),
            condition("country", "unknown", stringValue("US")) to context("user-1", "country" to stringValue("US")),
        )

        nonMatchingCases.forEach { (condition, context) ->
            val response = evaluateWithRules(rulesWithCondition(condition), context)
            assertEquals("off", response.variant, condition.toString())
        }
    }

    @Test
    fun `any conditions match when one branch matches`() {
        val rules = rulesWithCondition(
            buildJsonObject {
                put(
                    "any",
                    JsonArray(
                        listOf(
                            condition("country", "eq", stringValue("CA")),
                            condition("country", "eq", stringValue("US")),
                        )
                    )
                )
            }
        )

        val response = evaluateWithRules(rules, context("user-1", "country" to stringValue("US")))

        assertEquals("TARGETING_MATCH", response.reason)
        assertEquals("on", response.variant)
    }

    @Test
    fun `invalid rules and variants surface safe defaults`() {
        val missingVariant = evaluateWithRules(
            rulesWithCondition(condition("country", "eq", stringValue("US")), serve = stringValue("missing")),
            context("user-1", "country" to stringValue("US")),
        )
        val badVariantType = evaluator.evaluate(
            snapshot = snapshot(
                config = enabledConfig(rulesWithCondition(condition("country", "eq", stringValue("US")))),
                onValue = stringValue("not-a-boolean"),
            ),
            flagKey = FLAG_KEY,
            context = context("user-1", "country" to stringValue("US")),
            expectedType = null,
            keyType = FLAG_KEY_TYPE_SERVER,
        )
        val invalidRule = evaluateWithRules(
            buildJsonObject { put("rules", JsonArray(listOf(stringValue("not-an-object")))) },
            context("user-1", "country" to stringValue("US")),
        )

        assertEquals("off", missingVariant.variant)
        assertEquals("TYPE_MISMATCH", badVariantType.errorCode)
        assertEquals("off", invalidRule.variant)
    }

    private fun snapshot(
        config: FeatureFlagConfigSnapshot = FeatureFlagConfigSnapshot(
            enabled = true,
            defaultVariantKey = "on",
            offVariantKey = "off",
            rules = buildJsonObject { put("rules", JsonArray(emptyList())) },
            version = 1,
        ),
        clientVisible: Boolean = true,
        segments: List<FeatureFlagSegmentSnapshot> = emptyList(),
        onValue: JsonElement = JsonPrimitive(true),
    ): FeatureFlagEnvironmentConfigSnapshot {
        return FeatureFlagEnvironmentConfigSnapshot(
            organizationResourceId = "11111111-1111-1111-1111-111111111111",
            environment = FeatureFlagEnvironmentSnapshot(
                id = "22222222-2222-2222-2222-222222222222",
                key = ENVIRONMENT_KEY,
                name = "Production",
                version = 1,
                internalId = 1,
            ),
            etag = "\"test\"",
            flags = listOf(
                FeatureFlagSnapshotFlag(
                    id = "33333333-3333-3333-3333-333333333333",
                    key = FLAG_KEY,
                    valueType = FeatureFlagValueType.BOOLEAN,
                    clientVisible = clientVisible,
                    variants = listOf(
                        FeatureFlagVariantSnapshot("off", "Off", JsonPrimitive(false), 0),
                        FeatureFlagVariantSnapshot("on", "On", onValue, 1),
                    ),
                    config = config,
                    internalId = 1,
                )
            ),
            segments = segments,
            organizationId = ORGANIZATION_ID,
        )
    }

    private fun context(
        targetingKey: String,
        vararg attributes: Pair<String, kotlinx.serialization.json.JsonElement>,
    ) = buildJsonObject {
        put("targetingKey", JsonPrimitive(targetingKey))
        attributes.forEach { (key, value) -> put(key, value) }
    }

    private fun evaluateWithRules(
        rules: JsonObject,
        context: JsonObject,
    ) = evaluator.evaluate(
        snapshot = snapshot(config = enabledConfig(rules)),
        flagKey = FLAG_KEY,
        context = context,
        expectedType = null,
        keyType = FLAG_KEY_TYPE_SERVER,
    )

    private fun enabledConfig(rules: JsonObject): FeatureFlagConfigSnapshot =
        FeatureFlagConfigSnapshot(enabled = true, defaultVariantKey = "off", offVariantKey = "off", rules = rules, 2)

    private fun rulesWithCondition(
        condition: JsonObject,
        serve: JsonElement = buildJsonObject { put("variant", stringValue("on")) },
        fallthrough: JsonElement? = null,
    ): JsonObject =
        buildJsonObject {
            put(
                "rules",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("conditions", condition)
                            put("serve", serve)
                        }
                    )
                )
            )
            fallthrough?.let { put("fallthrough", it) }
        }

    private data class RolloutAllocationFixture(val variant: String, val weight: Int)

    private fun rolloutServe(vararg allocations: RolloutAllocationFixture): JsonObject =
        buildJsonObject {
            put("type", stringValue("rollout"))
            put(
                "allocations",
                JsonArray(
                    allocations.map { allocation ->
                        buildJsonObject {
                            put("variant", stringValue(allocation.variant))
                            put("weight", JsonPrimitive(allocation.weight))
                        }
                    }
                )
            )
        }

    private fun condition(
        attribute: String,
        operator: String,
        value: JsonElement? = null,
        operatorField: String = "op",
    ): JsonObject =
        buildJsonObject {
            put("attribute", stringValue(attribute))
            put(operatorField, stringValue(operator))
            value?.let { put("value", it) }
        }

    private fun stringValue(value: String): JsonPrimitive = JsonPrimitive(value)
}
