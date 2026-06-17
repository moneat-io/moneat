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

package com.moneat.security

/**
 * Encodes keyed identity segments into one stable, unambiguous string. Values can legally contain the
 * field/record separators (`|`, `=`), backslashes, or NUL; missing values must also stay distinct from
 * empty values. Each key and value escapes those separators, while missing values use a sentinel that
 * escaping never emits.
 */
fun encodeStableKeySegments(fields: List<String>, values: Map<String, String>): String =
    fields.joinToString("|") { field ->
        val raw = values[field]
        val value = if (raw == null) MISSING_VALUE_SENTINEL else escapeStableKeySegment(raw)
        "${escapeStableKeySegment(field)}=$value"
    }

fun encodeStableKeySegments(pairs: List<Pair<String, String?>>): String =
    pairs.joinToString("|") { (field, raw) ->
        val value = if (raw == null) MISSING_VALUE_SENTINEL else escapeStableKeySegment(raw)
        "${escapeStableKeySegment(field)}=$value"
    }

private const val MISSING_VALUE_SENTINEL = "\u0000"

private fun escapeStableKeySegment(segment: String): String =
    segment
        .replace("\\", "\\\\")
        .replace(MISSING_VALUE_SENTINEL, "\\0")
        .replace("|", "\\|")
        .replace("=", "\\=")
