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

import com.moneat.dashboards.models.CreateDashboardRequest
import com.moneat.dashboards.models.DashboardTemplateCatalog
import com.moneat.dashboards.models.DashboardTemplateDetail
import com.moneat.dashboards.models.DashboardTemplateSummary
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val templateLogger = KotlinLogging.logger {}
private const val DEFAULT_TEMPLATE_CATALOG = "dashboard-templates/catalog.json"

class DashboardTemplateCatalogService(
    private val catalogResourcePath: String = DEFAULT_TEMPLATE_CATALOG,
    private val classLoader: ClassLoader = DashboardTemplateCatalogService::class.java.classLoader,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun listTemplates(): List<DashboardTemplateSummary> =
        loadCatalog().templates

    fun getTemplate(id: String): DashboardTemplateDetail? {
        val summary = loadCatalog().templates.firstOrNull { it.id == id } ?: return null
        val resource = readResource(summary.resourcePath) ?: return null
        return try {
            json.decodeFromString<DashboardTemplateDetail>(resource)
        } catch (e: SerializationException) {
            templateLogger.warn(e) { "Failed to decode dashboard template ${summary.id}" }
            null
        }
    }

    fun getDashboardRequest(id: String): CreateDashboardRequest? =
        getTemplate(id)?.dashboard

    fun listDashboardRequests(): List<CreateDashboardRequest> =
        listTemplates().mapNotNull { summary -> getDashboardRequest(summary.id) }

    private fun loadCatalog(): DashboardTemplateCatalog {
        val resource = readResource(catalogResourcePath) ?: return DashboardTemplateCatalog()
        return try {
            json.decodeFromString<DashboardTemplateCatalog>(resource)
        } catch (e: SerializationException) {
            templateLogger.warn(e) { "Failed to decode dashboard template catalog" }
            DashboardTemplateCatalog()
        }
    }

    private fun readResource(path: String): String? =
        classLoader.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
}
