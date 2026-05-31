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

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Raised when a Sigma document cannot be faithfully translated into a [CreateDetectionRuleRequest].
 * The [message] is a human-readable, schema-free reason; it never carries ClickHouse text (the importer
 * never reaches ClickHouse — the resulting rule is only ever executed later through [RuleQueryCompiler]).
 *
 * Faithfulness is a security property: a Sigma construct that cannot be mapped exactly is **rejected**,
 * never approximated, so an import can neither silently widen a rule (false negatives) nor mistranslate
 * it into something broader than the author intended.
 */
class SigmaImportException(message: String) : IllegalArgumentException(message)

/**
 * Translates a community **Sigma** rule (the open YAML detection-rule standard) into a Moneat
 * [CreateDetectionRuleRequest] whose `filter` is a [LogQueryParser][com.moneat.logs.services.LogQueryParser]
 * expression. The produced request is created through the **same** path as a hand-authored rule
 * ([DetectionRuleService.create]), so it is compiled and sandboxed by [RuleQueryCompiler] and gets no
 * more power than a hand-written one — same org injection, same column whitelist, same caps, no raw SQL.
 *
 * ### Supported safe subset
 * - **logsource**: `category`/`product`/`service` are recorded as `tags[...]` and used to pick a default
 *   `group_by`; a logsource that is not a log/process/auth-type source we can serve is rejected.
 * - **detection selections**:
 *   - field equality (`field: value`) → `@field:"value"`;
 *   - value modifiers `contains` / `startswith` / `endswith` → wildcard match (`*v*`, `v*`, `*v`);
 *   - the `all` modifier on a list → AND of the values (default for a list is OR);
 *   - lists of values (OR), maps of fields (AND);
 *   - keyword / full-text selections (a bare list) → full-text terms (`*:term`).
 * - **condition**: `and` / `or` / `not`, `1 of <pattern>` / `all of <pattern>` (and `… of them`),
 *   and simple parenthesised groups; selection identifiers resolve to the mapped selections above.
 *
 * Everything else — regex (`|re`), base64/utf16/wide/cidr/numeric-comparison modifiers, aggregation
 * conditions (`| count() …`), `near`, timeframes, and any field name that cannot be expressed as a safe
 * identifier — is rejected with a [SigmaImportException], because translating it could produce a wrong
 * or over-broad rule. Imported rules are always created `enabled = false`.
 */
class SigmaImporter {

    /** One parsed-and-mapped Sigma document, ready for [DetectionRuleService.create]. */
    data class MappedRule(
        val request: CreateDetectionRuleRequest,
        /** The Sigma `title`, surfaced in per-document import results for correlation. */
        val title: String,
    )

    private val yaml: Yaml = Yaml(SafeConstructor(loaderOptions()))

    /**
     * Parse and map a single Sigma YAML document. Multi-document YAML is rejected here so the caller can
     * split on `---` and report a per-document result; pass exactly one document.
     *
     * @throws SigmaImportException with a schema-free message on any unmappable construct.
     */
    fun toRuleRequest(sigmaYaml: String): MappedRule {
        val root = parseSingleDocument(sigmaYaml)
        val title = requireText(root["title"], "title")
        val description = optionalText(root["description"])
        val severity = mapLevel(optionalText(root["level"]))
        val logsourceTags = mapLogsource(root["logsource"])

        val detection = asMap(root["detection"])
            ?: throw SigmaImportException("Sigma rule '$title' has no detection block")
        val condition = requireText(detection["condition"], "detection.condition")
        rejectAggregation(condition)

        val selections = buildSelections(detection, title)
        val filter = SigmaConditionCompiler(selections).compile(condition)
        if (filter.isBlank()) {
            throw SigmaImportException("Sigma rule '$title' produced an empty filter")
        }

        val groupBy = defaultGroupBy(logsourceTags)
        val request = CreateDetectionRuleRequest(
            name = title,
            description = description,
            source = DetectionSource.LOGS.wire,
            filter = filter,
            groupBy = groupBy,
            windowSeconds = DEFAULT_WINDOW_SECONDS,
            type = DetectionRuleType.THRESHOLD.wire,
            thresholdCount = DEFAULT_THRESHOLD,
            severity = severity,
            signalTitle = title,
            signalMessage = description.ifBlank { title },
            enabled = false,
            tags = sigmaTags(root, logsourceTags),
        )
        return MappedRule(request = request, title = title)
    }

    // ── Document parsing ────────────────────────────────────────────────────

