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

import com.moneat.utils.suspendRunCatching

private val searchableFields = listOf(
    "message",
    "body",
    "service",
    "environment",
    "host",
    "container_name"
)

class LogEntryFilterEvaluator(
    private val parser: LogQueryParser = LogQueryParser()
) {
    fun matches(
        filterQuery: String,
        entry: Map<String, String>
    ): Boolean {
        if (filterQuery.isBlank()) return true
        val parsed = parser.parse(filterQuery)
        val root = parsed.rootNode ?: return true
        return evaluate(root, entry)
    }

    fun evaluate(
        node: LogQueryParser.QueryNode,
        entry: Map<String, String>
    ): Boolean {
        return when (node) {
            is LogQueryParser.QueryNode.FieldNode -> evaluateFieldNode(node, entry)
            is LogQueryParser.QueryNode.FullTextNode -> searchableFields.any { field ->
                entry[field]?.contains(node.term, ignoreCase = true) == true
            }
            is LogQueryParser.QueryNode.AndNode ->
                evaluate(node.left, entry) && evaluate(node.right, entry)
            is LogQueryParser.QueryNode.OrNode ->
                evaluate(node.left, entry) || evaluate(node.right, entry)
            is LogQueryParser.QueryNode.NotNode ->
                !evaluate(node.node, entry)
            is LogQueryParser.QueryNode.ExistsNode ->
                !entry[node.field].isNullOrEmpty()
            is LogQueryParser.QueryNode.TagExistsNode ->
                entry.containsKey(node.tagKey)
            is LogQueryParser.QueryNode.TermNode ->
                entry.values.any { it.contains(node.term, ignoreCase = true) }
            is LogQueryParser.QueryNode.RangeNode -> evaluateRangeNode(node, entry)
            is LogQueryParser.QueryNode.ComparisonNode -> evaluateComparisonNode(node, entry)
        }
    }

    private fun evaluateFieldNode(
        node: LogQueryParser.QueryNode.FieldNode,
        entry: Map<String, String>
    ): Boolean {
        val value = entry[node.field] ?: return false
        if (!node.isWildcard) {
            return value.equals(node.value, ignoreCase = true)
        }

        val pattern = Regex.escape(node.value)
            .replace("\\*", ".*")
            .replace("\\?", ".")
        return suspendRunCatching {
            value.matches(Regex(pattern, RegexOption.IGNORE_CASE))
        }.getOrElse { false }
    }

    private fun evaluateRangeNode(
        node: LogQueryParser.QueryNode.RangeNode,
        entry: Map<String, String>
    ): Boolean {
        val value = entry[node.field]?.toDoubleOrNull() ?: return false
        val min = node.min.toDoubleOrNull() ?: return false
        val max = node.max.toDoubleOrNull() ?: return false
        return value in min..max
    }

    private fun evaluateComparisonNode(
        node: LogQueryParser.QueryNode.ComparisonNode,
        entry: Map<String, String>
    ): Boolean {
        val value = entry[node.field]?.toDoubleOrNull() ?: return false
        val target = node.value.toDoubleOrNull() ?: return false
        return when (node.operator) {
            ">" -> value > target
            ">=" -> value >= target
            "<" -> value < target
            "<=" -> value <= target
            else -> false
        }
    }
}
