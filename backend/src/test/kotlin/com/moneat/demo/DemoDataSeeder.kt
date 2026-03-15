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

package com.moneat.demo

import com.moneat.config.ClickHouseClient
import com.moneat.testsupport.TestIpConstants
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Releases
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Users
import com.moneat.statuspage.models.StatusPageIncidentUpdates
import com.moneat.statuspage.models.StatusPageIncidents
import com.moneat.statuspage.models.StatusPageMonitors
import com.moneat.statuspage.models.StatusPages
import com.moneat.uptime.models.UptimeMonitors
import io.ktor.client.statement.*
import io.ktor.http.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Demo Data Seeder for Moneat
 *
 * Seeds realistic, screenshot-ready demo data:
 * - Demo user and organization
 * - Multiple realistic projects
 * - Varied error issues with actual stack traces
 * - Events with realistic context (devices, OS versions, users)
 * - Release tracking
 */
object DemoDataSeeder {

    private fun hashPassword(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt())
    }

    private fun generateKey(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private val random = Random(42) // Deterministic seed for consistent demo data

    private val androidDevices =
        listOf(
            "Samsung Galaxy S23",
            "Google Pixel 8",
            "OnePlus 11",
            "Samsung Galaxy A54",
            "Xiaomi 13 Pro"
        )

    private val iosDevices =
        listOf(
            "iPhone 15 Pro",
            "iPhone 14",
            "iPhone 13",
            "iPad Air",
            "iPad Pro 11\""
        )

    private val androidVersions = listOf("14", "13", "12", "11")
    private val iosVersions = listOf("17.3", "17.2", "16.5", "16.4")

    private val userEmails =
        listOf(
            "sarah.johnson@example.com",
            "mike.chen@example.com",
            "alex.rivera@example.com",
            "priya.patel@example.com",
            "john.smith@example.com",
            "emma.williams@example.com"
        )

    private fun randomTime(daysAgo: Int): Instant {
        val hoursAgo = daysAgo * 24 + random.nextInt(24)
        return Instant.now().minus(hoursAgo.toLong(), ChronoUnit.HOURS)
    }

    suspend fun seed() {
        println("Starting demo data seeding...")

        // Initialize environment config
        EnvConfig.initialize()

        // Connect to database
        val dbUrl =
            EnvConfig.get("POSTGRES_URL")
                ?: "jdbc:postgresql://localhost:5499/moneat"
        val dbUser = EnvConfig.get("POSTGRES_USER") ?: "moneat"
        val dbPassword = EnvConfig.get("POSTGRES_PASSWORD") ?: "moneat_dev_password"

        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = dbUser,
            password = dbPassword
        )

        println("Connected to database: $dbUrl")

        // Initialize ClickHouse client
        val clickhouseUrl = EnvConfig.get("CLICKHOUSE_URL") ?: "http://localhost:8123"
        val clickhouseDb = EnvConfig.get("CLICKHOUSE_DATABASE") ?: "moneat"
        val clickhouseUser = EnvConfig.get("CLICKHOUSE_USER") ?: "moneat"
        val clickhousePassword = EnvConfig.get("CLICKHOUSE_PASSWORD") ?: "moneat_dev_password"

        ClickHouseClient.init(clickhouseUrl, clickhouseDb, clickhouseUser, clickhousePassword)
        println("Connected to ClickHouse: $clickhouseUrl")

        val (_, orgId, projects) =
            transaction {
                // Check if already seeded
                val existingUser = Users.selectAll().where { Users.email eq "demo@moneat.dev" }.firstOrNull()
                if (existingUser != null) {
                    println("Demo data already exists. Fetching existing data...")
                    val userId = existingUser[Users.id]
                    val membership = Memberships.selectAll().where { Memberships.user_id eq userId }.firstOrNull()
                    if (membership != null) {
                        val orgId = membership[Memberships.organization_id]
                        // Fetch existing projects
                        val existingProjects =
                            Projects
                                .selectAll()
                                .where { Projects.organization_id eq orgId }
                                .associate { row ->
                                    val framework = row[Projects.framework] ?: "unknown"
                                    val frameworkKey =
                                        when (framework) {
                                            "react-native" -> "react-native"
                                            "ios" -> "ios"
                                            "android" -> "android"
                                            else -> framework.lowercase()
                                        }
                                    val projectId = row[Projects.id]
                                    val publicKey =
                                        ProjectKeys
                                            .selectAll()
                                            .where { ProjectKeys.project_id eq projectId }
                                            .firstOrNull()
                                            ?.get(ProjectKeys.public_key) ?: ""
                                    frameworkKey to Pair(projectId, publicKey)
                                }
                        return@transaction Triple(userId, orgId, existingProjects)
                    }
                    return@transaction Triple(0, 0, emptyMap<String, Pair<Long, String>>())
                }

                println("Creating demo user...")
                val passwordHash = hashPassword("demo123")

                val userId =
                    Users.insert {
                        it[email] = "demo@moneat.dev"
                        it[password_hash] = passwordHash
                        it[name] = "Demo User"
                        it[email_verified] = true
                        it[onboarding_completed] = true
                    } get Users.id

                println("Created user: $userId")

                // Create organization
                println("Creating demo organization...")
                val orgId =
                    Organizations.insert {
                        it[name] = "Acme Mobile Inc"
                        it[slug] = "acme-mobile"
                        it[company_size] = "11-50"
                    } get Organizations.id

                println("Created organization: $orgId")

                // Add membership
                Memberships.insert {
                    it[user_id] = userId
                    it[organization_id] = orgId
                    it[role] = "owner"
                }

                // Add PRO subscription for demo user (so screenshots don't show upgrade prompts)
                println("Creating PRO subscription for demo org...")
                val now = Clock.System.now()
                val periodEnd = now + 30.days
                Subscriptions.insert {
                    it[organization_id] = orgId
                    it[plan] = "pro"
                    it[status] = "active"
                    it[billing_interval] = "monthly"
                    it[current_period_start] = now
                    it[current_period_end] = periodEnd
                    it[payg_budget_cents] = 0
                    it[payg_used_units] = 0
                    it[payg_used_micros] = 0
                    it[pending_meter_units] = 0
                }

                // Create projects with keys
                println("Creating demo projects...")
                val projects = mutableMapOf<String, Pair<Long, String>>()

                // Android Shopping App
                val androidProjectId =
                    Projects.insert {
                        it[organization_id] = orgId
                        it[name] = "Acme Shopping - Android"
                        it[slug] = "acme-shopping-android"
                        it[framework] = "android"
                    } get Projects.id

                val androidPublicKey = generateKey()
                ProjectKeys.insert {
                    it[project_id] = androidProjectId
                    it[public_key] = androidPublicKey
                    it[secret_key] = generateKey()
                    it[is_active] = true
                }
                projects["android"] = androidProjectId to androidPublicKey

                // iOS Shopping App
                val iosProjectId =
                    Projects.insert {
                        it[organization_id] = orgId
                        it[name] = "Acme Shopping - iOS"
                        it[slug] = "acme-shopping-ios"
                        it[framework] = "ios"
                    } get Projects.id

                val iosPublicKey = generateKey()
                ProjectKeys.insert {
                    it[project_id] = iosProjectId
                    it[public_key] = iosPublicKey
                    it[secret_key] = generateKey()
                    it[is_active] = true
                }
                projects["ios"] = iosProjectId to iosPublicKey

                // React Native App
                val rnProjectId =
                    Projects.insert {
                        it[organization_id] = orgId
                        it[name] = "Acme Shopping - React Native"
                        it[slug] = "acme-shopping-rn"
                        it[framework] = "react-native"
                    } get Projects.id

                val rnPublicKey = generateKey()
                ProjectKeys.insert {
                    it[project_id] = rnProjectId
                    it[public_key] = rnPublicKey
                    it[secret_key] = generateKey()
                    it[is_active] = true
                }
                projects["react-native"] = rnProjectId to rnPublicKey

                println("Created projects: Android=$androidProjectId, iOS=$iosProjectId, RN=$rnProjectId")

                // Create releases
                println("Creating releases...")
                val releases = listOf("1.0.0", "1.1.0", "1.2.0", "1.2.1", "1.3.0")
                releases.forEachIndexed { index, version ->
                    val timestamp = randomTime(30 - index * 5)
                    Releases.insert {
                        it[project_id] = androidProjectId
                        it[Releases.version] = version
                        it[created_at] = timestamp.epochSecond
                    }
                    Releases.insert {
                        it[project_id] = iosProjectId
                        it[Releases.version] = version
                        it[created_at] = timestamp.epochSecond
                    }
                }

                Triple(userId, orgId, projects)
            }

        if (projects.isEmpty()) {
            println("❌ No projects found. Cannot seed data.")
            return
        }

        // Check if issues already exist in ClickHouse
        val db = ClickHouseClient.getDatabase()
        val issueCountResult =
            ClickHouseClient.executeWithFormat(
                "SELECT count() as cnt FROM `$db`.issues",
                "TabSeparated"
            )
        val issueCount = issueCountResult.trim().toLongOrNull() ?: 0

        // Seed ClickHouse data (issues and events) only if not already seeded
        if (issueCount == 0L) {
            println("\nSeeding ClickHouse data (issues and events)...")
            seedClickHouseData(projects)
        } else {
            println("\nIssues already exist ($issueCount found). Skipping issue/event seeding.")
        }

        // Check if other data already exists
        val feedbackCountResult =
            kotlinx.coroutines.runBlocking {
                ClickHouseClient.executeWithFormat(
                    "SELECT count() FROM `$db`.user_feedback WHERE project_id IN (${projects.values.map { it.first }.joinToString(
                        ","
                    )})",
                    "TabSeparated"
                )
            }
        val feedbackCount = feedbackCountResult.trim().toLongOrNull() ?: 0

        val replayCountResult =
            kotlinx.coroutines.runBlocking {
                ClickHouseClient.executeWithFormat(
                    "SELECT count() FROM `$db`.replay_events WHERE project_id IN (${projects.values.map { it.first }.joinToString(
                        ","
                    )})",
                    "TabSeparated"
                )
            }
        val replayCount = replayCountResult.trim().toLongOrNull() ?: 0

        val monitorsExist =
            transaction {
                UptimeMonitors.selectAll().where { UptimeMonitors.organizationId eq orgId }.count() > 0
            }

        val statusPagesExist =
            transaction {
                StatusPages.selectAll().where { StatusPages.organizationId eq orgId }.count() > 0
            }

        // Always seed logs data (they're time-sensitive for demo)
        println("\nSeeding log data...")
        seedLogData(projects)

        // Seed performance/transaction data (only if not exists)
        val transactionCountResult =
            kotlinx.coroutines.runBlocking {
                ClickHouseClient.executeWithFormat(
                    "SELECT count() FROM `$db`.events WHERE event_type = 'transaction' AND project_id IN (${projects.values.map { it.first }.joinToString(
                        ","
                    )})",
                    "TabSeparated"
                )
            }
        val transactionCount = transactionCountResult.trim().toLongOrNull() ?: 0L
        if (transactionCount == 0L) {
            println("\nSeeding performance/transaction data...")
            seedTransactionData(projects)
        } else {
            println("\nTransactions already exist ($transactionCount found). Skipping transaction seeding.")
        }

        // Seed session replay data (only if not exists)
        if (replayCount == 0L) {
            println("\nSeeding session replay data...")
            seedReplayData(projects)
        } else {
            println("\nReplays already exist ($replayCount found). Skipping replay seeding.")
        }

        // Seed user feedback data (only if not exists)
        if (feedbackCount == 0L) {
            println("\nSeeding user feedback data...")
            seedFeedbackData(projects)
        } else {
            println("\nUser feedback already exists ($feedbackCount found). Skipping feedback seeding.")
        }

        // Seed uptime monitors (only if not exists)
        if (!monitorsExist) {
            println("\nSeeding uptime monitors...")
            seedUptimeMonitors(orgId)
        } else {
            println("\nUptime monitors already exist. Skipping monitor seeding.")
        }

        // Seed monitoring systems (always check for duplicates inside the function)
        println("\nSeeding monitoring systems...")
        seedMonitoringHosts(orgId)

        // Seed system metrics for monitoring systems
        println("\nSeeding system metrics...")
        seedSystemMetrics(orgId)

        // Seed container metrics for monitoring systems
        println("\nSeeding container metrics...")
        seedContainerMetrics(orgId)

        // Seed status pages (only if not exists)
        if (!statusPagesExist) {
            println("\nSeeding status pages...")
            seedStatusPages(orgId)
        } else {
            println("\nStatus pages already exist. Skipping status page seeding.")
        }

        // Seed Datadog agent data (hosts, traces, profiles, infra)
        println("\nSeeding Datadog agent data...")
        seedDatadogAgentData(orgId)

        val backendUrl = EnvConfig.get("BACKEND_URL", "http://localhost:8080")
        val backendHost = backendUrl.removePrefix("http://").removePrefix("https://")

        println("\n=== DEMO SETUP COMPLETE ===")
        println("Demo User:")
        println("  Email: demo@moneat.dev")
        println("  Password: demo123")
        println("\nOrganization: Acme Mobile Inc")
        println("\nProjects:")
        projects.forEach { (name, pair) ->
            val (projectId, publicKey) = pair
            println("  - $name (ID: $projectId)")
            println("    DSN: http://$publicKey@$backendHost/$projectId")
        }
        println("\nNext steps:")
        println("1. Login at http://localhost:3000 with demo@moneat.dev / demo123")
        println("2. Browse the realistic error data in the dashboard")
        println("3. Take screenshots for documentation!")
        println("=========================\n")
    }

    private suspend fun seedClickHouseData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()

        // Define realistic issues for each platform
        val androidIssues =
            listOf(
                IssueTemplate(
                    "NullPointerException in ProductDetailFragment",
                    "java.lang.NullPointerException",
                    "Attempt to invoke virtual method 'java.lang.String com.acme.Product.getName()' on a null object reference",
                    "android",
                    listOf(
                        "at com.acme.shopping.ui.ProductDetailFragment.updateUI(ProductDetailFragment.kt:87)",
                        "at com.acme.shopping.ui.ProductDetailFragment.onViewCreated(ProductDetailFragment.kt:45)",
                        "at androidx.fragment.app.Fragment.performViewCreated(Fragment.java:2987)",
                        "at androidx.fragment.app.FragmentStateManager.createView(FragmentStateManager.java:551)"
                    ),
                    eventCount = 347,
                    userCount = 89,
                    level = "error"
                ),
                IssueTemplate(
                    "OutOfMemoryError loading product images",
                    "java.lang.OutOfMemoryError",
                    "Failed to allocate a 8294400 byte allocation with 4194304 free bytes and 3MB until OOM",
                    "android",
                    listOf(
                        "at android.graphics.BitmapFactory.nativeDecodeStream(Native Method)",
                        "at android.graphics.BitmapFactory.decodeStreamInternal(BitmapFactory.java:746)",
                        "at com.acme.shopping.util.ImageLoader.loadBitmap(ImageLoader.kt:34)",
                        "at com.acme.shopping.ui.ProductListAdapter.onBindViewHolder(ProductListAdapter.kt:56)"
                    ),
                    eventCount = 156,
                    userCount = 42,
                    level = "fatal"
                ),
                IssueTemplate(
                    "NetworkOnMainThreadException in checkout",
                    "android.os.NetworkOnMainThreadException",
                    "Network operation on main thread",
                    "android",
                    listOf(
                        "at android.os.StrictMode\$AndroidBlockGuardPolicy.onNetwork(StrictMode.java:1605)",
                        "at java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:117)",
                        "at com.acme.shopping.api.CheckoutService.processPayment(CheckoutService.kt:89)",
                        "at com.acme.shopping.ui.CheckoutActivity.onPayButtonClick(CheckoutActivity.kt:145)"
                    ),
                    eventCount = 89,
                    userCount = 34,
                    level = "error"
                ),
                IssueTemplate(
                    "IllegalStateException: Fragment not attached",
                    "java.lang.IllegalStateException",
                    "Fragment CartFragment not attached to a context",
                    "android",
                    listOf(
                        "at androidx.fragment.app.Fragment.requireContext(Fragment.java:954)",
                        "at com.acme.shopping.ui.CartFragment.updateCartTotal(CartFragment.kt:123)",
                        "at com.acme.shopping.ui.CartFragment\$observeCart\$1.invoke(CartFragment.kt:78)"
                    ),
                    eventCount = 234,
                    userCount = 67,
                    level = "error"
                ),
                IssueTemplate(
                    "SQLiteException: database is locked",
                    "android.database.sqlite.SQLiteException",
                    "database is locked (code 5 SQLITE_BUSY)",
                    "android",
                    listOf(
                        "at android.database.sqlite.SQLiteConnection.nativeExecute(Native Method)",
                        "at com.acme.shopping.data.local.CartDao.updateQuantity(CartDao.kt:67)",
                        "at com.acme.shopping.repository.CartRepository.updateItem(CartRepository.kt:89)"
                    ),
                    eventCount = 178,
                    userCount = 54,
                    level = "error"
                ),
                IssueTemplate(
                    "ConcurrentModificationException in WishlistAdapter",
                    "java.util.ConcurrentModificationException",
                    "Collection was modified during iteration",
                    "android",
                    listOf(
                        "at java.util.ArrayList\$Itr.checkForComodification(ArrayList.java:911)",
                        "at com.acme.shopping.ui.WishlistAdapter.notifyDataChanged(WishlistAdapter.kt:123)",
                        "at com.acme.shopping.ui.WishlistFragment.onItemRemoved(WishlistFragment.kt:156)"
                    ),
                    eventCount = 92,
                    userCount = 38,
                    level = "error"
                ),
                IssueTemplate(
                    "JSONException: No value for 'price'",
                    "org.json.JSONException",
                    "No value for price",
                    "android",
                    listOf(
                        "at org.json.JSONObject.get(JSONObject.java:389)",
                        "at com.acme.shopping.api.ProductParser.parseProduct(ProductParser.kt:45)",
                        "at com.acme.shopping.api.ApiClient.fetchProductDetails(ApiClient.kt:234)"
                    ),
                    eventCount = 267,
                    userCount = 71,
                    level = "error"
                ),
                IssueTemplate(
                    "IndexOutOfBoundsException in SearchResultsAdapter",
                    "java.lang.IndexOutOfBoundsException",
                    "Index: 15, Size: 12",
                    "android",
                    listOf(
                        "at java.util.ArrayList.get(ArrayList.java:437)",
                        "at com.acme.shopping.ui.SearchResultsAdapter.onBindViewHolder(SearchResultsAdapter.kt:78)",
                        "at androidx.recyclerview.widget.RecyclerView\$Adapter.onBindViewHolder(RecyclerView.java:7065)"
                    ),
                    eventCount = 143,
                    userCount = 49,
                    level = "error"
                ),
                IssueTemplate(
                    "ActivityNotFoundException: No Activity found to handle Intent",
                    "android.content.ActivityNotFoundException",
                    "No Activity found to handle Intent { act=android.intent.action.VIEW dat=acme://product/123 }",
                    "android",
                    listOf(
                        "at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2073)",
                        "at com.acme.shopping.util.DeepLinkHandler.openProduct(DeepLinkHandler.kt:56)",
                        "at com.acme.shopping.MainActivity.handleIntent(MainActivity.kt:234)"
                    ),
                    eventCount = 67,
                    userCount = 28,
                    level = "error"
                ),
                IssueTemplate(
                    "InflateException: Error inflating class ImageView",
                    "android.view.InflateException",
                    "Binary XML file line #23: Error inflating class ImageView",
                    "android",
                    listOf(
                        "at android.view.LayoutInflater.createViewFromTag(LayoutInflater.java:829)",
                        "at com.acme.shopping.ui.ProductListAdapter.onCreateViewHolder(ProductListAdapter.kt:45)",
                        "at androidx.recyclerview.widget.RecyclerView\$Adapter.createViewHolder(RecyclerView.java:7078)"
                    ),
                    eventCount = 51,
                    userCount = 19,
                    level = "error"
                ),
                IssueTemplate(
                    "Resources\$NotFoundException: Resource ID #0x7f080abc",
                    "android.content.res.Resources\$NotFoundException",
                    "Resource ID #0x7f080abc",
                    "android",
                    listOf(
                        "at android.content.res.Resources.getValue(Resources.java:1351)",
                        "at com.acme.shopping.ui.theme.ThemeManager.applyTheme(ThemeManager.kt:89)",
                        "at com.acme.shopping.MainActivity.onCreate(MainActivity.kt:67)"
                    ),
                    eventCount = 34,
                    userCount = 15,
                    level = "error"
                ),
                IssueTemplate(
                    "TimeoutException: Coroutine timeout",
                    "kotlinx.coroutines.TimeoutCancellationException",
                    "Timed out waiting for 5000 ms",
                    "android",
                    listOf(
                        "at kotlinx.coroutines.withTimeout(Timeout.kt:45)",
                        "at com.acme.shopping.api.ApiClient.fetchWithTimeout(ApiClient.kt:123)",
                        "at com.acme.shopping.repository.ProductRepository.loadProducts(ProductRepository.kt:89)"
                    ),
                    eventCount = 198,
                    userCount = 62,
                    level = "warning"
                ),
                IssueTemplate(
                    "NumberFormatException: For input string '€12.99'",
                    "java.lang.NumberFormatException",
                    "For input string: '€12.99'",
                    "android",
                    listOf(
                        "at java.lang.Long.parseLong(Long.java:596)",
                        "at com.acme.shopping.util.PriceFormatter.parsePrice(PriceFormatter.kt:34)",
                        "at com.acme.shopping.ui.CartFragment.calculateTotal(CartFragment.kt:145)"
                    ),
                    eventCount = 112,
                    userCount = 41,
                    level = "error"
                ),
                IssueTemplate(
                    "SecurityException: Permission denied",
                    "java.lang.SecurityException",
                    "Permission denial: writing to settings requires android.permission.WRITE_SETTINGS",
                    "android",
                    listOf(
                        "at android.os.Parcel.createException(Parcel.java:2071)",
                        "at com.acme.shopping.util.SettingsManager.savePreference(SettingsManager.kt:78)",
                        "at com.acme.shopping.ui.SettingsActivity.onToggleChanged(SettingsActivity.kt:123)"
                    ),
                    eventCount = 23,
                    userCount = 12,
                    level = "error"
                ),
                IssueTemplate(
                    "FileNotFoundException: /storage/emulated/0/cache/image.jpg",
                    "java.io.FileNotFoundException",
                    "/storage/emulated/0/cache/image.jpg: open failed: ENOENT (No such file or directory)",
                    "android",
                    listOf(
                        "at java.io.FileInputStream.open0(Native Method)",
                        "at com.acme.shopping.util.ImageCache.loadFromDisk(ImageCache.kt:89)",
                        "at com.acme.shopping.ui.ProductDetailFragment.displayCachedImage(ProductDetailFragment.kt:156)"
                    ),
                    eventCount = 167,
                    userCount = 58,
                    level = "warning"
                ),
                IssueTemplate(
                    "ClassCastException: Cannot cast String to Integer",
                    "java.lang.ClassCastException",
                    "java.lang.String cannot be cast to java.lang.Integer",
                    "android",
                    listOf(
                        "at com.acme.shopping.util.DataConverter.convertToInt(DataConverter.kt:34)",
                        "at com.acme.shopping.ui.OrderHistoryFragment.parseOrderId(OrderHistoryFragment.kt:89)",
                        "at com.acme.shopping.ui.OrderHistoryFragment.onBindViewHolder(OrderHistoryFragment.kt:123)"
                    ),
                    eventCount = 78,
                    userCount = 31,
                    level = "error"
                ),
                IssueTemplate(
                    "BadTokenException: Unable to add window",
                    "android.view.WindowManager\$BadTokenException",
                    "Unable to add window -- token android.os.BinderProxy@abc123 is not valid",
                    "android",
                    listOf(
                        "at android.view.ViewRootImpl.setView(ViewRootImpl.java:958)",
                        "at com.acme.shopping.ui.dialogs.PromoDialog.show(PromoDialog.kt:45)",
                        "at com.acme.shopping.ui.HomeFragment.showPromotion(HomeFragment.kt:167)"
                    ),
                    eventCount = 43,
                    userCount = 22,
                    level = "error"
                ),
                IssueTemplate(
                    "SSLHandshakeException: Trust anchor not found",
                    "javax.net.ssl.SSLHandshakeException",
                    "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found",
                    "android",
                    listOf(
                        "at com.android.org.conscrypt.SSLUtils.toSSLHandshakeException(SSLUtils.java:384)",
                        "at com.acme.shopping.api.SecureApiClient.makeRequest(SecureApiClient.kt:123)",
                        "at com.acme.shopping.repository.UserRepository.fetchProfile(UserRepository.kt:67)"
                    ),
                    eventCount = 29,
                    userCount = 17,
                    level = "error"
                ),
                IssueTemplate(
                    "ViewPager2 adapter not set",
                    "java.lang.IllegalStateException",
                    "ViewPager2 adapter is not set",
                    "android",
                    listOf(
                        "at androidx.viewpager2.widget.ViewPager2.checkAdapter(ViewPager2.java:567)",
                        "at com.acme.shopping.ui.ProductGalleryFragment.setupViewPager(ProductGalleryFragment.kt:78)",
                        "at com.acme.shopping.ui.ProductGalleryFragment.onViewCreated(ProductGalleryFragment.kt:45)"
                    ),
                    eventCount = 56,
                    userCount = 24,
                    level = "error"
                ),
                IssueTemplate(
                    "CursorIndexOutOfBoundsException in order history",
                    "android.database.CursorIndexOutOfBoundsException",
                    "Index 0 requested, with a size of 0",
                    "android",
                    listOf(
                        "at android.database.AbstractCursor.checkPosition(AbstractCursor.java:468)",
                        "at com.acme.shopping.data.local.OrderDao.getLastOrder(OrderDao.kt:89)",
                        "at com.acme.shopping.ui.OrderHistoryFragment.loadOrders(OrderHistoryFragment.kt:123)"
                    ),
                    eventCount = 95,
                    userCount = 38,
                    level = "error"
                ),
                IssueTemplate(
                    "ArithmeticException: Division by zero in discount calculation",
                    "java.lang.ArithmeticException",
                    "divide by zero",
                    "android",
                    listOf(
                        "at com.acme.shopping.util.DiscountCalculator.calculatePercentage(DiscountCalculator.kt:45)",
                        "at com.acme.shopping.ui.CheckoutFragment.applyDiscount(CheckoutFragment.kt:189)",
                        "at com.acme.shopping.ui.CheckoutFragment.updateTotal(CheckoutFragment.kt:234)"
                    ),
                    eventCount = 31,
                    userCount = 18,
                    level = "error"
                ),
                IssueTemplate(
                    "UnknownHostException: Unable to resolve host",
                    "java.net.UnknownHostException",
                    "Unable to resolve host 'api.acme-shopping.com': No address associated with hostname",
                    "android",
                    listOf(
                        "at java.net.Inet6AddressImpl.lookupHostByName(Inet6AddressImpl.java:117)",
                        "at com.acme.shopping.api.ApiClient.connect(ApiClient.kt:67)",
                        "at com.acme.shopping.repository.ProductRepository.syncProducts(ProductRepository.kt:145)"
                    ),
                    eventCount = 124,
                    userCount = 51,
                    level = "warning"
                ),
                IssueTemplate(
                    "MalformedURLException: Invalid product URL",
                    "java.net.MalformedURLException",
                    "no protocol: /products/invalid",
                    "android",
                    listOf(
                        "at java.net.URL.<init>(URL.java:600)",
                        "at com.acme.shopping.util.ImageLoader.loadFromUrl(ImageLoader.kt:56)",
                        "at com.acme.shopping.ui.ProductAdapter.bindImage(ProductAdapter.kt:89)"
                    ),
                    eventCount = 67,
                    userCount = 29,
                    level = "error"
                ),
                IssueTemplate(
                    "NoSuchElementException in wishlist",
                    "java.util.NoSuchElementException",
                    "Collection is empty",
                    "android",
                    listOf(
                        "at kotlin.collections.CollectionsKt___CollectionsKt.first(_Collections.kt:208)",
                        "at com.acme.shopping.ui.WishlistFragment.getFirstItem(WishlistFragment.kt:123)",
                        "at com.acme.shopping.ui.WishlistFragment.onResume(WishlistFragment.kt:78)"
                    ),
                    eventCount = 84,
                    userCount = 36,
                    level = "error"
                ),
                IssueTemplate(
                    "ParseException: Unparseable date format",
                    "java.text.ParseException",
                    "Unparseable date: '2024-13-45T25:99:99Z'",
                    "android",
                    listOf(
                        "at java.text.DateFormat.parse(DateFormat.java:396)",
                        "at com.acme.shopping.util.DateParser.parseIso8601(DateParser.kt:45)",
                        "at com.acme.shopping.ui.OrderDetailFragment.formatDeliveryDate(OrderDetailFragment.kt:123)"
                    ),
                    eventCount = 52,
                    userCount = 23,
                    level = "error"
                ),
                IssueTemplate(
                    "SocketTimeoutException: Read timed out",
                    "java.net.SocketTimeoutException",
                    "timeout",
                    "android",
                    listOf(
                        "at java.net.SocketInputStream.socketRead0(Native Method)",
                        "at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.kt:89)",
                        "at com.acme.shopping.api.ApiClient.syncInventory(ApiClient.kt:234)"
                    ),
                    eventCount = 143,
                    userCount = 47,
                    level = "warning"
                ),
                IssueTemplate(
                    "RecyclerView no adapter attached",
                    "java.lang.IllegalStateException",
                    "RecyclerView has no adapter attached; skipping layout",
                    "android",
                    listOf(
                        "at androidx.recyclerview.widget.RecyclerView.onLayout(RecyclerView.java:4321)",
                        "at com.acme.shopping.ui.CategoryFragment.onViewCreated(CategoryFragment.kt:67)",
                        "at androidx.fragment.app.Fragment.performViewCreated(Fragment.java:2987)"
                    ),
                    eventCount = 38,
                    userCount = 19,
                    level = "warning"
                ),
                IssueTemplate(
                    "SQLiteDiskIOException: Disk I/O error",
                    "android.database.sqlite.SQLiteDiskIOException",
                    "disk I/O error (code 1034 SQLITE_IOERR_READ)",
                    "android",
                    listOf(
                        "at android.database.sqlite.SQLiteConnection.nativeExecuteForString(Native Method)",
                        "at com.acme.shopping.data.local.ProductDao.getAllProducts(ProductDao.kt:78)",
                        "at com.acme.shopping.repository.ProductRepository.loadFromCache(ProductRepository.kt:145)"
                    ),
                    eventCount = 14,
                    userCount = 8,
                    level = "fatal"
                ),
                IssueTemplate(
                    "URISyntaxException: Illegal character in path",
                    "java.net.URISyntaxException",
                    "Illegal character in path at index 12: /product/[id]",
                    "android",
                    listOf(
                        "at java.net.URI.create(URI.java:894)",
                        "at com.acme.shopping.util.UrlBuilder.buildProductUrl(UrlBuilder.kt:45)",
                        "at com.acme.shopping.ui.ProductListFragment.navigateToProduct(ProductListFragment.kt:189)"
                    ),
                    eventCount = 47,
                    userCount = 21,
                    level = "error"
                )
            )

        val iosIssues =
            listOf(
                IssueTemplate(
                    "Fatal error: Index out of range",
                    "Swift.fatalError",
                    "Fatal error: Index out of range",
                    "cocoa",
                    listOf(
                        "ProductListViewController.swift:45 - updateProduct(_:)",
                        "ProductListViewController.swift:89 - tableView(_:didSelectRowAt:)",
                        "UIKit - UITableView._selectRowAtIndexPath(_:animated:scrollPosition:notifyDelegate:)"
                    ),
                    eventCount = 56,
                    userCount = 14,
                    level = "fatal"
                ),
                IssueTemplate(
                    "NSInvalidArgumentException: Unrecognized selector",
                    "NSInvalidArgumentException",
                    "unrecognized selector sent to instance 0x600000abc123",
                    "cocoa",
                    listOf(
                        "CoreFoundation - __exceptionPreprocess",
                        "libobjc.A.dylib - objc_exception_throw",
                        "CheckoutViewController.swift:67 - processPayment()",
                        "UIKit - UIApplication.sendAction(_:to:from:for:)"
                    ),
                    eventCount = 22,
                    userCount = 9,
                    level = "error"
                ),
                IssueTemplate(
                    "Network request timeout",
                    "NSURLError",
                    "The request timed out",
                    "cocoa",
                    listOf(
                        "NetworkService.swift:123 - fetchProducts(completion:)",
                        "ProductRepository.swift:45 - loadProducts()",
                        "CFNetwork - URLSession:task:didCompleteWithError:"
                    ),
                    eventCount = 91,
                    userCount = 31,
                    level = "warning"
                ),
                IssueTemplate(
                    "Fatal error: Unexpectedly found nil while unwrapping",
                    "Swift.fatalError",
                    "Fatal error: Unexpectedly found nil while unwrapping an Optional value",
                    "cocoa",
                    listOf(
                        "CartViewController.swift:78 - calculateTotal()",
                        "CartViewController.swift:123 - updateUI()",
                        "UIKit - UIViewController.viewDidLoad()"
                    ),
                    eventCount = 134,
                    userCount = 42,
                    level = "fatal"
                ),
                IssueTemplate(
                    "NSRangeException: Array index out of bounds",
                    "NSRangeException",
                    "Index 5 beyond bounds [0 .. 3]",
                    "cocoa",
                    listOf(
                        "Foundation - __NSArrayM.objectAtIndexedSubscript(_:)",
                        "OrderHistoryViewController.swift:56 - displayOrder(at:)",
                        "OrderHistoryViewController.swift:89 - tableView(_:cellForRowAt:)"
                    ),
                    eventCount = 87,
                    userCount = 33,
                    level = "error"
                ),
                IssueTemplate(
                    "NSInternalInconsistencyException: Could not dequeue cell",
                    "NSInternalInconsistencyException",
                    "unable to dequeue a cell with identifier ProductCell",
                    "cocoa",
                    listOf(
                        "UIKit - UITableView.dequeueReusableCell(withIdentifier:for:)",
                        "ProductListViewController.swift:67 - tableView(_:cellForRowAt:)",
                        "UIKit - UITableView.reloadData()"
                    ),
                    eventCount = 29,
                    userCount = 14,
                    level = "error"
                ),
                IssueTemplate(
                    "NSURLError: No internet connection",
                    "NSURLError",
                    "The Internet connection appears to be offline",
                    "cocoa",
                    listOf(
                        "CFNetwork - URLSession:task:didCompleteWithError:",
                        "NetworkService.swift:178 - uploadImage(_:completion:)",
                        "ProfileViewController.swift:234 - updateProfilePicture()"
                    ),
                    eventCount = 163,
                    userCount = 58,
                    level = "warning"
                ),
                IssueTemplate(
                    "CoreData save failure",
                    "NSError",
                    "The operation couldn't be completed. (Cocoa error 134060.)",
                    "cocoa",
                    listOf(
                        "CoreData - NSManagedObjectContext.save()",
                        "DataManager.swift:89 - saveProduct(_:)",
                        "ProductRepository.swift:123 - cacheProducts(_:)"
                    ),
                    eventCount = 45,
                    userCount = 19,
                    level = "error"
                ),
                IssueTemplate(
                    "NSDecimalNumberException: Division by zero",
                    "NSDecimalNumberException",
                    "Attempt to divide by zero",
                    "cocoa",
                    listOf(
                        "Foundation - NSDecimalNumber.dividing(by:)",
                        "PriceCalculator.swift:45 - calculateDiscount()",
                        "CheckoutViewController.swift:178 - applyPromoCode(_:)"
                    ),
                    eventCount = 23,
                    userCount = 11,
                    level = "error"
                ),
                IssueTemplate(
                    "JSONDecoder typeMismatch error",
                    "DecodingError.typeMismatch",
                    "Expected to decode String but found a number instead",
                    "cocoa",
                    listOf(
                        "Foundation - JSONDecoder.decode(_:from:)",
                        "ProductParser.swift:56 - parseProduct(from:)",
                        "NetworkService.swift:234 - fetchProductDetails(id:completion:)"
                    ),
                    eventCount = 112,
                    userCount = 38,
                    level = "error"
                ),
                IssueTemplate(
                    "UIImage initialization failed",
                    "Swift.fatalError",
                    "Fatal error: Unable to load image asset",
                    "cocoa",
                    listOf(
                        "UIKit - UIImage.init(named:)",
                        "ImageAssets.swift:23 - loadPlaceholder()",
                        "ProductCell.swift:67 - configure(with:)"
                    ),
                    eventCount = 78,
                    userCount = 31,
                    level = "error"
                ),
                IssueTemplate(
                    "Keychain access error",
                    "NSError",
                    "The operation couldn't be completed. (OSStatus error -25300.)",
                    "cocoa",
                    listOf(
                        "Security - SecItemCopyMatching(_:_:)",
                        "KeychainManager.swift:45 - getToken()",
                        "AuthService.swift:89 - refreshSession()"
                    ),
                    eventCount = 56,
                    userCount = 24,
                    level = "error"
                ),
                IssueTemplate(
                    "AVFoundation playback error",
                    "NSError",
                    "The operation couldn't be completed. (AVFoundationErrorDomain error -11800.)",
                    "cocoa",
                    listOf(
                        "AVFoundation - AVPlayer.play()",
                        "VideoPlayerViewController.swift:123 - playProductVideo()",
                        "ProductDetailViewController.swift:234 - showVideo(at:)"
                    ),
                    eventCount = 34,
                    userCount = 16,
                    level = "error"
                ),
                IssueTemplate(
                    "File Manager error: File not found",
                    "NSError",
                    "The file doesn't exist",
                    "cocoa",
                    listOf(
                        "Foundation - FileManager.contentsOfDirectory(at:)",
                        "CacheManager.swift:67 - loadCachedImages()",
                        "ProductListViewController.swift:145 - loadFromCache()"
                    ),
                    eventCount = 91,
                    userCount = 36,
                    level = "warning"
                )
            )

        val rnIssues =
            listOf(
                IssueTemplate(
                    "TypeError: Cannot read property 'name' of undefined",
                    "TypeError",
                    "Cannot read property 'name' of undefined",
                    "javascript",
                    listOf(
                        "at ProductDetail.render (ProductDetail.js:45:12)",
                        "at finishClassComponent (react-reconciler.js:234:11)",
                        "at updateClassComponent (react-reconciler.js:189:23)"
                    ),
                    eventCount = 203,
                    userCount = 47,
                    level = "error"
                ),
                IssueTemplate(
                    "Invariant Violation: Element type is invalid",
                    "InvariantViolation",
                    "Element type is invalid: expected a string or a class/function but got: undefined",
                    "javascript",
                    listOf(
                        "at invariant (invariant.js:42:15)",
                        "at ReactElement (ReactElement.js:289:5)",
                        "at CartScreen.js:23:10"
                    ),
                    eventCount = 15,
                    userCount = 8,
                    level = "error"
                ),
                IssueTemplate(
                    "ReferenceError: product is not defined",
                    "ReferenceError",
                    "product is not defined",
                    "javascript",
                    listOf(
                        "at ProductList.js:67:23",
                        "at renderProduct (ProductList.js:45:5)",
                        "at Array.map (native)"
                    ),
                    eventCount = 124,
                    userCount = 39,
                    level = "error"
                ),
                IssueTemplate(
                    "TypeError: Cannot read property 'map' of null",
                    "TypeError",
                    "Cannot read property 'map' of null",
                    "javascript",
                    listOf(
                        "at OrderHistory.render (OrderHistory.js:56:18)",
                        "at finishClassComponent (react-reconciler.js:234:11)",
                        "at updateClassComponent (react-reconciler.js:189:23)"
                    ),
                    eventCount = 167,
                    userCount = 52,
                    level = "error"
                ),
                IssueTemplate(
                    "RangeError: Maximum call stack size exceeded",
                    "RangeError",
                    "Maximum call stack size exceeded",
                    "javascript",
                    listOf(
                        "at calculateDiscount (PriceUtils.js:23:5)",
                        "at calculateDiscount (PriceUtils.js:28:12)",
                        "at CheckoutScreen.js:145:19"
                    ),
                    eventCount = 34,
                    userCount = 15,
                    level = "error"
                ),
                IssueTemplate(
                    "SyntaxError: Unexpected token '<'",
                    "SyntaxError",
                    "Unexpected token '<' at position 0 in JSON",
                    "javascript",
                    listOf(
                        "at JSON.parse (native)",
                        "at parseResponse (ApiClient.js:78:19)",
                        "at fetchProducts (ProductService.js:45:23)"
                    ),
                    eventCount = 89,
                    userCount = 31,
                    level = "error"
                ),
                IssueTemplate(
                    "TypeError: Network request failed",
                    "TypeError",
                    "Network request failed",
                    "javascript",
                    listOf(
                        "at fetch (native)",
                        "at ApiClient.js:123:12",
                        "at syncInventory (InventoryService.js:67:8)"
                    ),
                    eventCount = 156,
                    userCount = 47,
                    level = "warning"
                ),
                IssueTemplate(
                    "TypeError: undefined is not a function",
                    "TypeError",
                    "undefined is not a function (near '...product.getPrice...')",
                    "javascript",
                    listOf(
                        "at ProductCard.js:89:23",
                        "at renderPrice (ProductCard.js:67:5)",
                        "at ProductList.js:145:12"
                    ),
                    eventCount = 112,
                    userCount = 38,
                    level = "error"
                ),
                IssueTemplate(
                    "Error: Request timeout of 5000ms exceeded",
                    "Error",
                    "Request timeout of 5000ms exceeded",
                    "javascript",
                    listOf(
                        "at createError (createError.js:16:15)",
                        "at settle (settle.js:17:12)",
                        "at fetchUserProfile (UserService.js:234:8)"
                    ),
                    eventCount = 98,
                    userCount = 34,
                    level = "warning"
                ),
                IssueTemplate(
                    "TypeError: Cannot destructure property 'id' of 'undefined'",
                    "TypeError",
                    "Cannot destructure property 'id' of 'undefined' as it is undefined",
                    "javascript",
                    listOf(
                        "at WishlistScreen.js:45:9",
                        "at removeFromWishlist (WishlistScreen.js:89:5)",
                        "at TouchableOpacity.onPress (WishlistScreen.js:123:12)"
                    ),
                    eventCount = 76,
                    userCount = 28,
                    level = "error"
                ),
                IssueTemplate(
                    "Error: Invalid navigation state",
                    "Error",
                    "The navigation state is invalid",
                    "javascript",
                    listOf(
                        "at navigation.navigate (react-navigation.js:456:12)",
                        "at navigateToProduct (ProductList.js:178:5)",
                        "at ProductCard.onPress (ProductCard.js:89:7)"
                    ),
                    eventCount = 67,
                    userCount = 25,
                    level = "error"
                ),
                IssueTemplate(
                    "Error: Image loading failed",
                    "Error",
                    "Failed to load image from URL",
                    "javascript",
                    listOf(
                        "at Image.onError (Image.js:234:9)",
                        "at ProductImage.js:56:12",
                        "at ProductDetail.js:178:5"
                    ),
                    eventCount = 143,
                    userCount = 49,
                    level = "warning"
                ),
                IssueTemplate(
                    "TypeError: Cannot convert undefined to object",
                    "TypeError",
                    "Cannot convert undefined or null to object",
                    "javascript",
                    listOf(
                        "at Object.keys (native)",
                        "at validateFormData (FormUtils.js:23:18)",
                        "at CheckoutScreen.handleSubmit (CheckoutScreen.js:234:5)"
                    ),
                    eventCount = 54,
                    userCount = 22,
                    level = "error"
                ),
                IssueTemplate(
                    "Error: Animated value already attached",
                    "Error",
                    "Animated value already attached to node",
                    "javascript",
                    listOf(
                        "at Animated.Value.attach (AnimatedValue.js:67:12)",
                        "at AnimatedProductCard.js:45:9",
                        "at ProductList.js:123:5"
                    ),
                    eventCount = 41,
                    userCount = 18,
                    level = "warning"
                ),
                IssueTemplate(
                    "URIError: Failed to decode URI component",
                    "URIError",
                    "URI malformed",
                    "javascript",
                    listOf(
                        "at decodeURIComponent (native)",
                        "at parseQueryString (UrlUtils.js:34:12)",
                        "at DeepLinkHandler.js:67:8"
                    ),
                    eventCount = 29,
                    userCount = 14,
                    level = "error"
                )
            )

        // Seed Android issues
        val (androidProjectId, _) = projects["android"]!!
        androidIssues.forEach { template ->
            seedIssue(db, androidProjectId, template)
        }

        // Seed iOS issues
        val (iosProjectId, _) = projects["ios"]!!
        iosIssues.forEach { template ->
            seedIssue(db, iosProjectId, template)
        }

        // Seed React Native issues
        val (rnProjectId, _) = projects["react-native"]!!
        rnIssues.forEach { template ->
            seedIssue(db, rnProjectId, template)
        }

        println("✅ Seeded ${androidIssues.size + iosIssues.size + rnIssues.size} issues with realistic events")
    }

    private suspend fun seedIssue(
        db: String,
        projectId: Long,
        template: IssueTemplate
    ) {
        val issueId = UUID.randomUUID().toString()
        val culprit = template.stackTrace.firstOrNull() ?: "unknown"
        val firstSeen = randomTime(random.nextInt(7, 30))
        val lastSeen = randomTime(random.nextInt(0, 3))

        // Insert issue
        val issueQuery =
            """
            INSERT INTO `$db`.issues (
                issue_id, project_id, fingerprint, title, culprit, level,
                first_seen, last_seen, event_count, user_count, status
            ) VALUES (
                '$issueId',
                $projectId,
                '${UUID.randomUUID().toString().replace("-", "")}',
                '${template.title.replace("'", "''")}',
                '${culprit.replace("'", "''")}',
                '${template.level}',
                '${firstSeen.epochSecond}',
                '${lastSeen.epochSecond}',
                ${template.eventCount},
                ${template.userCount},
                'unresolved'
            )
            """.trimIndent()

        ClickHouseClient.execute(issueQuery)

        // Insert events for this issue
        val eventCount = minOf(template.eventCount, 50) // Insert subset of events
        repeat(eventCount) { i ->
            val eventId = UUID.randomUUID().toString()
            val timestamp =
                if (i < 5) {
                    randomTime(random.nextInt(0, 2)) // Recent events
                } else {
                    randomTime(random.nextInt(2, 30)) // Older events
                }

            val device =
                when (template.platform) {
                    "android" -> androidDevices.random(random)
                    "cocoa" -> iosDevices.random(random)
                    else -> "Web Browser"
                }

            val osVersion =
                when (template.platform) {
                    "android" -> "Android ${androidVersions.random(random)}"
                    "cocoa" -> "iOS ${iosVersions.random(random)}"
                    else -> "N/A"
                }

            val userEmail = userEmails.random(random)
            val stackTraceJson =
                template.stackTrace.joinToString(",") {
                    "\"${it.replace("'", "''")}\""
                }

            // Generate realistic breadcrumbs
            val breadcrumbs = generateBreadcrumbs(template.platform, template.title)

            // Generate realistic contexts
            val contexts = generateContexts(template.platform, device, osVersion)

            // Generate realistic tags
            val releaseVersion = "1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}"
            val userId = UUID.randomUUID().toString()
            val username = userEmail.substringBefore("@").replace(".", "_")
            val userIp = "${random.nextInt(
                1,
                255
            )}.${random.nextInt(0, 255)}.${random.nextInt(0, 255)}.${random.nextInt(1, 255)}"
            val (sdkName, sdkVersion) =
                when (template.platform) {
                    "android" -> Pair("sentry.java.android", "7.${random.nextInt(0, 5)}.${random.nextInt(0, 10)}")
                    "cocoa" -> Pair("sentry.cocoa", "8.${random.nextInt(15, 30)}.${random.nextInt(0, 5)}")
                    else -> Pair(
                        "sentry.javascript.react-native",
                        "5.${random.nextInt(15, 30)}.${random.nextInt(0, 5)}"
                    )
                }
            val tags =
                buildString {
                    append("{'environment':'production'")
                    append(",'release':'$releaseVersion'")
                    append(",'platform':'${template.platform}'")
                    append(",'level':'${template.level}'")
                    append(
                        ",'os.name':'${if (template.platform == "android") "Android" else if (template.platform == "cocoa") "iOS" else "JavaScript"}'"
                    )
                    append(",'os.version':'$osVersion'")
                    append(",'device':'$device'")
                    append(",'user':'$username'")
                    append(",'sdk.name':'$sdkName'")
                    append(",'sdk.version':'$sdkVersion'")
                    if (random.nextBoolean()) append(",'handled':'no'")
                    if (random.nextBoolean()) {
                        append(
                            ",'mechanism':'${listOf(
                                "AppExceptionHandler",
                                "UncaughtExceptionHandler",
                                "NSException",
                                "unhandledrejection"
                            ).random(random)}'"
                        )
                    }
                    append("}")
                }

            // Generate request context
            val requestUrl =
                when (template.platform) {
                    "android", "cocoa" -> "https://api.acmeshopping.com/v1/${listOf(
                        "products",
                        "cart",
                        "user/profile",
                        "orders",
                        "checkout"
                    ).random(random)}"

                    else -> "https://acmeshopping.com/${listOf("products","cart","checkout","profile").random(random)}"
                }
            val requestBody = """{"url":"$requestUrl","method":"${listOf(
                "GET",
                "POST",
                "PUT"
            ).random(
                random
            )}","headers":{"User-Agent":"$sdkName/$sdkVersion","Content-Type":"application/json"},"env":{"REMOTE_ADDR":"$userIp"}}"""

            val eventQuery =
                """
                INSERT INTO `$db`.events (
                    event_id, project_id, issue_id, timestamp, received_at, event_type,
                    platform, level, message, exception_type, exception_value,
                    stack_trace, environment, release, user_id, user_email, user_username, user_ip_address,
                    device_model, os_name, os_version, breadcrumbs, contexts, tags, sdk_name, sdk_version, request
                ) VALUES (
                    '$eventId',
                    $projectId,
                    '$issueId',
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    'error',
                    '${template.platform}',
                    '${template.level}',
                    '${template.title.replace("'", "''")}',
                    '${template.exceptionType.replace("'", "''")}',
                    '${template.exceptionValue.replace("'", "''")}',
                    '[$stackTraceJson]',
                    'production',
                    '$releaseVersion',
                    '$userId',
                    '$userEmail',
                    '$username',
                    '$userIp',
                    '$device',
                    '${if (template.platform == "android") "Android" else if (template.platform == "cocoa") "iOS" else "JavaScript"}',
                    '$osVersion',
                    '${breadcrumbs.replace("'", "\\'")}',
                    '${contexts.replace("'", "\\'")}',
                    $tags,
                    '$sdkName',
                    '$sdkVersion',
                    '${requestBody.replace("'", "\\'")}'
                )
                """.trimIndent()

            ClickHouseClient.execute(eventQuery)
        }
    }

    private suspend fun seedLogData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        val (androidProjectId, _) = projects["android"] ?: return

        // Services generating logs
        val services =
            listOf("api-server", "auth-service", "payment-processor", "notification-service", "cache-service")
        val environments = listOf("production", "staging")
        val hosts = listOf("api-prod-1", "api-prod-2", "api-prod-3", "worker-prod-1", "worker-prod-2")
        val levels =
            listOf(
                "info" to 60,
                "warn" to 25,
                "error" to 10,
                "debug" to 5
            )

        // Log message templates
        val logTemplates =
            listOf(
                Triple(
                    "info",
                    "HTTP {method} {path} completed in {ms}ms with status {status}",
                    mapOf(
                        "method" to listOf("GET", "POST", "PUT"),
                        "path" to listOf("/api/products", "/api/orders", "/api/users"),
                        "ms" to listOf("45", "123", "234", "56", "789"),
                        "status" to listOf("200", "201", "204")
                    )
                ),
                Triple(
                    "info",
                    "User {user} authenticated successfully",
                    mapOf("user" to userEmails)
                ),
                Triple(
                    "warn",
                    "Cache miss for key: {key}",
                    mapOf("key" to listOf("product:123", "user:456", "session:789abc", "cart:def123"))
                ),
                Triple(
                    "warn",
                    "Rate limit approaching for IP {ip}: {count}/{limit} requests",
                    mapOf(
                        "ip" to listOf(TestIpConstants.IP_100, TestIpConstants.IP_45, TestIpConstants.IP_OTHER_2),
                        "count" to listOf("950", "980", "990"),
                        "limit" to listOf("1000")
                    )
                ),
                Triple(
                    "error",
                    "Database connection timeout after {timeout}s for query: {query}",
                    mapOf(
                        "timeout" to listOf("30", "45", "60"),
                        "query" to listOf("SELECT * FROM orders", "UPDATE users SET", "INSERT INTO products")
                    )
                ),
                Triple(
                    "error",
                    "Payment processing failed for order {orderId}: {reason}",
                    mapOf(
                        "orderId" to listOf("ORD-12345", "ORD-67890", "ORD-45678"),
                        "reason" to listOf("card_declined", "insufficient_funds", "expired_card")
                    )
                ),
                Triple(
                    "error",
                    "Failed to send notification to user {userId}: {error}",
                    mapOf(
                        "userId" to
                            userEmails.map {
                                it.substringBefore("@")
                            },
                        "error" to listOf("device_not_registered", "network_timeout", "invalid_token")
                    )
                ),
                Triple(
                    "debug",
                    "Redis command executed: {command} in {ms}ms",
                    mapOf(
                        "command" to listOf("GET product:123", "SET session:abc", "HGETALL user:456"),
                        "ms" to listOf("2", "5", "12", "8")
                    )
                )
            )

        // Generate realistic logs with timestamps spread over last 2 hours
        // This ensures logs are visible even if screenshots are taken later
        val now = Instant.now()
        val logCount = 300 // Generate 300 logs

        repeat(logCount) { i ->
            // Weight towards more recent logs, but spread over 2 hours
            val minutesAgo =
                when {
                    i < 80 -> random.nextInt(0, 10)

                    // Last 10 minutes: 80 logs
                    i < 160 -> random.nextInt(10, 30)

                    // 10-30 minutes ago: 80 logs
                    i < 240 -> random.nextInt(30, 60)

                    // 30-60 minutes ago: 80 logs
                    else -> random.nextInt(60, 120) // 1-2 hours ago: 60 logs
                }
            val secondsOffset = random.nextInt(0, 60)
            val timestamp = now.minus((minutesAgo * 60 + secondsOffset).toLong(), ChronoUnit.SECONDS)

            val (templateLevel, messageTemplate, placeholders) = logTemplates.random(random)

            // Determine actual level based on weighted distribution
            val level =
                levels.let { options ->
                    val total = options.sumOf { it.second }
                    val rand = random.nextInt(total)
                    var acc = 0
                    options
                        .first {
                            acc += it.second
                            rand < acc
                        }.first
                }

            // Use template's level if it's error/warn, otherwise use the random level
            val finalLevel = if (templateLevel in listOf("error", "warn")) templateLevel else level

            // Fill in placeholders
            var message = messageTemplate
            placeholders.forEach { (key, values) ->
                val value = values.random(random)
                message = message.replace("{$key}", value)
            }

            val logId = UUID.randomUUID().toString()
            val service = services.random(random)
            val environment = environments.random(random)
            val host = hosts.random(random)
            val traceId = UUID.randomUUID().toString().replace("-", "")
            val spanId = traceId.substring(0, 16)

            // Add some facets/tags for filtering
            val tags = mutableMapOf<String, String>()
            tags["service"] = service
            tags["environment"] = environment
            tags["version"] = "1.${random.nextInt(0, 5)}.${random.nextInt(0, 10)}"

            if (random.nextFloat() < 0.3) {
                tags["user_id"] = userEmails.random(random)
            }
            if (random.nextFloat() < 0.4) {
                tags["request_id"] = UUID.randomUUID().toString()
            }

            // Format tags as ClickHouse map
            val tagsJson =
                if (tags.isEmpty()) {
                    "map()"
                } else {
                    val pairs =
                        tags.entries.joinToString(",") { (k, v) ->
                            "'$k','${v.replace("'", "\\'")}'"
                        }
                    "map($pairs)"
                }

            val logQuery =
                """
                INSERT INTO `$db`.logs (
                    log_id, organization_id, timestamp, received_at, level, message, body,
                    service, environment, host, source, trace_id, span_id, tags,
                    container_name, container_id, container_image, resource_attributes
                ) VALUES (
                    '$logId',
                    toUInt64(-1),
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    now64(3),
                    '$finalLevel',
                    '${message.replace("'", "\\'")}',
                    '${message.replace("'", "\\'")}',
                    '$service',
                    '$environment',
                    '$host',
                    'sdk',
                    '$traceId',
                    '$spanId',
                    $tagsJson,
                    '',
                    '',
                    '',
                    map()
                )
                """.trimIndent()

            try {
                val response = ClickHouseClient.execute(logQuery)
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    println("❌ Error inserting log (attempt $i): ${response.status} - $errorBody")
                    if (i == 0) {
                        // Print the first failing query for debugging
                        println("Failing query: $logQuery")
                    }
                }
            } catch (e: Exception) {
                println("❌ Exception inserting log: ${e.message}")
                if (i == 0) {
                    e.printStackTrace()
                }
            }
        }

        println("✅ Seeded $logCount realistic log entries (spread over last 15 minutes)")
    }

    private suspend fun seedTransactionData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()

        // Transaction templates with realistic operations and timings
        data class TransactionTemplate(
            val name: String,
            val op: String,
            val avgDuration: Double,
            val variance: Double,
            val failureRate: Double,
            val platform: String
        )

        val transactionTemplates =
            listOf(
                // API endpoints
                TransactionTemplate("GET /api/products", "http.server", 145.0, 50.0, 0.5, "android"),
                TransactionTemplate("POST /api/checkout", "http.server", 320.0, 120.0, 2.3, "android"),
                TransactionTemplate("GET /api/user/profile", "http.server", 89.0, 30.0, 0.2, "android"),
                TransactionTemplate("PUT /api/cart/items", "http.server", 210.0, 80.0, 1.1, "android"),
                TransactionTemplate("GET /api/search", "http.server", 780.0, 300.0, 3.5, "android"),

                // Database queries
                TransactionTemplate("SELECT products WHERE category", "db.sql.query", 45.0, 15.0, 0.1, "android"),
                TransactionTemplate("INSERT INTO orders", "db.sql.query", 120.0, 40.0, 0.8, "android"),
                TransactionTemplate("UPDATE cart_items", "db.sql.query", 67.0, 20.0, 0.3, "android"),

                // UI screens/navigation
                TransactionTemplate("ProductListActivity", "navigation", 234.0, 90.0, 1.2, "android"),
                TransactionTemplate("ProductDetailActivity", "navigation", 189.0, 70.0, 0.7, "android"),
                TransactionTemplate("CheckoutActivity", "navigation", 456.0, 150.0, 2.1, "android"),
                TransactionTemplate("CartActivity", "navigation", 123.0, 50.0, 0.4, "android"),

                // Background tasks
                TransactionTemplate("sync_user_data", "task", 567.0, 200.0, 1.8, "android"),
                TransactionTemplate("upload_analytics", "task", 890.0, 350.0, 4.2, "android"),
                TransactionTemplate("refresh_product_cache", "task", 1240.0, 500.0, 2.9, "android"),
            )

        val (androidProjectId, _) = projects["android"] ?: return
        var totalTransactions = 0

        // Seed transactions for the past 7 days
        transactionTemplates.forEach { template ->
            // Number of transactions varies by template (more popular endpoints have more data)
            val transactionCount =
                when {
                    template.op == "http.server" && "GET" in template.name -> random.nextInt(200, 400)
                    template.op == "http.server" -> random.nextInt(100, 250)
                    template.op == "navigation" -> random.nextInt(150, 300)
                    template.op == "db.sql.query" -> random.nextInt(300, 600)
                    else -> random.nextInt(50, 150)
                }

            repeat(transactionCount) { i ->
                val eventId = UUID.randomUUID().toString()
                // Distribute over last 7 days, with more recent data
                val daysAgo =
                    when {
                        i < transactionCount * 0.3 -> random.nextInt(0, 1)

                        // 30% in last day
                        i < transactionCount * 0.6 -> random.nextInt(1, 3)

                        // 30% in last 2 days
                        else -> random.nextInt(3, 7) // rest distributed over week
                    }
                val timestamp = randomTime(daysAgo)

                // Calculate duration with variance
                val baseDuration = template.avgDuration + (random.nextDouble(-template.variance, template.variance))
                val duration = maxOf(10.0, baseDuration) // minimum 10ms

                // Determine if this is a failure
                val isFailed = random.nextDouble() * 100 < template.failureRate
                val level = if (isFailed) "error" else "info"

                val userEmail = userEmails.random(random)
                val device = androidDevices.random(random)
                val osVersion = "Android ${androidVersions.random(random)}"
                val environment = if (random.nextDouble() < 0.85) "production" else "staging"

                val eventQuery =
                    """
                    INSERT INTO `$db`.events (
                        event_id, project_id, timestamp, received_at, event_type,
                        level, platform, environment, release, 
                        transaction_name, transaction_op, duration_ms,
                        user_id, user_email, device_model, os_name, os_version,
                        browser_name, browser_version, message
                    ) VALUES (
                        '$eventId',
                        $androidProjectId,
                        toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                        toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                        'transaction',
                        '$level',
                        '${template.platform}',
                        '$environment',
                        '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                        '${template.name.replace("'", "''")}',
                        '${template.op}',
                        $duration,
                        '${UUID.randomUUID()}',
                        '$userEmail',
                        '$device',
                        'Android',
                        '$osVersion',
                        '',
                        '',
                        ''
                    )
                    """.trimIndent()

                try {
                    ClickHouseClient.execute(eventQuery)
                    totalTransactions++
                } catch (e: Exception) {
                    if (i < 2) println("❌ Error inserting transaction: ${e.message}")
                }
            }
        }

        println("✅ Seeded $totalTransactions transaction events across ${transactionTemplates.size} transaction types")
    }

    private suspend fun seedReplayData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        val (androidProjectId, _) = projects["android"] ?: return

        val browsers = listOf("Chrome", "Firefox", "Safari", "Edge")
        val browserVersions =
            mapOf(
                "Chrome" to listOf("120.0", "119.0", "118.0"),
                "Firefox" to listOf("121.0", "120.0"),
                "Safari" to listOf("17.2", "17.1", "16.6"),
                "Edge" to listOf("120.0", "119.0")
            )

        val osOptions =
            listOf(
                "Windows" to listOf("10", "11"),
                "macOS" to listOf("14.2", "13.6", "12.7"),
                "Linux" to listOf("Ubuntu 22.04", "Fedora 39")
            )

        val urls =
            listOf(
                "https://acme-shopping.com/",
                "https://acme-shopping.com/products",
                "https://acme-shopping.com/products/electronics",
                "https://acme-shopping.com/products/123/details",
                "https://acme-shopping.com/cart",
                "https://acme-shopping.com/checkout",
                "https://acme-shopping.com/account",
                "https://acme-shopping.com/orders"
            )

        val replayCount = 60
        var totalSegments = 0

        repeat(replayCount) { i ->
            val replayId = UUID.randomUUID().toString()

            // Session timing
            val daysAgo =
                when {
                    i < replayCount * 0.4 -> random.nextInt(0, 1)

                    // 40% in last day
                    i < replayCount * 0.7 -> random.nextInt(1, 3)

                    // 30% in days 1-3
                    else -> random.nextInt(3, 7) // rest over week
                }
            val startTime = randomTime(daysAgo)

            // Session duration (10 seconds to 20 minutes)
            val durationSeconds =
                when {
                    random.nextDouble() < 0.15 -> random.nextInt(10, 30)

                    // 15% very short (bounced)
                    random.nextDouble() < 0.50 -> random.nextInt(30, 180)

                    // 35% short (30s-3min)
                    random.nextDouble() < 0.80 -> random.nextInt(180, 600)

                    // 30% medium (3-10min)
                    else -> random.nextInt(600, 1200) // 20% long sessions (10-20min)
                }

            // User info
            val userEmail = userEmails.random(random)
            val userId = UUID.randomUUID().toString()
            val username = userEmail.substringBefore("@")

            // Device/browser info
            val browser = browsers.random(random)
            val browserVersion = browserVersions[browser]!!.random(random)
            val (osName, osVersionList) = osOptions.random(random)
            val osVersion = osVersionList.random(random)

            // Activity level (0-100, higher = more interactions)
            val activity =
                when {
                    durationSeconds < 30 -> random.nextInt(0, 20)

                    // Low activity for short sessions
                    durationSeconds < 180 -> random.nextInt(20, 60)

                    // Medium for medium sessions
                    else -> random.nextInt(60, 100) // High for long sessions
                }

            // Determine if session had errors
            val hasErrors = random.nextDouble() < 0.25 // 25% of replays have errors
            val errorCount = if (hasErrors) random.nextInt(1, 4) else 0
            val errorIds =
                if (hasErrors) {
                    (1..errorCount).map { UUID.randomUUID().toString() }
                } else {
                    emptyList()
                }

            // URLs visited during session (1-6 pages)
            val pageCount =
                when {
                    durationSeconds < 60 -> random.nextInt(1, 3)
                    durationSeconds < 300 -> random.nextInt(2, 5)
                    else -> random.nextInt(3, 7)
                }.coerceAtMost(urls.size)

            val visitedUrls = urls.shuffled(random).take(pageCount)

            val environment = if (random.nextDouble() < 0.90) "production" else "staging"

            // Generate 1-3 segments for this replay (simulating chunks of replay data)
            val segmentCount =
                when {
                    durationSeconds < 120 -> 1
                    durationSeconds < 600 -> random.nextInt(1, 3)
                    else -> random.nextInt(2, 4)
                }

            repeat(segmentCount) { segmentIndex ->
                val segmentId = segmentIndex.toUInt()
                val segmentDuration = durationSeconds / segmentCount
                val segmentTime = startTime.plus((segmentDuration * segmentIndex).toLong(), ChronoUnit.SECONDS)

                // Format arrays for ClickHouse
                val urlsArray = visitedUrls.joinToString(",") { "'${it.replace("'", "''")}'" }
                val errorIdsArray = errorIds.joinToString(",") { "'$it'" }

                val replayQuery =
                    """
                    INSERT INTO `$db`.replay_events (
                        replay_id, project_id, segment_id, timestamp, replay_start_timestamp,
                        urls, error_ids, trace_ids, environment, release, platform,
                        user_id, user_email, user_username, user_ip_address,
                        sdk_name, sdk_version, browser_name, browser_version,
                        os_name, os_version, device_name, device_family, activity, tags
                    ) VALUES (
                        '$replayId',
                        $androidProjectId,
                        $segmentId,
                        toDateTime64(${segmentTime.epochSecond}, 3, 'UTC'),
                        toDateTime64(${startTime.epochSecond}, 3, 'UTC'),
                        [$urlsArray],
                        [$errorIdsArray],
                        [],
                        '$environment',
                        '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                        'javascript',
                        '$userId',
                        '$userEmail',
                        '$username',
                        '${random.nextInt(
                        1,
                        255
                    )}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}',
                        'sentry.javascript.react',
                        '7.99.0',
                        '$browser',
                        '$browserVersion',
                        '$osName',
                        '$osVersion',
                        '',
                        '',
                        $activity,
                        ''
                    )
                    """.trimIndent()

                try {
                    ClickHouseClient.execute(replayQuery)
                    totalSegments++
                } catch (e: Exception) {
                    if (segmentIndex == 0) println("❌ Error inserting replay: ${e.message}")
                }
            }
        }

        println("✅ Seeded $replayCount session replays ($totalSegments segments total)")
    }

    private suspend fun seedFeedbackData(projects: Map<String, Pair<Long, String>>) {
        val db = ClickHouseClient.getDatabase()
        val (androidProjectId, _) = projects["android"] ?: return

        // Feedback message templates with realistic user feedback
        val feedbackTemplates =
            listOf(
                Triple("App crashes when trying to checkout with saved card", "sarah.johnson@example.com", "Sarah J."),
                Triple("Great app but the product images take too long to load", "mike.chen@example.com", "Mike Chen"),
                Triple("Cart doesn't update after adding items, have to refresh", "alex.rivera@example.com", "Alex R."),
                Triple("Love the new UI! Much cleaner than before", "priya.patel@example.com", "Priya Patel"),
                Triple(
                    "Can't apply discount code at checkout - keeps saying invalid",
                    "john.smith@example.com",
                    "John Smith"
                ),
                Triple("App froze on payment screen - lost my order", "emma.williams@example.com", "Emma W."),
                Triple("Search results are not relevant to what I'm looking for", "mike.chen@example.com", "Mike Chen"),
                Triple("Would be great to have a wishlist feature!", "sarah.johnson@example.com", "Sarah Johnson"),
                Triple(
                    "The app is very slow when scrolling through products",
                    "alex.rivera@example.com",
                    "Alex Rivera"
                ),
                Triple("Got an error message when viewing product details", "priya.patel@example.com", "Priya P."),
                Triple("Unable to login with Google - keeps timing out", "john.smith@example.com", "John S."),
                Triple("Product recommendations are really helpful!", "emma.williams@example.com", "Emma Williams"),
                Triple("App crashed while browsing electronics category", "sarah.johnson@example.com", "Sarah J."),
                Triple("Missing product images on several items", "mike.chen@example.com", "Mike C."),
                Triple("Filter options don't work properly", "alex.rivera@example.com", "Alex"),
            )

        val urls =
            listOf(
                "https://acme-shopping.com/products",
                "https://acme-shopping.com/cart",
                "https://acme-shopping.com/checkout",
                "https://acme-shopping.com/products/123/details",
                "https://acme-shopping.com/account",
                "https://acme-shopping.com/search",
                "https://acme-shopping.com/products/electronics"
            )

        // Get some event IDs to associate feedback with
        val eventIdsQuery =
            """
            SELECT toString(event_id) as event_id
            FROM `$db`.events
            WHERE project_id = $androidProjectId
                AND event_type = 'error'
            LIMIT 8
            FORMAT TabSeparated
            """.trimIndent()

        val eventIds =
            try {
                val response = ClickHouseClient.executeWithFormat(eventIdsQuery, "TabSeparated")
                response.lines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }

        // Get some replay IDs to associate feedback with
        val replayIdsQuery =
            """
            SELECT DISTINCT toString(replay_id) as replay_id
            FROM `$db`.replay_events
            WHERE project_id = $androidProjectId
            LIMIT 5
            FORMAT TabSeparated
            """.trimIndent()

        val replayIds =
            try {
                val response = ClickHouseClient.executeWithFormat(replayIdsQuery, "TabSeparated")
                response.lines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }

        var feedbackCount = 0

        feedbackTemplates.forEachIndexed { index, (message, email, name) ->
            val feedbackId = UUID.randomUUID().toString()

            // Distribute over last 14 days
            val daysAgo =
                when {
                    index < 5 -> random.nextInt(0, 2)

                    // 5 recent
                    index < 10 -> random.nextInt(2, 7)

                    // 5 medium
                    else -> random.nextInt(7, 14) // rest older
                }
            val timestamp = randomTime(daysAgo)

            // Some feedback has associated events or replays
            val associatedEventId =
                if (eventIds.isNotEmpty() && random.nextDouble() < 0.4) {
                    eventIds.random(random)
                } else {
                    ""
                }

            val associatedReplayId =
                if (replayIds.isNotEmpty() && random.nextDouble() < 0.3) {
                    replayIds.random(random)
                } else {
                    ""
                }

            val url = urls.random(random)
            val environment = if (random.nextDouble() < 0.9) "production" else "staging"

            // Status distribution: 60% unresolved, 30% resolved, 10% archived
            val status =
                when {
                    random.nextDouble() < 0.6 -> "unresolved"
                    random.nextDouble() < 0.9 -> "resolved"
                    else -> "archived"
                }

            val userId = UUID.randomUUID().toString()

            val feedbackQuery =
                """
                INSERT INTO `$db`.user_feedback (
                    feedback_id, project_id, timestamp, received_at,
                    message, contact_email, name, url,
                    associated_event_id, replay_id, environment, release,
                    platform, user_id, user_email, user_username, user_ip_address,
                    sdk_name, sdk_version, tags, status, updated_at
                ) VALUES (
                    '$feedbackId',
                    $androidProjectId,
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC'),
                    '${message.replace("'", "\\'")}',
                    '$email',
                    '${name.replace("'", "\\'")}',
                    '$url',
                    '$associatedEventId',
                    '$associatedReplayId',
                    '$environment',
                    '1.${random.nextInt(0, 4)}.${random.nextInt(0, 2)}',
                    'android',
                    '$userId',
                    '$email',
                    '${name.replace("'", "\\'")}',
                    '${random.nextInt(
                    1,
                    255
                )}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}.${random.nextInt(1, 255)}',
                    'sentry.java.android',
                    '6.34.0',
                    map(),
                    '$status',
                    toDateTime64(${timestamp.epochSecond}, 3, 'UTC')
                )
                """.trimIndent()

            try {
                ClickHouseClient.execute(feedbackQuery)
                feedbackCount++
            } catch (e: Exception) {
                if (index == 0) println("❌ Error inserting feedback: ${e.message}")
            }
        }

        println("✅ Seeded $feedbackCount user feedback items")
    }

    private suspend fun seedUptimeMonitors(organizationId: Int) {
        val db = ClickHouseClient.getDatabase()

        // Create realistic uptime monitors
        val monitors =
            listOf(
                Triple("Production API", "https://api.acme.com/health", 60),
                Triple("Website Homepage", "https://www.acme.com", 120),
                Triple("Payment Gateway", "https://payments.acme.com/status", 300),
                Triple("Mobile API", "https://mobile-api.acme.com/ping", 60),
                Triple("CDN Edge Server", "https://cdn.acme.com/healthz", 180)
            )

        val monitorIds =
            transaction {
                monitors.map { (name, url, intervalSeconds) ->
                    val monitorId = UUID.randomUUID()
                    val pushToken = UUID.randomUUID().toString().replace("-", "")
                    val createdAt = Instant.now().minus(random.nextInt(7, 30).toLong(), ChronoUnit.DAYS)
                    val lastCheckAt = Instant.now().minus(random.nextInt(0, 300).toLong(), ChronoUnit.SECONDS)

                    UptimeMonitors.insert {
                        it[UptimeMonitors.id] = monitorId
                        it[UptimeMonitors.organizationId] = organizationId
                        it[UptimeMonitors.name] = name
                        it[UptimeMonitors.type] = "http"
                        it[UptimeMonitors.active] = true
                        it[UptimeMonitors.url] = url
                        it[UptimeMonitors.intervalSeconds] = intervalSeconds
                        it[UptimeMonitors.timeoutSeconds] = 30
                        it[UptimeMonitors.retries] = 2
                        it[UptimeMonitors.method] = "GET"
                        it[UptimeMonitors.status] = if (random.nextFloat() < 0.9) "up" else "down"
                        it[UptimeMonitors.lastCheckAt] = kotlin.time.Instant.fromEpochMilliseconds(lastCheckAt.toEpochMilli())
                        it[UptimeMonitors.consecutiveFailures] = if (random.nextFloat() < 0.9) 0 else random.nextInt(1, 5)
                        it[UptimeMonitors.pushToken] = pushToken
                        it[UptimeMonitors.createdAt] = kotlin.time.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                        it[UptimeMonitors.updatedAt] = kotlin.time.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
                    }

                    Triple(monitorId, intervalSeconds, monitors.indexOf(Triple(name, url, intervalSeconds)))
                }
            }

        // Add heartbeat history to ClickHouse (outside transaction)
        monitorIds.forEach { (monitorId, intervalSeconds, _) ->
            val heartbeatCount = random.nextInt(20, 50)
            repeat(heartbeatCount) { i ->
                val heartbeatTime = Instant.now().minus((i * intervalSeconds).toLong(), ChronoUnit.SECONDS)
                val isUp = random.nextFloat() < 0.95 // 95% success rate

                val heartbeatQuery =
                    """
                    INSERT INTO `$db`.uptime_heartbeats (
                        monitor_id, timestamp, status, response_time_ms, status_code, message, ping_ms
                    ) VALUES (
                        '$monitorId',
                        toDateTime64(${heartbeatTime.epochSecond}, 3, 'UTC'),
                        ${if (isUp) 1 else 0},
                        ${if (isUp) random.nextInt(30, 300) else -1},
                        ${if (isUp) 200 else random.nextInt(500, 504)},
                        '${if (isUp) "OK" else "Connection timeout"}',
                        ${if (isUp) random.nextInt(10, 100).toFloat() else -1f}
                    )
                    """.trimIndent()

                ClickHouseClient.execute(heartbeatQuery)
            }
        }

        println("✅ Seeded ${monitors.size} uptime monitors with heartbeat history")
    }

    private suspend fun seedMonitoringHosts(organizationId: Int) {
        // Check if hosts already exist
        val existingHosts =
            transaction {
                Hosts
                    .selectAll()
                    .where { Hosts.organization_id eq organizationId }
                    .map { it[Hosts.id] to it[Hosts.hostname] }
            }

        if (existingHosts.isNotEmpty()) {
            println("Monitoring hosts already exist (${existingHosts.size} found).")

            // Update last_seen_at to now for all hosts so they appear online
            transaction {
                existingHosts.forEach { (hostId, _) ->
                    Hosts.update({ Hosts.id eq hostId }) {
                        it[Hosts.last_seen_at] = Clock.System.now()
                        it[Hosts.status] = "up"
                    }
                }
            }
            println("✅ Updated ${existingHosts.size} hosts to appear online with current timestamps")
            return
        }

        val hostsList =
            listOf(
                Triple("api-prod-1.acme.com", "Ubuntu 22.04 LTS", "x86_64"),
                Triple("api-prod-2.acme.com", "Ubuntu 22.04 LTS", "x86_64"),
                Triple("worker-prod-1.acme.com", "Debian 11", "x86_64"),
                Triple("db-primary.acme.com", "Ubuntu 20.04 LTS", "x86_64"),
                Triple("cache-redis-1.acme.com", "Alpine Linux 3.18", "x86_64")
            )

        transaction {
            val now = Clock.System.now()
            hostsList.forEach { (hostname, osInfo, arch) ->
                val agentKeyHash = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt())

                Hosts.insert {
                    it[Hosts.organization_id] = organizationId
                    it[Hosts.hostname] = hostname
                    it[Hosts.display_name] = hostname
                    it[Hosts.agent_key_hash] = agentKeyHash
                    it[Hosts.status] = "up"
                    it[Hosts.last_seen_at] = now
                    it[Hosts.agent_version] = "1.2.${random.nextInt(0, 10)}"
                    it[Hosts.os] = osInfo
                    it[Hosts.arch] = arch
                    it[Hosts.first_seen_at] = now
                }
            }
        }

        println("✅ Seeded ${hostsList.size} monitoring hosts")
    }

    private suspend fun seedContainerMetrics(organizationId: Int) {
        val db = ClickHouseClient.getDatabase()

        // Get seeded hosts
        val hosts =
            transaction {
                Hosts
                    .selectAll()
                    .where { Hosts.organization_id eq organizationId }
                    .map { it[Hosts.id] to it[Hosts.hostname] }
            }

        if (hosts.isEmpty()) {
            println("⚠️  No monitoring hosts found. Skipping container metrics seeding.")
            return
        }

        // Delete existing container metrics for this organization to ensure fresh data
        try {
            val deleteQuery = "ALTER TABLE `$db`.containers DELETE WHERE organization_id = $organizationId"
            ClickHouseClient.execute(deleteQuery)
            println("🗑️  Deleted old container metrics for fresh data")
        } catch (e: Exception) {
            println("⚠️  Could not delete old container metrics: ${e.message}")
        }

        // Container configurations for different system types
        val containerConfigs =
            mapOf(
                "api-prod-1.acme.com" to
                    listOf(
                        Triple("acme-api", "acme/api-server:1.8.3", "running"),
                        Triple("acme-worker", "acme/background-worker:1.8.3", "running"),
                        Triple("nginx-proxy", "nginx:1.25-alpine", "running"),
                        Triple("redis-cache", "redis:7.2-alpine", "running")
                    ),
                "api-prod-2.acme.com" to
                    listOf(
                        Triple("acme-api", "acme/api-server:1.8.3", "running"),
                        Triple("acme-worker", "acme/background-worker:1.8.3", "running"),
                        Triple("nginx-proxy", "nginx:1.25-alpine", "running")
                    ),
                "worker-prod-1.acme.com" to
                    listOf(
                        Triple("acme-worker-queue", "acme/background-worker:1.8.3", "running"),
                        Triple("acme-worker-scheduler", "acme/scheduler:1.2.0", "running"),
                        Triple("acme-mailer", "acme/mailer-service:2.1.5", "running")
                    ),
                "db-primary.acme.com" to
                    listOf(
                        Triple("postgres-main", "postgres:15.4-alpine", "running"),
                        Triple("pg-backup", "postgres:15.4-alpine", "exited")
                    ),
                "cache-redis-1.acme.com" to
                    listOf(
                        Triple("redis-master", "redis:7.2-alpine", "running"),
                        Triple("redis-exporter", "oliver006/redis_exporter:latest", "running")
                    )
            )

        var totalContainers = 0
        var totalMetrics = 0

        hosts.forEach { (hostId, hostName) ->
            val containers = containerConfigs[hostName] ?: emptyList()

            containers.forEach { (containerName, image, status) ->
                val containerId = UUID.randomUUID().toString().take(12) // Docker-style short ID
                val isRunning = status == "running"

                // Generate metrics for last 24 hours with 5-minute intervals (288 points)
                // This ensures screenshots always have recent data
                val now = Instant.now()
                val metricsCount = 288

                // Base resource usage patterns per container type
                val (baseCpu, baseMemMB, memLimitMB) =
                    when {
                        containerName.contains("api") -> Triple(25.0f, 512, 1024)
                        containerName.contains("worker") -> Triple(45.0f, 768, 2048)
                        containerName.contains("postgres") -> Triple(15.0f, 1024, 4096)
                        containerName.contains("redis") -> Triple(8.0f, 256, 512)
                        containerName.contains("nginx") -> Triple(5.0f, 128, 256)
                        else -> Triple(20.0f, 512, 1024)
                    }

                repeat(metricsCount) { i ->
                    val timestamp = now.minus((metricsCount - i).toLong() * 5, ChronoUnit.MINUTES)

                    // Add realistic variation to metrics
                    val cpuVariation = random.nextDouble(-10.0, 15.0).toFloat()
                    val memVariation = random.nextInt(-100, 200)

                    val cpuPercent =
                        if (isRunning) {
                            (baseCpu + cpuVariation).coerceIn(0.0f, 100.0f)
                        } else {
                            0.0f
                        }

                    val memUsedMB =
                        if (isRunning) {
                            (baseMemMB + memVariation).coerceAtLeast(50)
                        } else {
                            0
                        }

                    val memUsed = memUsedMB * 1024L * 1024L
                    val memLimit = memLimitMB * 1024L * 1024L

                    // Network metrics (bytes)
                    val netRecv = if (isRunning) random.nextLong(1024L * 1024L, 100L * 1024L * 1024L) else 0L
                    val netSent = if (isRunning) random.nextLong(512L * 1024L, 50L * 1024L * 1024L) else 0L

                    val ts = "fromUnixTimestamp64Milli(${timestamp.toEpochMilli()})"
                    val tagsMap = "map('host_id','$hostId')"
                    val containerQuery =
                        """
                        INSERT INTO `$db`.containers (
                            organization_id, host, container_id, name, image, state,
                            cpu_percent, mem_usage, mem_limit, net_rx_bytes, net_tx_bytes, tags, timestamp
                        ) VALUES (
                            $organizationId,
                            '$hostName',
                            '$containerId',
                            '$containerName',
                            '$image',
                            '$status',
                            $cpuPercent,
                            $memUsed,
                            $memLimit,
                            $netRecv,
                            $netSent,
                            $tagsMap,
                            $ts
                        )
                        """.trimIndent()

                    try {
                        ClickHouseClient.execute(containerQuery)
                        totalMetrics++
                    } catch (e: Exception) {
                        if (i == 0) println("❌ Error inserting container metric: ${e.message}")
                    }
                }

                totalContainers++
            }
        }

        println("✅ Seeded $totalContainers containers with $totalMetrics metric points")
    }

    private suspend fun seedSystemMetrics(organizationId: Int) {
        val db = ClickHouseClient.getDatabase()

        // Get seeded hosts
        val hosts =
            transaction {
                Hosts
                    .selectAll()
                    .where { Hosts.organization_id eq organizationId }
                    .map { it[Hosts.id] to it[Hosts.hostname] }
            }

        if (hosts.isEmpty()) {
            println("⚠️  No monitoring hosts found. Skipping metrics seeding.")
            return
        }

        // Delete existing system metrics for this organization to ensure fresh data
        try {
            val deleteQuery = "ALTER TABLE `$db`.metrics DELETE WHERE organization_id = $organizationId"
            ClickHouseClient.execute(deleteQuery)
            println("🗑️  Deleted old system metrics for fresh data")
        } catch (e: Exception) {
            println("⚠️  Could not delete old system metrics: ${e.message}")
        }

        var totalMetrics = 0

        hosts.forEach { (hostId, hostName) ->
            // Generate metrics for last 24 hours with 5-minute intervals (288 points)
            val now = Instant.now()
            val metricsCount = 288

            // Base resource usage patterns per system type
            val (baseCpu, memTotalGB, baseDiskGB) =
                when {
                    hostName.contains("api") -> Triple(35.0f, 8, 100)
                    hostName.contains("worker") -> Triple(55.0f, 16, 200)
                    hostName.contains("db") -> Triple(25.0f, 32, 500)
                    hostName.contains("cache") -> Triple(15.0f, 8, 50)
                    else -> Triple(30.0f, 8, 100)
                }

            val memTotal = memTotalGB * 1024L * 1024L * 1024L
            val diskTotal = baseDiskGB * 1024L * 1024L * 1024L

            repeat(metricsCount) { i ->
                val timestamp = now.minus((metricsCount - i).toLong() * 5, ChronoUnit.MINUTES)

                // Add realistic variation to metrics
                val cpuVariation = random.nextDouble(-15.0, 25.0).toFloat()
                val memUsedPercent = 60.0f + random.nextDouble(-20.0, 25.0).toFloat()
                val diskUsedPercent = 45.0f + random.nextDouble(-10.0, 15.0).toFloat()

                val cpuPercent = (baseCpu + cpuVariation).coerceIn(0.0f, 100.0f)
                val memUsed = (memTotal * memUsedPercent / 100).toLong()
                val memAvailable = memTotal - memUsed
                val diskUsed = (diskTotal * diskUsedPercent / 100).toLong()

                // Swap (typically low usage)
                val swapTotal = memTotal / 2
                val swapUsed = (swapTotal * random.nextDouble(0.0, 0.15)).toLong()

                // Disk I/O (bytes/sec, cumulative)
                val diskReadBytes = random.nextLong(1024L * 1024L, 100L * 1024L * 1024L)
                val diskWriteBytes = random.nextLong(512L * 1024L, 50L * 1024L * 1024L)

                // Network I/O (bytes/sec, cumulative)
                val netRecvBytes = random.nextLong(10L * 1024L * 1024L, 500L * 1024L * 1024L)
                val netSentBytes = random.nextLong(5L * 1024L * 1024L, 250L * 1024L * 1024L)

                // Load averages (typically < number of CPUs, let's assume 4 CPUs)
                val baseLoad = cpuPercent / 25.0f // Rough correlation
                val load1 = (baseLoad + random.nextDouble(-0.5, 0.5).toFloat()).coerceAtLeast(0.0f)
                val load5 = (baseLoad * 0.9f + random.nextDouble(-0.3, 0.3).toFloat()).coerceAtLeast(0.0f)
                val load15 = (baseLoad * 0.8f + random.nextDouble(-0.2, 0.2).toFloat()).coerceAtLeast(0.0f)

                // Temperature (Celsius, if applicable)
                val tempMax =
                    if (random.nextBoolean()) {
                        45.0f + random.nextDouble(-10.0, 25.0).toFloat()
                    } else {
                        0.0f
                    }

                // Optional: GPU metrics (only for some systems)
                val (gpuPercent, gpuMemPercent, gpuPower) =
                    if (hostName.contains("worker") && random.nextDouble() < 0.3) {
                        Triple(
                            random.nextDouble(20.0, 90.0).toFloat(),
                            random.nextDouble(30.0, 85.0).toFloat(),
                            random.nextDouble(100.0, 300.0).toFloat()
                        )
                    } else {
                        Triple(0.0f, 0.0f, 0.0f)
                    }

                // Battery (only for mobile/laptop monitoring, rare)
                val batteryPercent = 0.0f

                val ts = "fromUnixTimestamp64Milli(${timestamp.toEpochMilli()})"
                val tagsMap = "map('host_id','$hostId')"
                val metricRows =
                    listOf(
                        Triple("system.cpu.percent", cpuPercent.toDouble(), 1),
                        Triple("system.mem.total", memTotal.toDouble(), 1),
                        Triple("system.mem.used", memUsed.toDouble(), 1),
                        Triple("system.mem.available", memAvailable.toDouble(), 1),
                        Triple("system.swap.total", swapTotal.toDouble(), 1),
                        Triple("system.swap.used", swapUsed.toDouble(), 1),
                        Triple("system.disk.total", diskTotal.toDouble(), 1),
                        Triple("system.disk.used", diskUsed.toDouble(), 1),
                        Triple("system.disk.read_bytes", diskReadBytes.toDouble(), 1),
                        Triple("system.disk.write_bytes", diskWriteBytes.toDouble(), 1),
                        Triple("system.net.recv_bytes", netRecvBytes.toDouble(), 1),
                        Triple("system.net.sent_bytes", netSentBytes.toDouble(), 1),
                        Triple("system.load.1", load1.toDouble(), 1),
                        Triple("system.load.5", load5.toDouble(), 1),
                        Triple("system.load.15", load15.toDouble(), 1),
                        Triple("system.temp.max", tempMax.toDouble(), 1),
                        Triple("system.gpu.percent", gpuPercent.toDouble(), 1),
                        Triple("system.gpu.mem_percent", gpuMemPercent.toDouble(), 1),
                        Triple("system.gpu.power", gpuPower.toDouble(), 1),
                        Triple("system.battery.percent", batteryPercent.toDouble(), 1)
                    )
                val values =
                    metricRows.joinToString(",") { (name, value, mtype) ->
                        "($organizationId,'$name',$mtype,$ts,$value,'$hostName',$tagsMap,'','')"
                    }
                val systemMetricsQuery =
                    """
                    INSERT INTO `$db`.metrics (
                        organization_id, metric_name, metric_type, timestamp, value, host, tags, unit, source_type_name
                    ) VALUES $values
                    """.trimIndent()

                try {
                    ClickHouseClient.execute(systemMetricsQuery)
                    totalMetrics += metricRows.size
                } catch (e: Exception) {
                    if (i == 0) println("❌ Error inserting metric for $hostName: ${e.message}")
                }
            }
        }

        println("✅ Seeded ${hosts.size} hosts with $totalMetrics metric points")
    }

    private suspend fun seedStatusPages(organizationId: Int) {
        // Get existing monitors to associate with status page
        val monitorIds =
            transaction {
                UptimeMonitors
                    .selectAll()
                    .where { UptimeMonitors.organizationId eq organizationId }
                    .map { it[UptimeMonitors.id] to it[UptimeMonitors.name] }
            }

        if (monitorIds.isEmpty()) {
            println("⚠️  No monitors found. Skipping status page seeding.")
            return
        }

        // Create a status page
        val statusPageId =
            transaction {
                val pageId = UUID.randomUUID()
                val createdAt = Instant.now().minus(random.nextInt(14, 30).toLong(), ChronoUnit.DAYS)

                StatusPages.insert {
                    it[StatusPages.id] = pageId
                    it[StatusPages.organizationId] = organizationId
                    it[StatusPages.name] = "Acme Services Status"
                    it[StatusPages.slug] = "acme-status"
                    it[StatusPages.description] = "Real-time status and incident history for all Acme services"
                    it[StatusPages.logoUrl] = null
                    it[StatusPages.faviconUrl] = null
                    it[StatusPages.primaryColor] = "#3B82F6"
                    it[StatusPages.darkMode] = true
                    it[StatusPages.showUptimeHistory] = true
                    it[StatusPages.historyDays] = 90
                    it[StatusPages.isPublic] = true
                    it[StatusPages.createdAt] = kotlin.time.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                    it[StatusPages.updatedAt] = kotlin.time.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
                }
                pageId
            }

        // Associate all monitors with the status page
        transaction {
            monitorIds.forEachIndexed { index, (monitorId, _) ->
                StatusPageMonitors.insert {
                    it[StatusPageMonitors.statusPageId] = statusPageId
                    it[StatusPageMonitors.monitorId] = monitorId
                    it[StatusPageMonitors.displayName] = null // Use actual monitor name
                    it[StatusPageMonitors.sortOrder] = index
                    it[StatusPageMonitors.createdAt] = kotlin.time.Instant.fromEpochMilliseconds(Instant.now().toEpochMilli())
                }
            }
        }

        // Create some realistic incidents
        val incidents =
            listOf(
                // Resolved incident from 2 days ago
                listOf(
                    "Database Connection Pool Exhausted" to "resolved",
                    "Our primary database experienced connection pool exhaustion causing degraded performance." to "investigating",
                    "Database team has identified the issue as a connection leak in the payment service." to "identified",
                    "Fix deployed to production. Monitoring for stability." to "monitoring",
                    "All systems operating normally. Connection pool has stabilized." to "resolved"
                ) to Pair("major", 2),

                // Recently resolved incident
                listOf(
                    "API Gateway Timeout Issues" to "resolved",
                    "Investigating reports of increased timeout errors on API gateway." to "investigating",
                    "Root cause identified as upstream service degradation. Implementing retry logic." to "identified",
                    "Issue has been resolved. All API endpoints responding normally." to "resolved"
                ) to Pair("minor", 0),

                // Ongoing minor incident
                listOf(
                    "Elevated Error Rates on Mobile API" to "monitoring",
                    "We're seeing elevated error rates on the mobile API endpoint. Investigating the root cause." to "investigating",
                    "Issue identified as a cache invalidation problem. Applying fix now." to "identified",
                    "Fix applied. Monitoring error rates for the next hour to ensure stability." to "monitoring"
                ) to Pair("minor", 0),

                // Scheduled maintenance (future)
                listOf(
                    "Database Maintenance Window" to "scheduled",
                    "We will be performing routine database maintenance. Services may experience brief interruptions." to "scheduled"
                ) to Pair("none", -1)
            )

        transaction {
            incidents.forEach { (updates, metadata) ->
                val (impact, daysAgo) = metadata
                val incidentId = UUID.randomUUID()

                val firstUpdate = updates.first()
                val lastUpdate = updates.last()
                val finalStatus = lastUpdate.second
                val title = firstUpdate.first

                val createdAt =
                    if (daysAgo >= 0) {
                        Instant
                            .now()
                            .minus(daysAgo.toLong(), ChronoUnit.DAYS)
                            .minus(random.nextInt(1, 12).toLong(), ChronoUnit.HOURS)
                    } else {
                        Instant.now().plus(7, ChronoUnit.DAYS) // Future maintenance
                    }

                val resolvedAt =
                    if (finalStatus == "resolved" || finalStatus == "completed") {
                        createdAt.plus(random.nextInt(30, 180).toLong(), ChronoUnit.MINUTES)
                    } else {
                        null
                    }

                val isScheduledMaintenance = finalStatus == "scheduled"

                StatusPageIncidents.insert {
                    it[StatusPageIncidents.id] = incidentId
                    it[StatusPageIncidents.statusPageId] = statusPageId
                    it[StatusPageIncidents.title] = title
                    it[StatusPageIncidents.status] = finalStatus
                    it[StatusPageIncidents.type] = if (isScheduledMaintenance) "maintenance" else "incident"
                    it[StatusPageIncidents.impact] = impact

                    if (isScheduledMaintenance) {
                        it[StatusPageIncidents.scheduledStartAt] =
                            kotlin.time.Instant.fromEpochMilliseconds(
                                Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli()
                            )
                        it[StatusPageIncidents.scheduledEndAt] =
                            kotlin.time.Instant.fromEpochMilliseconds(
                                Instant
                                    .now()
                                    .plus(7, ChronoUnit.DAYS)
                                    .plus(2, ChronoUnit.HOURS)
                                    .toEpochMilli()
                            )
                    } else {
                        it[StatusPageIncidents.scheduledStartAt] = null
                        it[StatusPageIncidents.scheduledEndAt] = null
                    }

                    it[StatusPageIncidents.resolvedAt] =
                        resolvedAt?.let { resolved ->
                            kotlin.time.Instant.fromEpochMilliseconds(resolved.toEpochMilli())
                        }
                    it[StatusPageIncidents.createdAt] = kotlin.time.Instant.fromEpochMilliseconds(createdAt.toEpochMilli())
                    it[StatusPageIncidents.updatedAt] =
                        kotlin.time.Instant.fromEpochMilliseconds(
                            resolvedAt?.toEpochMilli() ?: createdAt.toEpochMilli()
                        )
                }

                // Add incident updates
                updates.forEachIndexed { index, (message, status) ->
                    val updateTime = createdAt.plus((index * 30L), ChronoUnit.MINUTES)

                    StatusPageIncidentUpdates.insert {
                        it[StatusPageIncidentUpdates.id] = UUID.randomUUID()
                        it[StatusPageIncidentUpdates.incidentId] = incidentId
                        it[StatusPageIncidentUpdates.status] = status
                        it[StatusPageIncidentUpdates.message] = message
                        it[StatusPageIncidentUpdates.createdAt] = kotlin.time.Instant.fromEpochMilliseconds(updateTime.toEpochMilli())
                    }
                }
            }
        }

        println("✅ Seeded 1 status page with ${monitorIds.size} monitors and ${incidents.size} incidents")
    }

    private fun generateBreadcrumbs(
        platform: String,
        errorTitle: String
    ): String {
        val breadcrumbs = mutableListOf<String>()
        val now = System.currentTimeMillis()

        // Determine flow variant based on error title for diversity
        val isAuthError = errorTitle.contains("auth", ignoreCase = true) || errorTitle.contains("login", ignoreCase = true) || errorTitle.contains("token", ignoreCase = true)
        val isNetworkError = errorTitle.contains("network", ignoreCase = true) || errorTitle.contains("timeout", ignoreCase = true) || errorTitle.contains("connection", ignoreCase = true)
        val isCheckoutError = errorTitle.contains("payment", ignoreCase = true) || errorTitle.contains("checkout", ignoreCase = true) || errorTitle.contains("cart", ignoreCase = true)
        val productId = random.nextInt(100, 999)

        when (platform) {
            "android" -> {
                if (isAuthError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"SplashActivity -> LoginActivity","level":"info","timestamp":${now - 45000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped email field","level":"info","timestamp":${now - 38000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped password field","level":"info","timestamp":${now - 32000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped Sign In button","level":"info","timestamp":${now - 25000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"POST /api/auth/login","level":"info","data":{"status_code":401,"method":"POST","url":"https://api.acmeshopping.com/v1/auth/login"},"timestamp":${now - 22000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"auth","message":"Token refresh attempted","level":"debug","timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"POST /api/auth/refresh","level":"info","data":{"status_code":403,"method":"POST"},"timestamp":${now - 15000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"error","category":"auth","message":"Authentication failed: invalid credentials","level":"error","timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"lifecycle","message":"LoginActivity.onResume called","level":"debug","timestamp":${now - 5000}}"""
                    )
                } else if (isNetworkError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"MainActivity -> ProductListFragment","level":"info","timestamp":${now - 40000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"network","message":"Network connectivity: WIFI connected","level":"debug","timestamp":${now - 35000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"GET /api/products?page=1","level":"info","data":{"status_code":200,"method":"GET","duration_ms":134},"timestamp":${now - 30000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User scrolled to bottom of list","level":"info","timestamp":${now - 22000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"GET /api/products?page=2","level":"info","data":{"status_code":200,"method":"GET","duration_ms":156},"timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"network","message":"Network connectivity: switching to cellular","level":"warning","timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"GET /api/products?page=3","level":"error","data":{"status_code":0,"method":"GET","reason":"Connection timed out"},"timestamp":${now - 8000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"error","category":"network","message":"Request failed: java.net.SocketTimeoutException","level":"error","timestamp":${now - 5000}}"""
                    )
                } else if (isCheckoutError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"MainActivity -> ProductListFragment","level":"info","timestamp":${now - 60000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped product #$productId","level":"info","timestamp":${now - 52000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"ProductListFragment -> ProductDetailFragment","level":"info","timestamp":${now - 50000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"GET /api/products/$productId","level":"info","data":{"status_code":200,"method":"GET","duration_ms":89},"timestamp":${now - 48000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped Add to Cart","level":"info","timestamp":${now - 35000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"POST /api/cart/items","level":"info","data":{"status_code":200,"method":"POST"},"timestamp":${now - 33000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"ProductDetailFragment -> CartFragment","level":"info","timestamp":${now - 28000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped Proceed to Checkout","level":"info","timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"CartFragment -> CheckoutFragment","level":"info","timestamp":${now - 16000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"POST /api/orders/checkout","level":"error","data":{"status_code":500,"method":"POST","url":"https://api.acmeshopping.com/v1/orders/checkout"},"timestamp":${now - 8000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"error","category":"payment","message":"Payment processing failed: gateway timeout","level":"error","timestamp":${now - 5000}}"""
                    )
                } else {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"MainActivity -> ProductListFragment","level":"info","timestamp":${now - 30000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped product item #$productId","level":"info","timestamp":${now - 25000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"ProductListFragment -> ProductDetailFragment","level":"info","timestamp":${now - 20000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"GET /api/products/$productId","level":"info","data":{"status_code":200,"method":"GET","duration_ms":112},"timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"User tapped add to cart button","level":"info","timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"http","message":"POST /api/cart/items","level":"info","data":{"status_code":200,"method":"POST"},"timestamp":${now - 10000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"ProductDetailFragment -> CartFragment","level":"info","timestamp":${now - 5000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"lifecycle","message":"CartFragment.onViewCreated called","level":"debug","timestamp":${now - 3000}}"""
                    )
                }
            }

            "cocoa" -> {
                if (isAuthError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"LaunchScreen -> LoginViewController","level":"info","timestamp":${now - 42000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"touch","message":"User tapped email field","level":"info","timestamp":${now - 35000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"touch","message":"User tapped Sign In","level":"info","timestamp":${now - 25000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"network","message":"POST /api/auth/login","level":"info","data":{"status_code":401,"url":"https://api.acmeshopping.com/v1/auth/login"},"timestamp":${now - 22000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"app.lifecycle","message":"Keychain read failed: item not found","level":"warning","timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"network","message":"POST /api/auth/refresh","level":"error","data":{"status_code":403},"timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"error","category":"auth","message":"Token refresh failed, user must re-authenticate","level":"error","timestamp":${now - 8000}}"""
                    )
                } else if (isNetworkError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"HomeViewController -> ProductListViewController","level":"info","timestamp":${now - 38000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"network","message":"URLSession configuration: .default","level":"debug","timestamp":${now - 33000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"network","message":"GET /api/products","level":"info","data":{"status_code":200,"duration_ms":98},"timestamp":${now - 30000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"touch","message":"User tapped product cell","level":"info","timestamp":${now - 20000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"network","message":"GET /api/products/$productId","level":"error","data":{"status_code":0,"reason":"The network connection was lost"},"timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"app.lifecycle","message":"Reachability changed: notReachable","level":"warning","timestamp":${now - 8000}}"""
                    )
                } else {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"HomeViewController -> ProductListViewController","level":"info","timestamp":${now - 28000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"touch","message":"User tapped product cell","level":"info","timestamp":${now - 22000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"network","message":"GET /api/products/$productId","level":"info","data":{"status_code":200,"duration_ms":75},"timestamp":${now - 20000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"ProductListViewController -> ProductDetailViewController","level":"info","timestamp":${now - 15000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"touch","message":"User tapped Add to Cart","level":"info","timestamp":${now - 8000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"network","message":"POST /api/cart/items","level":"info","data":{"status_code":200,"duration_ms":143},"timestamp":${now - 5000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"debug","category":"app.lifecycle","message":"viewWillDisappear called","level":"debug","timestamp":${now - 2000}}"""
                    )
                }
            }

            else -> {
                if (isAuthError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"Navigate to /login","level":"info","timestamp":${now - 35000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"Click Sign In button","level":"info","timestamp":${now - 25000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"POST /api/auth/login","level":"info","data":{"status":401,"url":"/api/auth/login"},"timestamp":${now - 22000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"console","category":"console","message":"AuthService: token validation failed","level":"warn","timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"POST /api/auth/refresh","level":"error","data":{"status":403},"timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"console","category":"console","message":"Redirecting to login: session expired","level":"log","timestamp":${now - 6000}}"""
                    )
                } else if (isNetworkError) {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"Navigate to /products","level":"info","timestamp":${now - 30000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"GET /api/products","level":"info","data":{"status":200,"duration_ms":87},"timestamp":${now - 27000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"Click on product item","level":"info","timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"GET /api/products/$productId","level":"error","data":{"status":0,"reason":"Failed to fetch"},"timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"console","category":"console","message":"Unhandled promise rejection: NetworkError","level":"error","timestamp":${now - 8000}}"""
                    )
                } else {
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"Navigate to /products","level":"info","timestamp":${now - 25000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"GET /api/products","level":"info","data":{"status":200,"duration_ms":92},"timestamp":${now - 23000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"Click on product item","level":"info","timestamp":${now - 18000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"navigation","category":"navigation","message":"Navigate to /products/$productId","level":"info","timestamp":${now - 15000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"GET /api/products/$productId","level":"info","data":{"status":200,"duration_ms":65},"timestamp":${now - 13000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"console","category":"console","message":"Product data loaded successfully","level":"log","timestamp":${now - 12000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"user","category":"ui.click","message":"Click add to cart","level":"info","timestamp":${now - 6000}}"""
                    )
                    breadcrumbs.add(
                        """{"type":"http","category":"fetch","message":"POST /api/cart/items","level":"info","data":{"status":200},"timestamp":${now - 4000}}"""
                    )
                }
            }
        }

        return "[${breadcrumbs.joinToString(",")}]"
    }

    private fun generateContexts(
        platform: String,
        device: String,
        osVersion: String
    ): String {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = (1..16).map { "0123456789abcdef".random(random) }.joinToString("")
        val contexts =
            when (platform) {
                "android" -> {
                    """
{
  "device": {
    "family": "Android",
    "model": "$device",
    "model_id": "${device.replace(" ", "_").lowercase()}",
    "arch": "arm64-v8a",
    "battery_level": ${70 + random.nextInt(30)}.0,
    "orientation": "portrait",
    "manufacturer": "${device.split(" ").firstOrNull() ?: "Unknown"}",
    "brand": "${device.split(" ").firstOrNull() ?: "Unknown"}",
    "screen_resolution": "1080x2400",
    "screen_density": 3.0,
    "online": true,
    "charging": ${random.nextBoolean()},
    "low_memory": false,
    "simulator": false,
    "memory_size": ${4 + random.nextInt(12)}000000000,
    "free_memory": ${2 + random.nextInt(4)}000000000,
    "storage_size": ${64 + random.nextInt(192)}000000000,
    "free_storage": ${32 + random.nextInt(96)}000000000,
    "boot_time": "2024-01-${10 + random.nextInt(20)}T08:${random.nextInt(60)}:${random.nextInt(60)}.000Z"
  },
  "os": {
    "name": "Android",
    "version": "$osVersion",
    "build": "API ${osVersion.substringAfter("Android ").trim()}",
    "kernel_version": "5.${random.nextInt(10)}.${random.nextInt(100)}"
  },
  "app": {
    "app_start_time": "2024-01-${15 + random.nextInt(15)}T${random.nextInt(
                        24
                    )}:${random.nextInt(60)}:${random.nextInt(60)}.000Z",
    "app_name": "Acme Shopping",
    "app_version": "1.${random.nextInt(4)}.${random.nextInt(3)}",
    "app_build": "${1000 + random.nextInt(200)}",
    "app_identifier": "com.acme.shopping",
    "app_memory": ${150 + random.nextInt(100)}000000
  },
  "culture": {
    "locale": "en_US",
    "timezone": "America/New_York"
  },
  "trace": {
    "trace_id": "$traceId",
    "span_id": "$spanId",
    "op": "ui.load",
    "status": "internal_error"
  },
  "runtime": {
    "name": "Android Runtime",
    "version": "$osVersion"
  }
}
                    """.trimIndent()
                }

                "cocoa" -> {
                    """
{
  "device": {
    "family": "iOS",
    "model": "$device",
    "model_id": "${device.replace(" ", "").replace("\"", "")}",
    "arch": "arm64",
    "battery_level": ${65 + random.nextInt(35)}.0,
    "orientation": "portrait",
    "manufacturer": "Apple",
    "brand": "Apple",
    "screen_resolution": "${if (device.contains("iPad")) "1668x2388" else "1170x2532"}",
    "online": true,
    "charging": ${random.nextBoolean()},
    "low_memory": false,
    "simulator": false,
    "memory_size": ${6 + random.nextInt(10)}000000000,
    "free_memory": ${3 + random.nextInt(3)}000000000,
    "storage_size": ${128 + random.nextInt(384)}000000000,
    "free_storage": ${64 + random.nextInt(128)}000000000
  },
  "os": {
    "name": "iOS",
    "version": "$osVersion",
    "build": "${random.nextInt(20)}A${random.nextInt(1000)}",
    "kernel_version": "Darwin ${random.nextInt(23)}.${random.nextInt(6)}.0"
  },
  "app": {
    "app_start_time": "2024-01-${15 + random.nextInt(15)}T${random.nextInt(
                        24
                    )}:${random.nextInt(60)}:${random.nextInt(60)}.000Z",
    "app_name": "Acme Shopping",
    "app_version": "1.${random.nextInt(4)}.${random.nextInt(3)}",
    "app_build": "${1000 + random.nextInt(200)}",
    "app_identifier": "com.acme.shopping.ios",
    "app_memory": ${120 + random.nextInt(80)}000000
  },
  "culture": {
    "locale": "en_US",
    "timezone": "America/Los_Angeles"
  },
  "trace": {
    "trace_id": "$traceId",
    "span_id": "$spanId",
    "op": "ui.load",
    "status": "internal_error"
  },
  "runtime": {
    "name": "Swift",
    "version": "5.9"
  }
}
                    """.trimIndent()
                }

                else -> {
                    val browsers =
                        listOf(
                            "Chrome" to "120.0.${random.nextInt(6000)}.${random.nextInt(200)}",
                            "Firefox" to "121.0",
                            "Safari" to "17.${random.nextInt(3)}",
                            "Edge" to "120.0.${random.nextInt(2000)}.${random.nextInt(100)}"
                        ).random(random)
                    val osList = listOf("Windows 10", "Windows 11", "macOS 14.2", "Ubuntu 22.04")
                    """
{
  "browser": {
    "name": "${browsers.first}",
    "version": "${browsers.second}"
  },
  "os": {
    "name": "${osList.random(random)}",
    "version": "10.0.${random.nextInt(20000)}"
  },
  "runtime": {
    "name": "javascript",
    "version": "ES2021"
  },
  "app": {
    "app_name": "Acme Shopping Web",
    "app_version": "1.${random.nextInt(4)}.${random.nextInt(3)}"
  },
  "culture": {
    "locale": "en-US",
    "timezone": "${listOf("America/Chicago","America/New_York","America/Los_Angeles","Europe/London").random(random)}"
  },
  "trace": {
    "trace_id": "$traceId",
    "span_id": "$spanId",
    "op": "pageload",
    "status": "internal_error"
  }
}
                    """.trimIndent()
                }
            }

        return contexts
    }

    @Suppress("LongMethod")
    private suspend fun seedDatadogAgentData(orgId: Int) {
        val db = ClickHouseClient.getDatabase()

        // Check if already seeded — require data in key tables to skip
        val datadogTables =
            listOf(
                Triple("apm_spans", "organization_id", "count()"),
                Triple("profiles", "organization_id", "count()"),
                Triple("service_checks", "organization_id", "count()"),
                Triple("containers", "organization_id", "count()"),
            )
        var allSeeded = true
        for ((table, orgCol, agg) in datadogTables) {
            val cnt =
                runCatching {
                    ClickHouseClient.executeWithFormat(
                        "SELECT $agg FROM `$db`.`$table` WHERE $orgCol = $orgId",
                        "TabSeparated",
                    ).trim().toLongOrNull() ?: 0L
                }.getOrElse { 0L }
            if (cnt == 0L) {
                allSeeded = false
                break
            }
        }
        if (allSeeded) {
            println("Datadog agent data already exists in all tables. Skipping.")
            return
        }

        // ── Hosts (PostgreSQL) ──
        val hosts =
            listOf(
                Host("prod-web-01", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", 8, 16_384_000L, "7.52.1"),
                Host("prod-web-02", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", 8, 16_384_000L, "7.52.1"),
                Host("prod-api-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", 16, 32_768_000L, "7.52.1"),
                Host("prod-db-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", 32, 65_536_000L, "7.52.1"),
                Host("prod-cache-01", "Ubuntu 22.04", "linux", "Intel Xeon E5-2686 v4", 4, 8_192_000L, "7.52.1"),
                Host("prod-worker-01", "Ubuntu 22.04", "linux", "AMD EPYC 7R13", 8, 16_384_000L, "7.52.1"),
            )
        transaction {
            exec("DELETE FROM hosts WHERE organization_id = $orgId")
            hosts.forEach { h ->
                val firstDays = random.nextInt(7, 30)
                val lastSecs = random.nextInt(0, 300)
                val firstTs =
                    Instant.now().minus(firstDays.toLong(), ChronoUnit.DAYS)
                        .toString()
                        .replace("T", " ")
                        .dropLast(1)
                val lastTs =
                    Instant.now().minusSeconds(lastSecs.toLong())
                        .toString()
                        .replace("T", " ")
                        .dropLast(1)
                exec(
                    """
                    INSERT INTO hosts (
                        organization_id, hostname, os, platform, processor,
                        cpu_cores, memory_total_kb, agent_version, gohai, tags,
                        first_seen_at, last_seen_at
                    ) VALUES (
                        $orgId,
                        '${h.hostname}',
                        '${h.os}',
                        '${h.platform}',
                        '${h.processor}',
                        ${h.cpuCores},
                        ${h.memoryKb},
                        '${h.agentVersion}',
                        '{}',
                        '{"env":"production","service":"acme-shopping"}',
                        '$firstTs',
                        '$lastTs'
                    )
                    """.trimIndent(),
                )
            }
        }
        println("✅ Seeded ${hosts.size} hosts")

        // ── APM Traces & Spans ──
        val services =
            listOf(
                Service("api-gateway", "web", "prod-web-01"),
                Service("user-service", "web", "prod-api-01"),
                Service("product-service", "web", "prod-api-01"),
                Service("order-service", "web", "prod-api-01"),
                Service("payment-service", "web", "prod-api-01"),
                Service("inventory-service", "worker", "prod-worker-01"),
                Service("cache-service", "cache", "prod-cache-01"),
                Service("postgres", "sql", "prod-db-01"),
            )

        // Generate ~20 traces with multiple spans each
        val traceCount = 20
        val spanRows = mutableListOf<String>()
        repeat(traceCount) { traceIdx ->
            val traceId = random.nextLong(1_000_000_000L, 9_999_999_999L)
            val baseTime = Instant.now().minus(random.nextInt(0, 48).toLong(), ChronoUnit.HOURS)
            val isError = random.nextFloat() < 0.15

            // Root span: api-gateway
            val rootSpanId = random.nextLong(100_000_000L, 999_999_999L)
            val rootDuration = random.nextLong(20_000_000L, 500_000_000L) // 20-500ms in ns
            val rootResource =
                listOf(
                    "GET /api/v1/products",
                    "POST /api/v1/orders",
                    "GET /api/v1/users/{id}",
                    "POST /api/v1/checkout",
                    "GET /api/v1/cart",
                    "PUT /api/v1/cart/items",
                    "GET /api/v1/search",
                ).let { it[traceIdx % it.size] }

            val rootTs = "fromUnixTimestamp64Nano(${baseTime.toEpochMilli() * 1_000_000L})"
            spanRows.add(
                "($rootSpanId, $traceId, 0, $orgId, 'http.request', 'api-gateway', " +
                    "'$rootResource', 'web', $rootTs, $rootDuration, ${if (isError) 1 else 0}, " +
                    "map('http.method','${rootResource.substringBefore(" ")}','http.url','${rootResource.substringAfter(" ")}','http.status_code','${if (isError) "500" else "200"}'), " +
                    "map('_sample_rate', 1.0), 'prod-web-01', 'production', '1.3.0')",
            )

            // Child spans: downstream services
            val downstreamCount = random.nextInt(2, 5)
            var elapsed = random.nextLong(1_000_000L, 5_000_000L)
            repeat(downstreamCount) { childIdx ->
                val svc = services[1 + (traceIdx + childIdx) % (services.size - 1)]
                val childSpanId = random.nextLong(100_000_000L, 999_999_999L)
                val childDuration = random.nextLong(2_000_000L, rootDuration / 2)
                val childStart = baseTime.plusNanos(elapsed)
                val childTs = "fromUnixTimestamp64Nano(${childStart.toEpochMilli() * 1_000_000L})"
                val childError = if (isError && childIdx == downstreamCount - 1) 1 else 0
                val opName =
                    when (svc.type) {
                        "sql" -> "postgresql.query"
                        "cache" -> "redis.command"
                        else -> "http.request"
                    }
                val resource =
                    when (svc.type) {
                        "sql" -> "SELECT * FROM products WHERE id = ?"
                        "cache" -> "GET product:cache:*"
                        else -> rootResource
                    }
                spanRows.add(
                    "($childSpanId, $traceId, $rootSpanId, $orgId, '$opName', '${svc.name}', " +
                        "'${resource.replace("'", "''")}', '${svc.type}', $childTs, $childDuration, $childError, " +
                        "map('component','${svc.name}'), map('_sample_rate', 1.0), '${svc.host}', 'production', '1.3.0')",
                )
                elapsed += childDuration + random.nextLong(500_000L, 2_000_000L)
            }
        }

        // Batch insert spans
        val spanBatch =
            """
            INSERT INTO `$db`.apm_spans (
                span_id, trace_id, parent_id, organization_id, name, service,
                resource, type, start, duration, error, meta, metrics, host, env, version
            ) VALUES ${spanRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(spanBatch)
        println("✅ Seeded $traceCount traces (${spanRows.size} spans)")

        // ── Profiles ──
        val profileTypes = listOf("cpu", "heap", "allocs", "goroutine", "block", "mutex")
        val profileRows = mutableListOf<String>()
        services.filter { it.type == "web" || it.type == "worker" }.forEach { svc ->
            repeat(3) { i ->
                val startTime = Instant.now().minus(random.nextInt(0, 24).toLong(), ChronoUnit.HOURS)
                val durationNs = 60_000_000_000L // 60s profile window
                val endTime = startTime.plusNanos(durationNs)
                val ptype = profileTypes[i % profileTypes.size]
                val startTs = "fromUnixTimestamp64Milli(${startTime.toEpochMilli()})"
                val endTs = "fromUnixTimestamp64Milli(${endTime.toEpochMilli()})"
                val storageKey = "$orgId/${UUID.randomUUID()}.pprof.gz"
                val sizeBytes = random.nextInt(50_000, 500_000)
                profileRows.add(
                    "(generateUUIDv4(), $orgId, '${svc.host}', '${svc.name}', 'production', '1.3.0', " +
                        "'go1.21', 'go', '$ptype', $startTs, $endTs, $durationNs, " +
                        "'$storageKey', map('service','${svc.name}','env','production'), $sizeBytes)",
                )
            }
        }

        val profileBatch =
            """
            INSERT INTO `$db`.profiles (
                profile_id, organization_id, host, service, env, version,
                runtime, language, profile_type, start_time, end_time, duration_ns,
                storage_key, tags, size_bytes
            ) VALUES ${profileRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(profileBatch)
        println("✅ Seeded ${profileRows.size} profiles")

        // ── Infrastructure Events ──
        val eventRows = mutableListOf<String>()
        val eventTemplates =
            listOf(
                EventTemplate(
                    "Deployment started: api-gateway v1.3.0",
                    "Rolling deployment initiated for api-gateway. 4 pods updating.",
                    "normal",
                    "info",
                    "deployment",
                ),
                EventTemplate(
                    "Deployment completed: api-gateway v1.3.0",
                    "All pods healthy. Zero-downtime deployment successful.",
                    "normal",
                    "success",
                    "deployment",
                ),
                EventTemplate(
                    "High memory usage on prod-db-01",
                    "Memory utilization at 87%. Consider scaling or optimizing queries.",
                    "normal",
                    "warning",
                    "system",
                ),
                EventTemplate(
                    "Auto-scaling triggered: order-service",
                    "CPU above 80% for 5 minutes. Scaling from 3 to 5 replicas.",
                    "normal",
                    "warning",
                    "kubernetes",
                ),
                EventTemplate(
                    "SSL certificate renewed: *.acme.com",
                    "Certificate auto-renewed via Let's Encrypt. Valid until 2026-05-25.",
                    "low",
                    "info",
                    "cert-manager",
                ),
                EventTemplate(
                    "Database backup completed",
                    "Full backup of prod-db-01 completed. Size: 42.3GB, Duration: 12m34s.",
                    "low",
                    "info",
                    "backup",
                ),
                EventTemplate(
                    "Rate limiting activated: /api/v1/search",
                    "Request rate exceeded 1000/min threshold from 203.0.113.42.",
                    "normal",
                    "warning",
                    "api-gateway",
                ),
                EventTemplate(
                    "Pod restart: payment-service-7f8d9c",
                    "Container OOMKilled. Memory limit: 512Mi. Peak usage: 498Mi.",
                    "normal",
                    "error",
                    "kubernetes",
                ),
                EventTemplate(
                    "Cache eviction spike on prod-cache-01",
                    "Redis evicted 15,000 keys in last 5 minutes. maxmemory-policy: allkeys-lru.",
                    "normal",
                    "warning",
                    "redis",
                ),
                EventTemplate(
                    "Deployment rolled back: user-service v1.2.9",
                    "Health check failures exceeded threshold. Automatic rollback to v1.2.8.",
                    "normal",
                    "error",
                    "deployment",
                ),
            )
        eventTemplates.forEachIndexed { idx, tmpl ->
            val tsEpochMs = Instant.now().minus(random.nextInt(0, 72).toLong(), ChronoUnit.HOURS).toEpochMilli()
            val ts = "fromUnixTimestamp64Milli($tsEpochMs)"
            val host = hosts[idx % hosts.size].hostname
            eventRows.add(
                "(generateUUIDv4(), $orgId, '${tmpl.title.replace("'", "''")}', " +
                    "'${tmpl.text.replace("'", "''")}', $ts, '${tmpl.priority}', '$host', " +
                    "map('env','production'), '${tmpl.alertType}', '', '${tmpl.source}', '')",
            )
        }

        val eventBatch =
            """
            INSERT INTO `$db`.infra_events (
                event_id, organization_id, title, text, timestamp, priority, host,
                tags, alert_type, aggregation_key, source_type_name, device_name
            ) VALUES ${eventRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(eventBatch)
        println("✅ Seeded ${eventRows.size} infrastructure events")

        // ── Service Checks ──
        val checkRows = mutableListOf<String>()
        val checks =
            listOf(
                Triple("datadog.agent.up", "ok", "Agent is reporting normally"),
                Triple("http.can_connect", "ok", "HTTP connection successful (200)"),
                Triple("postgres.can_connect", "ok", "PostgreSQL connection established"),
                Triple("redis.can_ping", "ok", "Redis PONG received in 0.3ms"),
                Triple("disk.check", "warning", "Disk usage at 82% on /dev/sda1"),
                Triple("ntp.offset", "ok", "NTP offset: +12ms"),
                Triple("tls.cert_expiry", "ok", "Certificate valid for 89 days"),
                Triple("http.can_connect", "critical", "Connection refused on port 8443"),
            )
        hosts.forEach { host ->
            checks.forEach { (checkName, status, message) ->
                val tsEpochMs =
                    Instant.now().minus(random.nextInt(0, 60).toLong(), ChronoUnit.MINUTES).toEpochMilli()
                val ts = "fromUnixTimestamp64Milli($tsEpochMs)"
                checkRows.add(
                    "(generateUUIDv4(), $orgId, '$checkName', '${host.hostname}', '$status', " +
                        "$ts, map('env','production'), '${message.replace("'", "''")}')",
                )
            }
        }

        val checkBatch =
            """
            INSERT INTO `$db`.service_checks (
                check_id, organization_id, check_name, host, status,
                timestamp, tags, message
            ) VALUES ${checkRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(checkBatch)
        println("✅ Seeded ${checkRows.size} service checks")

        // ── Processes ──
        val processTemplates =
            listOf(
                Process("nginx", "/usr/sbin/nginx -g daemon off;", "root", 1),
                Process("api-gateway", "/app/api-gateway serve --port 8080", "appuser", 12),
                Process("user-service", "java -jar /app/user-service.jar", "appuser", 45),
                Process("product-service", "java -jar /app/product-service.jar", "appuser", 38),
                Process("order-service", "/app/order-service serve", "appuser", 8),
                Process(
                    "postgres",
                    "/usr/lib/postgresql/15/bin/postgres -D /var/lib/postgresql/15/main",
                    "postgres",
                    24,
                ),
                Process("redis-server", "redis-server *:6379", "redis", 4),
                Process("node", "node /app/dist/worker.js", "appuser", 11),
                Process("datadog-agent", "/opt/datadog-agent/bin/agent/agent run", "dd-agent", 6),
                Process("containerd", "/usr/bin/containerd", "root", 15),
            )
        val processRows = mutableListOf<String>()
        hosts.forEach { host ->
            processTemplates.filter { proc ->
                when {
                    proc.name == "postgres" -> host.hostname.contains("db")
                    proc.name == "redis-server" -> host.hostname.contains("cache")
                    proc.name in listOf("nginx", "datadog-agent", "containerd") -> true
                    else -> host.hostname.contains("web") || host.hostname.contains("api") || host.hostname.contains("worker")
                }
            }.forEachIndexed { idx, proc ->
                val pid = 1000 + idx * 100 + random.nextInt(0, 50)
                val cpuPercent = random.nextDouble(0.1, 45.0)
                val memRss = random.nextLong(10L * 1024 * 1024, 2L * 1024 * 1024 * 1024)
                val memVms = memRss + random.nextLong(50L * 1024 * 1024, 500L * 1024 * 1024)
                val tsEpochMs =
                    Instant.now().minus(random.nextInt(0, 30).toLong(), ChronoUnit.MINUTES).toEpochMilli()
                val ts = "fromUnixTimestamp64Milli($tsEpochMs)"
                processRows.add(
                    "(generateUUIDv4(), $orgId, '${host.hostname}', $pid, '${proc.name}', " +
                        "'${proc.command.replace("'", "''")}', '${proc.user}', $cpuPercent, " +
                        "$memRss, $memVms, 'running', ${proc.threads}, ${random.nextInt(3, 256)}, " +
                        "map('env','production'), $ts)",
                )
            }
        }

        val processBatch =
            """
            INSERT INTO `$db`.processes (
                process_id, organization_id, host, pid, name, command, user,
                cpu_percent, mem_rss, mem_vms, state, thread_count, open_fd_count,
                tags, timestamp
            ) VALUES ${processRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(processBatch)
        println("✅ Seeded ${processRows.size} processes")

        // ── Containers ──
        val containerTemplates =
            listOf(
                Container("api-gateway", "acme/api-gateway:1.3.0", "running"),
                Container("user-service", "acme/user-service:1.2.8", "running"),
                Container("product-service", "acme/product-service:1.4.1", "running"),
                Container("order-service", "acme/order-service:2.0.3", "running"),
                Container("payment-service", "acme/payment-service:1.1.5", "running"),
                Container("nginx-ingress", "nginx/nginx-ingress:3.4.0", "running"),
                Container("datadog-agent", "datadog/agent:7.52.1", "running"),
                Container("fluentd", "fluent/fluentd:v1.16", "running"),
                Container("redis", "redis:7.2-alpine", "running"),
                Container("postgres", "postgres:15.5", "running"),
            )
        val containerRows = mutableListOf<String>()
        hosts.forEach { host ->
            containerTemplates.filter { c ->
                when {
                    c.name == "postgres" -> host.hostname.contains("db")
                    c.name == "redis" -> host.hostname.contains("cache")
                    c.name in listOf("datadog-agent", "fluentd") -> true
                    else -> host.hostname.contains("web") || host.hostname.contains("api") || host.hostname.contains("worker")
                }
            }.forEach { c ->
                val containerId = UUID.randomUUID().toString().replace("-", "").take(12)
                val cpuPercent = random.nextDouble(0.5, 65.0)
                val memLimit = random.nextLong(256L, 4096L) * 1024 * 1024
                val memUsage = (memLimit * random.nextDouble(0.2, 0.85)).toLong()
                val netRx = random.nextLong(1024L * 1024, 500L * 1024 * 1024)
                val netTx = random.nextLong(512L * 1024, 250L * 1024 * 1024)
                val tsEpochMs =
                    Instant.now().minus(random.nextInt(0, 30).toLong(), ChronoUnit.MINUTES).toEpochMilli()
                val ts = "fromUnixTimestamp64Milli($tsEpochMs)"
                containerRows.add(
                    "(generateUUIDv4(), $orgId, '${host.hostname}', '$containerId', " +
                        "'${c.name}', '${c.image}', '${c.state}', $cpuPercent, " +
                        "$memUsage, $memLimit, $netRx, $netTx, " +
                        "map('env','production','service','${c.name}'), $ts)",
                )
            }
        }

        val containerBatch =
            """
            INSERT INTO `$db`.containers (
                container_id_hash, organization_id, host, container_id, name, image, state,
                cpu_percent, mem_usage, mem_limit, net_rx_bytes, net_tx_bytes,
                tags, timestamp
            ) VALUES ${containerRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(containerBatch)
        println("✅ Seeded ${containerRows.size} containers")

        // ── Network Connections ──
        val connRows = mutableListOf<String>()
        val connTemplates =
            listOf(
                Conn("prod-web-01", 8080, "prod-api-01", 8080, "tcp", "outgoing"),
                Conn("prod-api-01", 8080, "prod-db-01", 5432, "tcp", "outgoing"),
                Conn("prod-api-01", 8080, "prod-cache-01", 6379, "tcp", "outgoing"),
                Conn("prod-web-02", 8080, "prod-api-01", 8080, "tcp", "outgoing"),
                Conn("prod-worker-01", 8080, "prod-db-01", 5432, "tcp", "outgoing"),
                Conn("prod-worker-01", 8080, "prod-cache-01", 6379, "tcp", "outgoing"),
                Conn("prod-web-01", 443, "0.0.0.0", 0, "tcp", "incoming"),
                Conn("prod-web-02", 443, "0.0.0.0", 0, "tcp", "incoming"),
            )
        connTemplates.forEach { conn ->
            val pid = random.nextInt(1000, 9999)
            val bytesSent = random.nextLong(10L * 1024, 100L * 1024 * 1024)
            val bytesRecv = random.nextLong(10L * 1024, 100L * 1024 * 1024)
            val tsEpochMs =
                Instant.now().minus(random.nextInt(0, 30).toLong(), ChronoUnit.MINUTES).toEpochMilli()
            val ts = "fromUnixTimestamp64Milli($tsEpochMs)"
            connRows.add(
                "(generateUUIDv4(), $orgId, '${conn.srcHost}', $pid, " +
                    "'${conn.srcHost}', ${conn.srcPort}, '${conn.dstHost}', ${conn.dstPort}, " +
                    "'${conn.protocol}', 'IPv4', '${conn.direction}', $bytesSent, $bytesRecv, " +
                    "map('env','production'), $ts)",
            )
        }

        val connBatch =
            """
            INSERT INTO `$db`.network_connections (
                connection_id, organization_id, host, pid, local_addr, local_port,
                remote_addr, remote_port, protocol, family, direction,
                bytes_sent, bytes_recv, tags, timestamp
            ) VALUES ${connRows.joinToString(",\n")}
            """.trimIndent()
        ClickHouseClient.execute(connBatch)
        println("✅ Seeded ${connRows.size} network connections")
    }

    private data class Host(
        val hostname: String,
        val os: String,
        val platform: String,
        val processor: String,
        val cpuCores: Int,
        val memoryKb: Long,
        val agentVersion: String,
    )

    private data class Service(val name: String, val type: String, val host: String)

    private data class EventTemplate(
        val title: String,
        val text: String,
        val priority: String,
        val alertType: String,
        val source: String,
    )

    private data class Process(val name: String, val command: String, val user: String, val threads: Int)

    private data class Container(val name: String, val image: String, val state: String)

    private data class Conn(
        val srcHost: String,
        val srcPort: Int,
        val dstHost: String,
        val dstPort: Int,
        val protocol: String,
        val direction: String,
    )

    private data class IssueTemplate(
        val title: String,
        val exceptionType: String,
        val exceptionValue: String,
        val platform: String,
        val stackTrace: List<String>,
        val eventCount: Int,
        val userCount: Int,
        val level: String
    )

    suspend fun deleteDemoData() {
        println("🗑️  Deleting existing demo data...")

        // Get organization ID and user ID first
        val (orgId, userId) =
            transaction {
                val org =
                    Organizations
                        .selectAll()
                        .where { Organizations.slug eq "acme-mobile" }
                        .firstOrNull()
                val user =
                    Users
                        .selectAll()
                        .where { Users.email eq "demo@moneat.dev" }
                        .firstOrNull()

                Pair(
                    org?.get(Organizations.id),
                    user?.get(Users.id)
                )
            }

        if (orgId == null && userId == null) {
            println("No demo data found, skipping delete")
            return
        }

        if (orgId != null) {
            println("Found demo organization ID: $orgId")
        }
        if (userId != null) {
            println("Found demo user ID: $userId")
        }

        // Delete PostgreSQL data - only core tables we know exist
        println("Deleting PostgreSQL demo data...")

        val deleteQueries = mutableListOf<String>()

        if (orgId != null) {
            deleteQueries.addAll(
                listOf(
                    // Emails and notifications first
                    "DELETE FROM emails_sent WHERE organization_id = $orgId",
                    // Datadog agent hosts
                    "DELETE FROM hosts WHERE organization_id = $orgId",
                    // Organization child data
                    "DELETE FROM subscriptions WHERE organization_id = $orgId",
                    "DELETE FROM uptime_monitors WHERE organization_id = $orgId",
                    // Project-related data
                    "DELETE FROM release_files WHERE release_id IN (SELECT id FROM releases WHERE project_id IN (SELECT id FROM projects WHERE organization_id = $orgId))",
                    "DELETE FROM releases WHERE project_id IN (SELECT id FROM projects WHERE organization_id = $orgId)",
                    "DELETE FROM project_keys WHERE project_id IN (SELECT id FROM projects WHERE organization_id = $orgId)",
                    "DELETE FROM projects WHERE organization_id = $orgId",
                    // Memberships and organization itself
                    "DELETE FROM memberships WHERE organization_id = $orgId",
                    "DELETE FROM organizations WHERE id = $orgId"
                )
            )
        }

        if (userId != null) {
            deleteQueries.addAll(
                listOf(
                    "DELETE FROM users WHERE id = $userId"
                )
            )
        }

        // Execute each delete in its own transaction to prevent cascading failures
        for (query in deleteQueries) {
            try {
                transaction {
                    exec(query)
                }
            } catch (e: Exception) {
                // Silently ignore errors - table might not exist or have no matching rows
            }
        }

        // Delete ClickHouse data - get actual project IDs
        if (orgId != null) {
            println("Deleting ClickHouse demo data...")
            val projectIds =
                transaction {
                    Projects
                        .selectAll()
                        .where { Projects.organization_id eq orgId }
                        .map { it[Projects.id] }
                }

            if (projectIds.isNotEmpty()) {
                val projectIdList = projectIds.joinToString(",")
                println("Deleting ClickHouse data for projects: $projectIdList")

                val clickhouseQueries =
                    listOf(
                        "ALTER TABLE issues DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE events DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE logs DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE user_feedback DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE replay_events DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE replay_segments DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE sessions DELETE WHERE project_id IN ($projectIdList)",
                        "ALTER TABLE spans DELETE WHERE project_id IN ($projectIdList)"
                    )

                for (query in clickhouseQueries) {
                    try {
                        ClickHouseClient.execute(query)
                    } catch (e: Exception) {
                        // Silently continue
                    }
                }
            }

            // Delete Datadog agent ClickHouse data (keyed by organization_id)
            println("Deleting Datadog agent ClickHouse data...")
            val ddClickhouseQueries =
                listOf(
                    "ALTER TABLE apm_spans DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE trace_stats DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE profiles DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE infra_events DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE service_checks DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE processes DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE containers DELETE WHERE toInt64(organization_id) = $orgId",
                    "ALTER TABLE network_connections DELETE WHERE toInt64(organization_id) = $orgId",
                )

            for (query in ddClickhouseQueries) {
                try {
                    ClickHouseClient.execute(query)
                } catch (_: Exception) {
                    // Silently continue - table may not exist
                }
            }
        }

        println("✅ Demo data deleted")
    }
}

suspend fun main(args: Array<String>) {
    val reseed = args.contains("--reseed")

    if (reseed) {
        println("🔄 Reseed mode enabled")

        // Initialize environment config first
        EnvConfig.initialize()

        // Connect to databases
        val dbUrl =
            EnvConfig.get("POSTGRES_URL")
                ?: "jdbc:postgresql://localhost:5499/moneat"
        val dbUser = EnvConfig.get("POSTGRES_USER") ?: "moneat"
        val dbPassword = EnvConfig.get("POSTGRES_PASSWORD") ?: "moneat_dev_password"

        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = dbUser,
            password = dbPassword
        )

        val clickhouseUrl = EnvConfig.get("CLICKHOUSE_URL") ?: "http://localhost:8123"
        val clickhouseDb = EnvConfig.get("CLICKHOUSE_DATABASE") ?: "moneat"
        val clickhouseUser = EnvConfig.get("CLICKHOUSE_USER") ?: "moneat"
        val clickhousePassword = EnvConfig.get("CLICKHOUSE_PASSWORD") ?: "moneat_dev_password"

        ClickHouseClient.init(clickhouseUrl, clickhouseDb, clickhouseUser, clickhousePassword)

        DemoDataSeeder.deleteDemoData()
    }

    DemoDataSeeder.seed()
}
