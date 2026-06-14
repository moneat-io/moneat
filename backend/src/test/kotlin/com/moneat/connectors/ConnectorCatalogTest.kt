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

package com.moneat.connectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectorCatalogTest {
    @Test
    fun `catalog keeps notification connectors on organization integrations`() {
        val slack = use("slack", "notifications")
        val discord = use("discord", "notifications")

        assertEquals(ConnectorFamily.NOTIFICATION, slack.family)
        assertEquals("organization_integrations", slack.stateSource)
        assertEquals("notification", slack.secretPurpose)
        assertEquals(ConnectorFamily.NOTIFICATION, discord.family)
        assertEquals("organization_integrations", discord.stateSource)
    }

    @Test
    fun `catalog lets one provider support multiple connector uses`() {
        val github = provider("github")
        val repositoryImport = use("github", "repository_import")
        val workflowActions = use("github", "workflow_actions")

        assertTrue(github.description.contains("repositories"))
        assertEquals(ConnectorFamily.DATA_IMPORT, repositoryImport.family)
        assertEquals("cloud_sources", repositoryImport.stateSource)
        assertEquals("data_import", repositoryImport.secretPurpose)
        assertEquals(ConnectorFamily.WORKFLOW_EGRESS, workflowActions.family)
        assertEquals("workflow_connections", workflowActions.stateSource)
        assertEquals("workflow_egress", workflowActions.secretPurpose)
        assertEquals(listOf("read_app_installation"), repositoryImport.allowedAuthProfileIds)
        assertEquals(listOf("workflow_app_installation"), workflowActions.allowedAuthProfileIds)
        assertEquals(
            setOf("read_app_installation", "workflow_app_installation"),
            github.authProfiles.map { it.id }.toSet()
        )
    }

    @Test
    fun `catalog models acquisition providers as data imports`() {
        val revenueCat = use("revenuecat", "subscription_import")
        val googleAds = use("google_ads", "ad_spend_import")

        assertEquals(ConnectorFamily.DATA_IMPORT, revenueCat.family)
        assertEquals("cloud_sources", revenueCat.stateSource)
        assertEquals("data_import", revenueCat.secretPurpose)
        assertEquals(ConnectorFamily.DATA_IMPORT, googleAds.family)
        assertEquals("cloud_sources", googleAds.stateSource)
    }

    private fun provider(id: String): ConnectorProviderDefinition {
        val provider = ConnectorCatalog.providers.firstOrNull { it.id == id }
        assertNotNull(provider)
        return provider
    }

    private fun use(providerId: String, useId: String): ConnectorUseDefinition {
        val use = provider(providerId).uses.firstOrNull { it.id == useId }
        assertNotNull(use)
        return use
    }
}
