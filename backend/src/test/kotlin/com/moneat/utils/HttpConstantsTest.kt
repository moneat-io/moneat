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

package com.moneat.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpConstantsTest {

    @Test
    fun `HTTP_SUCCESS_RANGE covers 2xx status codes`() {
        assertTrue(200 in HttpConstants.HTTP_SUCCESS_RANGE)
        assertTrue(299 in HttpConstants.HTTP_SUCCESS_RANGE)
        assertTrue(250 in HttpConstants.HTTP_SUCCESS_RANGE)
        assertFalse(199 in HttpConstants.HTTP_SUCCESS_RANGE)
        assertFalse(300 in HttpConstants.HTTP_SUCCESS_RANGE)
    }

    @Test
    fun `HTTP_SUCCESS_MIN and MAX are correct`() {
        assertEquals(200, HttpConstants.HTTP_SUCCESS_MIN)
        assertEquals(299, HttpConstants.HTTP_SUCCESS_MAX)
    }
}
