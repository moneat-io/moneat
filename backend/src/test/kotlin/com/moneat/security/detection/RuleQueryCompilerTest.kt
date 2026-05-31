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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security gate. These tests are the hard gate from `05-security.md`: org filter always injected,
 * whitelist enforced, no injection, cross-tenant fails closed, caps on every query, no DB-internal
 * leakage. A compiler regression here is a tenant-isolation regression.
 */
class RuleQueryCompilerTest {

    private val compiler = RuleQueryCompiler({ "moneat" })

    private fun input(
        filter: String = "",
        groupBy: List<String> = listOf("host"),
        windowSeconds: Int = 300,
        type: DetectionRuleType = DetectionRuleType.THRESHOLD,
        thresholdCount: Int? = 5,
        source: String = "logs",
    ) = RuleQueryCompiler.RuleInput(source, filter, groupBy, windowSeconds, type, thresholdCount)

    // (a) Org filter always present, and no rule field can set/override it.

    @Test
    fun `every compiled query injects the org clause for the context org`() {
        val sql = compiler.compile(orgId = 42, input()).sql
        assertTrue(sql.contains("organization_id = 42"), "missing org clause: $sql")
    }

    @Test
    fun `org clause is present even with empty filter and no group by`() {
        val sql = compiler.compile(orgId = 7, input(filter = "", groupBy = emptyList())).sql
        assertTrue(sql.contains("organization_id = 7"))
        assertTrue(sql.contains("count() AS match_count"))
    }

    @Test
    fun `a different context org changes only the injected org clause`() {
        val a = compiler.compile(orgId = 1, input()).sql
        val b = compiler.compile(orgId = 2, input()).sql
        assertTrue(a.contains("organization_id = 1"))
        assertTrue(b.contains("organization_id = 2"))
        assertFalse(a.contains("organization_id = 2"))
        assertFalse(b.contains("organization_id = 1"))
    }

    @Test
    fun `demo negative org uses the int64 cast clause and never bare equality`() {
        val sql = compiler.compile(orgId = -1, input()).sql
        assertTrue(sql.contains("toInt64(organization_id) IN (-1, -2, -3)"), sql)
    }

    @Test
    fun `org zero fails closed`() {
        val ex = assertFailsWith<DetectionCompileException> { compiler.compile(orgId = 0, input()) }
        assertTrue(ex.message!!.contains("organization", ignoreCase = true))
    }

    @Test
    fun `a filter that names organization_id cannot remove or weaken the injected clause`() {
        // Even if an author tries to talk about organization_id, the parser treats it as a field match
        // ANDed under the injected clause; the injected `organization_id = {org}` is still present.
        val sql = compiler.compile(orgId = 99, input(filter = "@organization_id:1")).sql
        assertTrue(sql.contains("organization_id = 99"), sql)
        // The org scoping conjunct is first and joined with AND, so the author's term cannot widen it.
        assertTrue(sql.indexOf("organization_id = 99") < sql.indexOf("GROUP BY"), sql)
    }

    // (b) Whitelist enforced: tables/columns/functions outside the allowlist are rejected.

    @Test
    fun `only the logs table is ever used`() {
        val sql = compiler.compile(orgId = 1, input()).sql
        assertTrue(sql.contains("`moneat`.logs"), sql)
        assertEquals(1, Regex("""\bFROM\b""").findAll(sql).count(), "exactly one FROM expected: $sql")
    }

    @Test
    fun `non-whitelisted group-by column is rejected with a DSL error`() {
        val ex = assertFailsWith<DetectionCompileException> {
            compiler.compile(orgId = 1, input(groupBy = listOf("password")))
        }
        assertTrue(ex.message!!.contains("Unknown group-by column"), ex.message)
    }

    @Test
    fun `org and project columns are not valid group-by targets`() {
        listOf("organization_id", "project_id", "log_id", "system_id").forEach { col ->
            assertFailsWith<DetectionCompileException>("$col should be rejected") {
                compiler.compile(orgId = 1, input(groupBy = listOf(col)))
            }
        }
    }

    @Test
    fun `a group-by attempting a subquery or function is rejected`() {
        listOf(
            "(SELECT 1)",
            "count()",
            "host) FROM system.tables --",
            "host, (SELECT password FROM users)",
        ).forEach { col ->
            assertFailsWith<DetectionCompileException>("$col should be rejected") {
                compiler.compile(orgId = 1, input(groupBy = listOf(col)))
            }
        }
    }

    @Test
    fun `whitelisted map access is allowed and the key is escaped`() {
        val sql = compiler.compile(orgId = 1, input(groupBy = listOf("tags['user']"))).sql
        assertTrue(sql.contains("tags['user']"), sql)
    }

    @Test
    fun `a non-whitelisted map column is rejected`() {
        assertFailsWith<DetectionCompileException> {
            compiler.compile(orgId = 1, input(groupBy = listOf("secrets['k']")))
        }
    }

    @Test
    fun `enum group-by columns are cast with toString for stable grouping`() {
        val sql = compiler.compile(orgId = 1, input(groupBy = listOf("level"))).sql
        assertTrue(sql.contains("toString(level) AS g0"), sql)
    }

    @Test
    fun `too many group-by columns are rejected`() {
        assertFailsWith<DetectionCompileException> {
            compiler.compile(orgId = 1, input(groupBy = List(6) { "host" }))
        }
    }

    @Test
    fun `an unsupported source is rejected`() {
        assertFailsWith<DetectionCompileException> {
            compiler.compile(orgId = 1, input(source = "security_events"))
        }
    }

    // (c) No injection: adversarial filter strings are escaped/parsed and never alter structure.

