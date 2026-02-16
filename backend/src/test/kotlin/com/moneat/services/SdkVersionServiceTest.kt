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
