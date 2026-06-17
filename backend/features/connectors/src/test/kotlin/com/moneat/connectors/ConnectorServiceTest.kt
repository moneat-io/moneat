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

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ConnectorServiceTest {
    companion object {
        private const val ORGANIZATION_ID = 10
        private const val OTHER_ORGANIZATION_ID = 11
        private const val USER_ID = 50
        private const val PROJECT_ID = 100L
        private const val OTHER_PROJECT_ID = 101L
        private const val REVENUECAT_PROJECT_ID = "proj_bandapella"
        private const val REVENUECAT_SECRET = "rc_sk_live_1234"
        private const val GOOGLE_ADS_CUSTOMER_ID = "1234567890"
        private const val GOOGLE_ADS_CHILD_CUSTOMER_ID = "2223334444"
        private const val GOOGLE_ADS_REFRESH_TOKEN = "google_refresh_3456"
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_connector_service;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        ConnectorH2Schema.reset(
            Users,
            Organizations,
            Projects,
        )
        seedBaseRows()
    }

    @Test
    fun `create installation redacts credentials and caches RevenueCat apps`() {
        val queuedMessages = mutableListOf<String>()
        val service = connectorService(queuedMessages)

        val response = service.createInstallation(ORGANIZATION_ID, USER_ID, createRequest())

        assertEquals(RevenueCatClient.PROVIDER_ID, response.providerId)
        assertEquals(REVENUECAT_PROJECT_ID, response.externalProjectId)
        assertEquals("Bandapella", response.externalProjectName)
        assertEquals("1234", response.apiSecretLastFour)
        assertNotNull(response.webhookToken)
        assertEquals(response.webhookToken.take(WEBHOOK_PREFIX_LENGTH), response.webhookTokenPrefix)
        assertTrue(response.webhookToken.startsWith("mrc_"))
        assertTrue(queuedMessages.isEmpty())

        transaction {
            val installation = ConnectorInstallations.selectAll().single()
            assertFalse(installation[ConnectorInstallations.apiSecretCiphertext].orEmpty().contains(REVENUECAT_SECRET))
            assertNotEquals(response.webhookToken, installation[ConnectorInstallations.webhookTokenHash])
            assertEquals(2L, ConnectorExternalResources.selectAll().count())
        }
    }

    @Test
    fun `create installation redacts Google Ads OAuth credentials and caches accounts`() {
        val service = connectorService()

        val response = service.createInstallation(ORGANIZATION_ID, USER_ID, googleAdsRequest())

        assertEquals(GoogleAdsClient.PROVIDER_ID, response.providerId)
        assertEquals(GOOGLE_ADS_CUSTOMER_ID, response.externalProjectId)
        assertEquals("Bandapella Google Ads", response.externalProjectName)
        assertEquals("oauth_refresh_token", response.credentialType)
        assertEquals("3456", response.apiSecretLastFour)
        assertEquals("healthy", response.status)

        transaction {
            val installation = ConnectorInstallations.selectAll().single()
            assertFalse(
                installation[ConnectorInstallations.apiSecretCiphertext].orEmpty().contains(GOOGLE_ADS_REFRESH_TOKEN)
            )
            val resources = ConnectorExternalResources.selectAll().toList()
            assertEquals(2, resources.size)
            assertTrue(
                resources.any { row ->
                    row[ConnectorExternalResources.externalResourceId] == GOOGLE_ADS_CUSTOMER_ID
                }
            )
            assertTrue(
                resources.any { row ->
                    row[ConnectorExternalResources.externalResourceId] == GOOGLE_ADS_CHILD_CUSTOMER_ID
                }
            )
        }
    }

    @Test
    fun `webhook auth is enforced and duplicate deliveries are idempotent raw events`() {
        val queuedMessages = mutableListOf<String>()
        val service = connectorService(queuedMessages)
        val installation = service.createInstallation(ORGANIZATION_ID, USER_ID, createRequest())
        val body = revenueCatBody(eventId = "evt_1", appId = "app_ios", environment = "sandbox")

        val error = assertFailsWith<ConnectorServiceException> {
            service.acceptRevenueCatWebhook(
                installationResourceId = installation.id,
                authorizationHeader = "Bearer wrong",
                body = body,
                requestHeaders = mapOf("user-agent" to "RevenueCat"),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, error.status)

        val accepted = service.acceptRevenueCatWebhook(
            installationResourceId = installation.id,
            authorizationHeader = "Bearer ${installation.webhookToken}",
            body = body,
            requestHeaders = mapOf("user-agent" to "RevenueCat"),
        )
        val duplicate = service.acceptRevenueCatWebhook(
            installationResourceId = installation.id,
            authorizationHeader = "Bearer ${installation.webhookToken}",
            body = body,
            requestHeaders = mapOf("user-agent" to "RevenueCat"),
        )

        assertFalse(accepted.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(1, queuedMessages.size)
        transaction {
            assertEquals(2L, ConnectorInboundEventsRaw.selectAll().count())
            assertEquals(1L, ConnectorEventReceipts.selectAll().count())
            val receipt = ConnectorEventReceipts.selectAll().single()
            assertEquals("queued", receipt[ConnectorEventReceipts.state])
            val raw = ConnectorInboundEventsRaw.selectAll().first()
            assertEquals("sandbox", raw[ConnectorInboundEventsRaw.environment])
        }
    }

    @Test
    fun `bindings replace active mappings and reject cross-org projects`() {
        val service = connectorService()
        val installation = service.createInstallation(ORGANIZATION_ID, USER_ID, createRequest())
        val projectResourceId = projectResourceId(PROJECT_ID)
        val otherProjectResourceId = projectResourceId(OTHER_PROJECT_ID)

        val crossOrgError = assertFailsWith<ConnectorServiceException> {
            service.upsertBindings(
                organizationId = ORGANIZATION_ID,
                userId = USER_ID,
                installationResourceId = installation.id,
                request = bindingRequest("app_ios", otherProjectResourceId),
            )
        }
        assertEquals(HttpStatusCode.NotFound, crossOrgError.status)

        val active = service.upsertBindings(
            organizationId = ORGANIZATION_ID,
            userId = USER_ID,
            installationResourceId = installation.id,
            request = bindingRequest("app_ios", projectResourceId),
        )
        assertEquals(1, active.bindings.count { binding -> binding.status == "active" })

        val removed = service.upsertBindings(
            organizationId = ORGANIZATION_ID,
            userId = USER_ID,
            installationResourceId = installation.id,
            request = UpsertConnectorBindingRequest(emptyList()),
        )

        assertEquals(0, removed.bindings.count { binding -> binding.status == "active" })
        assertEquals(1, removed.bindings.count { binding -> binding.status == "removed" })
        assertNotNull(removed.bindings.single().effectiveTo)
    }

    @Test
    fun `state reports environment counts and unmapped RevenueCat apps`() {
        val service = connectorService()
        val installation = service.createInstallation(ORGANIZATION_ID, USER_ID, createRequest())
        val webhookToken = installation.webhookToken

        service.acceptRevenueCatWebhook(
            installationResourceId = installation.id,
            authorizationHeader = "Bearer $webhookToken",
            body = revenueCatBody(eventId = "evt_sandbox", appId = "app_ios", environment = "sandbox"),
            requestHeaders = emptyMap(),
        )
        service.acceptRevenueCatWebhook(
            installationResourceId = installation.id,
            authorizationHeader = "Bearer $webhookToken",
            body = revenueCatBody(eventId = "evt_production", appId = "app_android", environment = "production"),
            requestHeaders = emptyMap(),
        )
        service.upsertBindings(
            organizationId = ORGANIZATION_ID,
            userId = USER_ID,
            installationResourceId = installation.id,
            request = bindingRequest("app_ios", projectResourceId(PROJECT_ID)),
        )

        val detail = assertNotNull(service.revenueCatState(ORGANIZATION_ID).second)

        assertEquals(1, detail.mappedResources)
        assertEquals(1, detail.unmappedEvents)
        assertEquals(1L, detail.sandboxEvents)
        assertEquals(1L, detail.productionEvents)
        assertEquals("needs_mapping", detail.health)
    }

    @Test
    fun `state reports connected Google Ads installation detail`() {
        val service = connectorService()

        service.createInstallation(ORGANIZATION_ID, USER_ID, googleAdsRequest())

        val detail = assertNotNull(service.googleAdsState(ORGANIZATION_ID).second)

        assertEquals(0, detail.mappedResources)
        assertEquals("healthy", detail.health)
        assertEquals("Connected to 2 Google Ads accounts", detail.message)
    }

    @Test
    fun `google ads sync run imports spend facts and updates state`() = runBlocking {
        val queuedImports = mutableListOf<String>()
        var importedFacts = 0
        val service = connectorService(
            importMessages = queuedImports,
            insertAdSpendFacts = { facts -> importedFacts += facts.size },
        )
        val installation = service.createInstallation(ORGANIZATION_ID, USER_ID, googleAdsRequest())
        service.upsertBindings(
            organizationId = ORGANIZATION_ID,
            userId = USER_ID,
            installationResourceId = installation.id,
            request = googleAdsBindingRequest(projectResourceId(PROJECT_ID)),
        )

        val queued = service.enqueueSync(
            organizationId = ORGANIZATION_ID,
            userId = USER_ID,
            installationResourceId = installation.id,
            request = ConnectorSyncRequest(startDate = "2026-06-01", endDate = "2026-06-02"),
        )

        assertEquals("queued", queued.status)
        assertEquals(1, queuedImports.size)

        service.processImportRun(queuedImports.single().toLong())

        val run = service.listImportRuns(ORGANIZATION_ID, installation.id).runs.single()
        assertEquals("succeeded", run.status)
        assertEquals(1, run.rowsImported)
        assertEquals(1, importedFacts)

        val detail = assertNotNull(service.googleAdsState(ORGANIZATION_ID).second)
        assertEquals("succeeded", detail.lastImportStatus)
        assertEquals(1, detail.lastImportRows)
        assertEquals("Imported 1 Google Ads spend rows", detail.message)
    }

    private fun connectorService(
        queuedMessages: MutableList<String> = mutableListOf(),
        importMessages: MutableList<String> = mutableListOf(),
        insertAdSpendFacts: suspend (List<AppAdSpendFact>) -> Unit = {},
    ): ConnectorService =
        connectorService(
            queuedMessages = queuedMessages,
            importService = ConnectorImportService(
                googleAdsClient = FakeGoogleAdsProviderClient(),
                secretCipherFactory = { FakeConnectorSecretCipher },
                enqueueConnectorImport = { payload -> importMessages += payload },
                insertAdSpendFacts = insertAdSpendFacts,
            ),
        )

    private fun connectorService(
        queuedMessages: MutableList<String>,
        importService: ConnectorImportService,
    ): ConnectorService =
        ConnectorService(
            revenueCatClient = FakeRevenueCatProviderClient(),
            googleAdsClient = FakeGoogleAdsProviderClient(),
            secretCipherFactory = { FakeConnectorSecretCipher },
            enqueueConnectorEvent = { payload -> queuedMessages += payload },
            importService = importService,
        )

    private fun createRequest(): CreateConnectorInstallationRequest =
        CreateConnectorInstallationRequest(
            providerId = RevenueCatClient.PROVIDER_ID,
            authProfileId = RevenueCatClient.AUTH_PROFILE_PROJECT_API_KEY,
            name = "Bandapella RevenueCat",
            externalAccount = ConnectorExternalAccountRequest(projectId = REVENUECAT_PROJECT_ID),
            secret = REVENUECAT_SECRET,
        )

    private fun googleAdsRequest(): CreateConnectorInstallationRequest =
        CreateConnectorInstallationRequest(
            providerId = GoogleAdsClient.PROVIDER_ID,
            authProfileId = GoogleAdsClient.AUTH_PROFILE_MANAGER_OAUTH,
            name = "Bandapella Google Ads",
            externalAccount = ConnectorExternalAccountRequest(
                customerId = GOOGLE_ADS_CUSTOMER_ID,
                managerCustomerId = GOOGLE_ADS_CUSTOMER_ID,
            ),
            secret = """{"refreshToken":"$GOOGLE_ADS_REFRESH_TOKEN"}""",
        )

    private fun bindingRequest(
        appId: String,
        projectResourceId: String,
    ): UpsertConnectorBindingRequest =
        UpsertConnectorBindingRequest(
            listOf(
                ConnectorBindingInput(
                    externalResourceType = RevenueCatClient.RESOURCE_TYPE_APP,
                    externalResourceId = appId,
                    localResourceType = RevenueCatClient.LOCAL_RESOURCE_PROJECT,
                    localResourceId = projectResourceId,
                )
            )
        )

    private fun googleAdsBindingRequest(projectResourceId: String): UpsertConnectorBindingRequest =
        UpsertConnectorBindingRequest(
            listOf(
                ConnectorBindingInput(
                    externalResourceType = GoogleAdsClient.RESOURCE_TYPE_MANAGER,
                    externalResourceId = GOOGLE_ADS_CUSTOMER_ID,
                    localResourceType = "project",
                    localResourceId = projectResourceId,
                )
            )
        )

    private fun revenueCatBody(
        eventId: String,
        appId: String,
        environment: String,
    ): ByteArray =
        """
        {
          "api_version": "1.0",
          "event": {
            "id": "$eventId",
            "type": "INITIAL_PURCHASE",
            "app_id": "$appId",
            "environment": "$environment",
            "event_timestamp_ms": 1720000000000,
            "app_user_id": "user_1",
            "product_id": "bandapella_pro_monthly"
          }
        }
        """.trimIndent().toByteArray()

    private fun projectResourceId(projectId: Long): String =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .single()[Projects.resource_id]
                .toString()
        }

    private fun seedBaseRows() {
        transaction {
            Users.insert {
                it[id] = USER_ID
                it[email] = "owner@example.com"
                it[password_hash] = "hash"
            }
            Organizations.insert {
                it[id] = ORGANIZATION_ID
                it[name] = "Bandapella"
                it[slug] = "bandapella"
            }
            Organizations.insert {
                it[id] = OTHER_ORGANIZATION_ID
                it[name] = "Other Org"
                it[slug] = "other-org"
            }
            Projects.insert {
                it[id] = PROJECT_ID
                it[organization_id] = ORGANIZATION_ID
                it[name] = "Bandapella iOS"
                it[slug] = "bandapella-ios"
            }
            Projects.insert {
                it[id] = OTHER_PROJECT_ID
                it[organization_id] = OTHER_ORGANIZATION_ID
                it[name] = "Other App"
                it[slug] = "other-app"
            }
        }
    }

    private class FakeRevenueCatProviderClient : RevenueCatProviderClient {
        override fun resolveProject(apiKey: String, projectId: String): RevenueCatProject {
            if (apiKey != REVENUECAT_SECRET) {
                throw RevenueCatClientException("RevenueCat rejected the API key", "revenuecat_unauthorized")
            }
            if (projectId != REVENUECAT_PROJECT_ID) {
                throw RevenueCatClientException("RevenueCat project was not visible", "project_not_found")
            }
            return RevenueCatProject(id = REVENUECAT_PROJECT_ID, name = "Bandapella")
        }

        override fun listApps(apiKey: String, projectId: String): List<RevenueCatApp> {
            resolveProject(apiKey, projectId)
            return listOf(
                RevenueCatApp(id = "app_ios", name = "Bandapella iOS", platform = "app_store"),
                RevenueCatApp(id = "app_android", name = "Bandapella Android", platform = "play_store"),
            )
        }

        override fun listWebhookIntegrations(
            apiKey: String,
            projectId: String,
            expectedUrl: String,
        ): Pair<List<ConnectorObservedWebhookIntegration>, List<String>> {
            resolveProject(apiKey, projectId)
            return emptyList<ConnectorObservedWebhookIntegration>() to emptyList()
        }
    }

    private class FakeGoogleAdsProviderClient : GoogleAdsProviderClient {
        override fun validateCustomer(
            credential: GoogleAdsOAuthCredential,
            customerId: String,
            managerCustomerId: String?,
        ): GoogleAdsCustomerAccount {
            if (credential.refreshToken != GOOGLE_ADS_REFRESH_TOKEN) {
                throw GoogleAdsClientException("Google Ads rejected the OAuth credential", "google_ads_unauthorized")
            }
            if (customerId != GOOGLE_ADS_CUSTOMER_ID) {
                throw GoogleAdsClientException("Google Ads customer was not visible", "google_ads_customer_not_found")
            }
            return GoogleAdsCustomerAccount(
                customerId = GOOGLE_ADS_CUSTOMER_ID,
                resourceName = "customers/$GOOGLE_ADS_CUSTOMER_ID",
                descriptiveName = "Bandapella Google Ads",
                manager = true,
                testAccount = false,
                status = "ENABLED",
                currencyCode = "USD",
                timeZone = "America/New_York",
                level = 0,
                loginCustomerId = managerCustomerId,
            )
        }

        override fun listAccessibleCustomers(credential: GoogleAdsOAuthCredential): List<GoogleAdsCustomerAccount> =
            listOf(validateCustomer(credential, GOOGLE_ADS_CUSTOMER_ID, null))

        override fun listCustomerClients(
            credential: GoogleAdsOAuthCredential,
            loginCustomerId: String,
        ): List<GoogleAdsCustomerAccount> {
            validateCustomer(credential, loginCustomerId, loginCustomerId)
            return listOf(
                GoogleAdsCustomerAccount(
                    customerId = GOOGLE_ADS_CHILD_CUSTOMER_ID,
                    resourceName = "customers/$GOOGLE_ADS_CHILD_CUSTOMER_ID",
                    descriptiveName = "Bandapella iOS UA",
                    manager = false,
                    testAccount = false,
                    status = "ENABLED",
                    currencyCode = "USD",
                    timeZone = "America/New_York",
                    level = 1,
                    loginCustomerId = loginCustomerId,
                )
            )
        }

        override fun fetchSpendReport(
            credential: GoogleAdsOAuthCredential,
            customerId: String,
            loginCustomerId: String?,
            startDate: LocalDate,
            endDate: LocalDate,
        ): List<GoogleAdsSpendReportRow> {
            validateCustomer(credential, customerId, loginCustomerId)
            return listOf(
                GoogleAdsSpendReportRow(
                    reportDate = startDate,
                    customerId = customerId,
                    loginCustomerId = loginCustomerId,
                    campaignId = "campaign_1",
                    campaignName = "Bandapella Search",
                    campaignStatus = "ENABLED",
                    campaignType = "SEARCH",
                    campaignSubType = null,
                    biddingStrategyType = "MAXIMIZE_CONVERSIONS",
                    adGroupId = "ad_group_1",
                    adGroupName = "Core",
                    adGroupStatus = "ENABLED",
                    device = "MOBILE",
                    geoTargetCountry = "geoTargetConstants/2840",
                    currencyCode = "USD",
                    timeZone = "America/New_York",
                    impressions = 100,
                    clicks = 12,
                    costMicros = 4_200_000,
                    conversions = 2.0,
                    conversionsValue = 12.0,
                    allConversions = 3.0,
                    allConversionsValue = 15.0,
                )
            )
        }
    }

    private object FakeConnectorSecretCipher : ConnectorSecretCipher {
        override val activeKeyId: String = "fake-key"

        override fun encrypt(plaintext: String, organizationId: Int): String =
            "fake:$organizationId:${Base64.getEncoder().encodeToString(plaintext.toByteArray())}"

        override fun decrypt(envelope: String, organizationId: Int): String {
            val prefix = "fake:$organizationId:"
            require(envelope.startsWith(prefix)) { "Unexpected fake secret envelope" }
            return String(Base64.getDecoder().decode(envelope.removePrefix(prefix)))
        }
    }
}

private const val WEBHOOK_PREFIX_LENGTH = 12
