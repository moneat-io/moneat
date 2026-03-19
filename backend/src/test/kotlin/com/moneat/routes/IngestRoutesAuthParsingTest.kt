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

import com.moneat.events.routes.extractPublicKey
import com.moneat.events.routes.extractPublicKeyFromDsn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngestRoutesAuthParsingTest {

    // ──── extractPublicKey ────

    // ──── Happy path ────

    @Test
    fun `extractPublicKey reads sentry key from auth header`() {
        val key =
            extractPublicKey(
                authHeader = "Sentry sentry_key=abc123def456, sentry_version=7"
            )

        assertEquals("abc123def456", key)
    }

    @Test
    fun `extractPublicKey falls back to query param when header is missing`() {
        val key =
            extractPublicKey(
                authHeader = null,
                sentryKeyParam = "abc123def456"
            )

        assertEquals("abc123def456", key)
    }

    @Test
    fun `extractPublicKey prefers header over query param`() {
        val key =
            extractPublicKey(
                authHeader = "Sentry sentry_key=headerkey123, sentry_version=7",
                sentryKeyParam = "querykey456"
            )

        assertEquals("headerkey123", key)
    }

    // ──── Special characters ────

    @Test
    fun `extractPublicKey reads key with underscores from auth header`() {
        val key =
            extractPublicKey(
                authHeader = "Sentry sentry_key=test_key_with_underscores_123, sentry_version=7"
            )

        assertEquals("test_key_with_underscores_123", key)
    }

    @Test
    fun `extractPublicKey reads key with hyphens from auth header`() {
        val key =
            extractPublicKey(
                authHeader = "Sentry sentry_key=test-key-with-hyphens-123, sentry_version=7"
            )

        assertEquals("test-key-with-hyphens-123", key)
    }

    @Test
    fun `extractPublicKey accepts query param with underscores`() {
        val key =
            extractPublicKey(
                authHeader = null,
                sentryKeyParam = "test_key_with_underscores_123"
            )

        assertEquals("test_key_with_underscores_123", key)
    }

    // ──── Rejection ────

    @Test
    fun `extractPublicKey rejects invalid query param characters`() {
        val key =
            extractPublicKey(
                authHeader = null,
                sentryKeyParam = "bad.key.123"
            )

        assertNull(key)
    }

    // ──── extractPublicKeyFromDsn ────

    // ──── Happy path ────

    @Test
    fun `extractPublicKeyFromDsn parses DSN auth header`() {
        val key = extractPublicKeyFromDsn("DSN https://abc123def@o1.ingest.sentry.io/42")
        assertEquals("abc123def", key)
    }

    // ──── Special characters ────

    @Test
    fun `extractPublicKeyFromDsn parses DSN with underscores in key`() {
        val key = extractPublicKeyFromDsn("DSN https://test_key_underscore@o1.ingest.sentry.io/42")
        assertEquals("test_key_underscore", key)
    }

    @Test
    fun `extractPublicKeyFromDsn parses DSN with hyphens in key`() {
        val key = extractPublicKeyFromDsn("DSN https://test-key-hyphens@o1.ingest.sentry.io/42")
        assertEquals("test-key-hyphens", key)
    }

    // ──── Rejection ────

    @Test
    fun `extractPublicKeyFromDsn returns null for invalid DSN format`() {
        val key = extractPublicKeyFromDsn("Bearer token")
        assertNull(key)
    }
}
