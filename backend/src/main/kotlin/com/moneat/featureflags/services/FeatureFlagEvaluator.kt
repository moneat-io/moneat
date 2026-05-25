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

import com.moneat.featureflags.models.FLAG_KEY_TYPE_CLIENT
import com.moneat.featureflags.models.FeatureFlagEnvironmentConfigSnapshot
import com.moneat.featureflags.models.FeatureFlagSegmentSnapshot
import com.moneat.featureflags.models.FeatureFlagSnapshotFlag
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantSnapshot
import com.moneat.featureflags.models.OfrepFlagEvaluationResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigInteger
import java.security.MessageDigest

private const val REASON_STATIC = "STATIC"
private const val REASON_DEFAULT = "DEFAULT"
private const val REASON_TARGETING_MATCH = "TARGETING_MATCH"
private const val REASON_SPLIT = "SPLIT"
private const val REASON_DISABLED = "DISABLED"
private const val REASON_ERROR = "ERROR"
private const val ERROR_FLAG_NOT_FOUND = "FLAG_NOT_FOUND"
private const val ERROR_TYPE_MISMATCH = "TYPE_MISMATCH"
private const val ERROR_TARGETING_KEY_MISSING = "TARGETING_KEY_MISSING"
private const val BUCKET_COUNT = 100_000
private const val PERCENT_WEIGHT_MAX = 100.0
private const val PERCENT_TO_BUCKET_MULTIPLIER = 1_000.0
private const val MIN_SEMVER_PARTS = 3
private const val SHA_256 = "SHA-256"

class FeatureFlagEvaluator {

    fun evaluate(
        snapshot: FeatureFlagEnvironmentConfigSnapshot,
        flagKey: String,
        context: JsonObject,
        expectedType: FeatureFlagValueType?,
        keyType: String,
    ): OfrepFlagEvaluationResponse {
        val flag = snapshot.flags.firstOrNull { it.key == flagKey }
        if (flag == null || (keyType == FLAG_KEY_TYPE_CLIENT && !flag.clientVisible)) {
            return errorResponse(
                key = flagKey,
                errorCode = ERROR_FLAG_NOT_FOUND,
                errorDetails = "Feature flag was not found"
            )
        }

        if (expectedType != null && expectedType != flag.valueType) {
            val fallback = resolveVariant(flag, flag.config.defaultVariantKey)
            return response(
                snapshot = snapshot,
                flag = flag,
                variant = fallback,
                reason = REASON_ERROR,
                errorCode = ERROR_TYPE_MISMATCH,
                errorDetails = "Requested $expectedType, but flag is ${flag.valueType}"
            )
        }

        if (!flag.config.enabled) {
            val disabledVariant = resolveVariant(flag, flag.config.offVariantKey)
                ?: resolveVariant(flag, flag.config.defaultVariantKey)
                ?: flag.variants.firstOrNull()
            return response(snapshot, flag, disabledVariant, REASON_DISABLED)
        }

        val segments = snapshot.segments.associateBy { it.key }
        val decision = decideVariant(snapshot, flag, segments, context)
        return response(
            snapshot = snapshot,
            flag = flag,
            variant = decision.variant,
            reason = decision.reason,
            errorCode = decision.errorCode,
            errorDetails = decision.errorDetails,
        )
    }

    fun evaluateAll(
        snapshot: FeatureFlagEnvironmentConfigSnapshot,
        context: JsonObject,
        keyType: String,
        flagKeys: List<String>? = null,
    ): List<OfrepFlagEvaluationResponse> {
        val visibleFlags = snapshot.flags
            .asSequence()
            .filter { keyType != FLAG_KEY_TYPE_CLIENT || it.clientVisible }
            .toList()

        if (flagKeys == null) {
            return visibleFlags.map { flag ->
                evaluate(
                    snapshot = snapshot,
                    flagKey = flag.key,
                    context = context,
                    expectedType = null,
                    keyType = keyType
                )
            }
        }

        val flagsByKey = visibleFlags.associateBy { it.key }
        return flagKeys.distinct().map { flagKey ->
            if (flagsByKey.containsKey(flagKey)) {
                evaluate(snapshot, flagKey, context, expectedType = null, keyType = keyType)
            } else {
                errorResponse(
                    key = flagKey,
                    errorCode = ERROR_FLAG_NOT_FOUND,
                    errorDetails = "Feature flag was not found"
                )
            }
        }
    }

