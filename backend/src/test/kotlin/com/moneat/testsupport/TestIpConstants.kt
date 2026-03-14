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

package com.moneat.testsupport

/**
 * RFC 5737 documentation IPs for test fixtures. Safe for use in tests — these
 * addresses are reserved for documentation and examples, not routable.
 *
 * - TEST-NET-1: 192.0.2.0/24
 * - TEST-NET-2: 198.51.100.0/24
 * - TEST-NET-3: 203.0.113.0/24
 */
object TestIpConstants {
    const val IP_1 = "192.0.2.1"
    const val IP_2 = "192.0.2.2"
    const val IP_5 = "192.0.2.5"
    const val IP_10 = "192.0.2.10"
    const val IP_45 = "192.0.2.45"
    const val IP_100 = "192.0.2.100"
    const val IP_254 = "192.0.2.254"

    /** Different subnet (TEST-NET-2) for path/hop tests */
    const val IP_OTHER = "198.51.100.1"

    /** Another subnet (TEST-NET-3) for multi-IP fixtures */
    const val IP_OTHER_2 = "203.0.113.23"
    /** Flow src/dst pairs (TEST-NET-1) */
    const val IP_SRC = "192.0.2.1"
    const val IP_DST = "192.0.2.2"
    /** Path destination (TEST-NET-2) */
    const val IP_PATH_DEST = "198.51.100.1"
}
