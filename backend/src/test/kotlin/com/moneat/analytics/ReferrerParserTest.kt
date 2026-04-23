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

import com.moneat.analytics.services.ReferrerParser
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferrerParserTest {

    @Test
    fun `parse returns Direct for null referrer`() {
        assertEquals("Direct", ReferrerParser.parse(null))
    }

    @Test
    fun `parse returns Direct for empty string referrer`() {
        assertEquals("Direct", ReferrerParser.parse(""))
    }

    @Test
    fun `parse returns Direct for blank referrer`() {
        assertEquals("Direct", ReferrerParser.parse("   "))
    }

    @Test
    fun `parse returns Direct for invalid URI`() {
        assertEquals("Direct", ReferrerParser.parse("not a valid uri"))
    }

    @Test
    fun `parse returns Direct when URI has no host`() {
        assertEquals("Direct", ReferrerParser.parse("urn:isbn:9780123456789"))
    }

    @Test
    fun `parse maps google com and www subdomain to Google`() {
        assertEquals("Google", ReferrerParser.parse("https://google.com/search?q=x"))
        assertEquals("Google", ReferrerParser.parse("https://www.google.com/"))
    }

    @Test
    fun `parse maps facebook com and l facebook subdomain to Facebook`() {
        assertEquals("Facebook", ReferrerParser.parse("https://facebook.com/"))
        assertEquals("Facebook", ReferrerParser.parse("https://l.facebook.com/l.php"))
    }

    @Test
    fun `parse maps t co to Twitter`() {
        assertEquals("Twitter", ReferrerParser.parse("https://t.co/abc123"))
    }

    @Test
    fun `parse maps news ycombinator com to Hacker News`() {
        assertEquals("Hacker News", ReferrerParser.parse("https://news.ycombinator.com/item?id=1"))
    }

    @Test
    fun `parse maps github com to GitHub`() {
        assertEquals("GitHub", ReferrerParser.parse("https://github.com/moneat/moneat"))
    }

    @Test
    fun `parse maps linkedin com to LinkedIn`() {
        assertEquals("LinkedIn", ReferrerParser.parse("https://linkedin.com/in/test"))
    }

    @Test
    fun `parse maps reddit com to Reddit`() {
        assertEquals("Reddit", ReferrerParser.parse("https://reddit.com/r/kotlin"))
    }

    @Test
    fun `parse returns hostname for unknown domains stripping www`() {
        assertEquals("example.com", ReferrerParser.parse("https://www.example.com/path"))
        assertEquals("example.com", ReferrerParser.parse("https://example.com/"))
    }
}