    fun bucket(
        organizationId: Int,
        environmentKey: String,
        flagKey: String,
        ruleId: String,
        targetingKey: String,
    ): Int {
        val input = "$organizationId:$environmentKey:$flagKey:$ruleId:$targetingKey"
        val bytes = MessageDigest.getInstance(SHA_256).digest(input.toByteArray())
        return BigInteger(1, bytes).mod(BigInteger.valueOf(BUCKET_COUNT.toLong())).toInt()
    }

    private fun decideVariant(
        snapshot: FeatureFlagEnvironmentConfigSnapshot,
        flag: FeatureFlagSnapshotFlag,
        segments: Map<String, FeatureFlagSegmentSnapshot>,
        context: JsonObject,
    ): EvaluationDecision {
        val rulesObject = flag.config.rules as? JsonObject
        val rules = rulesObject?.get("rules") as? JsonArray ?: JsonArray(emptyList())
        if (rules.isEmpty()) {
            return EvaluationDecision(resolveVariant(flag, flag.config.defaultVariantKey), REASON_STATIC)
        }

        val targetingKey = targetingKey(context)
        if (targetingKey.isNullOrBlank()) {
            return EvaluationDecision(
                variant = resolveVariant(flag, flag.config.defaultVariantKey),
                reason = REASON_ERROR,
                errorCode = ERROR_TARGETING_KEY_MISSING,
                errorDetails = "OpenFeature context must include targetingKey"
            )
        }

        rules.forEachIndexed { index, element ->
            val rule = element as? JsonObject ?: return@forEachIndexed
            val ruleId = readString(rule["id"]) ?: "rule-$index"
            val conditions = rule["conditions"] ?: rule["if"] ?: JsonObject(emptyMap())
            if (matchesConditions(conditions, segments, context)) {
                return serve(snapshot, flag, ruleId, targetingKey, rule["serve"], REASON_TARGETING_MATCH)
            }
        }

        val fallthrough = rulesObject?.get("fallthrough")
        if (fallthrough != null) {
            return serve(snapshot, flag, "fallthrough", targetingKey, fallthrough, REASON_DEFAULT)
        }

        return EvaluationDecision(resolveVariant(flag, flag.config.defaultVariantKey), REASON_DEFAULT)
    }

    private fun serve(
        snapshot: FeatureFlagEnvironmentConfigSnapshot,
        flag: FeatureFlagSnapshotFlag,
        ruleId: String,
        targetingKey: String,
        serve: JsonElement?,
        matchedReason: String,
    ): EvaluationDecision {
        val serveObject = serve as? JsonObject
        val type = readString(serveObject?.get("type")) ?: "variant"
        if (type == "rollout") {
            return rollout(snapshot, flag, ruleId, targetingKey, serveObject)
        }

        val variantKey = readString(serveObject?.get("variant")) ?: readString(serve)
        val variant = resolveVariant(flag, variantKey) ?: resolveVariant(flag, flag.config.defaultVariantKey)
        return EvaluationDecision(variant, matchedReason)
    }

    private fun rollout(
        snapshot: FeatureFlagEnvironmentConfigSnapshot,
        flag: FeatureFlagSnapshotFlag,
        ruleId: String,
        targetingKey: String,
        serve: JsonObject?,
    ): EvaluationDecision {
        val allocations = serve?.get("allocations") as? JsonArray ?: return EvaluationDecision(
            resolveVariant(flag, flag.config.defaultVariantKey),
            REASON_DEFAULT
        )
        val normalizedWeights = normalizeAllocations(allocations)
        val bucket = bucket(snapshot.organizationId, snapshot.environment.key, flag.key, ruleId, targetingKey)
        var cursor = 0

        normalizedWeights.forEach { allocation ->
            cursor += allocation.weight
            if (bucket < cursor) {
                return EvaluationDecision(resolveVariant(flag, allocation.variantKey), REASON_SPLIT)
            }
        }

        return EvaluationDecision(resolveVariant(flag, flag.config.defaultVariantKey), REASON_DEFAULT)
    }