    private fun parseSingleDocument(sigmaYaml: String): Map<String, Any?> {
        if (sigmaYaml.isBlank()) throw SigmaImportException("Sigma document is empty")
        if (sigmaYaml.length > MAX_DOCUMENT_CHARS) {
            // Reject before handing to snakeyaml; bounds the aggregate request even at the document cap.
            throw SigmaImportException("Sigma document is too large (limit ${MAX_DOCUMENT_KB}KB)")
        }
        val docs = try {
            yaml.loadAll(sigmaYaml).toList()
        } catch (e: org.yaml.snakeyaml.error.YAMLException) {
            // Surface a generic message; the underlying parser text can include input fragments.
            throw SigmaImportException("Sigma document is not valid YAML: ${e.message?.take(MSG_PREVIEW) ?: ""}")
        }
        val nonNull = docs.filterNotNull()
        if (nonNull.isEmpty()) throw SigmaImportException("Sigma document is empty")
        if (nonNull.size > 1) {
            throw SigmaImportException("Expected a single Sigma document; split multi-document YAML on '---'")
        }
        return asMap(nonNull.single())
            ?: throw SigmaImportException("Sigma document is not a mapping")
    }

    // ── Metadata mapping ────────────────────────────────────────────────────

    /** Sigma `level` → [com.moneat.security.signals.SignalSeverity] wire value. Unknown/absent → medium. */
    private fun mapLevel(level: String?): String = when (level?.trim()?.lowercase()) {
        "informational", "info" -> "info"
        "low" -> "low"
        "medium" -> "medium"
        "high" -> "high"
        "critical" -> "critical"
        null, "" -> "medium"
        else -> throw SigmaImportException("Unsupported Sigma level '${sanitize(level)}'")
    }

    /**
     * Validate the `logsource` targets something we can serve from logs, and return its
     * `category`/`product`/`service` for tagging + default grouping. We do not reject on product/service
     * (logs come from many products); we reject categories that are clearly non-log telemetry we cannot
     * evaluate over the `logs` table (e.g. raw network-flow or registry sources with no log representation).
     */
    private fun mapLogsource(raw: Any?): LogsourceTags {
        val map = asMap(raw) ?: return LogsourceTags(null, null, null)
        val category = optionalText(map["category"]).lowercase().ifBlank { null }
        val product = optionalText(map["product"]).lowercase().ifBlank { null }
        val service = optionalText(map["service"]).lowercase().ifBlank { null }
        if (category != null && category in REJECTED_CATEGORIES) {
            throw SigmaImportException(
                "Sigma logsource category '${sanitize(category)}' is not a log source Moneat can evaluate"
            )
        }
        return LogsourceTags(category, product, service)
    }

    private fun defaultGroupBy(tags: LogsourceTags): List<String> {
        // Group by host so signals dedup per machine; auth-type sources additionally key on the user
        // attribute, which the log search routes to tags/resource_attributes. Host is always a real column.
        val groups = mutableListOf("host")
        val authy = tags.category in AUTH_CATEGORIES || tags.service in AUTH_SERVICES
        if (authy) groups += "tags['user']"
        return groups
    }

    private fun sigmaTags(root: Map<String, Any?>, logsource: LogsourceTags): List<String> {
        val tags = mutableListOf("imported:sigma")
        logsource.category?.let { tags += "sigma.category:$it" }
        logsource.product?.let { tags += "sigma.product:$it" }
        logsource.service?.let { tags += "sigma.service:$it" }
        // Sigma `tags:` (MITRE attack.* etc.) carry over as plain labels when they are simple scalars.
        (root["tags"] as? List<*>)?.forEach { tag ->
            (tag as? String)?.let { tags += "sigma:${it.take(TAG_PREVIEW)}" }
        }
        return tags
    }

    // ── Selection mapping ───────────────────────────────────────────────────

    /** Maps every named entry of the detection block (except `condition`) to a filter fragment. */
    private fun buildSelections(detection: Map<String, Any?>, title: String): Map<String, String> =
        detection.entries
            .filter { it.key != "condition" }
            .associate { (name, value) -> name to mapSelection(name, value, title) }

    private fun mapSelection(name: String, value: Any?, title: String): String = when (value) {
        is Map<*, *> -> mapFieldMap(value, title)
        is List<*> -> mapKeywordList(value, title)
        is String -> fullTextTerm(value)
        null -> throw SigmaImportException("Sigma rule '$title' selection '${sanitize(name)}' is empty")
        else -> throw SigmaImportException(
            "Sigma rule '$title' selection '${sanitize(name)}' has an unsupported shape"
        )
    }

    /** A map selection = AND of `field[: modifiers]: value` entries. */
    private fun mapFieldMap(map: Map<*, *>, title: String): String {
        if (map.isEmpty()) throw SigmaImportException("Sigma rule '$title' has an empty selection map")
        val conjuncts = map.entries.map { (rawKey, rawValue) ->
            val key = (rawKey as? String)
                ?: throw SigmaImportException("Sigma rule '$title' has a non-string field key")
            mapFieldCondition(key, rawValue, title)
        }
        return joinAnd(conjuncts)
    }

