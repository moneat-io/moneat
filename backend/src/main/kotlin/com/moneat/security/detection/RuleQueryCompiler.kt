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

import com.moneat.config.EnvConfig
import com.moneat.logs.services.LogQueryParser
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql

/**
 * Raised when a rule cannot be compiled. The [message] is always a **DSL-level** description ("unknown
 * group-by column", "window must be at least…") and never contains ClickHouse parser/schema text, so
 * compiler failures can be surfaced to the rule author without leaking DB internals.
 */
class DetectionCompileException(message: String) : IllegalArgumentException(message)

/**
 * A compiled, parameterized, org-scoped ClickHouse aggregation for a detection rule, plus the metadata
 * an evaluator/preview needs to interpret its rows and build signal dedup keys/entities.
 */
data class CompiledRuleQuery(
    /** Full `SELECT … GROUP BY … HAVING … SETTINGS …` aggregation text, ready to execute. */
    val sql: String,
    /** Ordered group-by column names as written by the author (used for result mapping + entities). */
    val groupByColumns: List<String>,
    /** Stable result aliases (`g0`, `g1`, …) for each [groupByColumns] entry. */
    val groupByAliases: List<String>,
    /** Compiled WHERE clause (org clause + filter + window) as embedded in [sql], for diagnostics. */
    val whereClause: String,
    /** A human-readable, schema-free descriptor stored as signal evidence-by-reference. */
    val evidenceDescriptor: String,
    val windowSeconds: Int,
)

/**
 * The security gate of the detection engine. Compiles a [DetectionRule][DetectionRules] row into a
 * ClickHouse aggregation with three non-negotiable isolation properties:
 *
 *  1. **Org injection** — every query's first WHERE conjunct is
 *     [ClickHouseQueryUtils.orgIdClause] built from [orgId], which the caller passes from the
 *     scheduler/route context. No rule field maps to org scoping; the author can neither set, omit,
 *     nor override it. If [orgId] is absent the compiler refuses to emit (fail closed).
 *  2. **Whitelist** — the only `FROM` is the hardcoded `logs` table; the author never controls it.
 *     `group_by` columns must be in [ALLOWED_GROUP_BY_COLUMNS] (or whitelisted `tags[…]` /
 *     `resource_attributes[…]` map access with the key escaped). The `filter` is parsed by the
 *     already-trusted [LogQueryParser] and rendered through its [LogQueryParser.toClickHouseSql],
 *     so author values are escaped via [escapeSql] and can never become SQL structure. Anything the
 *     parser/whitelist doesn't recognize is rejected with a [DetectionCompileException].
 *  3. **Resource caps** — every emitted query (including preview) carries a `SETTINGS` clause with
 *     `max_execution_time`, `max_rows_to_read`, `max_result_rows`, `max_bytes_to_read` and
 *     overflow-mode `throw`, on top of the execution-time cap `ClickHouseClient.execute` already
 *     applies to SELECTs, so a pathological rule cannot saturate the shared cluster.
 *
 * No author-controlled string is ever concatenated as SQL structure.
 */
