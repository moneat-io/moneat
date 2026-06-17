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

package com.moneat.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    @Test
    fun `production code reads environment through approved boundaries only`() {
        val violations =
            productionFiles()
                .filterNot { file ->
                    val normalizedPath = file.normalizedPath()
                    normalizedPath.endsWith("/config/EnvConfig.kt")
                }
                .flatMap { file -> file.violatingLines(SYSTEM_GETENV_PATTERN) }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                prefix = "Use EnvConfig instead of direct System.getenv outside approved bootstrap files:\n",
                separator = "\n"
            )
        )
    }

    @Test
    fun `production code does not write directly to console or print stack traces`() {
        val violations =
            productionFiles()
                .flatMap { file -> file.violatingLines(CONSOLE_OUTPUT_PATTERN) }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                prefix = "Use structured logging instead of println or printStackTrace in production code:\n",
                separator = "\n"
            )
        )
    }

    @Test
    fun `vendor specific infrastructure metric aliases stay inside ingestion compatibility code`() {
        val violations =
            productionFiles()
                .filterNot { file -> file.allowsVendorSpecificMetricAliases() }
                .flatMap { file -> file.violatingLines(VENDOR_INFRA_METRIC_ALIAS_PATTERN) }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                prefix = "Normalize vendor-specific infrastructure metric aliases before core product code:\n",
                separator = "\n"
            )
        )
    }

    @Test
    fun `feature module import graph remains explicit`() {
        val edges =
            productionFiles()
                .mapNotNull { file ->
                    val sourceFeature = file.sourceFeature() ?: return@mapNotNull null
                    val importedFeatures =
                        file.imports
                            .mapNotNull { import ->
                                val targetFeature =
                                    FEATURE_MODULES.firstOrNull { feature ->
                                        import.name == "com.moneat.$feature" ||
                                            import.name.startsWith("com.moneat.$feature.")
                                    }
                                if (targetFeature != null && targetFeature != sourceFeature) {
                                    "$sourceFeature -> $targetFeature"
                                } else {
                                    null
                                }
                            }
                            .toSet()
                    importedFeatures
                }
                .flatten()
                .toSortedSet()

        assertEquals(FEATURE_IMPORT_EDGES, edges)
    }

    private fun productionFiles(): List<KoFileDeclaration> =
        Konsist
            .scopeFromProduction()
            .files
            .filter { file -> file.normalizedPath().contains("/src/main/kotlin/") }

    private fun KoFileDeclaration.normalizedPath(): String = path.replace('\\', '/')

    private fun KoFileDeclaration.allowsVendorSpecificMetricAliases(): Boolean =
        normalizedPath().endsWith(
            "/features/datadog/src/main/kotlin/com/moneat/datadog/services/DatadogMetricService.kt"
        )

    private fun KoFileDeclaration.sourceFeature(): String? {
        val normalizedPath = normalizedPath()
        return FEATURE_MODULES.firstOrNull { feature ->
            normalizedPath.contains("/features/$feature/src/main/kotlin/")
        }
    }

    private fun KoFileDeclaration.violatingLines(pattern: Regex): List<String> =
        text
            .lineSequence()
            .mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    null
                } else if (pattern.containsMatchIn(line)) {
                    "${normalizedPath()}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
            .toList()

    companion object {
        private val SYSTEM_GETENV_PATTERN = Regex("""\bSystem\.getenv\s*\(""")
        private val CONSOLE_OUTPUT_PATTERN = Regex("""\b(?:println|printStackTrace)\s*\(""")
        private val VENDOR_INFRA_METRIC_ALIAS_PATTERN =
            Regex(
                """\b(?:system\.mem\.pct_usable|system\.mem\.usable|""" +
                    """system\.disk\.in_use|system\.net\.bytes_rcvd|system\.net\.bytes_sent)\b"""
            )
        private val FEATURE_MODULES =
            setOf(
                "analytics",
                "contact",
                "datadog",
                "featureflags",
                "llm",
                "mcp",
                "monitoring",
                "overview",
                "sso",
                "statuspage",
                "summary",
            )
        private val FEATURE_IMPORT_EDGES =
            sortedSetOf(
                "analytics -> monitoring",
                "datadog -> monitoring",
                "llm -> monitoring",
                "mcp -> featureflags",
                "mcp -> statuspage",
                "overview -> statuspage",
            )
    }
}
