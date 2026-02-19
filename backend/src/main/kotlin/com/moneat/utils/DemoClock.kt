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

import com.moneat.config.EnvConfig
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Virtual clock for demo mode.
 * When demo mode is active, returns the configured demo epoch instead of the current time.
 */
object DemoClock {
    /**
     * Get current timestamp in milliseconds.
     * In demo mode, returns the demo epoch; otherwise returns actual current time.
     */
    fun nowMs(isDemo: Boolean): Long {
        return if (isDemo) {
            EnvConfig.Demo.epochMs
        } else {
            System.currentTimeMillis()
        }
    }

    /**
     * Get current instant.
     * In demo mode, returns the demo epoch; otherwise returns actual current time.
     */
    fun now(isDemo: Boolean): Instant {
        return if (isDemo) {
            Instant.fromEpochMilliseconds(EnvConfig.Demo.epochMs)
        } else {
            Clock.System.now()
        }
    }

    /**
     * Get current timestamp in seconds.
     * In demo mode, returns the demo epoch; otherwise returns actual current time.
     */
    fun nowSeconds(isDemo: Boolean): Long {
        return nowMs(isDemo) / 1000
    }
}