class RuleQueryCompiler(
    private val clickhouseDb: () -> String,
) {
    companion object {
        /**
         * Real `logs` columns a rule may group by. Deliberately excludes the isolation columns
         * (`organization_id`, `project_id`), the row identity (`log_id`, `system_id`), the unbounded
         * free-text columns (`message`, `body`), and time columns — none are valid grouping keys and
         * the isolation columns must never be author-reachable.
         */
        val ALLOWED_GROUP_BY_COLUMNS: Set<String> = setOf(
            "service", "environment", "host", "source", "level",
            "container_name", "container_id", "container_image",
            "trace_id", "span_id", "index_name",
        )

        /** Map columns that support `col['key']` access in a group-by. */
        val ALLOWED_MAP_COLUMNS: Set<String> = setOf("tags", "resource_attributes")

        val ALLOWED_ANOMALY_GROUP_BY_COLUMNS: Set<String> = setOf(
            "service",
            "environment",
            "host",
            "level",
            "source",
        )

        val ALLOWED_ANOMALY_FILTER_FIELDS: Set<String> = setOf(
            "service",
            "environment",
            "host",
            "level",
            "source",
        )

        const val MIN_WINDOW_SECONDS: Int = 60
        const val MAX_WINDOW_SECONDS: Int = 24 * 60 * 60
        const val MAX_GROUP_BY_COLUMNS: Int = 5
        const val MIN_THRESHOLD: Int = 1
        const val MAX_THRESHOLD: Int = 1_000_000
        const val ANOMALY_BASELINE_SECONDS: Int = 24 * 60 * 60
        private const val ANOMALY_STDDEV_MULTIPLIER = 3

        // map column, then a key restricted to a conservative safe charset (identifier-ish), with
        // optional surrounding quotes. Anything outside this set (quotes-in-key, brackets, parens,
        // spaces, SQL punctuation) fails to match and the column is rejected fail-closed.
        private const val MAP_KEY_PATTERN = """^([a-z_]+)\[(?:'|")?([A-Za-z0-9_.:/-]+)(?:'|")?]$"""
        private val MAP_ACCESS_REGEX = Regex(MAP_KEY_PATTERN)

        /**
         * Builds a safe `<column> = '<value>'` equality predicate for a stored group-by [column] (one
         * key of a signal's `entities`), or `null` if [column] is not a whitelisted top-level column or
         * whitelisted `map['key']` access. Reuses the same whitelist + map-access parsing as a compiled
         * group-by, so evidence sampling can re-scope on **every** dimension a rule grouped on — including
         * `tags[…]` / `resource_attributes[…]` — without widening past the whitelist. [value] is bound via
         * [escape] (the caller's `escapeSql`) and a map key is escaped too, so neither can alter structure.
         */
        fun entityPredicate(column: String, value: String, escape: (String) -> String): String? {
            val trimmed = column.trim()
            if (trimmed in ALLOWED_GROUP_BY_COLUMNS) {
                return "$trimmed = '${escape(value)}'"
            }
            val mapMatch = MAP_ACCESS_REGEX.find(trimmed) ?: return null
            val mapColumn = mapMatch.groupValues[1]
            if (mapColumn !in ALLOWED_MAP_COLUMNS) return null
            val key = escape(mapMatch.groupValues[2])
            return "$mapColumn['$key'] = '${escape(value)}'"
        }

        // Resource caps (env-overridable). Defaults are conservative for a shared multi-tenant cluster.
        private const val ENV_MAX_EXECUTION_TIME = "DETECTION_QUERY_MAX_EXECUTION_SECONDS"
        private const val ENV_MAX_ROWS_TO_READ = "DETECTION_QUERY_MAX_ROWS_TO_READ"
        private const val ENV_MAX_RESULT_ROWS = "DETECTION_QUERY_MAX_RESULT_ROWS"
        private const val ENV_MAX_BYTES_TO_READ = "DETECTION_QUERY_MAX_BYTES_TO_READ"
        private const val DEFAULT_MAX_EXECUTION_TIME = "10"
        private const val DEFAULT_MAX_ROWS_TO_READ = "50000000"
        private const val DEFAULT_MAX_RESULT_ROWS = "10000"
        private const val DEFAULT_MAX_BYTES_TO_READ = "10000000000"
        private const val SELECT_CLAUSE = "SELECT "
        private const val WHERE_CLAUSE = " WHERE "
    }

    private val parser = LogQueryParser()

    /** Inputs the compiler needs from a rule; decoupled from the Exposed row so it is easy to unit-test. */
    data class RuleInput(
        val source: String,
        val filter: String,
        val groupBy: List<String>,
        val windowSeconds: Int,
        val type: DetectionRuleType,
        val thresholdCount: Int?,
    )

    /**
     * Compile [rule] for [orgId]. [orgId] comes from the engine/route context, never the rule.
     *
     * @throws DetectionCompileException with a DSL-level message on any validation failure.
     */
    fun compile(orgId: Int, rule: RuleInput): CompiledRuleQuery {
        validateSource(rule.source)
        val window = validateWindow(rule.windowSeconds)
        if (rule.type == DetectionRuleType.RATE_ANOMALY) {
            return compileRateAnomaly(orgId, rule, window)
        }
        if (rule.type == DetectionRuleType.THRESHOLD) {
            validateThreshold(rule.thresholdCount)
        }
        val groupBy = validateGroupBy(rule.groupBy)
        val aliases = groupBy.indices.map { "g$it" }
        val groupExprs = groupBy.map { columnExpression(it) }

        val whereClause = buildWhere(orgId, rule.filter, window)
        val selectItems = aliases.indices.joinToString(", ") { i -> "${groupExprs[i]} AS ${aliases[i]}" }
        val groupByClause = aliases.joinToString(", ")
        val having = if (rule.type == DetectionRuleType.THRESHOLD) {
            "HAVING count() >= ${rule.thresholdCount}"
        } else {
            ""
        }

        val sql = buildString {
            append(SELECT_CLAUSE)
            if (selectItems.isNotBlank()) append("$selectItems, ")
            append("count() AS match_count")
            append(" FROM `${clickhouseDb()}`.logs")
            append(WHERE_CLAUSE).append(whereClause)
            if (groupByClause.isNotBlank()) append(" GROUP BY ").append(groupByClause)
            if (having.isNotBlank()) append(" ").append(having)
            append(" ").append(settingsClause())
        }

        return CompiledRuleQuery(
            sql = sql,
            groupByColumns = groupBy,
            groupByAliases = aliases,
            whereClause = whereClause,
            evidenceDescriptor = evidenceDescriptor(groupBy, window),
            windowSeconds = window,
        )
    }

    /**
     * The WHERE clause alone (org clause + parsed filter + window), used by [compile] to assemble the
     * aggregation. Private: the only safe entry point is [compile], which always wraps this in the
     * whitelisted SELECT/FROM and the resource caps; there is no supported raw-WHERE path. The
     * new-value path does not reuse this directly — [NewValueEvaluator] re-runs [compile] (it groups
     * without a HAVING), so it inherits the identical org injection and caps.
     */
    private fun buildWhere(orgId: Int, filter: String, windowSeconds: Int): String {
        // Fail closed: a non-positive org id is never a real tenant. Demo orgs use negative ids handled
        // by orgIdClause, so we reject only the impossible 0 / unset case here.
        if (orgId == 0) {
            throw DetectionCompileException("Detection query is missing organization context")
        }
        val window = validateWindow(windowSeconds)
        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(orgId.toLong()))
        conditions += "timestamp >= now() - INTERVAL $window SECOND"
        renderFilter(filter)?.let { conditions += it }
        return conditions.joinToString(" AND ")
    }

    private fun compileRateAnomaly(
        orgId: Int,
        rule: RuleInput,
        window: Int,
    ): CompiledRuleQuery {
        validateThreshold(rule.thresholdCount)
        val groupBy = validateAnomalyGroupBy(validateGroupBy(rule.groupBy))
        val aliases = groupBy.indices.map { "g$it" }
        val groupExprs = groupBy.map { rollupColumnExpression(it) }
        val filterSql = renderFilter(rule.filter, ::validateAnomalyFilterNode)
        val currentWhere = buildRollupWhere(
            orgId = orgId,
            timeClause = "bucket_start >= now() - INTERVAL $window SECOND",
            filterSql = filterSql,
        )
        val baselineWhere = buildRollupWhere(
            orgId = orgId,
            timeClause = "bucket_start >= now() - INTERVAL $ANOMALY_BASELINE_SECONDS SECOND " +
                "AND bucket_start < now() - INTERVAL $window SECOND",
            filterSql = filterSql,
        )
        val current = anomalyCurrentSubquery(groupExprs, aliases, currentWhere)
        val baseline = anomalyBaselineSubquery(groupExprs, aliases, baselineWhere)
        val selectItems = aliases.joinToString(", ") { "c.$it AS $it" }
        val projectedGroups = if (selectItems.isBlank()) "" else "$selectItems, "
        val join = if (aliases.isEmpty()) {
            "($current) c CROSS JOIN ($baseline) b"
        } else {
            "($current) c INNER JOIN ($baseline) b USING (${aliases.joinToString(", ")})"
        }
        val sql = buildString {
            append(SELECT_CLAUSE)
            append(projectedGroups)
            append("c.current_count AS match_count FROM ")
            append(join)
            append(WHERE_CLAUSE).append("c.current_count >= ${rule.thresholdCount} AND b.baseline_avg > 0")
            append(" AND c.current_count > b.baseline_avg + greatest(b.baseline_stddev * ")
            append(ANOMALY_STDDEV_MULTIPLIER)
            append(", 1)")
            append(" ").append(settingsClause())
        }
        return CompiledRuleQuery(
            sql = sql,
            groupByColumns = groupBy,
            groupByAliases = aliases,
            whereClause = currentWhere,
            evidenceDescriptor = anomalyEvidenceDescriptor(groupBy, window),
            windowSeconds = window,
        )
    }

    private fun buildRollupWhere(orgId: Int, timeClause: String, filterSql: String?): String {
        if (orgId == 0) {
            throw DetectionCompileException("Detection query is missing organization context")
        }
        val conditions = mutableListOf(ClickHouseQueryUtils.orgIdClause(orgId.toLong()), timeClause)
        filterSql?.let { conditions += it }
        return conditions.joinToString(" AND ")
    }

    private fun anomalyCurrentSubquery(
        groupExprs: List<String>,
        aliases: List<String>,
        whereClause: String,
    ): String {
        val selectItems = groupSelectItems(groupExprs, aliases)
        val groupByClause = groupByClause(aliases)
        return buildString {
            append(SELECT_CLAUSE)
            if (selectItems.isNotBlank()) append("$selectItems, ")
            append("sumMerge(event_count_state) AS current_count")
            append(" FROM `${clickhouseDb()}`.security_log_rate_rollup_5m")
            append(WHERE_CLAUSE).append(whereClause)
            append(groupByClause)
        }
    }

    private fun anomalyBaselineSubquery(
        groupExprs: List<String>,
        aliases: List<String>,
        whereClause: String,
    ): String {
        val selectItems = groupSelectItems(groupExprs, aliases)
        val aliasesCsv = aliases.joinToString(", ")
        val outerSelect = if (aliasesCsv.isBlank()) "" else "$aliasesCsv, "
        val outerGroupBy = if (aliasesCsv.isBlank()) "" else " GROUP BY $aliasesCsv"
        val innerGroupBy = if (aliasesCsv.isBlank()) {
            " GROUP BY bucket_start"
        } else {
            " GROUP BY bucket_start, $aliasesCsv"
        }
        val inner = buildString {
            append(SELECT_CLAUSE)
            if (selectItems.isNotBlank()) append("$selectItems, ")
            append("bucket_start, sumMerge(event_count_state) AS bucket_count")
            append(" FROM `${clickhouseDb()}`.security_log_rate_rollup_5m")
            append(WHERE_CLAUSE).append(whereClause)
            append(innerGroupBy)
        }
        return SELECT_CLAUSE + outerSelect + "avg(bucket_count) AS baseline_avg, " +
            "stddevPop(bucket_count) AS baseline_stddev FROM ($inner)$outerGroupBy"
    }

    private fun groupSelectItems(groupExprs: List<String>, aliases: List<String>): String =
        aliases.indices.joinToString(", ") { i -> "${groupExprs[i]} AS ${aliases[i]}" }

    private fun groupByClause(aliases: List<String>): String =
        if (aliases.isEmpty()) "" else " GROUP BY ${aliases.joinToString(", ")}"

    /** Resource-cap SETTINGS clause [compile] appends to every emitted query. */
    private fun settingsClause(): String {
        val maxExecution = EnvConfig.get(ENV_MAX_EXECUTION_TIME, DEFAULT_MAX_EXECUTION_TIME)
        val maxRowsToRead = EnvConfig.get(ENV_MAX_ROWS_TO_READ, DEFAULT_MAX_ROWS_TO_READ)
        val maxResultRows = EnvConfig.get(ENV_MAX_RESULT_ROWS, DEFAULT_MAX_RESULT_ROWS)
        val maxBytesToRead = EnvConfig.get(ENV_MAX_BYTES_TO_READ, DEFAULT_MAX_BYTES_TO_READ)
        return "SETTINGS max_execution_time = ${asLong(maxExecution, DEFAULT_MAX_EXECUTION_TIME)}, " +
            "max_rows_to_read = ${asLong(maxRowsToRead, DEFAULT_MAX_ROWS_TO_READ)}, " +
            "max_result_rows = ${asLong(maxResultRows, DEFAULT_MAX_RESULT_ROWS)}, " +
            "max_bytes_to_read = ${asLong(maxBytesToRead, DEFAULT_MAX_BYTES_TO_READ)}, " +
            "result_overflow_mode = 'throw', read_overflow_mode = 'throw', timeout_overflow_mode = 'throw'"
    }

    private fun validateSource(source: String) {
        if (DetectionSource.fromWire(source) == null) {
            throw DetectionCompileException("Unsupported detection source '${sanitizeToken(source)}'")
        }
    }

    private fun validateWindow(windowSeconds: Int): Int {
        if (windowSeconds < MIN_WINDOW_SECONDS) {
            throw DetectionCompileException("Window must be at least $MIN_WINDOW_SECONDS seconds")
        }
        if (windowSeconds > MAX_WINDOW_SECONDS) {
            throw DetectionCompileException("Window must be at most $MAX_WINDOW_SECONDS seconds")
        }
        return windowSeconds
    }

    private fun validateThreshold(thresholdCount: Int?) {
        val count = thresholdCount
            ?: throw DetectionCompileException("Threshold rules require a threshold_count")
        if (count < MIN_THRESHOLD || count > MAX_THRESHOLD) {
            throw DetectionCompileException("threshold_count must be between $MIN_THRESHOLD and $MAX_THRESHOLD")
        }
    }

    private fun validateGroupBy(groupBy: List<String>): List<String> {
        if (groupBy.size > MAX_GROUP_BY_COLUMNS) {
            throw DetectionCompileException("At most $MAX_GROUP_BY_COLUMNS group-by columns are allowed")
        }
        groupBy.forEach { ensureGroupByAllowed(it) }
        return groupBy
    }

    /** Throws unless [column] is a whitelisted top-level column or whitelisted `map['key']` access. */
    private fun ensureGroupByAllowed(column: String) {
        val trimmed = column.trim()
        if (trimmed in ALLOWED_GROUP_BY_COLUMNS) return
        val mapMatch = MAP_ACCESS_REGEX.find(trimmed)
        if (mapMatch != null && mapMatch.groupValues[1] in ALLOWED_MAP_COLUMNS) return
        throw DetectionCompileException("Unknown group-by column '${sanitizeToken(column)}'")
    }

    /**
     * Renders a whitelisted group-by column to its ClickHouse expression. Top-level columns are emitted
     * verbatim (already proven to be in the allowlist of literal identifiers — never author free-form);
     * map access becomes `col['<escaped key>']`. Enum columns are cast with `toString` for stable
     * grouping/equality with downstream signal keys.
     */
    private fun columnExpression(column: String): String {
        val trimmed = column.trim()
        if (trimmed in ALLOWED_GROUP_BY_COLUMNS) {
            return if (trimmed in LogQueryParser.ENUM_FIELDS) "toString($trimmed)" else trimmed
        }
        val mapMatch = MAP_ACCESS_REGEX.find(trimmed)
            ?: throw DetectionCompileException("Unknown group-by column '${sanitizeToken(column)}'")
        val mapColumn = mapMatch.groupValues[1]
        if (mapColumn !in ALLOWED_MAP_COLUMNS) {
            throw DetectionCompileException("Unknown group-by column '${sanitizeToken(column)}'")
        }
        val key = escapeSql(mapMatch.groupValues[2])
        return "$mapColumn['$key']"
    }

    /**
     * Parses [filter] via the trusted [LogQueryParser] and renders it through the same path the log
     * search uses, so values bind escaped and cannot alter structure. A parse failure (the parser
     * reports `errors`) is surfaced as a DSL-level message; the raw ClickHouse never appears because we
     * never reach ClickHouse with an unrendered filter.
     */
    private fun renderFilter(
        filter: String,
        validator: ((LogQueryParser.QueryNode) -> Unit)? = null,
    ): String? {
        if (filter.isBlank()) return null
        val parsed = parser.parse(filter)
        if (parsed.errors.isNotEmpty()) {
            throw DetectionCompileException("Invalid filter expression")
        }
        val node = parsed.rootNode ?: return null
        validator?.invoke(node)
        val rendered = parser.toClickHouseSql(node, ::escapeSql)
        return if (rendered.isBlank() || rendered == "1=1") null else "($rendered)"
    }

    private fun validateAnomalyGroupBy(groupBy: List<String>): List<String> {
        groupBy.forEach { column ->
            if (column.trim() !in ALLOWED_ANOMALY_GROUP_BY_COLUMNS) {
                throw DetectionCompileException("Unknown group-by column '${sanitizeToken(column)}'")
            }
        }
        return groupBy
    }

    private fun validateAnomalyFilterNode(node: LogQueryParser.QueryNode) {
        when (node) {
            is LogQueryParser.QueryNode.FieldNode -> requireAnomalyField(node.field)
            is LogQueryParser.QueryNode.RangeNode -> requireAnomalyField(node.field)
            is LogQueryParser.QueryNode.ComparisonNode -> requireAnomalyField(node.field)
            is LogQueryParser.QueryNode.ExistsNode -> requireAnomalyField(node.field)
            is LogQueryParser.QueryNode.AndNode -> validateAnomalyFilterChildren(node.left, node.right)
            is LogQueryParser.QueryNode.OrNode -> validateAnomalyFilterChildren(node.left, node.right)
            is LogQueryParser.QueryNode.NotNode -> validateAnomalyFilterNode(node.node)
            is LogQueryParser.QueryNode.FullTextNode,
            is LogQueryParser.QueryNode.TagExistsNode,
            is LogQueryParser.QueryNode.TermNode,
            -> throw DetectionCompileException("Rate anomaly filters may use only rollup fields")
        }
    }

    private fun validateAnomalyFilterChildren(
        left: LogQueryParser.QueryNode,
        right: LogQueryParser.QueryNode,
    ) {
        validateAnomalyFilterNode(left)
        validateAnomalyFilterNode(right)
    }

    private fun requireAnomalyField(field: String) {
        val normalized = when (field.lowercase()) {
            "status" -> "level"
            else -> field
        }
        if (normalized !in ALLOWED_ANOMALY_FILTER_FIELDS) {
            throw DetectionCompileException("Rate anomaly filters may use only rollup fields")
        }
    }

    private fun rollupColumnExpression(column: String): String =
        when (val trimmed = column.trim()) {
            in ALLOWED_ANOMALY_GROUP_BY_COLUMNS -> trimmed
            else -> throw DetectionCompileException("Unknown group-by column '${sanitizeToken(column)}'")
        }

    private fun evidenceDescriptor(groupBy: List<String>, windowSeconds: Int): String {
        val groups = if (groupBy.isEmpty()) "-" else groupBy.joinToString(",")
        return "table=logs group_by=$groups window=${windowSeconds}s"
    }

    private fun anomalyEvidenceDescriptor(groupBy: List<String>, windowSeconds: Int): String {
        val groups = if (groupBy.isEmpty()) "-" else groupBy.joinToString(",")
        return "table=security_log_rate_rollup_5m group_by=$groups window=${windowSeconds}s " +
            "baseline=${ANOMALY_BASELINE_SECONDS}s"
    }

    /** Coerce an env value to a non-negative long, falling back to [default] if it is not numeric. */
    private fun asLong(value: String, default: String): Long =
        value.trim().toLongOrNull()?.takeIf { it >= 0 } ?: default.toLong()

    /**
     * Sanitize an offending token before placing it in a DSL error: strip to a short, identifier-only
     * preview so an adversarial column/source name cannot smuggle quotes or newlines into the message.
     */
    private fun sanitizeToken(token: String): String =
        token.take(TOKEN_PREVIEW_LEN).filter { it.isLetterOrDigit() || it in "_-.[]'" }
}

private const val TOKEN_PREVIEW_LEN = 40
