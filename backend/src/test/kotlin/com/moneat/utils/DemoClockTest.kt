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
import kotlin.test.assertTrue

class DemoClockTest {

    @Test
    fun `nowMs returns current time when not demo`() {
        val before = System.currentTimeMillis()
        val result = DemoClock.nowMs(isDemo = false)
        val after = System.currentTimeMillis()
        assertTrue(result in before..after, "nowMs should return current time when not demo")
    }

    @Test
    fun `nowMs returns demo epoch when demo`() {
        // DemoClock.nowMs(true) returns EnvConfig.Demo.epochMs
        // Without DEMO_EPOCH_MS env var, it defaults to current time
        val before = System.currentTimeMillis()
        val result = DemoClock.nowMs(isDemo = true)
        val after = System.currentTimeMillis()
        // Without DEMO_EPOCH_MS set, should be approximately current time
        assertTrue(result in (before - 1000)..after, "Demo epochMs should default to current time")
    }

    @Test
    fun `now returns Instant when not demo`() {
        val before = System.currentTimeMillis()
        val result = DemoClock.now(isDemo = false)
        val after = System.currentTimeMillis()
        assertTrue(result.toEpochMilliseconds() in before..after)
    }

    @Test
    fun `now returns demo Instant when demo`() {
        val result = DemoClock.now(isDemo = true)
        // Should return a valid Instant
        assertTrue(result.toEpochMilliseconds() > 0)
    }

    @Test
    fun `nowSeconds returns time in seconds when not demo`() {
        val beforeSeconds = System.currentTimeMillis() / 1000
        val result = DemoClock.nowSeconds(isDemo = false)
        val afterSeconds = System.currentTimeMillis() / 1000
        assertTrue(result in beforeSeconds..afterSeconds)
    }

    @Test
    fun `nowSeconds is consistent with nowMs`() {
        val ms = DemoClock.nowMs(isDemo = false)
        val seconds = DemoClock.nowSeconds(isDemo = false)
        // Should be within 1 second
        assertTrue(kotlin.math.abs(ms / 1000 - seconds) <= 1)
    }
}