    private fun normalizeAllocations(allocations: JsonArray): List<RolloutAllocation> {
        val parsed = allocations.mapNotNull { allocation ->
            val obj = allocation as? JsonObject ?: return@mapNotNull null
            val variant = readString(obj["variant"]) ?: return@mapNotNull null
            val weight = readNumber(obj["weight"]) ?: return@mapNotNull null
            variant to weight
        }
        val total = parsed.sumOf { it.second }
        val multiplier = if (total <= PERCENT_WEIGHT_MAX) PERCENT_TO_BUCKET_MULTIPLIER else 1.0
        return parsed.map { (variant, weight) ->
            RolloutAllocation(variant, (weight * multiplier).toInt().coerceAtLeast(0))
        }
    }

    private fun matchesConditions(
        element: JsonElement,
        segments: Map<String, FeatureFlagSegmentSnapshot>,
        context: JsonObject,
    ): Boolean {
        val condition = element as? JsonObject ?: return false
        val all = condition["all"] as? JsonArray
        if (all != null) {
            return all.all { matchesConditions(it, segments, context) }
        }
        val any = condition["any"] as? JsonArray
        if (any != null) {
            return any.any { matchesConditions(it, segments, context) }
        }
        val segmentKey = readString(condition["segment"])
        if (segmentKey != null) {
            val segment = segments[segmentKey] ?: return false
            return matchesConditions(segment.conditions, segments, context)
        }
        return matchesAttributeCondition(condition, context)
    }

    private fun matchesAttributeCondition(condition: JsonObject, context: JsonObject): Boolean {
        val attribute = readString(condition["attribute"]) ?: return false
        val operator = readString(condition["op"]) ?: readString(condition["operator"]) ?: "eq"
        val actual = readPath(context, attribute)
        val expected = condition["value"]

        return when (operator) {
            "exists" -> actual != null && actual !is JsonNull
            "not_exists" -> actual == null || actual is JsonNull
            "eq" -> valuesEqual(actual, expected)
            "neq" -> !valuesEqual(actual, expected)
            "in" -> expected is JsonArray && expected.any { valuesEqual(actual, it) }
            "not_in" -> expected is JsonArray && expected.none { valuesEqual(actual, it) }
            "contains" -> readString(actual)?.contains(readString(expected).orEmpty()) == true
            "starts_with" -> readString(actual)?.startsWith(readString(expected).orEmpty()) == true
            "ends_with" -> readString(actual)?.endsWith(readString(expected).orEmpty()) == true
            "gt" -> compareNumbers(actual, expected) { result -> result > 0 }
            "gte" -> compareNumbers(actual, expected) { result -> result >= 0 }
            "lt" -> compareNumbers(actual, expected) { result -> result < 0 }
            "lte" -> compareNumbers(actual, expected) { result -> result <= 0 }
            "semver_gt" -> compareSemver(actual, expected) { result -> result > 0 }
            "semver_gte" -> compareSemver(actual, expected) { result -> result >= 0 }
            "semver_lt" -> compareSemver(actual, expected) { result -> result < 0 }
            "semver_lte" -> compareSemver(actual, expected) { result -> result <= 0 }
            else -> false
        }
    }

    private fun response(
        snapshot: FeatureFlagEnvironmentConfigSnapshot,
        flag: FeatureFlagSnapshotFlag,
        variant: FeatureFlagVariantSnapshot?,
        reason: String,
        errorCode: String? = null,
        errorDetails: String? = null,
    ): OfrepFlagEvaluationResponse {
        val value = variant?.value ?: JsonNull
        val resolvedErrorCode =
            if (variant != null && isValueCompatible(flag.valueType, value)) {
                errorCode
            } else {
                ERROR_TYPE_MISMATCH
            }
        val resolvedErrorMessage =
            if (resolvedErrorCode == ERROR_TYPE_MISMATCH && errorDetails == null) {
                "Variant value does not match ${flag.valueType}"
            } else {
                errorDetails
            }

        return OfrepFlagEvaluationResponse(
            key = flag.key,
            value = value,
            variant = variant?.key,
            reason = if (resolvedErrorCode == null) reason else REASON_ERROR,
            metadata = mapOf(
                "etag" to snapshot.etag,
                "environment" to snapshot.environment.key,
                "valueType" to flag.valueType.name,
            ),
            errorCode = resolvedErrorCode,
            errorDetails = resolvedErrorMessage,
        )
    }

