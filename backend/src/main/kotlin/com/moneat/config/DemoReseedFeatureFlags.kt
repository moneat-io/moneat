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

@file:Suppress("MagicNumber")

package com.moneat.config

import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import mu.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

// ── Feature Flag Demo Data ─────────────────────────────────────────────
//
// Postgres (org -1): flag definitions, variants, and per-environment configs so the
// Feature Flags management UI (and the #480 MCP flag tools) have content. The three
// default environments (production/staging/development) are auto-seeded for every org by
// V108__feature_flags.sql, so we only seed flags/variants/configs here.
//
// ClickHouse (org -1): evaluation + tracking events spread over the last 7 days relative to
// now() so the flag analytics view renders. The *_hourly aggregates fill automatically via
// the materialized views defined in V40__add_feature_flag_events.sql.

/** Demo org id for the UInt32-keyed feature-flag ClickHouse tables. */
private const val FF_ORG = "toUInt32(-1)"

/** One demo flag with its variants and per-environment enablement. */
private data class DemoFlagSpec(
    val key: String,
    val name: String,
    val description: String,
    val valueType: String,
    val clientVisible: Boolean,
    val tags: List<String>,
    /** variant key -> (display name, JSON value literal). First entry is the default. */
    val variants: List<Triple<String, String, String>>,
    val offVariantKey: String,
    /** env key -> enabled. Missing envs default to disabled. */
    val enabledByEnv: Map<String, Boolean>,
    /** Weighted variant keys used to shape evaluation analytics (repeat to bias share). */
    val evalVariantWeights: List<String>,
)

private val demoFlags: List<DemoFlagSpec> = listOf(
    DemoFlagSpec(
        key = "new-checkout-flow",
        name = "New checkout flow",
        description = "Rolls out the redesigned single-page checkout.",
        valueType = "BOOLEAN",
        clientVisible = true,
        tags = listOf("checkout", "growth"),
        variants = listOf(
            Triple("on", "On", "true"),
            Triple("off", "Off", "false"),
        ),
        offVariantKey = "off",
        enabledByEnv = mapOf("production" to true, "staging" to true, "development" to true),
        evalVariantWeights = listOf("on", "on", "on", "off"),
    ),
    DemoFlagSpec(
        key = "recommendations-v2",
        name = "Recommendations v2",
        description = "Switches product recommendations to the v2 ranking model.",
        valueType = "BOOLEAN",
        clientVisible = false,
        tags = listOf("ml", "catalog"),
        variants = listOf(
            Triple("on", "On", "true"),
            Triple("off", "Off", "false"),
        ),
        offVariantKey = "off",
        enabledByEnv = mapOf("production" to true, "staging" to true, "development" to true),
        evalVariantWeights = listOf("on", "off"),
    ),
    DemoFlagSpec(
        key = "dark-mode",
        name = "Dark mode",
        description = "Enables the dark theme in the mobile apps.",
        valueType = "BOOLEAN",
        clientVisible = true,
        tags = listOf("ui"),
        variants = listOf(
            Triple("on", "On", "true"),
            Triple("off", "Off", "false"),
        ),
        offVariantKey = "off",
        enabledByEnv = mapOf("production" to true, "staging" to true, "development" to true),
        evalVariantWeights = listOf("on", "on", "off"),
    ),
    DemoFlagSpec(
        key = "checkout-button-color",
        name = "Checkout button color",
        description = "A/B test for the primary checkout call-to-action color.",
        valueType = "STRING",
        clientVisible = true,
        tags = listOf("experiment", "checkout"),
        variants = listOf(
            Triple("control", "Control (gray)", "\"gray\""),
            Triple("blue", "Blue", "\"blue\""),
            Triple("green", "Green", "\"green\""),
        ),
        offVariantKey = "control",
        enabledByEnv = mapOf("production" to true, "staging" to true, "development" to true),
        evalVariantWeights = listOf("control", "blue", "green"),
    ),
    DemoFlagSpec(
        key = "payments-provider",
        name = "Payments provider",
        description = "Selects the active payment service provider.",
        valueType = "STRING",
        clientVisible = false,
        tags = listOf("payments", "ops"),
        variants = listOf(
            Triple("stripe", "Stripe", "\"stripe\""),
            Triple("adyen", "Adyen", "\"adyen\""),
            Triple("braintree", "Braintree", "\"braintree\""),
        ),
        offVariantKey = "stripe",
        enabledByEnv = mapOf("production" to true, "staging" to true, "development" to false),
        evalVariantWeights = listOf("stripe", "stripe", "stripe", "adyen", "braintree"),
    ),
    DemoFlagSpec(
        key = "payments-kill-switch",
        name = "Payments kill switch",
        description = "Emergency switch to disable card payments during incidents.",
        valueType = "BOOLEAN",
        clientVisible = false,
        tags = listOf("payments", "safety"),
        variants = listOf(
            Triple("off", "Off (payments enabled)", "false"),
            Triple("on", "On (payments disabled)", "true"),
        ),
        offVariantKey = "off",
        enabledByEnv = mapOf("production" to false, "staging" to false, "development" to false),
        evalVariantWeights = listOf("off", "off", "off", "off", "on"),
    ),
)

