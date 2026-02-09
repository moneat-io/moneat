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
