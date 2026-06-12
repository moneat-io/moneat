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

package com.moneat.contracts

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class PublicSerializableResourceIdsTest {
    @Test
    fun `public serializable DTOs do not expose numeric resource identifiers`() {
        val violations =
            sourceRoots()
                .flatMap(::findNumericIdLikeFields)
                .filterNot { it.isAllowedInternalIdentifier() }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Serializable public DTOs must expose resource_id UUID strings, not numeric DB IDs.")
                appendLine(
                    "If this is an internal queue, protocol-native, or third-party identifier, " +
                        "allowlist it here."
                )
                violations.forEach { violation ->
                    appendLine(
                        "${violation.relativePath}:${violation.lineNumber} " +
                            "${violation.className}.${violation.fieldName}: ${violation.typeName}"
                    )
                }
            }
        )
    }

    private fun Violation.isAllowedInternalIdentifier(): Boolean =
        allowedInternalIdentifiers.contains(
            AllowedInternalIdentifier(
                relativePath = relativePath,
                className = className,
                fieldName = fieldName
            )
        )

    private fun sourceRoots(): List<SourceRoot> {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        val repoRoot =
            if (workingDirectory.name == "backend") {
                workingDirectory.parent
            } else {
                workingDirectory
            }

        return listOf(
            SourceRoot("backend", repoRoot.resolve("backend/src/main/kotlin")),
            SourceRoot("ee", repoRoot.resolve("ee/backend/src/main/kotlin"))
        ).filter { it.path.exists() }
    }

    private fun findNumericIdLikeFields(sourceRoot: SourceRoot): List<Violation> {
        val violations = mutableListOf<Violation>()

        Files.walk(sourceRoot.path).use { paths ->
            paths
                .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
                .forEach { path ->
                    violations += findNumericIdLikeFields(sourceRoot, path)
                }
        }

        return violations.sortedWith(
            compareBy<Violation> { it.relativePath }
                .thenBy { it.lineNumber }
                .thenBy { it.className }
                .thenBy { it.fieldName }
        )
    }

    private fun findNumericIdLikeFields(sourceRoot: SourceRoot, path: Path): List<Violation> {
        val text = path.readText()
        val relativePath = sourceRoot.relativePath(path)
        return findSerializableClasses(text).flatMap { serializableClass ->
            serializableClass.parameters
                .filter { parameter -> parameter.isPublicNumericIdentifier() }
                .map { parameter ->
                    Violation(
                        relativePath = relativePath,
                        lineNumber = parameter.lineNumber,
                        className = serializableClass.name,
                        fieldName = parameter.name,
                        typeName = parameter.typeName
                    )
                }
        }
    }

    private fun SourceRoot.relativePath(path: Path): String =
        "$label:${this.path.relativize(path).toString().replace('\\', '/')}"

    private fun findSerializableClasses(text: String): List<SerializableClass> =
        serializableDataClassPattern
            .findAll(text)
            .mapNotNull { match ->
                val constructorStart = match.range.last
                val constructorEnd = findMatchingParenthesis(text, constructorStart) ?: return@mapNotNull null
                val className = match.groupValues[1]
                val parameters =
                    splitConstructorParameters(text, constructorStart + 1, constructorEnd)
                        .mapNotNull { segment ->
                            parseConstructorParameter(
                                segment = segment.text,
                                lineNumber = lineNumberAt(text, segment.startIndex)
                            )
                        }

                SerializableClass(name = className, parameters = parameters)
            }
            .toList()

    private fun splitConstructorParameters(
        text: String,
        startIndex: Int,
        endIndex: Int
    ): List<ConstructorSegment> {
        val segments = mutableListOf<ConstructorSegment>()
        val state = KotlinTextState()
        var segmentStart = startIndex

        for (index in startIndex until endIndex) {
            val char = text[index]
            state.consume(char, text, index)
            if (char == ',' && state.isTopLevel()) {
                segments += ConstructorSegment(
                    text = text.substring(segmentStart, index),
                    startIndex = segmentStart
                )
                segmentStart = index + 1
            }
        }

        segments += ConstructorSegment(
            text = text.substring(segmentStart, endIndex),
            startIndex = segmentStart
        )
        return segments
    }

    private fun parseConstructorParameter(
        segment: String,
        lineNumber: Int
    ): ConstructorParameter? {
        if (transientAnnotationPattern.containsMatchIn(segment)) {
            return null
        }

        val normalizedSegment = segment.replace('\n', ' ')
        val declaration = constructorParameterPattern.find(normalizedSegment) ?: return null

        return ConstructorParameter(
            name = declaration.groupValues[1].trim('`'),
            typeName = declaration.groupValues[2].trim(),
            lineNumber = lineNumber
        )
    }

    private fun ConstructorParameter.isPublicNumericIdentifier(): Boolean =
        isIdLikeName(name) && numericTypePattern.containsMatchIn(typeName)

    private fun isIdLikeName(name: String): Boolean =
        name == "id" ||
            name == "ids" ||
            name.endsWith("Id") ||
            name.endsWith("Ids") ||
            name.endsWith("_id") ||
            name.endsWith("_ids") ||
            name.endsWith("By") ||
            name.endsWith("_by") ||
            name.endsWith("To") ||
            name.endsWith("_to")

    private fun findMatchingParenthesis(text: String, startIndex: Int): Int? {
        val state = ParenthesisMatchState()

        for (index in startIndex until text.length) {
            if (state.consume(text[index], text, index)) return index
        }

        return null
    }

    private fun lineNumberAt(text: String, index: Int): Int =
        text.take(index).count { it == '\n' } + 1

    private data class SourceRoot(
        val label: String,
        val path: Path
    )

    private data class SerializableClass(
        val name: String,
        val parameters: List<ConstructorParameter>
    )

    private data class ConstructorParameter(
        val name: String,
        val typeName: String,
        val lineNumber: Int
    )

    private data class ConstructorSegment(
        val text: String,
        val startIndex: Int
    )

    private data class Violation(
        val relativePath: String,
        val lineNumber: Int,
        val className: String,
        val fieldName: String,
        val typeName: String
    )

    private data class AllowedInternalIdentifier(
        val relativePath: String,
        val className: String,
        val fieldName: String
    )

    private class ParenthesisMatchState {
        private var depth = 0
        private val textState = KotlinTextState()

        fun consume(char: Char, text: String, index: Int): Boolean {
            val matched = !textState.isInsideString() && updateDepth(char)
            textState.consume(char, text, index)
            return matched
        }

        private fun updateDepth(char: Char): Boolean =
            when (char) {
                '(' -> {
                    depth += 1
                    false
                }
                ')' -> {
                    depth -= 1
                    depth == 0
                }
                else -> false
            }
    }

    private class KotlinTextState {
        private var escaped = false
        private var insideString = false
        private var insideTripleString = false
        private var angleDepth = 0
        private var braceDepth = 0
        private var bracketDepth = 0
        private var parenthesisDepth = 0

        fun consume(char: Char, text: String, index: Int) {
            if (consumeTripleString(text, index)) return
            if (consumeString(char)) return
            if (startString(char, text, index)) return

            consumeStructuralChar(char)
        }

        private fun consumeTripleString(text: String, index: Int): Boolean {
            if (!insideTripleString) return false
            if (text.startsWith("\"\"\"", index)) {
                insideTripleString = false
            }
            return true
        }

        private fun consumeString(char: Char): Boolean {
            if (!insideString) return false
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> insideString = false
            }
            return true
        }

        private fun startString(char: Char, text: String, index: Int): Boolean {
            if (text.startsWith("\"\"\"", index)) {
                insideTripleString = true
                return true
            }
            if (char == '"') {
                insideString = true
                return true
            }
            return false
        }

        private fun consumeStructuralChar(char: Char) {
            when (char) {
                '<' -> angleDepth += 1
                '>' -> if (angleDepth > 0) angleDepth -= 1
                '{' -> braceDepth += 1
                '}' -> if (braceDepth > 0) braceDepth -= 1
                '[' -> bracketDepth += 1
                ']' -> if (bracketDepth > 0) bracketDepth -= 1
                '(' -> parenthesisDepth += 1
                ')' -> if (parenthesisDepth > 0) parenthesisDepth -= 1
            }
        }

        fun isInsideString(): Boolean =
            insideString || insideTripleString

        fun isTopLevel(): Boolean =
            !isInsideString() &&
                angleDepth == 0 &&
                braceDepth == 0 &&
                bracketDepth == 0 &&
                parenthesisDepth == 0
    }

    private companion object {
        private val serializableDataClassPattern =
            Regex(
                """@Serializable(?:\([^)]*\))?\s*(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s*)*""" +
                    """(?:data\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""
            )
        private val constructorParameterPattern =
            Regex("""\b(?:val|var)\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^=]+)""")
        private val transientAnnotationPattern = Regex("""@(?:kotlinx\.serialization\.)?Transient\b""")
        private val numericTypePattern = Regex("""\b(?:Int|Long|Short|UInt|ULong|UShort)\b""")

        private val allowedInternalIdentifiers =
            buildSet {
                addAll(
                    allow(
                        "backend:com/moneat/analytics/models/AnalyticsModels.kt",
                        "EnrichedAnalyticsEvent",
                        "projectId"
                    )
                )
                addAll(allow("backend:com/moneat/auth/services/OAuthService.kt", "GitHubUser", "id"))
                addAll(
                    allow(
                        "backend:com/moneat/datadog/models/DatadogAdvancedModels.kt",
                        "DdTelemetryPayload",
                        "seqId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/models/DatadogModels.kt",
                        "DatadogProcessPayload",
                        "groupId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/models/DatadogProfileModels.kt",
                        "DdProfileEndpoint",
                        "localRootSpanId",
                        "traceId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/models/DatadogTraceModels.kt",
                        "DdSpan",
                        "traceId",
                        "spanId",
                        "parentId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/networkdevices/NdmIngestionService.kt",
                        "QueuedNdmBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/security/SecurityIngestionService.kt",
                        "QueuedSecurityBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DatadogEventService.kt",
                        "QueuedEventBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DatadogEventService.kt",
                        "QueuedServiceCheckBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DatadogInfraService.kt",
                        "QueuedInfraBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DatadogMetricService.kt",
                        "QueuedMetricBatch",
                        "organizationId",
                        "projectId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DatadogMetricService.kt",
                        "QueuedSketchBatch",
                        "organizationId",
                        "projectId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DbmIngestionService.kt",
                        "QueuedDbmBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/DebuggerIngestionService.kt",
                        "QueuedDebuggerBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/MiscIngestionService.kt",
                        "QueuedMiscBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/datadog/services/OrchestratorIngestionService.kt",
                        "QueuedK8sResourceBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/events/models/SentryModels.kt",
                        "SentryReplayEvent",
                        "segmentId"
                    )
                )
                addAll(allow("backend:com/moneat/logs/models/LogModels.kt", "LogIngestEntry", "projectId"))
                addAll(
                    allow(
                        "backend:com/moneat/logs/models/LogModels.kt",
                        "QueuedLogBatch",
                        "organizationId",
                        "legacyProjectId",
                        "hostId"
                    )
                )
                addAll(allow("backend:com/moneat/logs/models/LogModels.kt", "QueuedLogEntry", "projectId"))
                addAll(
                    allow(
                        "backend:com/moneat/otlp/services/OtlpMetricsService.kt",
                        "OtlpMetricInsert",
                        "organizationId",
                        "projectId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/otlp/services/OtlpMetricsService.kt",
                        "QueuedOtlpMetricsBatch",
                        "organizationId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/otlp/services/OtlpTraceService.kt",
                        "OtlpSpanInsert",
                        "organizationId",
                        "projectId"
                    )
                )
                addAll(
                    allow(
                        "backend:com/moneat/otlp/services/OtlpTraceService.kt",
                        "QueuedOtlpTraceBatch",
                        "organizationId"
                    )
                )
                addAll(allow("backend:com/moneat/sso/services/SsoService.kt", "SsoStateData", "orgId"))
                addAll(
                    allow(
                        "backend:com/moneat/workflows/models/WorkflowModels.kt",
                        "WorkflowTriggerEvent",
                        "organizationId"
                    )
                )
            }

        private fun allow(
            relativePath: String,
            className: String,
            vararg fieldNames: String
        ): List<AllowedInternalIdentifier> =
            fieldNames.map { fieldName ->
                AllowedInternalIdentifier(
                    relativePath = relativePath,
                    className = className,
                    fieldName = fieldName
                )
            }
    }
}
