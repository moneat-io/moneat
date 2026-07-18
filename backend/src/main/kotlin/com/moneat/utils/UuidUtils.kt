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

package com.moneat.utils

private const val UUID_HEX_BLOCK1_END = 8
private const val UUID_HEX_BLOCK2_END = 12
private const val UUID_HEX_BLOCK3_END = 16
private const val UUID_HEX_BLOCK4_END = 20
private val CANONICAL_UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val COMPACT_UUID_REGEX = Regex("^[0-9a-f]{32}$")

fun normalizeUuidOrNull(value: String): String? {
    val normalized = value.trim().lowercase()
    if (CANONICAL_UUID_REGEX.matches(normalized)) return normalized
    if (!COMPACT_UUID_REGEX.matches(normalized)) return null

    return "${normalized.substring(0, UUID_HEX_BLOCK1_END)}-" +
        "${normalized.substring(UUID_HEX_BLOCK1_END, UUID_HEX_BLOCK2_END)}-" +
        "${normalized.substring(UUID_HEX_BLOCK2_END, UUID_HEX_BLOCK3_END)}-" +
        "${normalized.substring(UUID_HEX_BLOCK3_END, UUID_HEX_BLOCK4_END)}-" +
        normalized.substring(UUID_HEX_BLOCK4_END)
}
