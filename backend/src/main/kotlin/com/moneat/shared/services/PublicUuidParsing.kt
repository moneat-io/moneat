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

package com.moneat.shared.services

import java.util.UUID as JavaUuid
import kotlin.uuid.Uuid

fun String.toUuidOrNull(): Uuid? =
    trim()
        .takeIf { it.isNotEmpty() }
        ?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() }

fun parseUuidOrNull(value: String?): Uuid? =
    value?.toUuidOrNull()

fun String.toJavaUuidOrNull(): JavaUuid? =
    trim()
        .takeIf { it.isNotEmpty() }
        ?.let { value -> runCatching { JavaUuid.fromString(value) }.getOrNull() }

fun parseJavaUuidOrNull(value: String?): JavaUuid? =
    value?.toJavaUuidOrNull()
