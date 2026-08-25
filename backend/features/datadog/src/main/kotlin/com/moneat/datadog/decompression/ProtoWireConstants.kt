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

package com.moneat.datadog.decompression

object ProtoWireConstants {
    const val FIELD_SHIFT = 3
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5
    const val FIELD_3 = 3
    const val FIELD_4 = 4
    const val FIELD_5 = 5
    const val FIELD_6 = 6
    const val FIELD_7 = 7
    const val FIELD_8 = 8
    const val FIELD_14 = 14

    fun tag(fieldNumber: Int, wireType: Int): Int = (fieldNumber shl FIELD_SHIFT) or wireType
}
