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

package com.moneat.dashboards.services

import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.services.handlers.withConnectionOptions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val VARIABLE_QUERY_LIMIT = 500
private const val SQL_QUOTE = '\''
private const val ESCAPED_SQL_QUOTE = "''"

class DashboardVariableResolver(
    private val dataSourceService: CustomDataSourceService,
    private val dataSourceExecutor: CustomDataSourceExecutor,
) {
    suspend fun resolve(
        variables: List<DashboardVariable>,
        currentValues: Map<String, String>,
        orgId: Long,
    ): Map<String, List<String>> {
        val sources = dataSourceService.listDataSources(orgId)
        val resolved = mutableMapOf<String, List<String>>()
        for (variable in variables) {
            val query = variable.query ?: continue
            val request = buildVariableResolutionRequest(variable, query, currentValues, orgId, sources)
                ?: continue
            val options = resolveVariableOptions(request)
            if (options.isNotEmpty()) {
                resolved[variable.name] = options
            }
        }
        return resolved
    }

    private fun buildVariableResolutionRequest(
        variable: DashboardVariable,
        query: String,
        currentValues: Map<String, String>,
        orgId: Long,
        sources: List<CustomDataSourceResponse>,
    ): VariableResolutionRequest? {
        val requiredSourceType = DashboardQueryEngine.templateDataSourceType(variable.datasource)
        val source = sources.firstOrNull {
            it.enabled && it.sourceType.equals(requiredSourceType, ignoreCase = true)
        } ?: return null
        val creds = dataSourceService.getDecryptedCredentials(source.numericId, orgId) ?: return null
        val sourceType = CustomDataSourceType.fromString(source.sourceType) ?: return null
        val isSqlQuery = isSqlVariableQuery(query)
        return VariableResolutionRequest(
            source = source,
            sourceType = sourceType,
            credentials = creds,
            query = query,
            substitutedQuery = substituteVariableQuery(query, currentValues, isSqlQuery),
            variableName = variable.name,
        )
    }

    private suspend fun resolveVariableOptions(
        request: VariableResolutionRequest,
    ): List<String> =
        when {
            request.query.startsWith("label_values(") ->
                dataSourceExecutor.executeLabelValuesQuery(
                    request.sourceType,
                    request.source.host,
                    request.source.port,
                    request.credentials.withConnectionOptions(request.source.extraConfig),
                    request.substitutedQuery,
                )
            isSqlVariableQuery(request.query) -> {
                val rows = dataSourceExecutor.executeQuery(
                    request.source.numericId,
                    request.sourceType,
                    request.source.host,
                    request.source.port,
                    request.source.databaseName,
                    request.credentials.withConnectionOptions(request.source.extraConfig),
                    request.substitutedQuery,
                    VARIABLE_QUERY_LIMIT,
                    null,
                )
                queryRowsToVariableOptions(rows)
            }
            else -> emptyList()
        }

    private data class VariableResolutionRequest(
        val source: CustomDataSourceResponse,
        val sourceType: CustomDataSourceType,
        val credentials: DataSourceCredentials,
        val query: String,
        val substitutedQuery: String,
        val variableName: String,
    )
}

private fun substituteVariableQuery(
    query: String,
    currentValues: Map<String, String>,
    isSqlQuery: Boolean,
): String {
    var substituted = query
    for ((name, rawValue) in sortedVariableEntries(currentValues)) {
        substituted = variableTokenRegex(name).replace(substituted) { match ->
            if (isSqlQuery) {
                sqlVariableReplacement(substituted, match.range.first, rawValue)
            } else {
                rawValue
            }
        }
    }
    return substituted
}

private fun sortedVariableEntries(currentValues: Map<String, String>): List<Map.Entry<String, String>> =
    currentValues.entries.sortedByDescending { it.key.length }

private fun variableTokenRegex(name: String): Regex =
    Regex("""\${'$'}\{${Regex.escape(name)}\}|\${'$'}${Regex.escape(name)}(?![A-Za-z0-9_])""")

private fun sqlVariableReplacement(query: String, tokenStart: Int, rawValue: String): String {
    val escaped = rawValue.replace(SQL_QUOTE.toString(), ESCAPED_SQL_QUOTE)
    return if (isInsideSqlString(query, tokenStart)) escaped else "$SQL_QUOTE$escaped$SQL_QUOTE"
}

private fun isInsideSqlString(query: String, tokenStart: Int): Boolean {
    var insideString = false
    var index = 0
    while (index < tokenStart) {
        if (query[index] == SQL_QUOTE) {
            if (insideString && index + 1 < tokenStart && query[index + 1] == SQL_QUOTE) {
                index++
            } else {
                insideString = !insideString
            }
        }
        index++
    }
    return insideString
}

private fun isSqlVariableQuery(query: String): Boolean {
    val trimmed = query.trimStart()
    return trimmed.startsWith("SELECT", ignoreCase = true) || trimmed.startsWith("WITH", ignoreCase = true)
}

private fun queryRowsToVariableOptions(rows: List<Map<String, JsonElement>>): List<String> =
    rows.mapNotNull { row ->
        row.values.firstOrNull()?.let(::jsonElementToVariableOption)
    }.distinct()

private fun jsonElementToVariableOption(value: JsonElement): String? {
    val option = when (value) {
        is JsonPrimitive -> value.content
        else -> value.toString()
    }.trim()
    return option.takeIf { it.isNotEmpty() && it != "null" }
}
