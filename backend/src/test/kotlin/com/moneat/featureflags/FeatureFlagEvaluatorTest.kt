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
import kotlinx.serialization.json.JsonArray
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
    ): FeatureFlagEnvironmentConfigSnapshot {
        return FeatureFlagEnvironmentConfigSnapshot(
            organizationId = ORGANIZATION_ID,
            environment = FeatureFlagEnvironmentSnapshot(1, ENVIRONMENT_KEY, "Production", 1),
            etag = "\"test\"",
            flags = listOf(
                FeatureFlagSnapshotFlag(
                    id = 1,
                    key = FLAG_KEY,
                    valueType = FeatureFlagValueType.BOOLEAN,
                    clientVisible = clientVisible,
                    variants = listOf(
                        FeatureFlagVariantSnapshot("off", "Off", JsonPrimitive(false), 0),
                        FeatureFlagVariantSnapshot("on", "On", JsonPrimitive(true), 1),
                    ),
                    config = config,
                )
            ),
            segments = segments,
        )
    }

    private fun context(
        targetingKey: String,
        vararg attributes: Pair<String, kotlinx.serialization.json.JsonElement>,
    ) = buildJsonObject {
        put("targetingKey", JsonPrimitive(targetingKey))
        attributes.forEach { (key, value) -> put(key, value) }
    }
}
