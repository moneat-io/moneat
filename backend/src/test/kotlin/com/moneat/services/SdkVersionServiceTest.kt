package com.moneat.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SdkVersionServiceTest {
    @Test
    fun `normalizeVersionTag parses v-prefixed semver`() {
        assertEquals("8.57.1", normalizeVersionTag("v8.57.1"))
    }

    @Test
    fun `normalizeVersionTag parses semver within composite tag names`() {
        assertEquals("10.43.0", normalizeVersionTag("sentry.javascript-10.43.0"))
    }

    @Test
    fun `normalizeVersionTag keeps prerelease suffixes`() {
        assertEquals("1.2.3-beta.1", normalizeVersionTag("v1.2.3-beta.1"))
    }

    @Test
    fun `normalizeVersionTag returns null when tag has no semantic version`() {
        assertNull(normalizeVersionTag("release-candidate"))
    }
}
