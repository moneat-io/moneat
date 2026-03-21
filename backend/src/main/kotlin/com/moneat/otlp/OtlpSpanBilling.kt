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

package com.moneat.otlp

import com.moneat.otlp.services.OtlpSpanInsert

fun Iterable<OtlpSpanInsert>.calculateBillableBytes(): Int =
    sumOf { s ->
        utf8Size(s.name) + utf8Size(s.service) +
            s.meta.entries.sumOf { e -> utf8Size(e.key) + utf8Size(e.value) }
    }

private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size
