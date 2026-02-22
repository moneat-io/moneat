package com.moneat.services

import com.moneat.shared.services.normalizeVersionTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SdkVersionServiceExtendedTest {

    @Test
    fun `normalizeVersionTag handles plain semver`() {
        assertEquals("1.2.3", normalizeVersionTag("1.2.3"))
    }

    @Test
    fun `normalizeVersionTag handles v prefix`() {
        assertEquals("8.57.1", normalizeVersionTag("v8.57.1"))
    }

    @Test
    fun `normalizeVersionTag handles composite tag with dash`() {
        assertEquals("10.43.0", normalizeVersionTag("sentry.javascript-10.43.0"))
    }

    @Test
    fun `normalizeVersionTag handles composite tag with underscore prefix`() {
        assertEquals("7.0.0", normalizeVersionTag("sentry_android_7.0.0"))
    }

    @Test
    fun `normalizeVersionTag keeps prerelease suffix`() {
        assertEquals("1.2.3-beta.1", normalizeVersionTag("v1.2.3-beta.1"))
    }

    @Test
    fun `normalizeVersionTag keeps build metadata`() {
        assertEquals("1.2.3+build.456", normalizeVersionTag("v1.2.3+build.456"))
    }

    @Test
    fun `normalizeVersionTag returns null for non-semver`() {
        assertNull(normalizeVersionTag("release-candidate"))
    }

    @Test
    fun `normalizeVersionTag returns null for empty string`() {
        assertNull(normalizeVersionTag(""))
    }

    @Test
    fun `normalizeVersionTag returns null for text only`() {
        assertNull(normalizeVersionTag("latest"))
    }

    @Test
    fun `normalizeVersionTag handles four-part version`() {
        assertEquals("1.2.3.4", normalizeVersionTag("v1.2.3.4"))
    }

    @Test
    fun `normalizeVersionTag extracts first semver from complex string`() {
        assertEquals("2.0.0", normalizeVersionTag("release/2.0.0"))
    }

    @Test
    fun `normalizeVersionTag handles version with rc suffix`() {
        assertEquals("3.0.0-rc.1", normalizeVersionTag("v3.0.0-rc.1"))
    }

    @Test
    fun `normalizeVersionTag handles single digit versions`() {
        assertEquals("1.0.0", normalizeVersionTag("1.0.0"))
    }

    @Test
    fun `normalizeVersionTag handles large version numbers`() {
        assertEquals("100.200.300", normalizeVersionTag("v100.200.300"))
    }

    @Test
    fun `normalizeVersionTag returns null for partial version`() {
        assertNull(normalizeVersionTag("v1.2"))
    }

    @Test
    fun `normalizeVersionTag handles alpha suffix`() {
        assertEquals("5.0.0-alpha.2", normalizeVersionTag("v5.0.0-alpha.2"))
    }
}
