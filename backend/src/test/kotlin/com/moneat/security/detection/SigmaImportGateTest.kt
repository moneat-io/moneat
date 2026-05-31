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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hard gate for imported/template rules: a mapped Sigma rule and every starter-pack template must
 * compile through the **same** [RuleQueryCompiler] as a hand-authored rule, so they inherit the org
 * injection, the column whitelist, and the resource caps and gain no extra power. A crafted Sigma rule
 * cannot escape org scoping or reach a non-whitelisted column.
 */
class SigmaImportGateTest {

    private val importer = SigmaImporter()
    private val compiler = RuleQueryCompiler({ "moneat" })

    private fun compileRequest(orgId: Int, request: CreateDetectionRuleRequest): CompiledRuleQuery {
        val type = DetectionRuleType.fromWire(request.type)!!
        return compiler.compile(
            orgId,
            RuleQueryCompiler.RuleInput(
                source = request.source,
                filter = request.filter,
                groupBy = request.groupBy,
                windowSeconds = request.windowSeconds,
                type = type,
                thresholdCount = request.thresholdCount,
            ),
        )
    }

    private fun compileSigma(orgId: Int, yaml: String): CompiledRuleQuery =
        compileRequest(orgId, importer.toRuleRequest(yaml).request)

    @Test
    fun `a mapped sigma rule compiles and is org-scoped to the context org`() {
        val sql = compileSigma(
            orgId = 77,
            yaml = """
                title: Suspicious curl
                logsource:
                  category: process_creation
                detection:
                  selection:
                    Image|endswith: /curl
                    CommandLine|contains: http
                  condition: selection
            """.trimIndent(),
        ).sql
        assertTrue(sql.contains("organization_id = 77"), sql)
        assertTrue(sql.contains("`moneat`.logs"), sql)
        assertTrue(sql.contains("SETTINGS"), sql)
    }

    @Test
    fun `an imported rule cannot escape org scoping even with an adversarial value`() {
        // The Sigma value tries to break out of the string and drop the org clause; it must bind as a
        // value with its quote escaped, so it stays inside the string literal and cannot inject structure.
        val sql = compileSigma(
            orgId = 5,
            yaml = """
                title: evil
                detection:
                  selection:
                    CommandLine: "x' OR 1=1; DROP TABLE logs; --"
                  condition: selection
            """.trimIndent(),
        ).sql
        assertTrue(sql.contains("organization_id = 5"), sql)
        // Exactly one org predicate and it is the context org — the value could not add another.
        assertEquals(1, Regex("""organization_id""").findAll(sql).count(), sql)
        // The breakout quote is backslash-escaped, so the malicious tail lives inside the string literal.
        assertTrue(sql.contains("""x\' OR 1=1; DROP TABLE logs; --"""), sql)
        // No UNSCAPED quote-then-statement exists: the only `DROP` present sits after an escaped quote.
        assertFalse(Regex("""[^\\]'\s*;?\s*DROP\s+TABLE""", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `an imported rule cannot group on a non-whitelisted column`() {
        // Sigma cannot set group_by directly, but even a crafted logsource maps only to host/tags['user'].
        val request = importer.toRuleRequest(
            """
            title: t
            logsource:
              service: sshd
            detection:
              keywords:
                - "Failed password"
              condition: keywords
            """.trimIndent()
        ).request
        // Every group_by produced is within the compiler whitelist (host + map access only).
        request.groupBy.forEach { col ->
            val allowed = col in RuleQueryCompiler.ALLOWED_GROUP_BY_COLUMNS ||
                Regex("""^[a-z_]+\['?[A-Za-z0-9_.:/-]+'?]$""").matches(col)
            assertTrue(allowed, "group_by '$col' is outside the whitelist")
        }
    }

    @Test
    fun `every starter-pack rule compiles cleanly through the compiler`() {
        DetectionStarterPack.templates.forEach { template ->
            val compiled = compileRequest(orgId = 1, request = template.request)
            assertTrue(compiled.sql.contains("organization_id = 1"), "${template.id}: ${compiled.sql}")
            assertTrue(compiled.sql.contains("`moneat`.logs"), template.id)
            assertTrue(compiled.sql.contains("SETTINGS"), template.id)
        }
    }

    @Test
    fun `starter-pack new-value rule emits no HAVING and threshold rules do`() {
        val newValue = DetectionStarterPack.find("new-source-login")!!
        val nvSql = compileRequest(1, newValue.request).sql
        assertFalse(nvSql.contains("HAVING"), nvSql)

        val threshold = DetectionStarterPack.find("failed-auth-brute-force")!!
        val tSql = compileRequest(1, threshold.request).sql
        assertTrue(tSql.contains("HAVING count() >="), tSql)
    }
}