internal suspend fun checkFreshFeatureFlagsCount(): Long {
    val query = """
        SELECT count() FROM feature_flag_evaluations
        WHERE organization_id = $FF_ORG
            AND event_time >= now() - INTERVAL 7 DAY
    """.trimIndent()
    return suspendRunCatching {
        val response = ClickHouseClient.execute(query)
        if (response.status.value !in 200..299) {
            0L
        } else {
            response.bodyAsText().trim().toLongOrNull() ?: 0L
        }
    }.getOrElse {
        logger.warn { "Failed to check fresh feature flag demo data (non-fatal): ${it.message}" }
        0L
    }
}

internal suspend fun purgeFeatureFlagsDemoData() {
    // Postgres: deleting flags cascades to variants + environment configs (ON DELETE CASCADE).
    suspendRunCatching {
        transaction {
            exec("DELETE FROM feature_flags WHERE organization_id = -1")
            exec("DELETE FROM feature_flag_segments WHERE organization_id = -1")
        }
    }.onFailure { logger.warn { "Purge feature_flags (postgres) failed (non-fatal): ${it.message}" } }

    // ClickHouse: evaluation + tracking events (the *_hourly aggregates roll off via TTL/merges).
    for (table in listOf("feature_flag_evaluations", "feature_flag_tracking_events")) {
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute("ALTER TABLE $table DELETE WHERE organization_id = $FF_ORG"),
                "Purge $table"
            )
        }.onFailure { logger.warn { "Purge $table failed (non-fatal): ${it.message}" } }
    }
}

internal suspend fun reseedFeatureFlags() {
    reseedFeatureFlagDefinitions()
    reseedFeatureFlagEvaluations()
    logger.info { "Feature flag demo data reseed complete" }
}

private fun reseedFeatureFlagDefinitions() {
    runPostgresReseedCatching {
        transaction {
            for (flag in demoFlags) {
                exec(flagInsertSql(flag))
                flag.variants.forEachIndexed { idx, (vKey, vName, vJson) ->
                    exec(variantInsertSql(flag.key, vKey, vName, vJson, idx))
                }
                for ((envKey, enabled) in flag.enabledByEnv) {
                    exec(configInsertSql(flag, envKey, enabled))
                }
            }
        }
    }
}

private fun flagInsertSql(flag: DemoFlagSpec): String {
    val tagsJson = flag.tags.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    return """
        INSERT INTO feature_flags
            (organization_id, key, name, description, value_type, client_visible, tags)
        VALUES
            (-1, '${flag.key}', '${flag.name}', '${flag.description}', '${flag.valueType}',
             ${flag.clientVisible}, '$tagsJson'::jsonb)
        ON CONFLICT (organization_id, key) DO NOTHING
    """.trimIndent()
}

