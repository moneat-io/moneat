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

package com.moneat.datadog.routes

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.reserveDatadogQuota
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.decompression.DecompressionService
import com.moneat.datadog.models.DatadogMetricSeriesV1
import com.moneat.datadog.models.DatadogMetricV1
import com.moneat.datadog.services.DatadogMetricService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val DOGSTATSD_METRIC_SEPARATOR = '|'
private const val DOGSTATSD_TAG_SEPARATOR = '#'
private const val MILLIS_TO_SECONDS = 1000.0

fun Route.datadogDogStatsDRoutes(
    quotaService: BillingQuotaService = BillingQuotaService(),
) {
    route("/dd") {
        route("/dogstatsd/v2") {
            post("/proxy") {
                val orgId = DatadogAuthMiddleware.authenticate(call)
                    ?: return@post

                val contentEncoding =
                    call.request.headers["Content-Encoding"]
                val rawBody = call.receive<ByteArray>()
                val body = DecompressionService.decompress(
                    rawBody,
                    contentEncoding
                )
                val bodyStr = body.decodeToString()

                val metrics = parseDogStatsDLines(bodyStr)

                if (!reserveDatadogQuota(call, quotaService, orgId, metrics.size, "dd_metric", body.size.toLong())) {
                    return@post
                }

                if (metrics.isNotEmpty()) {
                    val payload = DatadogMetricSeriesV1(
                        series = metrics
                    )
                    DatadogMetricService.enqueueMetrics(
                        organizationId = orgId.toLong(),
                        payload = payload
                    )
                }

                logger.debug {
                    "Accepted ${metrics.size} DogStatsD metrics " +
                        "for org $orgId"
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf("status" to "ok")
                )
            }
        }
    }
}

internal fun parseDogStatsDLines(
    body: String
): List<DatadogMetricV1> {
    return body.lines()
        .filter { it.isNotBlank() }
        .mapNotNull { parseDogStatsDLine(it) }
}

@Suppress("ReturnCount")
internal fun parseDogStatsDLine(
    line: String
): DatadogMetricV1? {
    // Format: metric_name:value|type|#tag1:val1,tag2:val2
    val colonIdx = line.indexOf(':')
    if (colonIdx <= 0) return null

    val metricName = line.substring(0, colonIdx)
    val rest = line.substring(colonIdx + 1)

    val pipeParts = rest.split(DOGSTATSD_METRIC_SEPARATOR)
    if (pipeParts.size < 2) return null

    val value = pipeParts[0].toDoubleOrNull() ?: return null
    val type = when (pipeParts[1].trim()) {
        "g" -> "gauge"
        "c" -> "count"
        "r" -> "rate"
        "ms" -> "gauge"
        "h" -> "gauge"
        "s" -> "gauge"
        "d" -> "gauge"
        else -> "gauge"
    }

    val tags = mutableListOf<String>()
    pipeParts.drop(2).forEach { part ->
        if (part.startsWith(DOGSTATSD_TAG_SEPARATOR)) {
            tags.addAll(
                part.substring(1).split(",").filter { it.isNotBlank() }
            )
        }
    }

    val now = System.currentTimeMillis().toDouble() / MILLIS_TO_SECONDS

    return DatadogMetricV1(
        metric = metricName,
        type = type,
        points = listOf(listOf(now, value)),
        tags = tags
    )
}