    /** A bare list selection = OR of full-text keyword terms. */
    private fun mapKeywordList(list: List<*>, title: String): String {
        if (list.isEmpty()) throw SigmaImportException("Sigma rule '$title' has an empty keyword list")
        val terms = list.map { item ->
            val text = item as? String
                ?: throw SigmaImportException("Sigma rule '$title' keyword list has a non-string entry")
            fullTextTerm(text)
        }
        return joinOr(terms)
    }

    /** Maps one `field|modifier...: value-or-list` entry into a parenthesised filter fragment. */
    private fun mapFieldCondition(rawField: String, rawValue: Any?, title: String): String {
        val parts = rawField.split("|")
        val fieldName = parts.first()
        val modifiers = parts.drop(1).map { it.lowercase() }
        val safeField = safeFieldName(fieldName, title)
        rejectUnsupportedModifiers(modifiers, title)

        val combineWithAnd = modifiers.contains("all")
        val valueModifier = modifiers.firstOrNull { it in VALUE_MODIFIERS }

        val rendered = when (rawValue) {
            is List<*> -> {
                val fragments = rawValue.map { v -> fieldValueFragment(safeField, scalar(v, title), valueModifier) }
                if (combineWithAnd) joinAnd(fragments) else joinOr(fragments)
            }

            null -> fieldNullFragment(safeField)
            else -> fieldValueFragment(safeField, scalar(rawValue, title), valueModifier)
        }
        return rendered
    }

    /** `field: null` in Sigma means the field is absent → negated existence. */
    private fun fieldNullFragment(field: String): String = "-@$field:*"

    /**
     * One `field op value` fragment in [LogQueryParser][com.moneat.logs.services.LogQueryParser] syntax.
     * Equality uses a quoted value so separators bind as a literal; the contains/startswith/endswith
     * modifiers append the modifier's own wildcard, which the parser turns into an `ILIKE` (values are
     * still escaped by the compiler, so a value can never become SQL structure).
     */
    private fun fieldValueFragment(field: String, value: String, modifier: String?): String {
        val token = when (modifier) {
            "contains" -> "*${wildcardValue(value, modifier)}*"
            "startswith" -> "${wildcardValue(value, modifier)}*"
            "endswith" -> "*${wildcardValue(value, modifier)}"
            null -> null
            else -> throw SigmaImportException("Unsupported Sigma value modifier '${sanitize(modifier)}'")
        }
        return if (token == null) {
            "@$field:${quote(value)}"
        } else {
            // A wildcard token is unquoted (the parser treats * / ? literally inside quotes).
            "@$field:$token"
        }
    }

    private fun fullTextTerm(term: String): String = "*:${quote(term)}"

    // ── Validation helpers ──────────────────────────────────────────────────

    private fun rejectUnsupportedModifiers(modifiers: List<String>, title: String) {
        modifiers.forEach { mod ->
            if (mod !in SUPPORTED_MODIFIERS) {
                throw SigmaImportException(
                    "Sigma rule '$title' uses an unsupported field modifier '${sanitize(mod)}'"
                )
            }
        }
        if (modifiers.count { it in VALUE_MODIFIERS } > 1) {
            throw SigmaImportException("Sigma rule '$title' combines incompatible value modifiers")
        }
    }

    private fun rejectAggregation(condition: String) {
        if (condition.contains("|")) {
            throw SigmaImportException("Aggregation conditions (| count/min/max/…) are not supported")
        }
    }

    /**
     * Ensures a Sigma field maps to a [LogQueryParser][com.moneat.logs.services.LogQueryParser] field
     * name that tokenizes cleanly. Sigma uses dotted/CamelCase names (e.g. `Image`, `process.name`); we
     * accept an identifier-ish charset only, so a name can never carry the parser's control chars
     * (`:`, quotes, parentheses, whitespace) that could change how the filter is structured. Unknown but
     * safe names flow through the parser to `tags[…]`/`resource_attributes[…]`, never to a real column it
     * does not whitelist, so this is the safe "map unknown fields to tags only if safe, else reject" rule.
     */
    private fun safeFieldName(field: String, title: String): String {
        val trimmed = field.trim()
        if (trimmed.isEmpty() || !FIELD_NAME_REGEX.matches(trimmed)) {
            throw SigmaImportException(
                "Sigma rule '$title' field '${sanitize(field)}' cannot be mapped to a safe attribute name"
            )
        }
        return trimmed
    }

    private fun scalar(value: Any?, title: String): String = when (value) {
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        null -> throw SigmaImportException("Sigma rule '$title' has a null value where a scalar was expected")
        else -> throw SigmaImportException("Sigma rule '$title' has a non-scalar value in a field condition")
    }

