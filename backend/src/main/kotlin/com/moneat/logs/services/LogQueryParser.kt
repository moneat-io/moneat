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

package com.moneat.logs.services

import com.moneat.utils.ClickHouseSqlUtils
import com.moneat.utils.suspendRunCatching

/**
 * Datadog-compatible log search query parser.
 *
 * Supports:
 * - Boolean operators: AND, OR, NOT (-)
 * - Attribute search: @attribute:value
 * - Reserved attributes: service, status, host, source, environment, message, trace_id, span_id
 * - Full-text search: *:search_term
 * - Wildcards: * (multi-char), ? (single-char)
 * - Numerical ranges: [X TO Y]
 * - Numerical comparisons: >N, >=N, <N, <=N
 * - Existence checks: @field:* / -@field:*
 * - Tag key existence: tags:MY_TAG
 * - Grouped field values: field:(val1 OR val2)
 * - Tag search: key:value
 * - Exact match: "quoted strings"
 * - Special character escaping: \
 */
class LogQueryParser {

    companion object {
        /** Top-level ClickHouse log columns (not stored in tags/resource_attributes). */
        val TOP_LEVEL_FIELDS: Set<String> = setOf(
            "service", "environment", "host", "source", "level", "message", "body",
            "container_name", "container_id", "container_image", "trace_id", "span_id",
            "index_name"
        )

        val ENUM_FIELDS: Set<String> = setOf("level", "source")
    }

    sealed class Token {
        data class Text(val value: String) : Token()

        data class QuotedText(val value: String) : Token()

        data class Field(
            val name: String,
            val value: String,
            val isRange: Boolean = false,
            val rangeEnd: String? = null,
            val isQuoted: Boolean = false
        ) : Token()

        data class FieldGroup(val name: String, val tokens: List<Token>) : Token()

        object And : Token()

        object Or : Token()

        data class Not(val token: Token) : Token()

        data class Group(val tokens: List<Token>) : Token()
    }

    sealed class QueryNode {
        data class TermNode(val term: String, val isWildcard: Boolean = false) : QueryNode()

        data class FieldNode(val field: String, val value: String, val isWildcard: Boolean = false) : QueryNode()

        data class RangeNode(val field: String, val min: String, val max: String) : QueryNode()

        data class ComparisonNode(val field: String, val operator: String, val value: String) : QueryNode()

        data class ExistsNode(val field: String) : QueryNode()

        data class TagExistsNode(val tagKey: String) : QueryNode()

        data class FullTextNode(
            val term: String,
            val isWildcard: Boolean = false,
            val isPhrase: Boolean = false
        ) : QueryNode()

        data class AndNode(val left: QueryNode, val right: QueryNode) : QueryNode()

        data class OrNode(val left: QueryNode, val right: QueryNode) : QueryNode()

        data class NotNode(val node: QueryNode) : QueryNode()
    }

    data class ParsedQuery(
        val rootNode: QueryNode?,
        val errors: List<String> = emptyList()
    )

    /**
     * Parse a Datadog-style query string into a QueryNode tree.
     */
    fun parse(query: String): ParsedQuery {
        if (query.isBlank()) {
            return ParsedQuery(null)
        }

        val errors = mutableListOf<String>()

        suspendRunCatching {
            val tokens = tokenize(query)

            // Debug logging (remove after testing)
            // println("DEBUG: Tokenized '$query' into ${tokens.size} tokens:")
            // tokens.forEachIndexed { idx, token ->
            //     println("  [$idx] ${token.javaClass.simpleName}: $token")
            // }

            if (tokens.isEmpty()) {
                return ParsedQuery(null)
            }

            val node = parseExpression(tokens)
            return ParsedQuery(node, errors)
        }.getOrElse { e ->
            errors.add("Parse error: ${e.message}")
            // Fallback to simple full-text search
            return ParsedQuery(
                QueryNode.FullTextNode(query.trim(), false),
                errors
            )
        }
    }

    /**
     * Tokenize the query string.
     */
    private fun tokenize(query: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0

        while (i < query.length) {
            when {
                query[i].isWhitespace() -> {
                    i++
                }

                // Quoted string
                query[i] == '"' -> {
                    val (quoted, endIdx) = extractQuoted(query, i)
                    tokens.add(Token.QuotedText(quoted))
                    i = endIdx
                }

                // NOT operator
                query[i] == '-' && (i == 0 || query[i - 1].isWhitespace()) -> {
                    i++
                    // Skip whitespace after -
                    while (i < query.length && query[i].isWhitespace()) i++

                    // Get the next token
                    if (i < query.length) {
                        val (token, endIdx) = extractNextToken(query, i)
                        tokens.add(Token.Not(token))
                        i = endIdx
                    }
                }

                // Parentheses for grouping
                query[i] == '(' -> {
                    val (groupTokens, endIdx) = extractGroup(query, i)
                    tokens.add(Token.Group(groupTokens))
                    i = endIdx
                }

                else -> {
                    val (token, endIdx) = extractNextToken(query, i)
                    tokens.add(token)
                    i = endIdx
                }
            }
        }

        return tokens
    }

