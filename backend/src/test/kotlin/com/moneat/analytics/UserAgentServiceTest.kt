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

package com.moneat.analytics

import com.moneat.analytics.services.UserAgentService
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentServiceTest {

    @Test
    fun `parse null returns Unknown defaults`() {
        val r = UserAgentService.parse(null)
        assertEquals("Unknown", r.browser)
        assertEquals("", r.browserVersion)
        assertEquals("Unknown", r.os)
        assertEquals("", r.osVersion)
        assertEquals("Desktop", r.deviceType)
    }

    @Test
    fun `parse blank returns Unknown defaults`() {
        val r = UserAgentService.parse("   ")
        assertEquals("Unknown", r.browser)
        assertEquals("Desktop", r.deviceType)
    }

    @Test
    fun `parse Chrome on Windows 10`() {
        val ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val r = UserAgentService.parse(ua)
        assertEquals("Chrome", r.browser)
        assertEquals("120", r.browserVersion)
        assertEquals("Windows", r.os)
        assertEquals("10+", r.osVersion)
        assertEquals("Desktop", r.deviceType)
    }

    @Test
    fun `parse Safari on macOS`() {
        val ua =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.2 Safari/605.1.15"
        val r = UserAgentService.parse(ua)
        assertEquals("Safari", r.browser)
        assertEquals("17", r.browserVersion)
        assertEquals("macOS", r.os)
        assertEquals("10.15", r.osVersion)
        assertEquals("Desktop", r.deviceType)
    }

    @Test
    fun `parse Firefox on Linux`() {
        val ua = "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"
        val r = UserAgentService.parse(ua)
        assertEquals("Firefox", r.browser)
        assertEquals("121", r.browserVersion)
        assertEquals("Linux", r.os)
        assertEquals("", r.osVersion)
        assertEquals("Desktop", r.deviceType)
    }

    @Test
    fun `parse Edge on Windows`() {
        val ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
        val r = UserAgentService.parse(ua)
        assertEquals("Edge", r.browser)
        assertEquals("120", r.browserVersion)
        assertEquals("Windows", r.os)
        assertEquals("10+", r.osVersion)
    }

    @Test
    fun `parse Mobile Chrome on Android`() {
        val ua =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
        val r = UserAgentService.parse(ua)
        assertEquals("Chrome", r.browser)
        assertEquals("Android", r.os)
        assertEquals("13", r.osVersion)
        assertEquals("Mobile", r.deviceType)
    }

    @Test
    fun `parse Safari on iPhone`() {
        val ua =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        val r = UserAgentService.parse(ua)
        assertEquals("Safari", r.browser)
        assertEquals("iOS", r.os)
        assertEquals("17.0", r.osVersion)
        assertEquals("Mobile", r.deviceType)
    }

    @Test
    fun `parse Safari on iPad as Tablet`() {
        val ua =
            "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
        val r = UserAgentService.parse(ua)
        assertEquals("Tablet", r.deviceType)
        assertEquals("iOS", r.os)
    }

    @Test
    fun `parse Opera`() {
        val ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 OPR/105.0.0.0"
        val r = UserAgentService.parse(ua)
        assertEquals("Opera", r.browser)
        assertEquals("105", r.browserVersion)
    }

    @Test
    fun `parse Vivaldi`() {
        val ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Vivaldi/6.4"
        val r = UserAgentService.parse(ua)
        assertEquals("Vivaldi", r.browser)
        assertEquals("6", r.browserVersion)
    }

    @Test
    fun `parse Samsung Internet`() {
        val ua =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) SamsungBrowser/23.0 Chrome/115.0.0.0 Mobile Safari/537.36"
        val r = UserAgentService.parse(ua)
        assertEquals("Samsung Internet", r.browser)
        assertEquals("23", r.browserVersion)
        assertEquals("Mobile", r.deviceType)
    }

    @Test
    fun `Windows NT 6_1 maps to 7`() {
        val ua = "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:52.0) Gecko/20100101 Firefox/52.0"
        val r = UserAgentService.parse(ua)
        assertEquals("Windows", r.os)
        assertEquals("7", r.osVersion)
    }

    @Test
    fun `Windows NT 6_2 maps to 8`() {
        val ua =
            "Mozilla/5.0 (Windows NT 6.2; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
        val r = UserAgentService.parse(ua)
        assertEquals("8", r.osVersion)
    }

    @Test
    fun `Windows NT 6_3 maps to 8_1`() {
        val ua =
            "Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
        val r = UserAgentService.parse(ua)
        assertEquals("8.1", r.osVersion)
    }

    @Test
    fun `Windows NT 6_0 maps to Vista`() {
        val ua =
            "Mozilla/5.0 (Windows NT 6.0; WOW64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/49.0.2623.112 Safari/537.36"
        val r = UserAgentService.parse(ua)
        assertEquals("Vista", r.osVersion)
    }

    @Test
    fun `Windows NT 5_1 maps to XP`() {
        val ua =
            "Mozilla/5.0 (Windows NT 5.1; rv:52.0) Gecko/20100101 Firefox/52.0"
        val r = UserAgentService.parse(ua)
        assertEquals("XP", r.osVersion)
    }
}
