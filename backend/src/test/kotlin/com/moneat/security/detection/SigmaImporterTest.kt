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
 * Sigma → rule mapping: the supported safe subset is translated into a [LogQueryParser]-compatible
 * filter, and everything that cannot be translated faithfully is rejected (never approximated). The
 * companion [SigmaImportGateTest] proves the mapped filter still compiles through [RuleQueryCompiler]
 * (org-scoped, whitelisted), so an imported rule has no more power than a hand-authored one.
 */
class SigmaImporterTest {

    private val importer = SigmaImporter()

    private fun map(yaml: String) = importer.toRuleRequest(yaml).request

    // ── Supported subset ───────────────────────────────────────────────────

    @Test
    fun `title description and level map to name description and severity`() {
        val rule = map(
            """
            title: Suspicious thing
            description: it is suspicious
            level: critical
            logsource:
              category: process_creation
            detection:
              selection:
                Image: /usr/bin/curl
              condition: selection
            """.trimIndent()
        )
        assertEquals("Suspicious thing", rule.name)
        assertEquals("it is suspicious", rule.description)
        assertEquals("critical", rule.severity)
        // Imported rules are always disabled.
        assertFalse(rule.enabled)
        // Defaults to a threshold count>=1 rule.
        assertEquals(DetectionRuleType.THRESHOLD.wire, rule.type)
        assertEquals(1, rule.thresholdCount)
    }

    @Test
    fun `informational level maps to info`() {
        val rule = map(
            """
            title: t
            level: informational
            detection:
              sel:
                Image: x
              condition: sel
            """.trimIndent()
        )
        assertEquals("info", rule.severity)
    }

    @Test
    fun `field equality maps to a quoted attribute match`() {
        val rule = map(
            """
            title: t
            detection:
              selection:
                CommandLine: whoami
              condition: selection
            """.trimIndent()
        )
        assertEquals("@CommandLine:\"whoami\"", rule.filter)
    }

    @Test
    fun `contains startswith endswith map to wildcard matches`() {
        val contains = map(sel("CommandLine|contains", "mimikatz"))
        assertEquals("@CommandLine:*mimikatz*", contains.filter)
        val starts = map(sel("Image|startswith", "C:\\Temp"))
        assertTrue(starts.filter.startsWith("@Image:C:") && starts.filter.endsWith("*"), starts.filter)
        val ends = map(sel("TargetFilename|endswith", ".lnk"))
        assertEquals("@TargetFilename:*.lnk", ends.filter)
    }

    @Test
    fun `a list of values becomes an OR`() {
        val rule = map(
            """
            title: t
            detection:
              selection:
                Image:
                  - /bin/nc
                  - /bin/ncat
              condition: selection
            """.trimIndent()
        )
        assertEquals("(@Image:\"/bin/nc\" OR @Image:\"/bin/ncat\")", rule.filter)
    }

    @Test
    fun `the all modifier turns a list into an AND`() {
        val rule = map(
            """
            title: t
            detection:
              selection:
                CommandLine|contains|all:
                  - -enc
                  - hidden
              condition: selection
            """.trimIndent()
        )
        assertEquals("(@CommandLine:*-enc* AND @CommandLine:*hidden*)", rule.filter)
    }

    @Test
    fun `a map of fields becomes an AND`() {
        val rule = map(
            """
            title: t
            detection:
              selection:
                Image|endswith: \powershell.exe
                CommandLine|contains: -enc
              condition: selection
            """.trimIndent()
        )
        // The literal backslash in the value is escaped (`\\`) so the parser unescapes it back to a
        // single backslash rather than treating it as an escape of the following char.
        assertEquals("(@Image:*\\\\powershell.exe AND @CommandLine:*-enc*)", rule.filter)
    }

    @Test
    fun `keyword list becomes full-text OR`() {
        val rule = map(
            """
            title: t
            detection:
              keywords:
                - "failed password"
                - "invalid user"
              condition: keywords
            """.trimIndent()
        )
        assertEquals("(*:\"failed password\" OR *:\"invalid user\")", rule.filter)
    }

