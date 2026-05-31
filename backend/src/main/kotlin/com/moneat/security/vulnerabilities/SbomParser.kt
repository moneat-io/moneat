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

import com.moneat.datadog.models.DdSbomPayload
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

private const val MAX_SBOM_BYTES = 5 * 1024 * 1024
private const val MAX_PACKAGES = 5_000
private const val MAX_STRING_LENGTH = 512
private const val MAX_PURL_LENGTH = 2_048
private const val MAX_LICENSES = 20
private const val MAX_ECOSYSTEM_LENGTH = 64

class SbomValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object SbomParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawBody: ByteArray): ParsedSbom {
        validateSize(rawBody)
        val root = parseJson(rawBody)
        val parsed = when {
            root.s("bomFormat").equals("CycloneDX", ignoreCase = true) -> parseCycloneDx(root)
            root["spdxVersion"] != null || root["SPDXID"] != null -> parseSpdx(root)
            else -> throw SbomValidationException("Unsupported SBOM format")
        }
        if (parsed.packages.isEmpty()) {
            throw SbomValidationException("SBOM contains no packages with name and version")
        }
        if (parsed.packages.size > MAX_PACKAGES) {
            throw SbomValidationException("SBOM package count exceeds limit")
        }
        return parsed
    }

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
            throw SbomValidationException("SBOM contains no packages with name and version")
        }
        return ParsedSbom(
            format = SbomFormat.AGENT,
            packages = packages,
            targetName = clean(payload.imageName).ifBlank { clean(payload.host) },
        )
    }

    private fun validateSize(rawBody: ByteArray) {
        if (rawBody.isEmpty()) {
            throw SbomValidationException("SBOM payload is empty")
        }
        if (rawBody.size > MAX_SBOM_BYTES) {
            throw SbomValidationException("SBOM payload is too large")
        }
    }

    private fun parseJson(rawBody: ByteArray): JsonObject {
        val element = try {
            json.parseToJsonElement(rawBody.decodeToString())
        } catch (e: SerializationException) {
            throw SbomValidationException("Malformed SBOM JSON", e)
        }
        return element as? JsonObject ?: throw SbomValidationException("SBOM JSON root must be an object")
    }

    private fun parseCycloneDx(root: JsonObject): ParsedSbom {
        val packages = root.a("components").mapNotNull { element ->
            val component = element as? JsonObject ?: return@mapNotNull null
            val name = clean(component.s("name"))
            val version = clean(component.s("version"))
            if (name.isBlank() || version.isBlank()) {
                return@mapNotNull null
            }
            val purl = cleanPurl(component.s("purl"))
            val purlParts = parsePurl(purl)
            SbomPackageRecord(
                name = name,
                version = version,
                packageType = clean(purlParts.type.ifBlank { component.s("type") }),
                ecosystem = normalizeEcosystem(purlParts.type.ifBlank { component.s("type") }),
                purl = purl,
                licenses = parseCycloneDxLicenses(component.a("licenses")),
                supplier = clean(component.obj("supplier")?.s("name") ?: component.s("supplier")),
                bomRef = clean(component.s("bom-ref")),
            )
        }
        return ParsedSbom(
            format = SbomFormat.CYCLONEDX,
            packages = packages,
            targetName = root.obj("metadata")?.obj("component")?.s("name")?.let(::clean).orEmpty(),
        )
    }

    private fun parseSpdx(root: JsonObject): ParsedSbom {
        val packages = root.a("packages").mapNotNull { element ->
            val pkg = element as? JsonObject ?: return@mapNotNull null
            val name = clean(pkg.s("name"))
            val version = clean(pkg.s("versionInfo"))
            if (name.isBlank() || version.isBlank()) {
                return@mapNotNull null
            }
            val purl = spdxPurl(pkg.a("externalRefs"))
            val purlParts = parsePurl(purl)
            SbomPackageRecord(
                name = name,
                version = version,
                packageType = clean(purlParts.type),
                ecosystem = normalizeEcosystem(purlParts.type),
                purl = purl,
                licenses = spdxLicenses(pkg),
                supplier = clean(pkg.s("supplier").removePrefix("Organization:").trim()),
                bomRef = clean(pkg.s("SPDXID")),
            )
        }
        return ParsedSbom(
            format = SbomFormat.SPDX,
            packages = packages,
            targetName = clean(root.s("name")),
        )
    }

    private fun parseCycloneDxLicenses(licenses: List<JsonElement>): List<String> =
        licenses.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val license = obj.obj("license")
            val expression = obj.s("expression")
            val value = license?.s("id").orEmpty()
                .ifBlank { license?.s("name").orEmpty() }
                .ifBlank { expression }
            clean(value).takeIf { it.isNotBlank() }
        }.take(MAX_LICENSES)

    private fun spdxPurl(externalRefs: List<JsonElement>): String {
        externalRefs.forEach { element ->
            val ref = element as? JsonObject ?: return@forEach
            val category = ref.s("referenceCategory")
            val type = ref.s("referenceType")
            if (category.equals("PACKAGE-MANAGER", ignoreCase = true) && type.equals("purl", ignoreCase = true)) {
                return cleanPurl(ref.s("referenceLocator"))
            }
        }
        return ""
    }

    private fun spdxLicenses(pkg: JsonObject): List<String> {
        val values = listOf(pkg.s("licenseConcluded"), pkg.s("licenseDeclared"))
        return values.map { clean(it) }
            .filter { it.isNotBlank() && it != "NOASSERTION" && it != "NONE" }
            .distinct()
            .take(MAX_LICENSES)
    }

    private fun parsePurl(purl: String): PurlParts {
        if (!purl.startsWith("pkg:")) return PurlParts()
        val withoutPrefix = purl.removePrefix("pkg:")
        val typeEnd = withoutPrefix.indexOf('/')
        if (typeEnd <= 0) return PurlParts()
        val rawType = withoutPrefix.substring(0, typeEnd)
        return PurlParts(type = clean(rawType))
    }

    private fun cleanPurl(value: String): String = clean(value, MAX_PURL_LENGTH)

    internal fun clean(value: String, maxLength: Int = MAX_STRING_LENGTH): String =
        value.replace('\u0000', ' ').trim().take(maxLength)

    internal fun normalizeEcosystem(value: String): String =
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

    private fun JsonObject.s(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.let { it as? JsonObject }

    private fun JsonObject.a(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.jsonArray ?: emptyList()

    private data class PurlParts(
        val type: String = "",
    )
}