    private fun errorResponse(
        key: String,
        errorCode: String,
        errorDetails: String,
    ): OfrepFlagEvaluationResponse {
        return OfrepFlagEvaluationResponse(
            key = key,
            value = JsonNull,
            variant = null,
            reason = REASON_ERROR,
            errorCode = errorCode,
            errorDetails = errorDetails
        )
    }

    private fun resolveVariant(flag: FeatureFlagSnapshotFlag, key: String?): FeatureFlagVariantSnapshot? {
        if (key == null) return null
        return flag.variants.firstOrNull { it.key == key }
    }

    private fun targetingKey(context: JsonObject): String? {
        return readString(context["targetingKey"])
            ?: readString(context["targeting_key"])
            ?: readString(context["key"])
    }

    private fun readPath(context: JsonObject, attribute: String): JsonElement? {
        var current: JsonElement? = context
        attribute.split('.').forEach { part ->
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }

    private fun valuesEqual(actual: JsonElement?, expected: JsonElement?): Boolean {
        val actualPrimitive = actual as? JsonPrimitive
        val expectedPrimitive = expected as? JsonPrimitive
        if (actualPrimitive != null && expectedPrimitive != null) {
            val actualNumber = readNumber(actualPrimitive)
            val expectedNumber = readNumber(expectedPrimitive)
            if (actualNumber != null && expectedNumber != null) {
                return actualNumber == expectedNumber
            }
        }
        return actual == expected
    }

    private fun compareNumbers(
        actual: JsonElement?,
        expected: JsonElement?,
        predicate: (Int) -> Boolean,
    ): Boolean {
        val actualNumber = readNumber(actual) ?: return false
        val expectedNumber = readNumber(expected) ?: return false
        return predicate(actualNumber.compareTo(expectedNumber))
    }

    private fun compareSemver(
        actual: JsonElement?,
        expected: JsonElement?,
        predicate: (Int) -> Boolean,
    ): Boolean {
        val actualVersion = parseSemver(readString(actual)) ?: return false
        val expectedVersion = parseSemver(readString(expected)) ?: return false
        return predicate(compareSemverParts(actualVersion, expectedVersion))
    }

    private fun parseSemver(value: String?): List<Int>? {
        if (value.isNullOrBlank()) return null
        val parts = value
            .substringBefore("+")
            .substringBefore("-")
            .split(".")
            .map { it.toIntOrNull() ?: return null }
            .toMutableList()
        while (parts.size < MIN_SEMVER_PARTS) {
            parts.add(0)
        }
        return parts
    }

    private fun compareSemverParts(actual: List<Int>, expected: List<Int>): Int {
        val maxParts = maxOf(actual.size, expected.size)
        for (index in 0 until maxParts) {
            val actualPart = actual.getOrElse(index) { 0 }
            val expectedPart = expected.getOrElse(index) { 0 }
            val comparison = actualPart.compareTo(expectedPart)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun readString(value: JsonElement?): String? {
        return (value as? JsonPrimitive)?.contentOrNull
    }

    private fun readNumber(value: JsonElement?): Double? {
        return (value as? JsonPrimitive)?.doubleOrNull
    }

    private fun isValueCompatible(type: FeatureFlagValueType, value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive
        return when (type) {
            FeatureFlagValueType.BOOLEAN -> primitive?.booleanOrNull != null
            FeatureFlagValueType.STRING -> primitive?.isString == true
            FeatureFlagValueType.INTEGER -> primitive?.intOrNull != null
            FeatureFlagValueType.DOUBLE -> primitive?.doubleOrNull != null
            FeatureFlagValueType.OBJECT -> value is JsonObject
        }
    }
}

private data class EvaluationDecision(
    val variant: FeatureFlagVariantSnapshot?,
    val reason: String,
    val errorCode: String? = null,
    val errorDetails: String? = null,
)

private data class RolloutAllocation(
    val variantKey: String,
    val weight: Int,
)