    private fun assertFilterCannotAlterStructure(filter: String) {
        val sql = compiler.compile(orgId = 5, input(filter = filter)).sql
        // The org clause is intact and the injected org is unchanged.
        assertTrue(sql.contains("organization_id = 5"), "org clause lost for: $filter -> $sql")
        assertFalse(sql.contains("organization_id = 2"), "structure altered by: $filter -> $sql")
        // A raw closing of the string + SQL keyword must never appear unescaped.
        assertFalse(sql.contains("DROP TABLE logs;"), "unescaped DROP in: $sql")
        assertFalse(Regex("""UNION\s+SELECT""", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `adversarial filter - quote semicolon drop comment`() =
        assertFilterCannotAlterStructure("service:'; DROP TABLE logs; --")

    @Test
    fun `adversarial filter - double quote or 1 equals 1`() =
        assertFilterCannotAlterStructure("service:\" OR 1=1 --")

    @Test
    fun `adversarial filter - union select`() =
        assertFilterCannotAlterStructure("host:x' UNION SELECT password FROM users --")

    @Test
    fun `adversarial filter - wildcard close paren or org`() =
        assertFilterCannotAlterStructure("message:*) OR organization_id = 2 --")

    @Test
    fun `a single quote in a filter value is backslash-escaped not terminated`() {
        val sql = compiler.compile(orgId = 1, input(filter = """service:"o'brien"""")).sql
        assertTrue(sql.contains("""o\'brien"""), sql)
    }

    @Test
    fun `a map group-by key containing a quote is rejected fail-closed`() {
        // Map keys are restricted to a safe charset; a key with a quote cannot match the allowlist
        // pattern and is rejected outright, so there is no escaping for an attacker to defeat.
        listOf("""tags[a'b]""", """tags[a"b]""", """tags[a]b]""", "tags[a)b]").forEach { col ->
            assertFailsWith<DetectionCompileException>("$col should be rejected") {
                compiler.compile(orgId = 1, input(groupBy = listOf(col)))
            }
        }
    }

    @Test
    fun `a normal map group-by key compiles to escaped map access`() {
        val sql = compiler.compile(orgId = 1, input(groupBy = listOf("tags[user.id]"))).sql
        assertTrue(sql.contains("tags['user.id']"), sql)
    }

    // (d) Cross-tenant read fails closed — covered structurally: there is no author path to another org.

    @Test
    fun `no rule field maps to org scoping so a rule cannot target another tenant`() {
        // Source is the only other "table-ish" knob and it is whitelisted to logs; org is a parameter.
        val sql = compiler.compile(orgId = 11, input(filter = "service:api", groupBy = listOf("host"))).sql
        // Exactly one org predicate, equal to the context org.
        assertEquals(1, Regex("""organization_id""").findAll(sql).count(), sql)
        assertTrue(sql.contains("organization_id = 11"))
    }

    // (e) Resource caps attached to every compiled query.

    @Test
    fun `every compiled query carries the resource caps`() {
        val sql = compiler.compile(orgId = 1, input()).sql
        assertTrue(sql.contains("SETTINGS"), sql)
        listOf(
            "max_execution_time",
            "max_rows_to_read",
            "max_result_rows",
            "max_bytes_to_read",
            "result_overflow_mode = 'throw'",
        ).forEach { cap -> assertTrue(sql.contains(cap), "missing cap '$cap' in: $sql") }
    }

    @Test
    fun `the where clause helper used by baseline and preview carries org and window`() {
        val where = compiler.buildWhere(orgId = 3, filter = "service:api", windowSeconds = 600)
        assertTrue(where.contains("organization_id = 3"))
        assertTrue(where.contains("INTERVAL 600 SECOND"))
    }

    // Window + threshold validation produce DSL messages, not CH text.

    @Test
    fun `a too-short window is rejected with a DSL message`() {
        val ex = assertFailsWith<DetectionCompileException> {
            compiler.compile(orgId = 1, input(windowSeconds = 5))
        }
        assertTrue(ex.message!!.contains("Window must be at least"))
    }

    @Test
    fun `a threshold rule without a count is rejected`() {
        val ex = assertFailsWith<DetectionCompileException> {
            compiler.compile(orgId = 1, input(thresholdCount = null))
        }
        assertTrue(ex.message!!.contains("threshold_count"))
    }

    @Test
    fun `a threshold rule emits HAVING count gte threshold`() {
        val sql = compiler.compile(orgId = 1, input(thresholdCount = 9)).sql
        assertTrue(sql.contains("HAVING count() >= 9"), sql)
    }

    @Test
    fun `a new-value rule emits no HAVING`() {
        val sql = compiler.compile(orgId = 1, input(type = DetectionRuleType.NEW_VALUE, thresholdCount = null)).sql
        assertFalse(sql.contains("HAVING"), sql)
    }

    // (f) Compiler/validation errors never leak ClickHouse text.

    @Test
    fun `compile errors never contain ClickHouse internals`() {
        val messages = mutableListOf<String>()
        runCatching { compiler.compile(orgId = 1, input(groupBy = listOf("DB::Exception"))) }
            .onFailure { messages += it.message.orEmpty() }
        runCatching { compiler.compile(orgId = 1, input(windowSeconds = 1)) }
            .onFailure { messages += it.message.orEmpty() }
        runCatching { compiler.compile(orgId = 0, input()) }
            .onFailure { messages += it.message.orEmpty() }
        messages.forEach { msg ->
            listOf("Code:", "DB::Exception", "SETTINGS", "FROM `", "syntax error").forEach { leak ->
                assertFalse(msg.contains(leak), "DSL error leaked '$leak': $msg")
            }
        }
    }
}