    @Test
    fun `condition and or not compose selection fragments`() {
        val rule = map(
            """
            title: t
            detection:
              sel_a:
                Image: a
              sel_b:
                Image: b
              filter_c:
                User: root
              condition: (sel_a or sel_b) and not filter_c
            """.trimIndent()
        )
        // Explicit Sigma parentheses are preserved as a nested group (the extra parens are harmless and
        // the LogQueryParser handles them); the boolean shape is (A or B) and not C.
        assertEquals(
            "(((@Image:\"a\" OR @Image:\"b\")) AND NOT (@User:\"root\"))",
            rule.filter,
        )
    }

    @Test
    fun `1 of selection-star expands to an OR over matching selections`() {
        val rule = map(
            """
            title: t
            detection:
              selection_curl:
                Image: curl
              selection_wget:
                Image: wget
              condition: 1 of selection_*
            """.trimIndent()
        )
        // Order follows declaration order in the detection map.
        assertEquals("(@Image:\"curl\" OR @Image:\"wget\")", rule.filter)
    }

    @Test
    fun `all of them expands to an AND over every selection`() {
        val rule = map(
            """
            title: t
            detection:
              sel1:
                Image: a
              sel2:
                CommandLine: b
              condition: all of them
            """.trimIndent()
        )
        assertEquals("(@Image:\"a\" AND @CommandLine:\"b\")", rule.filter)
    }

    @Test
    fun `auth logsource adds a user group key`() {
        val rule = map(
            """
            title: t
            logsource:
              product: linux
              service: sshd
            detection:
              keywords:
                - "Failed password"
              condition: keywords
            """.trimIndent()
        )
        assertEquals(listOf("host", "tags['user']"), rule.groupBy)
    }

    @Test
    fun `non-auth logsource groups by host only`() {
        val rule = map(
            """
            title: t
            logsource:
              category: process_creation
            detection:
              selection:
                Image: x
              condition: selection
            """.trimIndent()
        )
        assertEquals(listOf("host"), rule.groupBy)
    }

    @Test
    fun `sigma metadata is preserved as tags`() {
        val rule = map(
            """
            title: t
            logsource:
              category: process_creation
            tags:
              - attack.execution
              - attack.t1059
            detection:
              selection:
                Image: x
              condition: selection
            """.trimIndent()
        )
        assertTrue(rule.tags.contains("imported:sigma"), rule.tags.toString())
        assertTrue(rule.tags.contains("sigma.category:process_creation"))
        assertTrue(rule.tags.any { it.contains("attack.t1059") })
    }

    // ── Rejections (faithfulness: reject rather than mistranslate) ──────────

    @Test
    fun `regex modifier is rejected`() {
        assertFailsWith<SigmaImportException> { map(sel("CommandLine|re", "ev[il]+")) }
    }

    @Test
    fun `base64 and cidr and numeric modifiers are rejected`() {
        listOf("CommandLine|base64offset|contains", "DestinationIp|cidr", "EventID|gte").forEach { key ->
            assertFailsWith<SigmaImportException>("$key should be rejected") { map(sel(key, "1")) }
        }
    }

