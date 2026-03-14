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
 * SNMP OID (Object Identifier) constants for test fixtures. These are MIB tree
 * identifiers used in SNMP traps, not IP addresses.
 *
 * - OID_TRAP_TEST: 1.3.6.1 — internet subtree root (iso.org.dod.internet)
 * - OID_CISCO: 1.3.6.1.4.1.9.9.43 — Cisco enterprise MIB
 */
object TestOidConstants {
    /** Short OID for trap tests (internet subtree). Not an IP address. */
    const val OID_TRAP_TEST = "1.3.6.1"

    /** Cisco enterprise OID for vendor-specific trap tests. */
    const val OID_CISCO = "1.3.6.1.4.1.9.9.43"
}