    // ── Small text utilities ────────────────────────────────────────────────

    private fun requireText(value: Any?, field: String): String {
        val text = (value as? String)?.trim().orEmpty()
        if (text.isEmpty()) throw SigmaImportException("Sigma rule is missing required field '$field'")
        return text
    }

    private fun optionalText(value: Any?): String = (value as? String)?.trim().orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

    /** Quote a value for the parser and neutralise the quote/backslash the parser itself unescapes. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /**
     * Prepare a `contains`/`startswith`/`endswith` value for an unquoted wildcard token. A raw `*` or `?`
     * in such a value is **rejected**, not escaped or passed through: faithfully matching a Sigma literal
     * `a*b` is ambiguous (Sigma treats it as a literal there, but emitting it would either widen the rule
     * to an extra wildcard or silently drop the glob), so per the "never silently produce an over-broad
     * rule" requirement we refuse it rather than guess. The backslash and quote are escaped so the value
     * still cannot break out of the parser token; the modifier's own surrounding `*` stays a wildcard.
     */
    private fun wildcardValue(value: String, modifier: String): String {
        if (value.contains('*') || value.contains('?')) {
            throw SigmaImportException(
                "Sigma '$modifier' value '${sanitize(value)}' contains a literal '*'/'?'; " +
                    "this cannot be mapped without changing the rule's breadth"
            )
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun joinAnd(fragments: List<String>): String = combine(fragments, "AND")

    private fun joinOr(fragments: List<String>): String = combine(fragments, "OR")

    private fun combine(fragments: List<String>, op: String): String {
        val nonBlank = fragments.filter { it.isNotBlank() }
        return when (nonBlank.size) {
            0 -> ""
            1 -> nonBlank.single()
            else -> "(" + nonBlank.joinToString(" $op ") + ")"
        }
    }

    private fun sanitize(token: String): String =
        token.take(TOKEN_PREVIEW).filter { it.isLetterOrDigit() || it in "_-. /:|" }

    private data class LogsourceTags(val category: String?, val product: String?, val service: String?)

    companion object {
        private const val DEFAULT_THRESHOLD = 1
        private const val MSG_PREVIEW = 80
        private const val TOKEN_PREVIEW = 60
        private const val TAG_PREVIEW = 60

        private const val BYTES_PER_KB = 1024

        /** Per-document input cap (~512KB). Bounds parser work and the aggregate request body up front. */
        private const val MAX_DOCUMENT_KB = 512
        private const val MAX_DOCUMENT_CHARS = MAX_DOCUMENT_KB * BYTES_PER_KB

        /** snakeyaml DoS limits pinned explicitly so safety does not depend on library defaults. */
        private const val YAML_NESTING_DEPTH_LIMIT = 50
        private const val YAML_MAX_ALIASES = 50
        private const val YAML_CODEPOINT_LIMIT = MAX_DOCUMENT_CHARS

        /**
         * A [LoaderOptions] with the DoS limits set explicitly (rather than relying on snakeyaml's
         * defaults, which can shift across upgrades): no duplicate keys, no recursive keys, a bounded
         * alias count, a bounded nesting depth, and a tight per-document code-point limit. Combined with
         * [SafeConstructor]/`UnTrustedTagInspector` this keeps untrusted Sigma YAML from exhausting CPU
         * or memory.
         */
        private fun loaderOptions(): LoaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            allowRecursiveKeys = false
            maxAliasesForCollections = YAML_MAX_ALIASES
            nestingDepthLimit = YAML_NESTING_DEPTH_LIMIT
            codePointLimit = YAML_CODEPOINT_LIMIT
        }

        /** Field name charset: identifier chars plus the dot/dash Sigma uses; nothing the parser treats specially. */
        private val FIELD_NAME_REGEX = Regex("^[A-Za-z0-9_.-]{1,128}$")

        /** Modifiers that change how a single value is matched. At most one may apply to a value. */
        private val VALUE_MODIFIERS = setOf("contains", "startswith", "endswith")

        /** The full set of field modifiers we can translate faithfully (`all` toggles list AND). */
        private val SUPPORTED_MODIFIERS = VALUE_MODIFIERS + "all"

        /** Sigma logsource categories with no representation in the `logs` table → reject up front. */
        private val REJECTED_CATEGORIES = setOf(
            "registry_event", "registry_set", "registry_add", "registry_delete", "registry_rename",
            "raw_access_thread", "create_remote_thread", "image_load", "driver_load",
            "network_connection", "dns_query", "pipe_created", "wmi_event",
        )

        private val AUTH_CATEGORIES = setOf("authentication")
        private val AUTH_SERVICES = setOf("auth", "authentication", "sshd", "sudo", "security", "system")
    }
}