    @Test
    fun `aggregation conditions are rejected`() {
        val ex = assertFailsWith<SigmaImportException> {
            map(
                """
                title: t
                detection:
                  selection:
                    Image: x
                  condition: selection | count() by host > 5
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("Aggregation", ignoreCase = true))
    }

    @Test
    fun `an unknown selection name in the condition is rejected`() {
        assertFailsWith<SigmaImportException> {
            map(
                """
                title: t
                detection:
                  selection:
                    Image: x
                  condition: selection and missing
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a field name with unsafe characters is rejected`() {
        // A field carrying parser control chars (a colon) cannot be expressed as a safe attribute name.
        assertFailsWith<SigmaImportException> { map(sel("we:ird", "x")) }
    }

    @Test
    fun `a missing title is rejected`() {
        assertFailsWith<SigmaImportException> {
            map(
                """
                detection:
                  selection:
                    Image: x
                  condition: selection
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a missing detection block is rejected`() {
        assertFailsWith<SigmaImportException> { map("title: t\nlevel: low") }
    }

    @Test
    fun `a non-log logsource category is rejected`() {
        val ex = assertFailsWith<SigmaImportException> {
            map(
                """
                title: t
                logsource:
                  category: registry_set
                detection:
                  selection:
                    TargetObject: x
                  condition: selection
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("registry_set"))
    }

    @Test
    fun `multi-document yaml is rejected so the caller splits first`() {
        assertFailsWith<SigmaImportException> {
            map("title: a\ndetection:\n  s:\n    Image: x\n  condition: s\n---\ntitle: b")
        }
    }

    @Test
    fun `invalid yaml is rejected without leaking parser internals to a rule`() {
        val ex = assertFailsWith<SigmaImportException> { map("title: [unterminated") }
        assertTrue(ex.message!!.contains("YAML", ignoreCase = true))
    }

    @Test
    fun `an unsupported level value is rejected`() {
        assertFailsWith<SigmaImportException> {
            map(
                """
                title: t
                level: catastrophic
                detection:
                  s:
                    Image: x
                  condition: s
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a quote in a value is neutralised in the produced filter`() {
        val rule = map(sel("CommandLine", "say \"hi\""))
        // The embedded quote is backslash-escaped so it cannot terminate the parser's quoted value.
        assertTrue(rule.filter.contains("\\\""), rule.filter)
    }

    @Test
    fun `a literal wildcard in a contains-startswith-endswith value is rejected not widened`() {
        // Faithfulness: a raw `*`/`?` inside a value modifier must not silently become a wildcard (a
        // broader rule than authored), so it is rejected rather than emitted. Values are YAML-quoted so
        // the glob reaches the importer (an unquoted leading `*` is a YAML alias, a different error).
        listOf("CommandLine|contains" to "a*b", "Image|startswith" to "C:?x", "TargetFilename|endswith" to "x*.exe")
            .forEach { (key, value) ->
                val ex = assertFailsWith<SigmaImportException>("$key:$value should be rejected") {
                    map(sel(key, "\"$value\""))
                }
                assertTrue(ex.message!!.contains("literal", ignoreCase = true), ex.message)
            }
    }

    @Test
    fun `an over-nested condition trips the per-node depth guard not just the size pre-filter`() {
        // Stays under the coarse length/paren pre-filter (so this exercises the recursive depth() guard
        // itself) yet nests past the depth cap; it must reject gracefully, never StackOverflowError.
        val depth = 90
        val nested = "(".repeat(depth) + "selection" + ")".repeat(depth)
        val ex = assertFailsWith<SigmaImportException> {
            map(
                """
                title: t
                detection:
                  selection:
                    Image: x
                  condition: $nested
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("nested too deeply", ignoreCase = true), ex.message)
    }

    @Test
    fun `a huge-paren condition is rejected by the coarse pre-filter before recursing`() {
        // Thousands of parens would otherwise drive the recursive descent into a StackOverflowError; the
        // pre-tokenize bound rejects it up front as an ordinary import error.
        val depth = 5_000
        val nested = "(".repeat(depth) + "selection" + ")".repeat(depth)
        val ex = assertFailsWith<SigmaImportException> {
            map(
                """
                title: t
                detection:
                  selection:
                    Image: x
                  condition: $nested
                """.trimIndent()
            )
        }
        assertTrue(ex.message!!.contains("too", ignoreCase = true), ex.message)
    }

    @Test
    fun `an oversized document is rejected before parsing`() {
        val huge = "title: t\ndescription: " + "x".repeat(600 * 1024) +
            "\ndetection:\n  s:\n    Image: x\n  condition: s"
        val ex = assertFailsWith<SigmaImportException> { map(huge) }
        assertTrue(ex.message!!.contains("too large", ignoreCase = true), ex.message)
    }

    @Test
    fun `a yaml billion-laughs alias bomb is bounded by the alias limit`() {
        // Explicit alias cap (not the library default) keeps an alias-expansion bomb from exhausting memory.
        val bomb = buildString {
            append("title: t\n")
            append("detection:\n  s:\n    Image: x\n  condition: s\n")
            append("a: &a [\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\"]\n")
            for (i in 0 until 12) {
                append("b$i: [")
                append((0 until 10).joinToString(",") { "*a" })
                append("]\n")
            }
        }
        assertFailsWith<SigmaImportException> { map(bomb) }
    }

    private fun sel(field: String, value: String): String =
        """
        title: t
        detection:
          selection:
            $field: $value
          condition: selection
        """.trimIndent()
}
