package com.moneat.utils

import com.moneat.config.EnvConfig
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

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