    private fun extractQuoted(
        query: String,
        startIdx: Int
    ): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIdx + 1 // Skip opening quote

        while (i < query.length) {
            when {
                query[i] == '\\' && i + 1 < query.length -> {
                    // Escaped character
                    sb.append(query[i + 1])
                    i += 2
                }

                query[i] == '"' -> {
                    // Closing quote
                    return Pair(sb.toString(), i + 1)
                }

                else -> {
                    sb.append(query[i])
                    i++
                }
            }
        }

        // Unclosed quote - return what we have
        return Pair(sb.toString(), query.length)
    }

    private fun extractGroup(
        query: String,
        startIdx: Int
    ): Pair<List<Token>, Int> {
        var depth = 0
        var i = startIdx
        val groupContent = StringBuilder()

        while (i < query.length) {
            when (query[i]) {
                '(' -> {
                    if (depth > 0) groupContent.append(query[i])
                    depth++
                }

                ')' -> {
                    depth--
                    if (depth == 0) {
                        // End of group
                        return Pair(tokenize(groupContent.toString()), i + 1)
                    }
                    groupContent.append(query[i])
                }

                else -> {
                    if (depth > 0) groupContent.append(query[i])
                }
            }
            i++
        }

        // Unclosed group - tokenize what we have
        return Pair(tokenize(groupContent.toString()), query.length)
    }

    private fun extractNextToken(
        query: String,
        startIdx: Int
    ): Pair<Token, Int> {
        val sb = StringBuilder()
        var i = startIdx

        // First, check if this token contains a field:value pattern with potential quoted value
        // Look ahead for colon
        var tempI = i
        while (tempI < query.length && !query[tempI].isWhitespace() && query[tempI] != ')' && query[tempI] != ':' && query[tempI] != '"') {
            tempI++
        }

        // If we found a colon, this might be a field:value pair
        if (tempI < query.length && query[tempI] == ':') {
            val field = query.substring(i, tempI)
            i = tempI + 1 // Skip the colon

            // Check if value starts with quote
            if (i < query.length && query[i] == '"') {
                val (quotedValue, endIdx) = extractQuoted(query, i)
                return Pair(Token.Field(field, quotedValue, isQuoted = true), endIdx)
            }

            // Check if value starts with '(' for grouped field values: field:(val1 OR val2)
            if (i < query.length && query[i] == '(') {
                val (groupTokens, endIdx) = extractGroup(query, i)
                return Pair(Token.FieldGroup(field, groupTokens), endIdx)
            }

            // Otherwise, extract value normally until whitespace or )
            // BUT: if we see '[', keep going until we see ']' to handle range syntax
            val valueSb = StringBuilder()
            var inBrackets = false
            while (i < query.length && (inBrackets || (!query[i].isWhitespace() && query[i] != ')'))) {
                when {
                    query[i] == '[' -> {
                        inBrackets = true
                        valueSb.append(query[i])
                        i++
                    }

                    query[i] == ']' -> {
                        inBrackets = false
                        valueSb.append(query[i])
                        i++
                    }

                    query[i] == '\\' && i + 1 < query.length -> {
                        valueSb.append(query[i + 1])
                        i += 2
                    }

                    else -> {
                        valueSb.append(query[i])
                        i++
                    }
                }
            }

            val value = valueSb.toString()

            // Empty value after colon (e.g., "auth-service:") — treat as plain text search
            if (value.isEmpty()) {
                return Pair(Token.Text(field), i)
            }

            // Check for range syntax [X TO Y]
            if (value.startsWith('[') && value.contains(" TO ") && value.endsWith(']')) {
                val rangeContent = value.substring(1, value.length - 1)
                val parts = rangeContent.split(" TO ")
                if (parts.size == 2) {
                    return Pair(Token.Field(field, parts[0].trim(), true, parts[1].trim()), i)
                }
            }

            return Pair(Token.Field(field, value), i)
        }

        // No colon found, extract as regular text
        while (i < query.length && !query[i].isWhitespace() && query[i] != ')') {
            when {
                query[i] == '\\' && i + 1 < query.length -> {
                    // Escaped character
                    sb.append(query[i + 1])
                    i += 2
                }

                else -> {
                    sb.append(query[i])
                    i++
                }
            }
        }

        val text = sb.toString()

        // Check for operators
        return when (text) {
            "AND" -> Pair(Token.And, i)
            "OR" -> Pair(Token.Or, i)
            else -> Pair(Token.Text(text), i)
        }
    }

    /**
     * Parse tokens into a QueryNode tree using operator precedence.
     */
    private fun parseExpression(tokens: List<Token>): QueryNode? {
        if (tokens.isEmpty()) return null

        // Handle OR (lowest precedence)
        val orIdx = tokens.indexOfLast { it is Token.Or }
        if (orIdx > 0) {
            val left = parseExpression(tokens.subList(0, orIdx))
            val right = parseExpression(tokens.subList(orIdx + 1, tokens.size))
            if (left != null && right != null) {
                return QueryNode.OrNode(left, right)
            }
        }

        // Handle AND
        val andIdx = tokens.indexOfLast { it is Token.And }
        if (andIdx > 0) {
            val left = parseExpression(tokens.subList(0, andIdx))
            val right = parseExpression(tokens.subList(andIdx + 1, tokens.size))
            if (left != null && right != null) {
                return QueryNode.AndNode(left, right)
            }
        }

        // Handle implicit AND (consecutive tokens)
        if (tokens.size > 1 && tokens.none { it is Token.And || it is Token.Or }) {
            val first = parseSingleToken(tokens[0])
            val rest = parseExpression(tokens.subList(1, tokens.size))
            if (first != null && rest != null) {
                return QueryNode.AndNode(first, rest)
            }
        }

        // Single token
        if (tokens.size == 1) {
            return parseSingleToken(tokens[0])
        }

        return null
    }

    private fun parseSingleToken(token: Token): QueryNode? {
        return when (token) {
            is Token.Text -> {
                QueryNode.FullTextNode(token.value, token.value.contains('*') || token.value.contains('?'))
            }

            is Token.QuotedText -> {
                // Quoted text is never a wildcard — wildcards inside quotes are literal
                QueryNode.FullTextNode(token.value, false, isPhrase = true)
            }

            is Token.Field -> {
                // Strip @ prefix if present
                val field = if (token.name.startsWith('@')) token.name.substring(1) else token.name

                if (token.isRange && token.rangeEnd != null) {
                    QueryNode.RangeNode(field, token.value, token.rangeEnd)
                } else {
                    // Existence check: @field:* (value is exactly "*" and not quoted)
                    if (token.value == "*" && !token.isQuoted) {
                        // Special case: tags:MY_TAG — check tag key existence
                        if (token.name == "tags") {
                            return QueryNode.FullTextNode("*", true)
                        }
                        return QueryNode.ExistsNode(field)
                    }

                    // tags:MY_TAG syntax — tag key existence
                    if (token.name == "tags") {
                        return QueryNode.TagExistsNode(token.value)
                    }

                    // Numerical comparison operators: >N, >=N, <N, <=N (only for non-quoted values)
                    if (!token.isQuoted) {
                        val comparisonMatch = Regex("""^(>=|<=|>|<)(.+)$""").find(token.value)
                        if (comparisonMatch != null) {
                            val op = comparisonMatch.groupValues[1]
                            val numValue = comparisonMatch.groupValues[2]
                            return QueryNode.ComparisonNode(field, op, numValue)
                        }
                    }

                    val isFullText = field == "*"
                    // Quoted values are never treated as wildcards
                    val isWildcard = !token.isQuoted && (token.value.contains('*') || token.value.contains('?'))

                    if (isFullText) {
                        QueryNode.FullTextNode(token.value, isWildcard, isPhrase = token.isQuoted)
                    } else {
                        QueryNode.FieldNode(field, token.value, isWildcard)
                    }
                }
            }

            is Token.FieldGroup -> {
                // field:(val1 OR val2) — parse group tokens as values for this field
                val field = if (token.name.startsWith('@')) token.name.substring(1) else token.name
                val innerNode = parseFieldGroupValues(field, token.tokens)
                innerNode
            }

            is Token.Not -> {
                val inner = parseSingleToken(token.token)
                if (inner != null) QueryNode.NotNode(inner) else null
            }

            is Token.Group -> {
                parseExpression(token.tokens)
            }

            else -> {
                null
            }
        }
    }

    /**
     * Parse grouped field values like field:(val1 OR val2 OR val3) into an OR tree of FieldNodes.
     */
    private fun parseFieldGroupValues(
        field: String,
        tokens: List<Token>
    ): QueryNode? {
        // Extract only the value tokens (skip OR operators)
        val values = tokens.filter { it !is Token.Or && it !is Token.And }
        if (values.isEmpty()) return null

        val nodes =
            values.mapNotNull { token ->
                when (token) {
                    is Token.Text -> {
                        QueryNode.FieldNode(
                            field,
                            token.value,
                            token.value.contains('*') || token.value.contains('?')
                        )
                    }

                    is Token.QuotedText -> {
                        QueryNode.FieldNode(field, token.value, false)
                    }

                    else -> {
                        null
                    }
                }
            }

        if (nodes.isEmpty()) return null
        if (nodes.size == 1) return nodes[0]

        // Build OR tree
        return nodes.drop(1).fold<QueryNode, QueryNode>(nodes.first()) { acc, node -> QueryNode.OrNode(acc, node) }
    }

    /**
     * Convert a QueryNode tree to a ClickHouse WHERE clause.
     */
    fun toClickHouseSql(
        node: QueryNode?,
        escapeFn: (String) -> String
    ): String {
        if (node == null) return "1=1"

        return when (node) {
            is QueryNode.TermNode -> {
                buildFullTextCondition(node.term, node.isWildcard, false, escapeFn)
            }

            is QueryNode.FullTextNode -> {
                buildFullTextCondition(node.term, node.isWildcard, node.isPhrase, escapeFn)
            }

            is QueryNode.FieldNode -> {
                buildFieldCondition(node.field, node.value, node.isWildcard, escapeFn)
            }

            is QueryNode.RangeNode -> {
                buildRangeCondition(node.field, node.min, node.max, escapeFn)
            }

            is QueryNode.ComparisonNode -> {
                buildComparisonCondition(node.field, node.operator, node.value, escapeFn)
            }

            is QueryNode.ExistsNode -> {
                buildExistsCondition(node.field, escapeFn)
            }

            is QueryNode.TagExistsNode -> {
                val escapedKey = escapeFn(node.tagKey)
                "has(tags, '$escapedKey')"
            }

            is QueryNode.AndNode -> {
                "(${toClickHouseSql(node.left, escapeFn)} AND ${toClickHouseSql(node.right, escapeFn)})"
            }

            is QueryNode.OrNode -> {
                "(${toClickHouseSql(node.left, escapeFn)} OR ${toClickHouseSql(node.right, escapeFn)})"
            }

            is QueryNode.NotNode -> {
                "NOT (${toClickHouseSql(node.node, escapeFn)})"
            }
        }
    }

    private fun buildFullTextCondition(
        term: String,
        isWildcard: Boolean,
        isPhrase: Boolean,
        escapeFn: (String) -> String
    ): String {
        // Search in message, body, and text fields (excluding Enum8 columns level/source
        // which cause "Block structure mismatch" when SELECT aliases them with toString())
        val fields = listOf("message", "body", "service", "environment", "host", "container_name")

        // hasTokenCaseInsensitive rejects needles containing separator chars (hyphens, dots, etc.)
        val hasSeparators = term.any { it == '-' || it == '.' || it == '/' || it == ':' || it == ' ' }

        return if (isWildcard) {
            val pattern = wildcardToLikePattern(term)
            val escaped = escapeFn(pattern)
            "(" + fields.joinToString(" OR ") { field ->
                "$field ILIKE '$escaped'"
            } + ")"
        } else if (isPhrase || hasSeparators) {
            // Phrase search or terms with separators: use ILIKE for substring match
            val escaped = escapeFn(term)
            "(" + fields.joinToString(" OR ") { field ->
                "$field ILIKE '%$escaped%'"
            } + ")"
        } else {
            val escaped = escapeFn(term)
            "(" + fields.joinToString(" OR ") { field ->
                "hasTokenCaseInsensitive($field, '$escaped')"
            } + ")"
        }
    }

    private fun buildFieldCondition(
        field: String,
        value: String,
        isWildcard: Boolean,
        escapeFn: (String) -> String
    ): String {
        // Map reserved attributes
        val actualField =
            when (field.lowercase()) {
                "status" -> "level"
                "message" -> "message"
                "index" -> "index_name"
                else -> field
            }

        // Check if it's a top-level field or a tag/attribute
        val topLevelFields = TOP_LEVEL_FIELDS

        // Enum8 columns need toString() cast for string comparison
        val enumFields = ENUM_FIELDS

        return if (actualField in topLevelFields) {
            val fieldRef = if (actualField in enumFields) "toString($actualField)" else actualField
            if (isWildcard) {
                val pattern = wildcardToLikePattern(value)
                val escaped = escapeFn(pattern)
                "$fieldRef ILIKE '$escaped'"
            } else {
                val escaped = escapeFn(value)
                "$fieldRef = '$escaped'"
            }
        } else {
            // Search in tags or resource_attributes
            val escaped = escapeFn(value)
            val escapedField = escapeFn(actualField)
            if (isWildcard) {
                val pattern = wildcardToLikePattern(value)
                val escapedPattern = escapeFn(pattern)
                "((has(tags, '$escapedField') AND tags['$escapedField'] ILIKE '$escapedPattern') OR " +
                    "(has(resource_attributes, '$escapedField') AND resource_attributes['$escapedField'] ILIKE '$escapedPattern'))"
            } else {
                "((has(tags, '$escapedField') AND tags['$escapedField'] = '$escaped') OR " +
                    "(has(resource_attributes, '$escapedField') AND resource_attributes['$escapedField'] = '$escaped'))"
            }
        }
    }

    private fun buildRangeCondition(
        field: String,
        min: String,
        max: String,
        escapeFn: (String) -> String
    ): String {
        // Numerical range - assume the field is numeric
        val actualField =
            when (field.lowercase()) {
                "status" -> "level"
                else -> field
            }

        val minVal = min.toIntOrNull() ?: 0
        val maxVal = max.toIntOrNull() ?: Int.MAX_VALUE

        // For top-level numeric fields, use direct comparison
        // For tags/attributes, cast to Int
        val topLevelFields = TOP_LEVEL_FIELDS

        return if (actualField in topLevelFields) {
            val fieldRef = if (actualField in ENUM_FIELDS) "toString($actualField)" else actualField
            "($fieldRef >= $minVal AND $fieldRef <= $maxVal)"
        } else {
            val escapedField = escapeFn(actualField)
            "(has(tags, '$escapedField') AND toInt32OrNull(tags['$escapedField']) >= $minVal AND toInt32OrNull(tags['$escapedField']) <= $maxVal) OR " +
                "(has(resource_attributes, '$escapedField') AND toInt32OrNull(resource_attributes['$escapedField']) >= $minVal AND toInt32OrNull(resource_attributes['$escapedField']) <= $maxVal)"
        }
    }

    private fun buildComparisonCondition(
        field: String,
        operator: String,
        value: String,
        escapeFn: (String) -> String
    ): String {
        // Validate operator to prevent SQL injection
        ClickHouseSqlUtils.validateOperator(operator)

        val actualField =
            when (field.lowercase()) {
                "status" -> "level"
                "index" -> "index_name"
                else -> field
            }

        val numVal = value.toDoubleOrNull()

        val topLevelFields = TOP_LEVEL_FIELDS

        val enumFields = ENUM_FIELDS

        return if (actualField in topLevelFields) {
            val fieldRef = if (actualField in enumFields) "toString($actualField)" else actualField
            if (numVal != null) {
                "$fieldRef $operator $numVal"
            } else {
                val escaped = escapeFn(value)
                "$fieldRef $operator '$escaped'"
            }
        } else {
            val escapedField = escapeFn(actualField)
            if (numVal != null) {
                "(has(tags, '$escapedField') AND toFloat64OrNull(tags['$escapedField']) $operator $numVal) OR " +
                    "(has(resource_attributes, '$escapedField') AND toFloat64OrNull(resource_attributes['$escapedField']) $operator $numVal)"
            } else {
                val escaped = escapeFn(value)
                "(has(tags, '$escapedField') AND tags['$escapedField'] $operator '$escaped') OR " +
                    "(has(resource_attributes, '$escapedField') AND resource_attributes['$escapedField'] $operator '$escaped')"
            }
        }
    }

    private fun buildExistsCondition(
        field: String,
        escapeFn: (String) -> String
    ): String {
        val actualField =
            when (field.lowercase()) {
                "status" -> "level"
                "index" -> "index_name"
                else -> field
            }

        val topLevelFields = TOP_LEVEL_FIELDS

        return if (actualField in topLevelFields) {
            val fieldRef = if (actualField in ENUM_FIELDS) "toString($actualField)" else actualField
            "$fieldRef IS NOT NULL AND $fieldRef != ''"
        } else {
            val escapedField = escapeFn(actualField)
            "(has(tags, '$escapedField') OR has(resource_attributes, '$escapedField'))"
        }
    }

    private fun wildcardToLikePattern(term: String): String {
        // Convert * to % and ? to _ for SQL LIKE
        // Escape existing % and _ characters first
        return term
            .replace("%", "\\%")
            .replace("_", "\\_")
            .replace("*", "%")
            .replace("?", "_")
    }
}
