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

package com.moneat.dashboards.services.handlers

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.Dimension
import aws.sdk.kotlin.services.cloudwatch.model.GetMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.Metric
import aws.sdk.kotlin.services.cloudwatch.model.MetricDataQuery
import aws.sdk.kotlin.services.cloudwatch.model.MetricStat
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import com.moneat.dashboards.models.DataSourceField
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.DataSourceCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * CloudWatch handler using AWS SDK GetMetricData.
 * Host is unused; region comes from credentials.
 * Query format: JSON with namespace, metricName, dimensions.
 * Example: {"Namespace":"AWS/EC2","MetricName":"CPUUtilization"}
 */
class CloudWatchHandler : DataSourceHandler {

    companion object {
        private const val CLOUDWATCH_DEFAULT_PERIOD_SECONDS = 300
        private const val CLOUDWATCH_MAX_DATAPOINTS = 100_800
        private const val MILLIS_PER_SECOND = 1_000L
        private const val DAYS_PER_WEEK = 7L
        private const val DAYS_PER_MONTH = 30L
        private const val DAYS_PER_YEAR = 365L
    }

    override suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val region = request.region ?: "us-east-1"
        val accessKey = request.accessKeyId
        val secretKey = request.secretAccessKey
        if (accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
            return TestConnectionResult(false, "AWS access key and secret key are required")
        }

        return suspendRunCatching {
            createClient(region, accessKey, secretKey).use { client ->
                val now = Clock.System.now()
                val startJava = java.time.Instant.ofEpochMilli(now.minus(1.hours).toEpochMilliseconds())
                val endJava = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
                val request = GetMetricDataRequest {
                    startTime = aws.smithy.kotlin.runtime.time.Instant(startJava)
                    endTime = aws.smithy.kotlin.runtime.time.Instant(endJava)
                    metricDataQueries = listOf(
                        MetricDataQuery {
                            id = "m1"
                            metricStat = MetricStat {
                                metric = Metric {
                                    namespace = "AWS/EC2"
                                    metricName = "CPUUtilization"
                                }
                                period = CLOUDWATCH_DEFAULT_PERIOD_SECONDS
                                stat = "Average"
                            }
                            returnData = true
                        }
                    )
                }
                client.getMetricData(request)
            }
            TestConnectionResult(true, "Connected successfully")
        }.getOrElse { e ->
            logger.warn(e) { "CloudWatch connection test failed" }
            TestConnectionResult(false, "Connection failed: ${e.message}")
        }
    }

    override suspend fun executeQuery(
        sourceId: Long,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int,
        timeRange: TimeRangeDef?,
    ): List<Map<String, JsonElement>> {
        val region = credentials.region ?: "us-east-1"
        val accessKey = credentials.accessKeyId ?: return emptyList()
        val secretKey = credentials.secretAccessKey ?: return emptyList()

        val metricSpec = suspendRunCatching {
            json.parseToJsonElement(query).jsonObject
        }.getOrElse { e ->
            logger.error(e) { "Invalid CloudWatch query JSON" }
            return emptyList()
        }

        val namespace = metricSpec["Namespace"]?.jsonPrimitive?.content
            ?: return emptyList()
        val metricName = metricSpec["MetricName"]?.jsonPrimitive?.content
            ?: return emptyList()
        val dimensions = metricSpec["Dimensions"]?.jsonArray?.mapNotNull {
            val n = it.jsonObject["Name"]?.jsonPrimitive?.content
            val v = it.jsonObject["Value"]?.jsonPrimitive?.content
            if (!n.isNullOrEmpty() && !v.isNullOrEmpty()) {
                Dimension {
                    name = n
                    value = v
                }
            } else {
                null
            }
        } ?: emptyList()

        val now = Clock.System.now()
        val startTime = resolveTime(timeRange?.from ?: "now-1h", now)
        val endTime = resolveTime(timeRange?.to ?: "now", now)

        return suspendRunCatching {
            val normalizedLimit = limit.coerceIn(1, CLOUDWATCH_MAX_DATAPOINTS)
            createClient(region, accessKey, secretKey).use { client ->
                val startJava = java.time.Instant.ofEpochMilli(startTime.toEpochMilliseconds())
                val endJava = java.time.Instant.ofEpochMilli(endTime.toEpochMilliseconds())
                val request = GetMetricDataRequest {
                    this.startTime = aws.smithy.kotlin.runtime.time.Instant(startJava)
                    this.endTime = aws.smithy.kotlin.runtime.time.Instant(endJava)
                    maxDatapoints = normalizedLimit
                    metricDataQueries = listOf(
                        MetricDataQuery {
                            id = "m1"
                            metricStat = MetricStat {
                                metric = Metric {
                                    this.namespace = namespace
                                    this.metricName = metricName
                                    this.dimensions = dimensions
                                }
                                period = CLOUDWATCH_DEFAULT_PERIOD_SECONDS
                                stat = "Average"
                            }
                            returnData = true
                        }
                    )
                }
                val response = client.getMetricData(request)

                val rows = mutableListOf<Map<String, JsonElement>>()
                for (result in response.metricDataResults ?: emptyList()) {
                    val label = result.label ?: metricName
                    val timestamps = result.timestamps ?: emptyList()
                    val values = result.values ?: emptyList()
                    for ((i, ts) in timestamps.withIndex()) {
                        val value = values.getOrNull(i) ?: continue
                        val tsMs = ts.epochSeconds * MILLIS_PER_SECOND
                        rows.add(
                            mapOf(
                                "time_bucket" to JsonPrimitive(tsMs),
                                "value" to JsonPrimitive(value),
                                "metric" to JsonPrimitive(label)
                            )
                        )
                        if (rows.size >= normalizedLimit) return rows
                    }
                }
                rows
            }
        }.getOrElse { e ->
            logger.error(e) { "CloudWatch query failed" }
            emptyList()
        }
    }

    override suspend fun getSchema(
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ): List<DataSourceField> {
        return listOf(
            DataSourceField("time_bucket", "timestamp", "Metric timestamp"),
            DataSourceField("value", "double", "Metric value")
        )
    }

    private fun createClient(region: String, accessKey: String, secretKey: String): CloudWatchClient {
        return CloudWatchClient {
            this.region = region
            credentialsProvider = StaticCredentialsProvider(Credentials(accessKey, secretKey, null))
        }
    }

    private fun resolveTime(expr: String, now: Instant): Instant {
        if (expr == "now") return now
        val match = Regex("""^now-(\d+)([smhdwMy])$""").matchEntire(expr) ?: return now
        val amount = match.groupValues[1].toLongOrNull() ?: return now
        fun safeDays(multiplier: Long): Long? =
            if (amount <= Long.MAX_VALUE / multiplier) amount * multiplier else null
        val offset = when (match.groupValues[2]) {
            "s" -> Duration.parse("${amount}s")
            "m" -> Duration.parse("${amount}m")
            "h" -> Duration.parse("${amount}h")
            "d" -> Duration.parse("${amount}d")
            "w" -> Duration.parse("${safeDays(DAYS_PER_WEEK) ?: return now}d")
            "M" -> Duration.parse("${safeDays(DAYS_PER_MONTH) ?: return now}d")
            "y" -> Duration.parse("${safeDays(DAYS_PER_YEAR) ?: return now}d")
            else -> Duration.ZERO
        }
        return now - offset
    }
}
