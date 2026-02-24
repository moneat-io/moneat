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

package com.moneat.dashboards.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DataSource(val tableName: String) {
    @SerialName("events")
    EVENTS("events"),

    @SerialName("spans")
    SPANS("spans"),

    @SerialName("logs")
    LOGS("logs"),

    @SerialName("system_metrics")
    SYSTEM_METRICS("system_metrics"),

    @SerialName("container_metrics")
    CONTAINER_METRICS("container_metrics"),

    @SerialName("uptime_heartbeats")
    UPTIME_HEARTBEATS("uptime_heartbeats"),

    @SerialName("llm_generations")
    LLM_GENERATIONS("llm_generations"),

    @SerialName("analytics_events")
    ANALYTICS_EVENTS("analytics_events");

    companion object {
        fun fromString(value: String): DataSource? = entries.find { it.tableName == value }
    }
}

@Serializable
enum class AggFunction(val value: String) {
    @SerialName("count")
    COUNT("count"),

    @SerialName("avg")
    AVG("avg"),

    @SerialName("sum")
    SUM("sum"),

    @SerialName("min")
    MIN("min"),

    @SerialName("max")
    MAX("max"),

    @SerialName("p50")
    P50("p50"),

    @SerialName("p75")
    P75("p75"),

    @SerialName("p90")
    P90("p90"),

    @SerialName("p95")
    P95("p95"),

    @SerialName("p99")
    P99("p99"),

    @SerialName("uniq")
    UNIQ("uniq"),

    @SerialName("countIf")
    COUNT_IF("countIf");

    fun toClickHouse(field: String?): String = when (this) {
        COUNT -> "count()"
        AVG -> "avg(${field ?: "*"})"
        SUM -> "sum(${field ?: "0"})"
        MIN -> "min(${field ?: "0"})"
        MAX -> "max(${field ?: "0"})"
        P50 -> "quantile(0.50)(${field ?: "0"})"
        P75 -> "quantile(0.75)(${field ?: "0"})"
        P90 -> "quantile(0.90)(${field ?: "0"})"
        P95 -> "quantile(0.95)(${field ?: "0"})"
        P99 -> "quantile(0.99)(${field ?: "0"})"
        UNIQ -> "uniq(${field ?: "''"})"
        COUNT_IF -> "countIf(${field ?: "1"})"
    }
}

@Serializable
enum class FilterOp(val value: String) {
    @SerialName("eq")
    EQ("="),

    @SerialName("neq")
    NEQ("!="),

    @SerialName("gt")
    GT(">"),

    @SerialName("gte")
    GTE(">="),

    @SerialName("lt")
    LT("<"),

    @SerialName("lte")
    LTE("<="),

    @SerialName("like")
    LIKE("LIKE"),

    @SerialName("not_like")
    NOT_LIKE("NOT LIKE"),

    @SerialName("in")
    IN("IN"),

    @SerialName("not_in")
    NOT_IN("NOT IN"),

    @SerialName("is_null")
    IS_NULL("IS NULL"),

    @SerialName("is_not_null")
    IS_NOT_NULL("IS NOT NULL")
}

@Serializable
enum class GroupByType {
    @SerialName("field")
    FIELD,

    @SerialName("time")
    TIME
}

@Serializable
data class MetricDef(
    val function: AggFunction,
    val field: String? = null,
    val alias: String? = null
)

@Serializable
data class GroupByDef(
    val field: String,
    val type: GroupByType = GroupByType.FIELD,
    val interval: String? = null
)

@Serializable
data class FilterDef(
    val field: String,
    val op: FilterOp,
    val value: String? = null,
    val values: List<String>? = null
)

@Serializable
data class OrderByDef(
    val field: String,
    val direction: String = "desc"
)

@Serializable
data class TimeRangeDef(
    val from: String = "now-24h",
    val to: String = "now"
)

@Serializable
data class QueryDsl(
    val dataSource: String,
    val metrics: List<MetricDef> = emptyList(),
    val groupBy: List<GroupByDef> = emptyList(),
    val filters: List<FilterDef> = emptyList(),
    val orderBy: OrderByDef? = null,
    val limit: Int = 100,
    val timeRange: TimeRangeDef = TimeRangeDef(),
    val rawQuery: String? = null,
    @SerialName("ref_id") val refId: String? = null
)

@Serializable
data class DataSourceInfo(
    val name: String,
    val label: String,
    val fields: List<DataSourceField>
)

@Serializable
data class DataSourceField(
    val name: String,
    val type: String,
    val description: String = ""
)
