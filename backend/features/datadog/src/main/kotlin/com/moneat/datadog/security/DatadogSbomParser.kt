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

import com.moneat.datadog.models.DdSbomPayload
import com.moneat.security.vulnerabilities.ParsedSbom
import com.moneat.security.vulnerabilities.SbomFormat
import com.moneat.security.vulnerabilities.SbomPackageRecord
import com.moneat.security.vulnerabilities.SbomValidationException
import com.moneat.security.vulnerabilities.SBOM_NO_USABLE_PACKAGES_MESSAGE

private const val MAX_PACKAGES = 5_000
private const val MAX_STRING_LENGTH = 512
private const val MAX_ECOSYSTEM_LENGTH = 64

object DatadogSbomParser {
    fun parseAgentPayload(payload: DdSbomPayload): ParsedSbom {
        if (payload.packages.size > MAX_PACKAGES) {
            throw SbomValidationException("SBOM package count exceeds limit")
        }
        val packages = payload.packages.mapNotNull { pkg ->
            val name = clean(pkg.name)
            val version = clean(pkg.version)
            if (name.isBlank() || version.isBlank()) {
                null
            } else {
                SbomPackageRecord(
                    name = name,
                    version = version,
                    packageType = clean(pkg.type),
                    ecosystem = normalizeEcosystem(pkg.type),
                )
            }
        }
        if (packages.isEmpty()) {
            throw SbomValidationException(SBOM_NO_USABLE_PACKAGES_MESSAGE)
        }
        return ParsedSbom(
            format = SbomFormat.AGENT,
            packages = packages,
            targetName = clean(payload.imageName).ifBlank { clean(payload.host) },
        )
    }

    private fun clean(value: String, maxLength: Int = MAX_STRING_LENGTH): String =
        value.replace('\u0000', ' ').trim().take(maxLength)

    private fun normalizeEcosystem(value: String): String =
        when (value.trim().lowercase()) {
            "node", "nodejs", "javascript", "npm" -> "npm"
            "python", "pip", "pypi" -> "pypi"
            "java", "jar", "maven" -> "maven"
            "golang", "go" -> "go"
            "gem", "rubygems" -> "rubygems"
            "deb", "debian" -> "debian"
            "rpm", "redhat" -> "rpm"
            else -> clean(value.lowercase(), MAX_ECOSYSTEM_LENGTH)
        }
}
