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

package com.moneat.dashboards

import com.moneat.dashboards.services.DashboardTemplateCatalogService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardTemplateCatalogServiceTest {

    @Test
    fun `listTemplates loads generated Moneat dashboard resources`() {
        val service = DashboardTemplateCatalogService()

        val templates = service.listTemplates()

        assertTrue(templates.size >= 100)
        assertTrue(templates.none { it.resourcePath.contains("grafana", ignoreCase = true) })
        assertTrue(templates.any { it.requiredSources.contains("Prometheus") })
    }

    @Test
    fun `getTemplate returns dashboard payload by id`() {
        val service = DashboardTemplateCatalogService()
        val summary = service.listTemplates().first()

        val detail = service.getTemplate(summary.id)

        assertNotNull(detail)
        assertEquals(summary.id, detail.id)
        assertEquals(summary.title, detail.dashboard.title)
        assertTrue(detail.dashboard.widgets.isNotEmpty())
    }

    @Test
    fun `getTemplate returns null for missing id`() {
        val service = DashboardTemplateCatalogService()

        val detail = service.getTemplate("missing-template")

        assertEquals(null, detail)
    }

    @Test
    fun `listTemplates returns empty list when catalog resource is missing`() {
        val service = DashboardTemplateCatalogService("dashboard-templates/missing-catalog.json")

        val templates = service.listTemplates()

        assertTrue(templates.isEmpty())
    }

    @Test
    fun `dashboard request helpers return converted create requests`() {
        val service = DashboardTemplateCatalogService()
        val summary = service.listTemplates().first()

        val request = service.getDashboardRequest(summary.id)
        val requests = service.listDashboardRequests()

        assertNotNull(request)
        assertEquals(summary.title, request.title)
        assertTrue(requests.size >= 100)
        assertTrue(requests.any { it.title == summary.title })
    }

    @Test
    fun `catalog entries point to parseable templates with matching counts`() {
        val service = DashboardTemplateCatalogService()

        val failures = service.listTemplates().mapNotNull { summary ->
            val detail = service.getTemplate(summary.id)
                ?: return@mapNotNull "${summary.id}: missing or undecodable ${summary.resourcePath}"
            val widgetCount = detail.dashboard.widgets.size
            val variableCount = detail.dashboard.variables.size
            when {
                widgetCount != summary.widgetCount ->
                    "${summary.id}: widget_count expected ${summary.widgetCount}, got $widgetCount"
                variableCount != summary.variableCount ->
                    "${summary.id}: variable_count expected ${summary.variableCount}, got $variableCount"
                else -> null
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
