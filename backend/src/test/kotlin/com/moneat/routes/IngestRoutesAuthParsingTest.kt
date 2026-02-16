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

package com.moneat.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngestRoutesAuthParsingTest {
    @Test
    fun `extractPublicKey reads sentry key from auth header`() {
        val key = extractPublicKey(
            authHeader = "Sentry sentry_key=b422c0677570443e8ab25450d20b0f0c, sentry_version=7"
        )

        assertEquals("b422c0677570443e8ab25450d20b0f0c", key)
    }

    @Test
    fun `extractPublicKey falls back to query param when header is missing`() {
        val key = extractPublicKey(
            authHeader = null,
            sentryKeyParam = "b422c0677570443e8ab25450d20b0f0c"
        )

        assertEquals("b422c0677570443e8ab25450d20b0f0c", key)
    }

    @Test
    fun `extractPublicKey prefers header over query param`() {
        val key = extractPublicKey(
            authHeader = "Sentry sentry_key=headerkey123, sentry_version=7",
            sentryKeyParam = "querykey456"
        )

        assertEquals("headerkey123", key)
    }

    @Test
    fun `extractPublicKey rejects invalid query param characters`() {
        val key = extractPublicKey(
            authHeader = null,
            sentryKeyParam = "bad-key-123"
        )

        assertNull(key)
    }
}
