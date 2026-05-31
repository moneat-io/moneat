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
import kotlin.test.assertFailsWith

class SbomParserTest {

    @Test
    fun `parses CycloneDX packages and purl ecosystem`() {
        val parsed = SbomParser.parse(
            """
            {
              "bomFormat": "CycloneDX",
              "metadata": {"component": {"name": "checkout-api"}},
              "components": [
                {
                  "type": "library",
                  "name": "lodash",
                  "version": "4.17.11",
                  "purl": "pkg:npm/lodash@4.17.11",
                  "licenses": [{"license": {"id": "MIT"}}]
                }
              ]
            }
            """.trimIndent().encodeToByteArray()
        )

        assertEquals(SbomFormat.CYCLONEDX, parsed.format)
        assertEquals("checkout-api", parsed.targetName)
        assertEquals("lodash", parsed.packages.single().name)
        assertEquals("npm", parsed.packages.single().ecosystem)
        assertEquals(listOf("MIT"), parsed.packages.single().licenses)
    }

    @Test
    fun `parses SPDX package external refs`() {
        val parsed = SbomParser.parse(
            """
            {
              "spdxVersion": "SPDX-2.3",
              "name": "worker-image",
              "packages": [
                {
                  "SPDXID": "SPDXRef-Package-minimist",
                  "name": "minimist",
                  "versionInfo": "1.2.5",
                  "licenseDeclared": "MIT",
                  "externalRefs": [
                    {
                      "referenceCategory": "PACKAGE-MANAGER",
                      "referenceType": "purl",
                      "referenceLocator": "pkg:npm/minimist@1.2.5"
                    }
                  ]
                }
              ]
            }
            """.trimIndent().encodeToByteArray()
        )

        assertEquals(SbomFormat.SPDX, parsed.format)
        assertEquals("worker-image", parsed.targetName)
        assertEquals("minimist", parsed.packages.single().name)
        assertEquals("1.2.5", parsed.packages.single().version)
        assertEquals("npm", parsed.packages.single().packageType)
    }

    @Test
    fun `rejects malformed SBOMs safely`() {
        val error = assertFailsWith<SbomValidationException> {
            SbomParser.parse("""{"components": []}""".encodeToByteArray())
        }
        assertEquals("Unsupported SBOM format", error.message)
    }
}
