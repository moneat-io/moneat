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

package com.moneat.security.detection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dedup/baseline key encoding must be unambiguous: group values can legally contain the `|` / `=`
 * separators, and a missing value must stay distinct from an empty one, or distinct matches collapse
 * into one dedup key and suppress signals. It must also be stable (same inputs → same key).
 */
class DedupKeyEncodingTest {

    @Test
    fun `encoding is stable for identical inputs`() {
        val a = encodeGroupKey(listOf("service", "host"), mapOf("service" to "api", "host" to "web-01"))
        val b = encodeGroupKey(listOf("service", "host"), mapOf("service" to "api", "host" to "web-01"))
        assertEquals(a, b)
    }

    @Test
    fun `values containing the separators do not collide across group boundaries`() {
        // Naively joined as "a=x|b=y", these two distinct group tuples would both render "a=x|b=y".
        val left = encodeGroupKey(listOf("a", "b"), mapOf("a" to "x|b=y", "b" to ""))
        val right = encodeGroupKey(listOf("a", "b"), mapOf("a" to "x", "b" to "y"))
        assertNotEquals(left, right, "values with | and = must not collapse distinct matches")
    }

    @Test
    fun `pipe in one value does not bleed into the next field`() {
        val piped = encodeGroupKey(listOf("a", "b"), mapOf("a" to "1|2", "b" to "3"))
        val split = encodeGroupKey(listOf("a", "b"), mapOf("a" to "1", "b" to "2|3"))
        assertNotEquals(piped, split)
    }

    @Test
    fun `missing value is distinct from empty value`() {
        val missing = encodeGroupKey(listOf("host"), emptyMap())
        val empty = encodeGroupKey(listOf("host"), mapOf("host" to ""))
        assertNotEquals(missing, empty, "absent group value must not encode like an empty string")
    }

    @Test
    fun `equals sign in a value does not forge a new key-value pair`() {
        val withEquals = encodeGroupKey(listOf("env"), mapOf("env" to "k=v"))
        val plain = encodeGroupKey(listOf("env"), mapOf("env" to "kv"))
        assertNotEquals(withEquals, plain)
    }

    @Test
    fun `dedup key prefixes the rule id`() {
        val rule = DedupKeyEncodingFixtures.rule(id = 7, groupBy = listOf("host"))
        val key = dedupKeyFor(rule, mapOf("host" to "web-01"))
        assertTrue(key.startsWith("detection-7|"), "dedup key must be namespaced by rule id")
    }

    @Test
    fun `entity predicate covers whitelisted top-level and map dimensions and escapes values`() {
        val esc: (String) -> String = com.moneat.utils.ClickHouseSqlUtils::escapeSql
        assertEquals("service = 'api'", RuleQueryCompiler.entityPredicate("service", "api", esc))
        assertEquals(
            "tags['env'] = 'prod'",
            RuleQueryCompiler.entityPredicate("tags['env']", "prod", esc),
        )
        assertEquals(
            "resource_attributes['k8s.namespace'] = 'payments'",
            RuleQueryCompiler.entityPredicate("resource_attributes['k8s.namespace']", "payments", esc),
        )
        // A single quote in the value is escaped, never breaking out of the literal.
        assertEquals(
            "host = 'a\\'b'",
            RuleQueryCompiler.entityPredicate("host", "a'b", esc),
        )
        // Non-whitelisted columns / map columns yield no predicate (skipped, never widened).
        assertNull(RuleQueryCompiler.entityPredicate("message", "secret", esc))
        assertNull(RuleQueryCompiler.entityPredicate("organization_id", "1", esc))
        assertNull(RuleQueryCompiler.entityPredicate("evil['k']", "v", esc))
    }
}

private object DedupKeyEncodingFixtures {
    fun rule(id: Int, groupBy: List<String>): DetectionRuleRecord = DetectionRuleRecord(
        id = id,
        organizationId = 1,
        name = "r",
        source = "logs",
        filter = "",
        groupBy = groupBy,
        windowSeconds = 300,
        type = DetectionRuleType.THRESHOLD,
        thresholdCount = 1,
        severity = com.moneat.security.signals.SignalSeverity.MEDIUM,
        signalTitle = "",
        signalMessage = "",
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
    )
}
