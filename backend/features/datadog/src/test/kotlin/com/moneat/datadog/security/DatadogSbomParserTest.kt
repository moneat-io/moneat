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

package com.moneat.datadog.security

import com.moneat.datadog.models.DdSbomPackage
import com.moneat.datadog.models.DdSbomPayload
import com.moneat.security.vulnerabilities.SbomFormat
import com.moneat.security.vulnerabilities.SbomValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatadogSbomParserTest {
    @Test
    fun `parses agent payload and drops incomplete package rows`() {
        val parsed = DatadogSbomParser.parseAgentPayload(
            DdSbomPayload(
                host = "prod-web",
                imageName = "registry/checkout:latest",
                packages = listOf(
                    DdSbomPackage(name = "requests", version = "2.32.0", type = "python"),
                    DdSbomPackage(name = "ignored", version = "", type = "npm"),
                ),
            )
        )

        assertEquals(SbomFormat.AGENT, parsed.format)
        assertEquals("registry/checkout:latest", parsed.targetName)
        assertEquals("requests", parsed.packages.single().name)
        assertEquals("pypi", parsed.packages.single().ecosystem)
    }

    @Test
    fun `rejects agent payloads without complete packages`() {
        val error = assertFailsWith<SbomValidationException> {
            DatadogSbomParser.parseAgentPayload(
                DdSbomPayload(
                    host = "prod-web",
                    packages = listOf(DdSbomPackage(name = "requests", version = "")),
                )
            )
        }

        assertEquals("SBOM contains no packages with name and version", error.message)
    }
}
