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

package com.moneat.security.vulnerabilities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvisoryParserTest {

    @Test
    fun `parseDocument handles arrays primitives and unsupported advisory objects`() {
        assertEquals(emptyList(), AdvisoryParser.parseDocument(""))
        assertEquals(emptyList(), AdvisoryParser.parseDocument("true"))

        val advisories = AdvisoryParser.parseDocument(
            """
            [
              {"id": "ignored"},
              {
                "id": "OSV-2026-0001",
                "aliases": ["GHSA-test-0000-0000", "CVE-2026-0001"],
                "summary": "Prototype pollution",
                "database_specific": {"severity": "low"},
                "affected": [
                  {"package": {"ecosystem": "npm", "name": "lodash"}, "versions": ["4.17.11"]},
                  {"package": {"ecosystem": "npm", "name": ""}},
                  true
                ],
                "published": "2026-05-30T00:00:00Z",
                "modified": "not-an-instant"
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, advisories.size)
        val advisory = advisories.single()
        assertEquals("OSV-2026-0001", advisory.advisoryId)
        assertEquals("CVE-2026-0001", advisory.cveId)
        assertEquals("low", advisory.severity)
        assertEquals(listOf("4.17.11"), advisory.affectedVersions)
        assertTrue(advisory.affectedRanges.isEmpty())
        assertEquals("2026-05-30T00:00:00Z", advisory.publishedAt.toString())
        assertNull(advisory.modifiedAt)
    }

    @Test
    fun `parseNdjson combines GHSA rows and maps severity fallbacks`() {
        val advisories = AdvisoryParser.parseNdjson(
            ghsaRow(
                """{"ghsa_id":"GHSA-critical","cvss":{"score":9.0},"vulnerabilities":[""",
                """{"package":{"ecosystem":"npm","name":"critical-pkg"},"vulnerable_version_range":"< 2.0.0"}]}""",
            ) + "\n" +
                ghsaRow(
                    """{"id":"GHSA-info","cvss_score":0.0,"vulnerabilities":[""",
                    """{"package":{"ecosystem":"pip","name":"info-pkg"}}],""",
                    """"withdrawn_at":"2026-05-30T00:00:00Z"}""",
                ) + "\n" +
                ghsaRow(
                    """{"id":"GHSA-moderate","severity":"moderate","description":"fallback text","identifiers":[""",
                    """{"type":"CVE","value":"CVE-2026-1000"}],"vulnerabilities":[""",
                    """{"package":{"ecosystem":"maven","name":"moderate-pkg"}}]}""",
                )
        )

        assertEquals(listOf("critical", "info", "medium"), advisories.map { it.severity })
        assertEquals("https://github.com/advisories/GHSA-critical", advisories.first().link)
        assertTrue(advisories[1].withdrawn)
        assertEquals("CVE-2026-1000", advisories[2].cveId)
        assertEquals("fallback text", advisories[2].summary)
    }

    @Test
    fun `parseDocument preserves OSV limit range events`() {
        val advisories = AdvisoryParser.parseDocument(
            """
            {
              "id": "OSV-2026-0002",
              "affected": [
                {
                  "package": {"ecosystem": "npm", "name": "left-pad"},
                  "ranges": [
                    {"type": "SEMVER", "events": [{"introduced": "1.0.0"}, {"limit": "2.0.0"}]}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val range = advisories.single().affectedRanges.single()
        assertEquals("1.0.0", range.introduced)
        assertEquals("2.0.0", range.limit)
        assertNull(range.fixed)
        assertNull(range.lastAffected)
    }

    private fun ghsaRow(vararg parts: String): String =
        parts.joinToString("")
}