private fun variantInsertSql(flagKey: String, vKey: String, vName: String, vJson: String, sortOrder: Int): String =
    """
        INSERT INTO feature_flag_variants (flag_id, key, name, value_json, sort_order)
        SELECT f.id, '$vKey', '$vName', '$vJson'::jsonb, $sortOrder
        FROM feature_flags f
        WHERE f.organization_id = -1 AND f.key = '$flagKey'
        ON CONFLICT (flag_id, key) DO NOTHING
    """.trimIndent()

private fun configInsertSql(flag: DemoFlagSpec, envKey: String, enabled: Boolean): String {
    val defaultVariant = flag.variants.first().first
    return """
        INSERT INTO feature_flag_environment_configs
            (flag_id, environment_id, enabled, default_variant_key, off_variant_key, rules_json)
        SELECT f.id, e.id, $enabled, '$defaultVariant', '${flag.offVariantKey}', '{"rules":[]}'::jsonb
        FROM feature_flags f
        JOIN feature_flag_environments e ON e.organization_id = f.organization_id
        WHERE f.organization_id = -1 AND f.key = '${flag.key}' AND e.key = '$envKey'
        ON CONFLICT (flag_id, environment_id) DO NOTHING
    """.trimIndent()
}

private suspend fun reseedFeatureFlagEvaluations() {
    for (flag in demoFlags) {
        suspendRunCatching {
            requireClickHouse2xx(
                ClickHouseClient.execute(evaluationsInsertSql(flag)),
                "Reseed evaluations ${flag.key}"
            )
        }.onFailure { logger.warn { "Reseed evaluations ${flag.key} failed (non-fatal): ${it.message}" } }
    }

    // A handful of conversion-tracking events tied to the checkout experiment.
    suspendRunCatching {
        requireClickHouse2xx(ClickHouseClient.execute(trackingEventsInsertSql()), "Reseed flag tracking events")
    }.onFailure { logger.warn { "Reseed flag tracking events failed (non-fatal): ${it.message}" } }
}

private fun evaluationsInsertSql(flag: DemoFlagSpec): String {
    val variantArray = flag.evalVariantWeights.joinToString(", ") { "'$it'" }
    val variantCount = flag.evalVariantWeights.size
    return """
        INSERT INTO feature_flag_evaluations (
            event_time, organization_id, environment, flag_key, variant_key, value_type,
            reason, error_code, targeting_key, sdk_key_prefix, key_type, context_json, duration_ms
        )
        SELECT
            now64(3) - INTERVAL (number * 23 % 10080) MINUTE,
            $FF_ORG,
            arrayElement(['production', 'production', 'production', 'staging', 'development'], number % 5 + 1),
            '${flag.key}',
            arrayElement([$variantArray], number % $variantCount + 1),
            '${flag.valueType}',
            arrayElement(['TARGETING_MATCH', 'TARGETING_MATCH', 'SPLIT', 'DEFAULT', 'STATIC'], number % 5 + 1),
            '',
            concat('user-', toString(number % 800)),
            'srv_demo',
            'server',
            concat('{"targetingKey":"user-', toString(number % 800), '"}'),
            0.2 + (number % 60) / 20.0
        FROM numbers(400)
    """.trimIndent()
}

private fun trackingEventsInsertSql(): String =
    """
        INSERT INTO feature_flag_tracking_events (
            event_time, organization_id, environment, event_name, targeting_key,
            flag_key, variant_key, sdk_key_prefix, key_type, value, properties_json
        )
        SELECT
            now64(3) - INTERVAL (number * 47 % 10080) MINUTE,
            $FF_ORG,
            'production',
            'checkout_completed',
            concat('user-', toString(number % 800)),
            'checkout-button-color',
            arrayElement(['control', 'blue', 'green'], number % 3 + 1),
            'srv_demo',
            'server',
            29.0 + (number % 120),
            '{}'
        FROM numbers(200)
    """.trimIndent()

/** Postgres reseed is synchronous (Exposed transaction); wrap it so failures stay non-fatal like the CH path. */
private fun runPostgresReseedCatching(block: () -> Unit) {
    runCatching(block).onFailure {
        logger.warn { "Reseed feature_flags (postgres) failed (non-fatal): ${it.message}" }
    }
}
