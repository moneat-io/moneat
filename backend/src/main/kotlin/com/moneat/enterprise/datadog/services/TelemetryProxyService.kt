// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.services

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Telemetry proxy: acknowledges DD Agent telemetry payloads.
 * These contain tracer/agent metadata that is not stored but
 * must return 202 to keep the agent happy.
 */
object TelemetryProxyService {

    fun acknowledge(
        organizationId: Int,
        path: String,
        bodySize: Int,
    ) {
        logger.debug {
            "DD telemetry proxy: org=$organizationId path=$path " +
                "size=${bodySize}B"
        }
    }
}
